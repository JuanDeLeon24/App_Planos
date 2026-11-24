package com.cadfield.core.cad.model

import com.cadfield.core.cad.geom.Affine2D
import com.cadfield.core.cad.geom.BBox
import com.cadfield.core.cad.geom.Vec2
import com.cadfield.core.cad.geom.Vec3

/**
 * Expande referencias a bloques (INSERT, DIMENSION) a geometría en coordenadas de mundo:
 *  - bloques anidados (hasta [maxDepth] niveles, protegido contra ciclos)
 *  - escala/rotación/punto base compuestos en doble precisión
 *  - herencia CAD estándar: capa "0" → capa del INSERT; color BYBLOCK → color del INSERT
 *  - textos de bloque y atributos visibles
 */
class BlockExpander(private val doc: CadDocument, private val maxDepth: Int = 8) {

    fun interface GeometrySink { fun polyline(layer: String, color: CadColor, points: List<Vec2>) }
    fun interface TextSink { fun text(layer: String, color: CadColor, text: CadEntity.Text) }

    private val blockBoundsCache = HashMap<String, BBox>()

    fun transformOf(ins: CadEntity.Insert): Affine2D {
        val base = doc.blocks[ins.blockName]?.basePoint ?: Vec3(0.0, 0.0)
        return Affine2D.translate(ins.insert.x, ins.insert.y)
            .concat(Affine2D.rotate(Math.toRadians(ins.rotationDeg)))
            .concat(Affine2D(a = ins.scaleX, d = ins.scaleY))
            .concat(Affine2D.translate(-base.x, -base.y))
    }

    fun expand(ins: CadEntity.Insert, chordTol: Double, geometry: GeometrySink, texts: TextSink? = null) {
        expandInto(ins, Affine2D.IDENTITY, ins.layer, ins.color, chordTol, geometry, texts, 0, HashSet())
        if (texts != null) ins.attribTexts.forEach { texts.text(it.layer, it.color, it) }
    }

    private fun expandInto(
        ins: CadEntity.Insert, parent: Affine2D, parentLayer: String, parentColor: CadColor,
        chordTol: Double, geometry: GeometrySink, texts: TextSink?, depth: Int, stack: MutableSet<String>,
    ) {
        val block = doc.blocks[ins.blockName] ?: return
        if (depth >= maxDepth || !stack.add(ins.blockName)) return
        val t = parent.concat(transformOf(ins))
        val localTol = chordTol / t.scale.coerceAtLeast(1e-12)
        val layer = if (ins.layer == "0") parentLayer else ins.layer
        val color = if (ins.color.aci == CadColor.BY_BLOCK) parentColor else ins.color
        for (e in block.entities) {
            val el = if (e.layer == "0") layer else e.layer
            val ec = if (e.color.aci == CadColor.BY_BLOCK) color else e.color
            when (e) {
                is CadEntity.Insert -> {
                    expandInto(e, t, layer, color, chordTol, geometry, texts, depth + 1, stack)
                    if (texts != null) e.attribTexts.forEach { a -> texts.text(a.layer, a.color, transformText(a, t)) }
                }
                is CadEntity.Text -> texts?.text(el, ec, transformText(e, t))
                else -> for (pl in e.toPolylines(localTol)) if (pl.isNotEmpty()) geometry.polyline(el, ec, pl.map(t::apply))
            }
        }
        stack.remove(ins.blockName)
    }

    private fun transformText(e: CadEntity.Text, t: Affine2D): CadEntity.Text {
        val p = t.apply(e.insert.xy())
        return e.copy(insert = Vec3(p.x, p.y, e.insert.z), height = e.height * t.scale,
            rotationDeg = e.rotationDeg + Math.toDegrees(t.rotation))
    }

    /** Caja envolvente en mundo de un INSERT (usa la caja local del bloque, cacheada). */
    fun bounds(ins: CadEntity.Insert): BBox {
        val local = blockBounds(ins.blockName, 0) ?: return BBox.around(ins.insert.xy(), 0.0)
        if (local.isEmpty) return BBox.around(ins.insert.xy(), 0.0)
        val t = transformOf(ins)
        return BBox.of(listOf(Vec2(local.minX, local.minY), Vec2(local.maxX, local.minY),
            Vec2(local.minX, local.maxY), Vec2(local.maxX, local.maxY)).map(t::apply))
    }

    private fun blockBounds(name: String, depth: Int): BBox? {
        blockBoundsCache[name]?.let { return it }
        val block = doc.blocks[name] ?: return null
        if (depth > maxDepth) return BBox.EMPTY
        blockBoundsCache[name] = BBox.EMPTY // protección contra ciclos
        var b = BBox.EMPTY
        for (e in block.entities) b = b.union(
            if (e is CadEntity.Insert) {
                val inner = blockBounds(e.blockName, depth + 1)
                if (inner == null || inner.isEmpty) BBox.around(e.insert.xy(), 0.0) else {
                    val t = transformOf(e)
                    BBox.of(listOf(Vec2(inner.minX, inner.minY), Vec2(inner.maxX, inner.minY),
                        Vec2(inner.minX, inner.maxY), Vec2(inner.maxX, inner.maxY)).map(t::apply))
                }
            } else e.bounds()
        )
        blockBoundsCache[name] = b
        return b
    }
}
