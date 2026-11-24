package com.cadfield.core.cad.model

import com.cadfield.core.cad.geom.BBox
import com.cadfield.core.cad.geom.Tessellation
import com.cadfield.core.cad.geom.Vec2
import com.cadfield.core.cad.geom.Vec3

/** Color ACI (AutoCAD Color Index) o verdadero RGB. 256 = BYLAYER, 0 = BYBLOCK. */
data class CadColor(val aci: Int = BY_LAYER, val rgb: Int? = null) {
    companion object {
        const val BY_LAYER = 256
        const val BY_BLOCK = 0
        val BYLAYER = CadColor()
    }
}

data class Layer(
    val name: String,
    val color: CadColor = CadColor(7),
    val lineType: String = "CONTINUOUS",
    val visible: Boolean = true,
    val frozen: Boolean = false,
    val locked: Boolean = false,
    /** 0..1 — control de transparencia por capa en el visor. */
    val transparency: Double = 0.0,
)

/** Entidades soportadas por el motor interno. Las desconocidas se conservan como [Unknown]. */
sealed class CadEntity {
    abstract val handle: String
    abstract val layer: String
    abstract val color: CadColor
    abstract fun bounds(): BBox

    /** Representación como polilíneas para render/picking con tolerancia de cuerda dada. */
    abstract fun toPolylines(chordTol: Double): List<List<Vec2>>

    data class Line(
        override val handle: String, override val layer: String, override val color: CadColor,
        val start: Vec3, val end: Vec3,
    ) : CadEntity() {
        override fun bounds() = BBox.of(listOf(start.xy(), end.xy()))
        override fun toPolylines(chordTol: Double) = listOf(listOf(start.xy(), end.xy()))
    }

    data class Polyline(
        override val handle: String, override val layer: String, override val color: CadColor,
        val vertices: List<Vec3>,
        /** bulge por vértice (segmento i → i+1); vacío = todo recto. */
        val bulges: List<Double> = emptyList(),
        val closed: Boolean = false,
    ) : CadEntity() {
        override fun bounds() = BBox.of(toPolylines(0.01).flatten())
        override fun toPolylines(chordTol: Double): List<List<Vec2>> {
            if (vertices.size < 2) return listOf(vertices.map { it.xy() })
            val out = ArrayList<Vec2>()
            val n = if (closed) vertices.size else vertices.size - 1
            for (i in 0 until n) {
                val p1 = vertices[i].xy()
                val p2 = vertices[(i + 1) % vertices.size].xy()
                val seg = Tessellation.bulgeSegment(p1, p2, bulges.getOrElse(i) { 0.0 }, chordTol)
                if (out.isEmpty()) out.addAll(seg) else out.addAll(seg.drop(1))
            }
            return listOf(out)
        }
    }

    data class Circle(
        override val handle: String, override val layer: String, override val color: CadColor,
        val center: Vec3, val radius: Double,
    ) : CadEntity() {
        override fun bounds() = BBox.around(center.xy(), radius)
        override fun toPolylines(chordTol: Double) = listOf(Tessellation.circle(center.xy(), radius, chordTol))
    }

    data class Arc(
        override val handle: String, override val layer: String, override val color: CadColor,
        val center: Vec3, val radius: Double, val startDeg: Double, val endDeg: Double,
    ) : CadEntity() {
        override fun bounds() = BBox.of(toPolylines(radius * 1e-3).flatten())
        override fun toPolylines(chordTol: Double) = listOf(
            Tessellation.arc(center.xy(), radius, Math.toRadians(startDeg), Math.toRadians(endDeg), chordTol)
        )
    }

    data class Text(
        override val handle: String, override val layer: String, override val color: CadColor,
        val insert: Vec3, val height: Double, val value: String, val rotationDeg: Double = 0.0,
    ) : CadEntity() {
        override fun bounds() = BBox(insert.x, insert.y, insert.x + height * 0.6 * value.length, insert.y + height)
        override fun toPolylines(chordTol: Double) = emptyList<List<Vec2>>()
    }

    data class Point(
        override val handle: String, override val layer: String, override val color: CadColor,
        val position: Vec3,
    ) : CadEntity() {
        override fun bounds() = BBox.around(position.xy(), 0.0)
        override fun toPolylines(chordTol: Double) = listOf(listOf(position.xy()))
    }

    /** Cara 3D (malla). Base de visualización 3D de DXF (3DFACE). */
    data class Face3D(
        override val handle: String, override val layer: String, override val color: CadColor,
        val corners: List<Vec3>,
    ) : CadEntity() {
        override fun bounds() = BBox.of(corners.map { it.xy() })
        override fun toPolylines(chordTol: Double) = listOf(corners.map { it.xy() } + corners.first().xy())
    }

    /** Referencia a bloque (INSERT). Se expande bajo demanda contra [CadDocument.blocks]. */
    data class Insert(
        override val handle: String, override val layer: String, override val color: CadColor,
        val blockName: String, val insert: Vec3,
        val scaleX: Double = 1.0, val scaleY: Double = 1.0, val rotationDeg: Double = 0.0,
        val attributes: Map<String, String> = emptyMap(),
        /** Textos de atributos visibles (ATTRIB), ya en coordenadas de mundo. */
        val attribTexts: List<Text> = emptyList(),
    ) : CadEntity() {
        override fun bounds() = BBox.around(insert.xy(), 0.0)
        override fun toPolylines(chordTol: Double) = emptyList<List<Vec2>>()
    }

    /** Elipse o arco elíptico (parámetros en radianes). */
    data class Ellipse(
        override val handle: String, override val layer: String, override val color: CadColor,
        val center: Vec3, val majorAxis: Vec3, val ratio: Double, val startParam: Double, val endParam: Double,
    ) : CadEntity() {
        override fun bounds() = BBox.of(toPolylines(majorAxis.length() * 1e-3).flatten())
        override fun toPolylines(chordTol: Double): List<List<Vec2>> {
            val a = majorAxis.xy()
            val b = Vec2(-a.y, a.x) * ratio
            var sweep = endParam - startParam
            while (sweep <= 0) sweep += 2 * Math.PI
            val n = Tessellation.segmentsFor(a.length(), sweep, chordTol).coerceAtLeast(8)
            val c = center.xy()
            return listOf((0..n).map { i ->
                val t = startParam + sweep * i / n
                c + a * kotlin.math.cos(t) + b * kotlin.math.sin(t)
            })
        }
    }

    /** B-spline / NURBS (evaluación de De Boor). Sin nudos → polilínea por puntos de ajuste. */
    data class Spline(
        override val handle: String, override val layer: String, override val color: CadColor,
        val degree: Int, val knots: List<Double>, val controlPoints: List<Vec3>,
        val weights: List<Double> = emptyList(), val fitPoints: List<Vec3> = emptyList(),
    ) : CadEntity() {
        override fun bounds() = BBox.of(toPolylines(0.0).flatten())
        override fun toPolylines(chordTol: Double): List<List<Vec2>> {
            val n = controlPoints.size
            val p = degree
            if (n < 2 || p < 1 || knots.size != n + p + 1) {
                val pts = (fitPoints.ifEmpty { controlPoints }).map { it.xy() }
                return if (pts.size >= 2) listOf(pts) else emptyList()
            }
            val t0 = knots[p]; val t1 = knots[n]
            if (t1 <= t0) return listOf(controlPoints.map { it.xy() })
            val samples = (n * 8).coerceIn(16, 2048)
            return listOf((0..samples).map { i -> evaluate(t0 + (t1 - t0) * i / samples) })
        }

        fun evaluate(t: Double): Vec2 {
            val p = degree; val n = controlPoints.size
            var k = p
            while (k < n - 1 && t >= knots[k + 1]) k++
            val w = DoubleArray(p + 1) { weights.getOrElse(k - p + it) { 1.0 } }
            val x = DoubleArray(p + 1) { controlPoints[k - p + it].x * w[it] }
            val y = DoubleArray(p + 1) { controlPoints[k - p + it].y * w[it] }
            for (r in 1..p) for (j in p downTo r) {
                val i = j + k - p
                val den = knots[i + p - r + 1] - knots[i]
                val a = if (den == 0.0) 0.0 else (t - knots[i]) / den
                x[j] = (1 - a) * x[j - 1] + a * x[j]
                y[j] = (1 - a) * y[j - 1] + a * y[j]
                w[j] = (1 - a) * w[j - 1] + a * w[j]
            }
            return Vec2(x[p] / w[p], y[p] / w[p])
        }
    }

    /** Sombreado (HATCH): se representa por sus contornos; [solid] indica relleno sólido. */
    data class Hatch(
        override val handle: String, override val layer: String, override val color: CadColor,
        val boundaries: List<List<Vec2>>, val solid: Boolean, val pattern: String,
    ) : CadEntity() {
        override fun bounds() = BBox.of(boundaries.flatten())
        override fun toPolylines(chordTol: Double) = boundaries.map { if (it.size > 2 && it.first() != it.last()) it + it.first() else it }
    }

    /** Entidad no interpretada: se preserva (nunca se pierde información del original). */
    data class Unknown(
        override val handle: String, override val layer: String, override val color: CadColor,
        val type: String,
    ) : CadEntity() {
        override fun bounds() = BBox.EMPTY
        override fun toPolylines(chordTol: Double) = emptyList<List<Vec2>>()
    }
}

data class Block(val name: String, val basePoint: Vec3, val entities: List<CadEntity>)

enum class DrawingUnits(val code: Int, val toMeters: Double) {
    UNITLESS(0, 1.0), INCHES(1, 0.0254), FEET(2, 0.3048), MILLIMETERS(4, 0.001),
    CENTIMETERS(5, 0.01), METERS(6, 1.0), KILOMETERS(7, 1000.0);

    companion object {
        fun fromCode(c: Int) = entries.firstOrNull { it.code == c } ?: UNITLESS
    }
}

class CadDocument(
    val layers: Map<String, Layer>,
    val blocks: Map<String, Block>,
    val entities: List<CadEntity>,
    val units: DrawingUnits = DrawingUnits.UNITLESS,
    val acadVersion: String? = null,
    headerExtents: BBox? = null,
) {
    /**
     * Extensión real calculada desde la geometría (incluye bloques insertados).
     * El encabezado ($EXTMIN/$EXTMAX) suele estar desactualizado en DWG reales, así
     * que sólo se usa si el dibujo no tiene geometría medible.
     */
    val extents: BBox by lazy {
        val expander = BlockExpander(this)
        var b = BBox.EMPTY
        for (e in entities) b = b.union(if (e is CadEntity.Insert) expander.bounds(e) else e.bounds())
        if (!b.isEmpty) b else headerExtents?.takeUnless { it.isEmpty || it.width > 1e12 } ?: BBox.EMPTY
    }

    /**
     * Zona de trabajo para el encuadre inicial: descarta entidades atípicas (objetos
     * perdidos lejos del dibujo, bloques gigantes) usando percentiles 1–99 % de los
     * centros. Así "ajustar a pantalla" muestra el plano y no un punto diminuto.
     */
    val focusExtents: BBox by lazy {
        val expander = BlockExpander(this)
        val boxes = entities.map { if (it is CadEntity.Insert) expander.bounds(it) else it.bounds() }.filter { !it.isEmpty }
        if (boxes.size < 20) return@lazy extents
        val xs = boxes.map { it.center.x }.sorted(); val ys = boxes.map { it.center.y }.sorted()
        fun pct(v: List<Double>, q: Double) = v[((v.size - 1) * q).toInt()]
        val core = BBox(pct(xs, 0.01), pct(ys, 0.01), pct(xs, 0.99), pct(ys, 0.99))
        val margin = maxOf(core.width, core.height) * 0.05
        val zone = core.expand(maxOf(margin, 1e-9))
        val typical = boxes.map { maxOf(it.width, it.height) }.sorted().let { pct(it, 0.99) }
        var b = BBox.EMPTY
        for (bx in boxes) if (zone.contains(bx.center) && maxOf(bx.width, bx.height) <= maxOf(typical, zone.width + zone.height)) b = b.union(bx)
        if (b.isEmpty) extents else b
    }

    val entityCountByType: Map<String, Int> by lazy {
        entities.groupingBy { (it as? CadEntity.Unknown)?.type ?: it::class.simpleName!!.uppercase() }.eachCount()
    }
}
