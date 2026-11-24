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

    private fun next(): Group? {
        peeked?.let { peeked = null; return it }
        val codeLine = reader.readLine() ?: return null
        val value = reader.readLine() ?: throw DxfParseException("Archivo truncado: falta valor de grupo", lineNo)
        lineNo += 2
        val code = codeLine.trim().toIntOrNull() ?: throw DxfParseException("Código de grupo inválido '$codeLine'", lineNo - 1)
        if (lineNo % 4096 == 0L) {
            if (isCancelled()) throw cancelled()
            reportProgress()
        }
        return Group(code, value)
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
            val e = parseEntity(g.value) ?: continue
            if (applyLayerFilter && layerFilter != null && !layerFilter.invoke(e.layer)) continue
            out += e
        }
    }

    private fun parseEntity(type: String): CadEntity? {
        val body = readBody()
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

    private fun parseInsert(h: String, l: String, c: CadColor, body: List<Group>): CadEntity {
        val attrs = LinkedHashMap<String, String>()
        val hasAttribs = (body.firstOrNull { it.code == 66 }?.i() ?: 0) == 1
        if (hasAttribs) {
            while (true) {
                val g = next() ?: break
                if (g.code != 0) continue
                if (g.value == "SEQEND") { readBody(); break }
                val ab = readBody()
                if (g.value == "ATTRIB") attrs[str(ab, 2)] = str(ab, 1)
            }
        }
        return CadEntity.Insert(
            h, l, c, blockName = str(body, 2), insert = vec(body, 10),
            scaleX = body.firstOrNull { it.code == 41 }?.d() ?: 1.0,
            scaleY = body.firstOrNull { it.code == 42 }?.d() ?: 1.0,
            rotationDeg = dbl(body, 50), attributes = attrs,
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
