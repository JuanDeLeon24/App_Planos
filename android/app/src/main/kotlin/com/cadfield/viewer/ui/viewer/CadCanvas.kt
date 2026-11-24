package com.cadfield.viewer.ui.viewer

import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import com.cadfield.core.cad.geom.Affine2D
import com.cadfield.core.cad.geom.BBox
import com.cadfield.core.cad.geom.Vec2
import com.cadfield.core.versioning.AnnotationRecord
import com.cadfield.viewer.data.AnnotationKind
import com.cadfield.viewer.data.kind
import com.cadfield.viewer.data.payloadObj

/**
 * Lienzo CAD 2D:
 *  - Zoom "infinito" (sólo limitado por precisión doble), pan y rotación con dos dedos.
 *  - Culling por quadtree: sólo se envían a la GPU las entidades dentro del viewport.
 *  - LOD: entidades de tamaño subpíxel se omiten.
 *  - Líneas "hairline" (strokeWidth=0): 1 px nítido a cualquier zoom, sin coste de teselado.
 */
@Composable
fun CadCanvas(
    state: ViewerState,
    view: Affine2D,
    /** Recibe el paso del gesto (pantalla→pantalla); quien posee la vista lo compone con la vista vigente. */
    onViewStep: (Affine2D) -> Unit,
    rotationEnabled: Boolean,
    onTapWorld: (Vec2, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val paint = remember { Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0f; isAntiAlias = false } }
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val annPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFFE53935.toInt() } }
    val annText = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt(); textSize = 34f; setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt()) } }
    val measurePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f; color = 0xFF4FC3F7.toInt() } }
    val matrix = remember { Matrix() }
    val values = remember { FloatArray(9) }
    val buffer = remember { ArrayList<RenderItem>(4096) }
    // Los detectores de gestos viven más que una recomposición: deben leer SIEMPRE la vista
    // actual (no la capturada al crearse), o cada movimiento se aplicaría a la vista inicial.
    val currentView by rememberUpdatedState(view)
    val currentOnViewStep by rememberUpdatedState(onViewStep)
    val currentOnTap by rememberUpdatedState(onTapWorld)

    Canvas(
        modifier
            .pointerInput(rotationEnabled) {
                detectTransformGestures(panZoomLock = !rotationEnabled) { centroid, pan, zoom, rotation ->
                    val c = Vec2(centroid.x.toDouble(), centroid.y.toDouble())
                    val step = Affine2D.translate(c.x + pan.x, c.y + pan.y)
                        .concat(Affine2D.rotate(if (rotationEnabled) Math.toRadians(rotation.toDouble()) else 0.0))
                        .concat(Affine2D.scale(zoom.toDouble()))
                        .concat(Affine2D.translate(-c.x, -c.y))
                    currentOnViewStep(step)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { o: Offset ->
                        val v = currentView
                        val w = v.inverse().apply(Vec2(o.x.toDouble(), o.y.toDouble()))
                        currentOnTap(w, 28.0 / v.scale) // 28 px de radio de captura
                    },
                    onDoubleTap = { o -> // zoom x2 centrado en el toque
                        val step = Affine2D.translate(o.x.toDouble(), o.y.toDouble()).concat(Affine2D.scale(2.0))
                            .concat(Affine2D.translate(-o.x.toDouble(), -o.y.toDouble()))
                        currentOnViewStep(step)
                    },
                )
            }
    ) {
        val render = state.render ?: return@Canvas
        drawCad(render, state, view, paint, textPaint, matrix, values, buffer)
        drawAnnotations(state.annotations, view, annPaint, annText)
        state.measure?.let { m ->
            val pts = m.points.map { view.apply(it.xy()) }
            val nc = drawContext.canvas.nativeCanvas
            for (i in 1 until pts.size) nc.drawLine(pts[i - 1].x.toFloat(), pts[i - 1].y.toFloat(), pts[i].x.toFloat(), pts[i].y.toFloat(), measurePaint)
            if (m.tool == Tool.AREA && pts.size > 2)
                nc.drawLine(pts.last().x.toFloat(), pts.last().y.toFloat(), pts[0].x.toFloat(), pts[0].y.toFloat(), measurePaint)
            pts.forEach { nc.drawCircle(it.x.toFloat(), it.y.toFloat(), 10f, measurePaint) }
        }
    }
}

private fun DrawScope.drawCad(
    render: RenderModel, state: ViewerState, view: Affine2D, paint: Paint, textPaint: Paint,
    matrix: Matrix, values: FloatArray, buffer: ArrayList<RenderItem>,
) {
    val nc = drawContext.canvas.nativeCanvas
    val inv = view.inverse()
    val corners = listOf(Vec2(0.0, 0.0), Vec2(size.width.toDouble(), 0.0), Vec2(0.0, size.height.toDouble()), Vec2(size.width.toDouble(), size.height.toDouble()))
    val worldView = BBox.of(corners.map { inv.apply(it) })
    val layerState = state.layers.associateBy { it.layer.name }
    val minWorld = 1.0 / view.scale // 1 px

    // vista ∘ traslación al origen local (en doble precisión, luego a float)
    val m = view.concat(Affine2D.translate(render.origin.x, render.origin.y))
    values[0] = m.a.toFloat(); values[1] = m.c.toFloat(); values[2] = m.e.toFloat()
    values[3] = m.b.toFloat(); values[4] = m.d.toFloat(); values[5] = m.f.toFloat()
    values[6] = 0f; values[7] = 0f; values[8] = 1f
    matrix.setValues(values)

    buffer.clear()
    render.index.query(worldView, buffer)
    nc.save()
    nc.concat(matrix)
    for (item in buffer) {
        val ls = layerState[item.layer]
        if (ls != null && !ls.visible) continue
        if (item.bounds.width < minWorld && item.bounds.height < minWorld) continue
        if (state.hiddenHandles.isNotEmpty() && render.doc.entities[item.entityIndex].handle in state.hiddenHandles) continue
        val alpha = ((ls?.alpha ?: 1f) * 255).toInt()
        paint.color = (item.argb and 0x00FFFFFF) or (alpha shl 24)
        nc.drawLines(item.segments, paint)
    }
    nc.restore()

    // Textos en espacio de pantalla (legibles, sin deformación por la matriz)
    for ((_, t) in render.texts) {
        val ls = layerState[t.layer]
        if (ls != null && !ls.visible) continue
        val px = (t.height * view.scale).toFloat()
        if (px < 5f || !worldView.contains(t.insert.xy())) continue
        val s = view.apply(t.insert.xy())
        val layer = render.doc.layers[t.layer]
        textPaint.color = AciPalette.resolve(t.color.aci, t.color.rgb, layer?.color?.aci ?: 7, layer?.color?.rgb)
        textPaint.textSize = px
        nc.save()
        nc.rotate((-(t.rotationDeg + Math.toDegrees(view.rotation))).toFloat(), s.x.toFloat(), s.y.toFloat())
        t.value.lineSequence().forEachIndexed { i, line -> nc.drawText(line, s.x.toFloat(), s.y.toFloat() + i * px * 1.3f, textPaint) }
        nc.restore()
    }
}

private fun DrawScope.drawAnnotations(anns: List<AnnotationRecord>, view: Affine2D, p: Paint, t: Paint) {
    val nc = drawContext.canvas.nativeCanvas
    anns.forEachIndexed { i, a ->
        val payload = runCatching { a.payloadObj() }.getOrNull() ?: return@forEachIndexed
        val pts = payload.points.map { view.apply(it.v()) }
        if (pts.isEmpty()) return@forEachIndexed
        val x = pts[0].x.toFloat(); val y = pts[0].y.toFloat()
        when (a.kind) {
            AnnotationKind.CLOUD -> { // nube de revisión: arcos alrededor del punto
                for (k in 0 until 12) {
                    val ang = k * 30.0
                    nc.drawCircle(x + 46f * kotlin.math.cos(Math.toRadians(ang)).toFloat(), y + 46f * kotlin.math.sin(Math.toRadians(ang)).toFloat(), 14f, p)
                }
            }
            AnnotationKind.ARROW -> {
                nc.drawLine(x - 70f, y - 70f, x, y, p); nc.drawLine(x, y, x - 24f, y - 6f, p); nc.drawLine(x, y, x - 6f, y - 24f, p)
            }
            else -> {
                for (k in 1 until pts.size) nc.drawLine(pts[k - 1].x.toFloat(), pts[k - 1].y.toFloat(), pts[k].x.toFloat(), pts[k].y.toFloat(), p)
                nc.drawCircle(x, y, 16f, p)
            }
        }
        nc.drawText("${i + 1}${if (payload.text.isNotBlank()) " · " + payload.text.take(28) else ""}", x + 20f, y - 20f, t)
    }
}
