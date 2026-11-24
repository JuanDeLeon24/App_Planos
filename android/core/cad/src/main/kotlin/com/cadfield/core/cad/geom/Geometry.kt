package com.cadfield.core.cad.geom

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Punto/vector 2D en coordenadas de mundo (unidades del dibujo). */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    infix fun cross(o: Vec2) = x * o.y - y * o.x
    fun length() = hypot(x, y)
    fun distanceTo(o: Vec2) = hypot(o.x - x, o.y - y)
    fun angle() = atan2(y, x)

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        fun polar(center: Vec2, radius: Double, angleRad: Double) =
            Vec2(center.x + radius * cos(angleRad), center.y + radius * sin(angleRad))
    }
}

/** Punto 3D (la Z se conserva para cotas, pendientes y modelos 3D). */
data class Vec3(val x: Double, val y: Double, val z: Double = 0.0) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun xy() = Vec2(x, y)
}

/** Caja envolvente alineada a ejes. Inmutable. */
data class BBox(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width get() = maxX - minX
    val height get() = maxY - minY
    val center get() = Vec2((minX + maxX) / 2, (minY + maxY) / 2)
    val isEmpty get() = minX > maxX || minY > maxY

    fun intersects(o: BBox) = !(o.minX > maxX || o.maxX < minX || o.minY > maxY || o.maxY < minY)
    fun contains(p: Vec2) = p.x in minX..maxX && p.y in minY..maxY
    fun contains(o: BBox) = o.minX >= minX && o.maxX <= maxX && o.minY >= minY && o.maxY <= maxY
    fun union(o: BBox) = if (isEmpty) o else if (o.isEmpty) this else
        BBox(min(minX, o.minX), min(minY, o.minY), max(maxX, o.maxX), max(maxY, o.maxY))
    fun expand(d: Double) = BBox(minX - d, minY - d, maxX + d, maxY + d)

    companion object {
        val EMPTY = BBox(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        fun of(points: Iterable<Vec2>): BBox {
            var b = EMPTY
            for (p in points) b = BBox(min(b.minX, p.x), min(b.minY, p.y), max(b.maxX, p.x), max(b.maxY, p.y))
            return b
        }
        fun around(c: Vec2, r: Double) = BBox(c.x - r, c.y - r, c.x + r, c.y + r)
    }
}

/**
 * Transformación afín 2D (mundo -> pantalla) con zoom, rotación y desplazamiento.
 * | a c e |
 * | b d f |
 */
data class Affine2D(
    val a: Double = 1.0, val b: Double = 0.0,
    val c: Double = 0.0, val d: Double = 1.0,
    val e: Double = 0.0, val f: Double = 0.0,
) {
    fun apply(p: Vec2) = Vec2(a * p.x + c * p.y + e, b * p.x + d * p.y + f)

    /** this ∘ o : primero se aplica [o], luego this. */
    fun concat(o: Affine2D) = Affine2D(
        a = a * o.a + c * o.b, b = b * o.a + d * o.b,
        c = a * o.c + c * o.d, d = b * o.c + d * o.d,
        e = a * o.e + c * o.f + e, f = b * o.e + d * o.f + f,
    )

    fun inverse(): Affine2D {
        val det = a * d - b * c
        require(det != 0.0) { "Transformación no invertible" }
        return Affine2D(
            a = d / det, b = -b / det, c = -c / det, d = a / det,
            e = (c * f - d * e) / det, f = (b * e - a * f) / det,
        )
    }

    val scale get() = sqrt(a * a + b * b)
    val rotation get() = atan2(b, a)

    companion object {
        val IDENTITY = Affine2D()
        fun translate(dx: Double, dy: Double) = Affine2D(e = dx, f = dy)
        fun scale(s: Double) = Affine2D(a = s, d = s)
        fun rotate(rad: Double) = Affine2D(a = cos(rad), b = sin(rad), c = -sin(rad), d = cos(rad))

        /** Ajusta [world] dentro de un viewport de [w]x[h] px, invirtiendo Y (CAD Y-arriba → pantalla Y-abajo). */
        fun fit(world: BBox, w: Double, h: Double, marginPx: Double = 16.0): Affine2D {
            if (world.isEmpty || world.width == 0.0 && world.height == 0.0) return translate(w / 2, h / 2)
            val s = min((w - 2 * marginPx) / max(world.width, 1e-12), (h - 2 * marginPx) / max(world.height, 1e-12))
            val c = world.center
            return Affine2D(a = s, d = -s, e = w / 2 - s * c.x, f = h / 2 + s * c.y)
        }
    }
}
