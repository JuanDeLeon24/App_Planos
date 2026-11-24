package com.cadfield.core.cad.dxf

import com.cadfield.core.cad.model.CadEntity
import com.cadfield.core.cad.model.Layer
import java.io.Writer
import java.util.Locale

/**
 * Escritor DXF (R12 ASCII, máxima compatibilidad). Se usa para exportar
 * anotaciones/observaciones de campo como capa superpuesta ("overlay")
 * SIN modificar jamás el plano original.
 */
class DxfWriter(private val out: Writer) {

    fun write(layers: Collection<Layer>, entities: Collection<CadEntity>) {
        pair(0, "SECTION"); pair(2, "HEADER")
        pair(9, "\$ACADVER"); pair(1, "AC1009")
        pair(0, "ENDSEC")
        pair(0, "SECTION"); pair(2, "TABLES")
        pair(0, "TABLE"); pair(2, "LAYER"); pair(70, layers.size.toString())
        for (l in layers) {
            pair(0, "LAYER"); pair(2, l.name); pair(70, "0")
            pair(62, (if (l.visible) l.color.aci else -l.color.aci).toString()); pair(6, l.lineType)
        }
        pair(0, "ENDTAB"); pair(0, "ENDSEC")
        pair(0, "SECTION"); pair(2, "ENTITIES")
        entities.forEach(::entity)
        pair(0, "ENDSEC"); pair(0, "EOF")
        out.flush()
    }

    private fun entity(e: CadEntity) {
        when (e) {
            is CadEntity.Line -> { head("LINE", e); xyz(10, e.start.x, e.start.y, e.start.z); xyz(11, e.end.x, e.end.y, e.end.z) }
            is CadEntity.Circle -> { head("CIRCLE", e); xyz(10, e.center.x, e.center.y, e.center.z); num(40, e.radius) }
            is CadEntity.Arc -> { head("ARC", e); xyz(10, e.center.x, e.center.y, e.center.z); num(40, e.radius); num(50, e.startDeg); num(51, e.endDeg) }
            is CadEntity.Point -> { head("POINT", e); xyz(10, e.position.x, e.position.y, e.position.z) }
            is CadEntity.Text -> e.value.split('\n').forEachIndexed { i, line ->
                // R12 TEXT es de una sola línea: el texto multilínea se emite como líneas apiladas
                val dy = -i * e.height * 1.5
                val rad = Math.toRadians(e.rotationDeg)
                head("TEXT", e)
                xyz(10, e.insert.x - dy * Math.sin(rad), e.insert.y + dy * Math.cos(rad), e.insert.z)
                num(40, e.height); pair(1, line.replace("\r", "")); num(50, e.rotationDeg)
            }
            is CadEntity.Polyline -> {
                head("POLYLINE", e); pair(66, "1"); xyz(10, 0.0, 0.0, 0.0); pair(70, if (e.closed) "1" else "0")
                e.vertices.forEachIndexed { i, v ->
                    pair(0, "VERTEX"); pair(8, e.layer); xyz(10, v.x, v.y, v.z)
                    e.bulges.getOrNull(i)?.takeIf { it != 0.0 }?.let { num(42, it) }
                }
                pair(0, "SEQEND"); pair(8, e.layer)
            }
            is CadEntity.Face3D -> { head("3DFACE", e); e.corners.forEachIndexed { i, c -> xyz(10 + i, c.x, c.y, c.z) } }
            else -> Unit // INSERT/Unknown no se re-emiten en el overlay
        }
    }

    private fun head(type: String, e: CadEntity) {
        pair(0, type); pair(8, e.layer)
        if (e.color.aci != 256) pair(62, e.color.aci.toString())
    }

    private fun xyz(base: Int, x: Double, y: Double, z: Double) { num(base, x); num(base + 10, y); num(base + 20, z) }
    private fun num(code: Int, v: Double) = pair(code, String.format(Locale.ROOT, "%.10g", v))
    private fun pair(code: Int, v: String) { out.write(code.toString()); out.write("\n"); out.write(v); out.write("\n") }
}
