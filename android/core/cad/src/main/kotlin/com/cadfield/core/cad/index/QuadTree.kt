package com.cadfield.core.cad.index

import com.cadfield.core.cad.geom.BBox
import com.cadfield.core.cad.geom.Vec2

/**
 * Índice espacial (quadtree de cajas) para:
 *  - culling de viewport (sólo se dibuja lo visible → planos de cientos de MB fluidos)
 *  - selección/picking por toque
 *  - snapping a vértices.
 */
class QuadTree<T>(
    private val bounds: BBox,
    private val maxItems: Int = 32,
    private val maxDepth: Int = 12,
    private val depth: Int = 0,
) {
    private val items = ArrayList<Pair<BBox, T>>()
    private var children: Array<QuadTree<T>>? = null
    var size = 0
        private set

    fun insert(box: BBox, value: T) {
        size++
        val ch = children
        if (ch != null) {
            ch.firstOrNull { it.bounds.contains(box) }?.let { it.insert(box, value); return }
            items += box to value
            return
        }
        items += box to value
        if (items.size > maxItems && depth < maxDepth) split()
    }

    private fun split() {
        val c = bounds.center
        val ch = arrayOf(
            QuadTree<T>(BBox(bounds.minX, bounds.minY, c.x, c.y), maxItems, maxDepth, depth + 1),
            QuadTree<T>(BBox(c.x, bounds.minY, bounds.maxX, c.y), maxItems, maxDepth, depth + 1),
            QuadTree<T>(BBox(bounds.minX, c.y, c.x, bounds.maxY), maxItems, maxDepth, depth + 1),
            QuadTree<T>(BBox(c.x, c.y, bounds.maxX, bounds.maxY), maxItems, maxDepth, depth + 1),
        )
        children = ch
        val keep = ArrayList<Pair<BBox, T>>()
        for (it in items) {
            val target = ch.firstOrNull { q -> q.bounds.contains(it.first) }
            if (target != null) target.insert(it.first, it.second) else keep += it
        }
        items.clear(); items.addAll(keep)
    }

    fun query(area: BBox, out: MutableList<T> = ArrayList()): MutableList<T> {
        if (!bounds.intersects(area) && depth > 0) return out
        for ((b, v) in items) if (b.intersects(area)) out += v
        children?.forEach { it.query(area, out) }
        return out
    }

    fun queryPoint(p: Vec2, tolerance: Double) = query(BBox.around(p, tolerance))

    companion object {
        fun <T> build(items: List<T>, boxOf: (T) -> BBox): QuadTree<T> {
            var world = BBox.EMPTY
            val boxes = items.map { boxOf(it).also { b -> world = world.union(b) } }
            if (world.isEmpty) world = BBox(0.0, 0.0, 1.0, 1.0)
            val tree = QuadTree<T>(world.expand(maxOf(world.width, world.height) * 0.01 + 1e-9))
            items.forEachIndexed { i, it -> if (!boxes[i].isEmpty) tree.insert(boxes[i], it) }
            return tree
        }
    }
}
