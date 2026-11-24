package com.cadfield.viewer.export

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.cadfield.core.cad.geom.Affine2D
import com.cadfield.core.cad.geom.BBox
import com.cadfield.core.cad.model.CadDocument
import com.cadfield.core.cad.model.CadEntity
import com.cadfield.core.versioning.AnnotationRecord
import com.cadfield.viewer.data.kind
import com.cadfield.viewer.data.payloadObj
import com.cadfield.viewer.ui.viewer.AciPalette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

data class ReportInfo(
    val project: String, val document: String, val version: String, val user: String,
    val signature: String? = null,
)

/**
 * Exportación técnica: PDF vectorial (A3 horizontal) con plano + observaciones +
 * cajetín (proyecto, versión, escala gráfica, fecha, usuario, firma), y CSV de observaciones.
 */
object Exporters {
    private const val A3_W = 1191 // pt (420 mm)
    private const val A3_H = 842  // pt (297 mm)

    fun pdf(ctx: Context, doc: CadDocument, hiddenLayers: Set<String>, annotations: Collection<AnnotationRecord>,
            info: ReportInfo, view: BBox = doc.focusExtents): File {
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(A3_W, A3_H, 1).create())
        val c = page.canvas
        val drawArea = BBox(20.0, 20.0, A3_W - 20.0, A3_H - 140.0)
        val t = Affine2D.fit(view, drawArea.width, drawArea.height, 10.0).let { Affine2D.translate(20.0, 20.0).concat(it) }
        val tol = view.width / 4000
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.4f }

        c.save()
        c.clipRect(drawArea.minX.toFloat(), drawArea.minY.toFloat(), drawArea.maxX.toFloat(), drawArea.maxY.toFloat())
        val expander = com.cadfield.core.cad.model.BlockExpander(doc)
        fun stroke(layer: String, color: com.cadfield.core.cad.model.CadColor, pl: List<com.cadfield.core.cad.geom.Vec2>) {
            if (layer in hiddenLayers || pl.isEmpty()) return
            val aci = if (color.aci == 256 || color.aci == 0) doc.layers[layer]?.color?.aci ?: 7 else color.aci
            paint.color = if (aci == 7) Color.BLACK else AciPalette.argb(aci) // fondo blanco en papel
            val path = Path()
            pl.forEachIndexed { i, p -> val s = t.apply(p); if (i == 0) path.moveTo(s.x.toFloat(), s.y.toFloat()) else path.lineTo(s.x.toFloat(), s.y.toFloat()) }
            c.drawPath(path, paint)
        }
        for (e in doc.entities) {
            if (e is CadEntity.Insert) {
                if (expander.bounds(e).intersects(view)) expander.expand(e, tol, { l, col, pl -> stroke(l, col, pl) })
                continue
            }
            if (e.layer in hiddenLayers || !e.bounds().intersects(view)) continue
            val aci = if (e.color.aci == 256) doc.layers[e.layer]?.color?.aci ?: 7 else e.color.aci
            paint.color = if (aci == 7) Color.BLACK else AciPalette.argb(aci)
            for (pl in e.toPolylines(tol)) stroke(e.layer, e.color, pl)
            if (e is CadEntity.Text) {
                val s = t.apply(e.insert.xy())
                val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paint.color; textSize = (e.height * t.scale).toFloat().coerceAtLeast(2f) }
                c.drawText(e.value.lineSequence().first(), s.x.toFloat(), s.y.toFloat(), txt)
            }
        }
        // Observaciones en rojo, numeradas
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 30, 30); strokeWidth = 1.2f; style = Paint.Style.STROKE }
        val redText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 30, 30); textSize = 9f }
        val active = annotations.filter { !it.deleted }.sortedBy { it.modifiedAt }
        active.forEachIndexed { i, a ->
            val pts = a.payloadObj().points.map { t.apply(it.v()) }
            if (pts.isEmpty()) return@forEachIndexed
            for (k in 1 until pts.size) c.drawLine(pts[k - 1].x.toFloat(), pts[k - 1].y.toFloat(), pts[k].x.toFloat(), pts[k].y.toFloat(), red)
            c.drawCircle(pts[0].x.toFloat(), pts[0].y.toFloat(), 6f, red)
            c.drawText("${i + 1}", pts[0].x.toFloat() - 3, pts[0].y.toFloat() + 3, redText)
        }
        c.restore()

        // Cajetín
        val y0 = A3_H - 130f
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.BLACK }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.BLACK }
        c.drawRect(20f, y0, A3_W - 20f, A3_H - 20f, box)
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
        listOf("Proyecto: ${info.project}", "Documento: ${info.document}", "Versión: ${info.version}",
            "Usuario: ${info.user}", "Fecha: $date", "Unidades: ${doc.units}").forEachIndexed { i, s ->
            c.drawText(s, 30f, y0 + 18 + i * 16, txt)
        }
        // Escala gráfica: segmento "redondo" ≈ 100 pt
        val worldLen = 100 / t.scale
        val nice = 10.0.pow(kotlin.math.floor(kotlin.math.log10(worldLen)))
        val px = (nice * t.scale).toFloat()
        c.drawLine(300f, y0 + 30, 300f + px, y0 + 30, box)
        c.drawText("0", 296f, y0 + 44, txt); c.drawText("${nice.toBigDecimal().stripTrailingZeros().toPlainString()} ${doc.units.name.lowercase()}", 300f + px - 10, y0 + 44, txt)
        // Observaciones (primeras 6)
        active.take(6).forEachIndexed { i, a ->
            val p = a.payloadObj()
            c.drawText("${i + 1}. [${a.kind}] ${p.text.take(70)} ${p.value?.let { "= %.3f %s".format(it, p.unit ?: "") } ?: ""} — ${a.author}",
                520f, y0 + 18 + i * 16, txt)
        }
        if (active.size > 6) c.drawText("… ${active.size - 6} observaciones más en el CSV adjunto", 520f, y0 + 114, txt)
        c.drawText("Firma digital: ${info.signature?.take(32) ?: "—"}", 30f, A3_H - 26f, txt)
        pdf.finishPage(page)

        val out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "${info.document}_${info.version}.pdf")
        out.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        return out
    }

    fun csv(ctx: Context, annotations: Collection<AnnotationRecord>, name: String): File {
        val out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "$name-observaciones.csv")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        out.bufferedWriter().use { w ->
            w.write("﻿id;tipo;texto;valor;unidad;estado;x;y;lat;lon;usuario;fecha;eliminada\n") // BOM → Excel
            for (a in annotations.sortedBy { it.modifiedAt }) {
                val p = a.payloadObj()
                val first = p.points.firstOrNull()
                w.write(listOf(a.id, a.type, p.text, p.value ?: "", p.unit ?: "", p.status, first?.x ?: "", first?.y ?: "",
                    p.lat ?: "", p.lon ?: "", a.author, fmt.format(Date(a.modifiedAt)), a.deleted)
                    .joinToString(";") { "\"" + it.toString().replace("\"", "\"\"") + "\"" })
                w.write("\n")
            }
        }
        return out
    }

    /** Hoja de compartir del sistema: correo, WhatsApp, Teams, OneDrive, SharePoint, Drive... */
    fun share(ctx: Context, files: List<File>) {
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) })
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (files.all { it.extension == "pdf" }) "application/pdf" else "*/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
