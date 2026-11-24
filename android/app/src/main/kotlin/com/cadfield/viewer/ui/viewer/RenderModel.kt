package com.cadfield.viewer.ui.viewer

import com.cadfield.core.cad.geom.BBox
import com.cadfield.core.cad.geom.Vec2
import com.cadfield.core.cad.geom.Vec3
import com.cadfield.core.cad.index.QuadTree
import com.cadfield.core.cad.model.CadDocument
import com.cadfield.core.cad.model.CadEntity
import kotlin.math.hypot

/**
 * Geometría pre-teselada lista para GPU.
 *
 * Precisión: las coordenadas UTM (~8 000 000 m) no caben en float32 con precisión
 * milimétrica, por eso toda la geometría se almacena RELATIVA a [origin] (centro del
 * dibujo) y la traslación se aplica en doble precisión en la matriz de vista.
 */
class RenderItem(
    val entityIndex: Int,
    val layer: String,
    val argb: Int,
    /** pares de segmentos (x0,y0,x1,y1, ...) para Canvas.drawLines — sin asignaciones por frame. */
    val segments: FloatArray,
    val bounds: BBox,
)

class RenderModel(val doc: CadDocument) {
    val origin: Vec2 = doc.extents.center.takeUnless { doc.extents.isEmpty } ?: Vec2.ZERO
    val items: List<RenderItem>
    val index: QuadTree<RenderItem>
    val texts: List<Pair<Int, CadEntity.Text>>

    init {
        val diag = hypot(doc.extents.width, doc.extents.height).takeIf { it > 0 && it.isFinite() } ?: 1.0
        val tol = diag / 50_000 // tolerancia de cuerda relativa al tamaño del plano
        val list = ArrayList<RenderItem>(doc.entities.size)
        val txt = ArrayList<Pair<Int, CadEntity.Text>>()
        doc.entities.forEachIndexed { i, e ->
            if (e is CadEntity.Text) { txt += i to e; return@forEachIndexed }
            val layer = doc.layers[e.layer]
            val color = AciPalette.resolve(e.color.aci, e.color.rgb, layer?.color?.aci ?: 7, layer?.color?.rgb)
            val polys = expand(e, tol)
            if (polys.isEmpty()) return@forEachIndexed
            val count = polys.sumOf { maxOf(0, it.size - 1) * 4 }
            val seg = FloatArray(count)
            var k = 0
            for (pl in polys) for (j in 1 until pl.size) {
                seg[k++] = (pl[j - 1].x - origin.x).toFloat(); seg[k++] = (pl[j - 1].y - origin.y).toFloat()
                seg[k++] = (pl[j].x - origin.x).toFloat(); seg[k++] = (pl[j].y - origin.y).toFloat()
            }
            list += RenderItem(i, e.layer, color, seg, BBox.of(polys.flatten()))
        }
        items = list
        texts = txt
        index = QuadTree.build(list) { it.bounds }
    }

    /** Expande INSERT de bloques (con escala/rotación) — un nivel de anidamiento por rendimiento. */
    private fun expand(e: CadEntity, tol: Double): List<List<Vec2>> {
        if (e !is CadEntity.Insert) return e.toPolylines(tol)
        val block = doc.blocks[e.blockName] ?: return emptyList()
        val rad = Math.toRadians(e.rotationDeg)
        val cos = kotlin.math.cos(rad); val sin = kotlin.math.sin(rad)
        return block.entities.flatMap { it.toPolylines(tol) }.map { pl ->
            pl.map { p ->
                val x = (p.x - block.basePoint.x) * e.scaleX
                val y = (p.y - block.basePoint.y) * e.scaleY
                Vec2(e.insert.x + x * cos - y * sin, e.insert.y + x * sin + y * cos)
            }
        }
    }

    /** Puntos notables con Z (para snapping, cotas y pendientes). */
    fun keyPoints(e: CadEntity): List<Vec3> = when (e) {
        is CadEntity.Line -> listOf(e.start, e.end, Vec3((e.start.x + e.end.x) / 2, (e.start.y + e.end.y) / 2, (e.start.z + e.end.z) / 2))
        is CadEntity.Polyline -> e.vertices
        is CadEntity.Circle -> listOf(e.center)
        is CadEntity.Arc -> listOf(e.center)
        is CadEntity.Point -> listOf(e.position)
        is CadEntity.Insert -> listOf(e.insert)
        is CadEntity.Face3D -> e.corners
        is CadEntity.Text -> listOf(e.insert)
        is CadEntity.Unknown -> emptyList()
    }
}
