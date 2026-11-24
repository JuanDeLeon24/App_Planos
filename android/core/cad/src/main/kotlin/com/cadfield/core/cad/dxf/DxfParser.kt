package com.cadfield.core.cad.dxf

import com.cadfield.core.cad.geom.BBox
import com.cadfield.core.cad.geom.Vec3
import com.cadfield.core.cad.model.Block
import com.cadfield.core.cad.model.CadColor
import com.cadfield.core.cad.model.CadDocument
import com.cadfield.core.cad.model.CadEntity
import com.cadfield.core.cad.model.DrawingUnits
import com.cadfield.core.cad.model.Layer
import java.io.BufferedReader
import java.io.FilterReader
import java.io.Reader
import kotlin.math.abs

class DxfParseException(message: String, val line: Long) : Exception("$message (línea $line)")

/** Par código de grupo / valor de DXF. */
data class Group(val code: Int, val value: String) {
    fun d() = value.trim().toDoubleOrNull() ?: 0.0
    fun i() = value.trim().toIntOrNull() ?: 0
}

/**
 * Parser DXF ASCII en streaming (O(1) memoria adicional sobre el modelo resultante).
 * Diseñado para archivos grandes: no carga el archivo completo en memoria, informa
 * progreso y permite cancelar mediante [isCancelled].
 *
 * Secciones: HEADER, TABLES(LAYER), BLOCKS, ENTITIES. El resto se omite sin error.
 */
class DxfParser(
    private val totalChars: Long = -1,
    private val onProgress: (Double) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
    /** Filtro de carga por capas (carga bajo demanda). null = todas. */
    private val layerFilter: ((String) -> Boolean)? = null,
) {
    private lateinit var reader: BufferedReader
    private var counting: CountingReader? = null
    private var lineNo = 0L
    private var peeked: Group? = null
    private var lastReported = -1.0

    private val layers = LinkedHashMap<String, Layer>()
    private val blocks = LinkedHashMap<String, Block>()
    private val entities = ArrayList<CadEntity>()
    private var units = DrawingUnits.UNITLESS
    private var acadVer: String? = null
    private var extMin: Vec3? = null
    private var extMax: Vec3? = null
    private var autoHandle = 0L

    fun parse(input: Reader): CadDocument {
        val c = CountingReader(input)
        counting = c
        reader = if (c is BufferedReader) c else BufferedReader(c, 1 shl 16)
        while (true) {
            val g = next() ?: break
            if (g.code == 0 && g.value == "EOF") break
            if (g.code == 0 && g.value == "SECTION") {
                val name = next() ?: break
                when (name.value) {
                    "HEADER" -> parseHeader()
                    "TABLES" -> parseTables()
                    "BLOCKS" -> parseBlocks()
                    "ENTITIES" -> entities += parseEntityList(endMarker = "ENDSEC")
                    else -> skipUntil("ENDSEC")
                }
            }
        }
        onProgress(1.0)
        if ("0" !in layers) layers["0"] = Layer("0")
        val ext = if (extMin != null && extMax != null) BBox(extMin!!.x, extMin!!.y, extMax!!.x, extMax!!.y) else null
        return CadDocument(layers, blocks, entities, units, acadVer, ext)
    }

    // ---------- Lectura de grupos ----------

    private var pushback: String? = null
    private var groupCount = 0L
    /** Líneas no interpretables descartadas al re-sincronizar (diagnóstico). */
    var skippedLines = 0L
        private set

    private fun line(): String? {
        pushback?.let { pushback = null; return it }
        return reader.readLine()?.also { lineNo++ }
    }

    private fun isCode(l: String): Boolean = l.trim().toIntOrNull()?.let { it in -5..1071 } == true

    /**
     * Lee un par (código, valor) de forma tolerante:
     *  - Valores con saltos de línea crudos (textos multilínea mal exportados por algunos
     *    programas/convertidores, o '\r' sueltos) se re-unen con "\n".
     *  - Si aparece una línea que no es código válido, se re-sincroniza saltándola en lugar
     *    de abortar la lectura de todo el plano.
     */
    private fun next(): Group? {
        peeked?.let { peeked = null; return it }
        var codeLine = line() ?: return null
        while (!isCode(codeLine)) {
            skippedLines++
            codeLine = line() ?: return null
        }
        val code = codeLine.trim().toInt()
        val sb = StringBuilder(line() ?: throw DxfParseException("Archivo truncado: falta valor de grupo", lineNo))
        // Continuación: si la siguiente línea no es un código válido, pertenece a este valor
        // (sólo en valores de texto; los numéricos nunca contienen saltos de línea).
        if (code in 1..4 || code in 1000..1009 || code == 7) {
            while (true) {
                val la = line() ?: break
                if (isCode(la)) { pushback = la; break }
                sb.append('\n').append(la)
            }
        }
        if (++groupCount % 4096 == 0L) {
            if (isCancelled()) throw cancelled()
            reportProgress()
        }
        return Group(code, sb.toString())
    }

    private fun cancelled() = java.util.concurrent.CancellationException("Lectura DXF cancelada")

    private fun peek(): Group? = peeked ?: next().also { peeked = it }

    private fun reportProgress() {
        val c = counting ?: return
        if (totalChars <= 0) return
        val p = (c.count.toDouble() / totalChars).coerceIn(0.0, 0.999)
        if (p - lastReported >= 0.01) { lastReported = p; onProgress(p) }
    }

    private fun skipUntil(marker: String) {
        while (true) {
            val g = next() ?: return
            if (g.code == 0 && g.value == marker) return
        }
    }

    /** Lee los grupos de un objeto hasta el siguiente código 0 (sin consumirlo). */
    private fun readBody(): List<Group> {
        val out = ArrayList<Group>(16)
        while (true) {
            val g = peek() ?: return out
            if (g.code == 0) return out
            out += next()!!
        }
    }

    // ---------- Secciones ----------

    private fun parseHeader() {
        var currentVar: String? = null
        while (true) {
            val g = next() ?: return
            if (g.code == 0 && g.value == "ENDSEC") return
            if (g.code == 9) { currentVar = g.value.trim(); continue }
            when (currentVar) {
                "\$ACADVER" -> if (g.code == 1) acadVer = g.value.trim()
                "\$INSUNITS" -> if (g.code == 70) units = DrawingUnits.fromCode(g.i())
                "\$EXTMIN" -> extMin = updateVec(extMin, g)
                "\$EXTMAX" -> extMax = updateVec(extMax, g)
            }
        }
    }

    private fun updateVec(v: Vec3?, g: Group): Vec3 {
        val b = v ?: Vec3(0.0, 0.0, 0.0)
        return when (g.code) { 10 -> b.copy(x = g.d()); 20 -> b.copy(y = g.d()); 30 -> b.copy(z = g.d()); else -> b }
    }

    private fun parseTables() {
        while (true) {
            val g = next() ?: return
            if (g.code != 0) continue
            when (g.value) {
                "ENDSEC" -> return
                "LAYER" -> {
                    val body = readBody()
                    // la entrada de tabla LAYER tiene código 2 = nombre (la cabecera TABLE también usa 2=LAYER)
                    val name = body.firstOrNull { it.code == 2 }?.value?.trim() ?: continue
                    val aci = body.firstOrNull { it.code == 62 }?.i() ?: 7
                    val flags = body.firstOrNull { it.code == 70 }?.i() ?: 0
                    val rgb = body.firstOrNull { it.code == 420 }?.i()
                    layers[name] = Layer(
                        name = name,
                        color = CadColor(abs(aci), rgb),
                        lineType = body.firstOrNull { it.code == 6 }?.value?.trim() ?: "CONTINUOUS",
                        visible = aci >= 0,
                        frozen = flags and 1 != 0,
                        locked = flags and 4 != 0,
                    )
                }
            }
        }
    }

    private fun parseBlocks() {
        while (true) {
            val g = next() ?: return
            if (g.code != 0) continue
            when (g.value) {
                "ENDSEC" -> return
                "BLOCK" -> {
                    val body = readBody()
                    val name = body.firstOrNull { it.code == 2 }?.value?.trim() ?: ""
                    val base = vec(body, 10)
                    val ents = parseEntityList(endMarker = "ENDBLK", applyLayerFilter = false)
                    readBody() // cuerpo de ENDBLK
                    blocks[name] = Block(name, base, ents)
                }
            }
        }
    }

    private fun parseEntityList(endMarker: String, applyLayerFilter: Boolean = true): List<CadEntity> {
        val out = ArrayList<CadEntity>()
        while (true) {
            val g = next() ?: return out
            if (g.code != 0) continue
            if (g.value == endMarker) return out
            val e = parseEntity(g.value, skipPaperSpace = applyLayerFilter) ?: continue
            if (applyLayerFilter && layerFilter != null && !layerFilter.invoke(e.layer)) continue
            out += e
        }
    }

    private fun parseEntity(type: String, skipPaperSpace: Boolean = false): CadEntity? {
        val body = readBody()
        // Entidades de espacio papel (código 67 = 1: viewports, cajetines de layout) no se dibujan en el modelo
        if (skipPaperSpace && body.any { it.code == 67 && it.i() == 1 }) {
            if (type == "POLYLINE" || type == "INSERT") skipSequence(body)
            return null
        }
        val handle = body.firstOrNull { it.code == 5 }?.value?.trim() ?: "AUTO${autoHandle++}"
        val layer = body.firstOrNull { it.code == 8 }?.value?.trim() ?: "0"
        val color = CadColor(
            body.firstOrNull { it.code == 62 }?.i() ?: CadColor.BY_LAYER,
            body.firstOrNull { it.code == 420 }?.i(),
        )
        return when (type) {
            "LINE" -> CadEntity.Line(handle, layer, color, vec(body, 10), vec(body, 11))
            "CIRCLE" -> CadEntity.Circle(handle, layer, color, vec(body, 10), dbl(body, 40))
            "ARC" -> CadEntity.Arc(handle, layer, color, vec(body, 10), dbl(body, 40), dbl(body, 50), dbl(body, 51))
            "POINT" -> CadEntity.Point(handle, layer, color, vec(body, 10))
            "TEXT" -> CadEntity.Text(handle, layer, color, vec(body, 10), dbl(body, 40),
                str(body, 1), dbl(body, 50))
            "MTEXT" -> CadEntity.Text(handle, layer, color, vec(body, 10), dbl(body, 40),
                cleanMText(body.filter { it.code == 3 }.joinToString("") { it.value } + str(body, 1)), dbl(body, 50))
            "3DFACE" -> CadEntity.Face3D(handle, layer, color, listOf(vec(body, 10), vec(body, 11), vec(body, 12), vec(body, 13)))
            // SOLID/TRACE: el orden de vértices DXF es 1-2-4-3
            "SOLID", "TRACE" -> CadEntity.Face3D(handle, layer, color, listOf(vec(body, 10), vec(body, 11), vec(body, 13), vec(body, 12)))
            "ELLIPSE" -> CadEntity.Ellipse(handle, layer, color, vec(body, 10), vec(body, 11),
                body.firstOrNull { it.code == 40 }?.d() ?: 1.0, dbl(body, 41),
                body.firstOrNull { it.code == 42 }?.d() ?: (2 * Math.PI))
            "SPLINE" -> parseSpline(handle, layer, color, body)
            "HATCH" -> runCatching { parseHatch(handle, layer, color, body) }.getOrElse { CadEntity.Unknown(handle, layer, color, type) }
            "LEADER" -> CadEntity.Polyline(handle, layer, color, pointList(body, 10))
            // La geometría de una cota vive en su bloque anónimo (*Dnn), ya en coordenadas de mundo
            "DIMENSION" -> str(body, 2).takeIf { it.isNotBlank() }
                ?.let { CadEntity.Insert(handle, layer, color, it.trim(), com.cadfield.core.cad.geom.Vec3(0.0, 0.0, 0.0)) }
                ?: CadEntity.Unknown(handle, layer, color, type)
            "LWPOLYLINE" -> parseLwPolyline(handle, layer, color, body)
            "POLYLINE" -> parseOldPolyline(handle, layer, color, body)
            "INSERT" -> parseInsert(handle, layer, color, body)
            else -> CadEntity.Unknown(handle, layer, color, type)
        }
    }

    private fun parseLwPolyline(h: String, l: String, c: CadColor, body: List<Group>): CadEntity {
        val flags = body.firstOrNull { it.code == 70 }?.i() ?: 0
        val elev = body.firstOrNull { it.code == 38 }?.d() ?: 0.0
        val verts = ArrayList<Vec3>()
        val bulges = ArrayList<Double>()
        var x: Double? = null
        for (g in body) when (g.code) {
            10 -> { x = g.d(); bulges += 0.0 }
            20 -> { verts += Vec3(x ?: 0.0, g.d(), elev) }
            42 -> if (bulges.isNotEmpty()) bulges[bulges.size - 1] = g.d()
        }
        return CadEntity.Polyline(h, l, c, verts, bulges, closed = flags and 1 != 0)
    }

    /** POLYLINE clásica: VERTEX* SEQEND. Soporta polilíneas 3D. */
    private fun parseOldPolyline(h: String, l: String, c: CadColor, body: List<Group>): CadEntity {
        val flags = body.firstOrNull { it.code == 70 }?.i() ?: 0
        val verts = ArrayList<Vec3>()
        val bulges = ArrayList<Double>()
        while (true) {
            val g = next() ?: break
            if (g.code != 0) continue
            if (g.value == "SEQEND") { readBody(); break }
            val vb = readBody()
            if (g.value == "VERTEX") {
                val vflags = vb.firstOrNull { it.code == 70 }?.i() ?: 0
                if (vflags and 128 != 0 && vflags and 64 == 0) continue // vértices de índice de malla
                verts += vec(vb, 10)
                bulges += vb.firstOrNull { it.code == 42 }?.d() ?: 0.0
            }
        }
        return CadEntity.Polyline(h, l, c, verts, bulges, closed = flags and 1 != 0)
    }

    private fun parseSpline(h: String, l: String, c: CadColor, body: List<Group>): CadEntity {
        val knots = body.filter { it.code == 40 }.map { it.d() }
        val weights = body.filter { it.code == 41 }.map { it.d() }
        fun pts(cx: Int): List<com.cadfield.core.cad.geom.Vec3> {
            val out = ArrayList<com.cadfield.core.cad.geom.Vec3>()
            var x = 0.0; var y = 0.0; var has = false
            for (g in body) when (g.code) {
                cx -> { if (has) out += com.cadfield.core.cad.geom.Vec3(x, y, 0.0); x = g.d(); y = 0.0; has = true }
                cx + 10 -> y = g.d()
                cx + 20 -> { out += com.cadfield.core.cad.geom.Vec3(x, y, g.d()); has = false }
            }
            if (has) out += com.cadfield.core.cad.geom.Vec3(x, y, 0.0)
            return out
        }
        return CadEntity.Spline(h, l, c, body.firstOrNull { it.code == 71 }?.i() ?: 3, knots, pts(10), weights, pts(11))
    }

    /** Lista de puntos repetidos (código base 10/20/30). */
    private fun pointList(body: List<Group>, base: Int): List<com.cadfield.core.cad.geom.Vec3> {
        val out = ArrayList<com.cadfield.core.cad.geom.Vec3>()
        var x = 0.0; var y = 0.0; var has = false
        for (g in body) when (g.code) {
            base -> { if (has) out += com.cadfield.core.cad.geom.Vec3(x, y, 0.0); x = g.d(); y = 0.0; has = true }
            base + 10 -> y = g.d()
            base + 20 -> { out += com.cadfield.core.cad.geom.Vec3(x, y, g.d()); has = false }
        }
        if (has) out += com.cadfield.core.cad.geom.Vec3(x, y, 0.0)
        return out
    }

    /**
     * HATCH: contornos de trayectorias tipo polilínea (con bulges) y de aristas
     * (línea, arco, elipse, spline). Se lee secuencialmente según los contadores DXF.
     */
    private fun parseHatch(h: String, l: String, c: CadColor, body: List<Group>): CadEntity {
        var i = body.indexOfFirst { it.code == 91 }
        val pattern = body.firstOrNull { it.code == 2 }?.value?.trim() ?: ""
        val solid = (body.firstOrNull { it.code == 70 }?.i() ?: 0) == 1
        if (i < 0) return CadEntity.Hatch(h, l, c, emptyList(), solid, pattern)
        val nPaths = body[i++].i()
        fun at(code: Int): Group { while (i < body.size && body[i].code != code) i++; return body.getOrNull(i++) ?: Group(code, "0") }
        val tol = 1e-3
        val paths = ArrayList<List<com.cadfield.core.cad.geom.Vec2>>()
        repeat(nPaths) {
            val flags = at(92).i()
            val pts = ArrayList<com.cadfield.core.cad.geom.Vec2>()
            if (flags and 2 != 0) {
                val hasBulge = at(72).i() != 0
                at(73)
                val n = at(93).i()
                val vs = ArrayList<com.cadfield.core.cad.geom.Vec2>(); val bs = ArrayList<Double>()
                repeat(n) {
                    val x = at(10).d(); val y = at(20).d()
                    vs += com.cadfield.core.cad.geom.Vec2(x, y)
                    bs += if (hasBulge && body.getOrNull(i)?.code == 42) body[i++].d() else 0.0
                }
                for (k in vs.indices) {
                    val seg = com.cadfield.core.cad.geom.Tessellation.bulgeSegment(vs[k], vs[(k + 1) % vs.size], bs[k], tol * 10)
                    if (pts.isEmpty()) pts.addAll(seg) else pts.addAll(seg.drop(1))
                }
            } else {
                val nEdges = at(93).i()
                repeat(nEdges) {
                    when (at(72).i()) {
                        1 -> { val a = com.cadfield.core.cad.geom.Vec2(at(10).d(), at(20).d()); val b = com.cadfield.core.cad.geom.Vec2(at(11).d(), at(21).d()); pts += a; pts += b }
                        2 -> {
                            val cx = at(10).d(); val cy = at(20).d(); val r = at(40).d(); val s0 = at(50).d(); val e0 = at(51).d(); val ccw = at(73).i() != 0
                            val (s1, e1) = if (ccw) s0 to e0 else (360 - e0) to (360 - s0)
                            val arc = com.cadfield.core.cad.geom.Tessellation.arc(com.cadfield.core.cad.geom.Vec2(cx, cy), r, Math.toRadians(s1), Math.toRadians(e1), maxOf(r * 1e-3, 1e-9))
                            pts.addAll(if (ccw) arc else arc.reversed())
                        }
                        3 -> {
                            val cx = at(10).d(); val cy = at(20).d(); val mx = at(11).d(); val my = at(21).d()
                            val ratio = at(40).d(); val s0 = Math.toRadians(at(50).d()); val e0 = Math.toRadians(at(51).d()); val ccw = at(73).i() != 0
                            val el = CadEntity.Ellipse(h, l, c, com.cadfield.core.cad.geom.Vec3(cx, cy), com.cadfield.core.cad.geom.Vec3(mx, my), ratio,
                                if (ccw) s0 else -e0, if (ccw) e0 else -s0)
                            val e = el.toPolylines(maxOf(kotlin.math.hypot(mx, my) * 1e-3, 1e-9)).first()
                            pts.addAll(if (ccw) e else e.reversed())
                        }
                        4 -> {
                            val degree = at(94).i(); at(73); at(74)
                            val nk = at(95).i(); val nc = at(96).i()
                            val knots = List(nk) { at(40).d() }
                            val ctrl = ArrayList<com.cadfield.core.cad.geom.Vec3>()
                            val w = ArrayList<Double>()
                            repeat(nc) {
                                ctrl += com.cadfield.core.cad.geom.Vec3(at(10).d(), at(20).d())
                                if (body.getOrNull(i)?.code == 42) w += body[i++].d()
                            }
                            pts.addAll(CadEntity.Spline(h, l, c, degree, knots, ctrl, w).toPolylines(0.0).firstOrNull().orEmpty())
                        }
                        else -> Unit
                    }
                }
            }
            if (pts.size >= 2) paths += pts
        }
        return CadEntity.Hatch(h, l, c, paths, solid, pattern)
    }

    /** Consume VERTEX/ATTRIB ... SEQEND de una entidad descartada. */
    private fun skipSequence(body: List<Group>) {
        val hasSeq = when {
            body.any { it.code == 66 && it.i() == 1 } -> true
            else -> false
        }
        if (!hasSeq && peek()?.value != "VERTEX") return
        while (true) {
            val g = next() ?: return
            if (g.code == 0 && g.value == "SEQEND") { readBody(); return }
        }
    }

    private fun parseInsert(h: String, l: String, c: CadColor, body: List<Group>): CadEntity {
        val attrs = LinkedHashMap<String, String>()
        val texts = ArrayList<CadEntity.Text>()
        val hasAttribs = (body.firstOrNull { it.code == 66 }?.i() ?: 0) == 1
        if (hasAttribs) {
            while (true) {
                val g = next() ?: break
                if (g.code != 0) continue
                if (g.value == "SEQEND") { readBody(); break }
                val ab = readBody()
                if (g.value == "ATTRIB") {
                    attrs[str(ab, 2)] = str(ab, 1)
                    val invisible = (ab.firstOrNull { it.code == 70 }?.i() ?: 0) and 1 != 0
                    if (!invisible && str(ab, 1).isNotBlank()) texts += CadEntity.Text(
                        ab.firstOrNull { it.code == 5 }?.value?.trim() ?: "AUTO${autoHandle++}",
                        ab.firstOrNull { it.code == 8 }?.value?.trim() ?: l,
                        CadColor(ab.firstOrNull { it.code == 62 }?.i() ?: CadColor.BY_LAYER, ab.firstOrNull { it.code == 420 }?.i()),
                        vec(ab, 10), dbl(ab, 40), str(ab, 1), dbl(ab, 50),
                    )
                }
            }
        }
        return CadEntity.Insert(
            h, l, c, blockName = str(body, 2), insert = vec(body, 10),
            scaleX = body.firstOrNull { it.code == 41 }?.d() ?: 1.0,
            scaleY = body.firstOrNull { it.code == 42 }?.d() ?: 1.0,
            rotationDeg = dbl(body, 50), attributes = attrs, attribTexts = texts,
        )
    }

    // ---------- utilidades ----------

    private fun dbl(b: List<Group>, code: Int) = b.firstOrNull { it.code == code }?.d() ?: 0.0
    private fun str(b: List<Group>, code: Int) = b.firstOrNull { it.code == code }?.value ?: ""
    private fun vec(b: List<Group>, base: Int) = Vec3(dbl(b, base), dbl(b, base + 10), dbl(b, base + 20))

    private fun cleanMText(s: String) = s
        .replace(Regex("""\\P"""), "\n")
        .replace(Regex("""\\[A-Za-z][^;\\{}]*;"""), "")
        .replace("{", "").replace("}", "")

    private class CountingReader(inner: Reader) : FilterReader(inner) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(cbuf: CharArray, off: Int, len: Int): Int =
            super.read(cbuf, off, len).also { if (it > 0) count += it }
    }
}
