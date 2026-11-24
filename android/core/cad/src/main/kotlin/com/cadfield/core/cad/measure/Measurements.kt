package com.cadfield.core.cad.measure

import com.cadfield.core.cad.geom.Tessellation
import com.cadfield.core.cad.geom.Vec2
import com.cadfield.core.cad.geom.Vec3
import com.cadfield.core.cad.model.DrawingUnits
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.sqrt

/** Resultado de medición siempre expresado en unidades del dibujo y en metros. */
data class MeasureResult(val value: Double, val unit: String, val meters: Double? = null)

/** Motor de mediciones técnicas (distancias, áreas, pendientes, cotas, volúmenes). */
object Measurements {

    fun distance2D(a: Vec2, b: Vec2) = a.distanceTo(b)

    fun distance3D(a: Vec3, b: Vec3) = (b - a).length()

    /** Longitud acumulada de una polilínea (con arcos por bulge). */
    fun polylineLength(points: List<Vec2>, bulges: List<Double> = emptyList(), closed: Boolean = false): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        val n = if (closed) points.size else points.size - 1
        for (i in 0 until n) total += Tessellation.bulgeLength(points[i], points[(i + 1) % points.size], bulges.getOrElse(i) { 0.0 })
        return total
    }

    /** Área por fórmula de Gauss (shoelace). Valor absoluto; polígono implícitamente cerrado. */
    fun polygonArea(points: List<Vec2>): Double = abs(signedArea(points))

    fun signedArea(points: List<Vec2>): Double {
        if (points.size < 3) return 0.0
        var s = 0.0
        for (i in points.indices) s += points[i] cross points[(i + 1) % points.size]
        return s / 2
    }

    fun centroid(points: List<Vec2>): Vec2 {
        val a = signedArea(points)
        if (abs(a) < 1e-15) return Vec2(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
        var cx = 0.0; var cy = 0.0
        for (i in points.indices) {
            val p = points[i]; val q = points[(i + 1) % points.size]
            val f = p cross q
            cx += (p.x + q.x) * f; cy += (p.y + q.y) * f
        }
        return Vec2(cx / (6 * a), cy / (6 * a))
    }

    fun perimeter(points: List<Vec2>) = polylineLength(points, closed = true)

    /** Pendiente entre dos puntos 3D. */
    data class Slope(val percent: Double, val degrees: Double, val ratioH: Double, val deltaZ: Double, val horizontal: Double)

    fun slope(a: Vec3, b: Vec3): Slope {
        val h = hypot(b.x - a.x, b.y - a.y)
        val dz = b.z - a.z
        val pct = if (h == 0.0) Double.POSITIVE_INFINITY else dz / h * 100
        val deg = Math.toDegrees(atan(if (h == 0.0) Double.POSITIVE_INFINITY else dz / h))
        return Slope(pct, deg, if (dz == 0.0) Double.POSITIVE_INFINITY else h / abs(dz), dz, h)
    }

    fun elevationDifference(a: Vec3, b: Vec3) = b.z - a.z

    /**
     * Volumen encerrado por una malla triangular cerrada (teorema de la divergencia).
     * Triángulos con orientación consistente; el signo se descarta.
     */
    fun meshVolume(triangles: List<Triple<Vec3, Vec3, Vec3>>): Double =
        abs(triangles.sumOf { (a, b, c) -> a dot (b cross c) } / 6.0)

    /** Área de superficie de una malla triangular. */
    fun meshArea(triangles: List<Triple<Vec3, Vec3, Vec3>>): Double =
        triangles.sumOf { (a, b, c) -> ((b - a) cross (c - a)).length() / 2 }

    /**
     * Volumen de excavación/relleno por método de áreas medias entre secciones
     * (práctica estándar en túneles y movimiento de tierras).
     * @param stations lista (progresiva, área de sección).
     */
    fun averageEndAreaVolume(stations: List<Pair<Double, Double>>): Double =
        stations.zipWithNext().sumOf { (s1, s2) -> (s2.first - s1.first) * (s1.second + s2.second) / 2 }

    /** Volumen prismoidal (más preciso) dados área inicial, media y final. */
    fun prismoidalVolume(length: Double, a1: Double, am: Double, a2: Double) = length / 6 * (a1 + 4 * am + a2)

    fun toMeters(value: Double, units: DrawingUnits, power: Int = 1): Double {
        var f = 1.0
        repeat(power) { f *= units.toMeters }
        return value * f
    }

    /** Distancia punto–segmento (para snapping y selección). */
    fun pointSegmentDistance(p: Vec2, a: Vec2, b: Vec2): Double {
        val ab = b - a
        val len2 = ab dot ab
        if (len2 == 0.0) return p.distanceTo(a)
        val t = (((p - a) dot ab) / len2).coerceIn(0.0, 1.0)
        return p.distanceTo(a + ab * t)
    }

    fun rms(values: List<Double>) = sqrt(values.sumOf { it * it } / values.size)
}
