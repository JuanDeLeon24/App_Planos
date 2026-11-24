package com.cadfield.core.cad.geom

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Discretización de curvas a polilíneas con tolerancia de cuerda (para render y medición). */
object Tessellation {

    /** Número de segmentos para que la flecha (sagitta) no supere [chordTol]. */
    fun segmentsFor(radius: Double, sweepRad: Double, chordTol: Double): Int {
        if (radius <= 0 || chordTol <= 0) return 8
        val t = min(1.0, chordTol / radius)
        val step = 2 * acos(1 - t)
        return max(2, min(4096, ceil(abs(sweepRad) / step).toInt()))
    }

    fun arc(center: Vec2, radius: Double, startRad: Double, endRad: Double, chordTol: Double): List<Vec2> {
        var sweep = endRad - startRad
        while (sweep <= 0) sweep += 2 * PI // arcos DXF van en sentido antihorario
        val n = segmentsFor(radius, sweep, chordTol)
        return (0..n).map { Vec2.polar(center, radius, startRad + sweep * it / n) }
    }

    fun circle(center: Vec2, radius: Double, chordTol: Double): List<Vec2> {
        val n = segmentsFor(radius, 2 * PI, chordTol)
        return (0..n).map { Vec2.polar(center, radius, 2 * PI * it / n) }
    }

    /**
     * Segmento de polilínea con "bulge" (tan(θ/4)). bulge=0 → recta.
     * Devuelve puntos desde p1 (incluido) hasta p2 (incluido).
     */
    fun bulgeSegment(p1: Vec2, p2: Vec2, bulge: Double, chordTol: Double): List<Vec2> {
        if (abs(bulge) < 1e-12) return listOf(p1, p2)
        val theta = 4 * atan(bulge)
        val chord = p1.distanceTo(p2)
        val radius = chord / (2 * kotlin.math.sin(abs(theta) / 2))
        val mid = (p1 + p2) * 0.5
        val dir = (p2 - p1) * (1 / chord)
        val normal = Vec2(-dir.y, dir.x)
        val h = radius * kotlin.math.cos(abs(theta) / 2)
        val sign = if (bulge > 0) 1.0 else -1.0
        val center = mid + normal * (h * sign)
        val a1 = (p1 - center).angle()
        val n = segmentsFor(radius, abs(theta), chordTol)
        return (0..n).map { Vec2.polar(center, radius, a1 + theta * it / n) }
    }

    /** Longitud exacta de un segmento con bulge (arco). */
    fun bulgeLength(p1: Vec2, p2: Vec2, bulge: Double): Double {
        val chord = p1.distanceTo(p2)
        if (abs(bulge) < 1e-12) return chord
        val theta = 4 * atan(abs(bulge))
        val radius = chord / (2 * kotlin.math.sin(theta / 2))
        return radius * theta
    }
}
