package com.cadfield.viewer.ui.viewer

import com.cadfield.core.cad.geom.BBox
import com.cadfield.core.cad.geom.Vec2
import com.cadfield.core.cad.geom.Vec3
import com.cadfield.core.cad.index.QuadTree
import com.cadfield.core.cad.model.BlockExpander
import com.cadfield.core.cad.model.CadColor
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
    /** Zona de trabajo (sin entidades atípicas) para el encuadre inicial. */
    val focus: BBox = doc.focusExtents
    val origin: Vec2 = focus.center.takeUnless { focus.isEmpty } ?: Vec2.ZERO
    val items: List<RenderItem>
    val index: QuadTree<RenderItem>
    val texts: List<Pair<Int, CadEntity.Text>>

    init {
        val diag = hypot(focus.width, focus.height).takeIf { it > 0 && it.isFinite() } ?: 1.0
        val tol = diag / 50_000 // tolerancia de cuerda relativa al tamaño del plano
        val list = ArrayList<RenderItem>(doc.entities.size)
        val txt = ArrayList<Pair<Int, CadEntity.Text>>()
        val expander = BlockExpander(doc)

        fun add(i: Int, layerName: String, c: CadColor, polys: List<List<Vec2>>) {
            val layer = doc.layers[layerName]
            val color = AciPalette.resolve(c.aci, c.rgb, layer?.color?.aci ?: 7, layer?.color?.rgb)
            val count = polys.sumOf { maxOf(0, it.size - 1) * 4 }
            if (count == 0) {
                // punto aislado: segmento degenerado para que sea visible y seleccionable
                val p = polys.firstOrNull()?.firstOrNull() ?: return
                list += RenderItem(i, layerName, color, floatArrayOf(
                    (p.x - origin.x).toFloat(), (p.y - origin.y).toFloat(), (p.x - origin.x).toFloat(), (p.y - origin.y).toFloat()),
                    BBox.around(p, 0.0))
                return
            }
            val seg = FloatArray(count)
            var k = 0
            for (pl in polys) for (j in 1 until pl.size) {
                seg[k++] = (pl[j - 1].x - origin.x).toFloat(); seg[k++] = (pl[j - 1].y - origin.y).toFloat()
                seg[k++] = (pl[j].x - origin.x).toFloat(); seg[k++] = (pl[j].y - origin.y).toFloat()
            }
            list += RenderItem(i, layerName, color, seg, BBox.of(polys.flatten()))
        }

        doc.entities.forEachIndexed { i, e ->
            when (e) {
                is CadEntity.Text -> txt += i to e
                is CadEntity.Insert -> expander.expand(e, tol,
                    { l, c, pl -> add(i, l, c, listOf(pl)) },
                    { l, c, t -> txt += i to t.copy(layer = l, color = c) })
                else -> add(i, e.layer, e.color, e.toPolylines(tol))
            }
        }
        items = list
        texts = txt
        index = QuadTree.build(list) { it.bounds }
    }

    /** Puntos notables con Z (para snapping, cotas y pendientes). */
    fun keyPoints(e: CadEntity): List<Vec3> = when (e) {
        is CadEntity.Line -> listOf(e.start, e.end, Vec3((e.start.x + e.end.x) / 2, (e.start.y + e.end.y) / 2, (e.start.z + e.end.z) / 2))
        is CadEntity.Polyline -> e.vertices
        is CadEntity.Circle -> listOf(e.center)
        is CadEntity.Arc -> listOf(e.center)
        is CadEntity.Ellipse -> listOf(e.center)
        is CadEntity.Spline -> e.controlPoints.ifEmpty { e.fitPoints }
        is CadEntity.Point -> listOf(e.position)
        is CadEntity.Insert -> listOf(e.insert)
        is CadEntity.Face3D -> e.corners
        is CadEntity.Text -> listOf(e.insert)
        is CadEntity.Unknown -> emptyList()
    }
}
