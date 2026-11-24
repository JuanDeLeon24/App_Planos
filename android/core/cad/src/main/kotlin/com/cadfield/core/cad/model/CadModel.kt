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
    ) : CadEntity() {
        override fun bounds() = BBox.around(insert.xy(), 0.0)
        override fun toPolylines(chordTol: Double) = emptyList<List<Vec2>>()
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
    val extents: BBox by lazy {
        headerExtents?.takeUnless { it.isEmpty || it.width > 1e12 }
            ?: entities.fold(BBox.EMPTY) { acc, e -> acc.union(e.bounds()) }
    }

    val entityCountByType: Map<String, Int> by lazy {
        entities.groupingBy { (it as? CadEntity.Unknown)?.type ?: it::class.simpleName!!.uppercase() }.eachCount()
    }
}
