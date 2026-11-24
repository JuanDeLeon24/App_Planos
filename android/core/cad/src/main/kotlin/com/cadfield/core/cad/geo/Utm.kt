package com.cadfield.core.cad.geo

import com.cadfield.core.cad.geom.Vec2
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class UtmCoordinate(val zone: Int, val north: Boolean, val easting: Double, val northing: Double) {
    override fun toString() = "%d%s E %.3f N %.3f".format(zone, if (north) "N" else "S", easting, northing)
}

data class LatLon(val lat: Double, val lon: Double)

/**
 * Conversión geográfica WGS84 ↔ UTM (series de Krüger, precisión sub-milimétrica
 * dentro de la zona). Permite asociar GPS de campo a coordenadas del plano.
 */
object Utm {
    private const val A = 6378137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996
    private val N = F / (2 - F)
    private val A_ = A / (1 + N) * (1 + N * N / 4 + N * N * N * N / 64)
    private val alpha = doubleArrayOf(
        N / 2 - 2 * N * N / 3 + 5 * N * N * N / 16,
        13 * N * N / 48 - 3 * N * N * N / 5,
        61 * N * N * N / 240,
    )
    private val beta = doubleArrayOf(
        N / 2 - 2 * N * N / 3 + 37 * N * N * N / 96,
        N * N / 48 + N * N * N / 15,
        17 * N * N * N / 480,
    )
    private val delta = doubleArrayOf(
        2 * N - 2 * N * N / 3 - 2 * N * N * N,
        7 * N * N / 3 - 8 * N * N * N / 5,
        56 * N * N * N / 15,
    )

    fun zoneFor(lon: Double) = (floor((lon + 180) / 6).toInt() % 60) + 1

    fun fromLatLon(p: LatLon, forcedZone: Int? = null): UtmCoordinate {
        val zone = forcedZone ?: zoneFor(p.lon)
        val lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3.0)
        val phi = Math.toRadians(p.lat)
        val lam = Math.toRadians(p.lon) - lon0
        val e = 2 * sqrt(N) / (1 + N)
        val t = kotlin.math.sinh(atanh(sin(phi)) - e * atanh(e * sin(phi)))
        val xiP = atan2(t, cos(lam))
        val etaP = atanh(sin(lam) / sqrt(1 + t * t))
        var xi = xiP; var eta = etaP
        for (j in 1..3) {
            xi += alpha[j - 1] * sin(2 * j * xiP) * kotlin.math.cosh(2 * j * etaP)
            eta += alpha[j - 1] * cos(2 * j * xiP) * kotlin.math.sinh(2 * j * etaP)
        }
        val easting = 500000 + K0 * A_ * eta
        var northing = K0 * A_ * xi
        val north = p.lat >= 0
        if (!north) northing += 10_000_000
        return UtmCoordinate(zone, north, easting, northing)
    }

    fun toLatLon(u: UtmCoordinate): LatLon {
        val xi = (if (u.north) u.northing else u.northing - 10_000_000) / (K0 * A_)
        val eta = (u.easting - 500000) / (K0 * A_)
        var xiP = xi; var etaP = eta
        for (j in 1..3) {
            xiP -= beta[j - 1] * sin(2 * j * xi) * kotlin.math.cosh(2 * j * eta)
            etaP -= beta[j - 1] * cos(2 * j * xi) * kotlin.math.sinh(2 * j * eta)
        }
        val chi = kotlin.math.asin(sin(xiP) / kotlin.math.cosh(etaP))
        var phi = chi
        for (j in 1..3) phi += delta[j - 1] * sin(2 * j * chi)
        val lon0 = (u.zone - 1) * 6 - 180 + 3.0
        val lam = atan(kotlin.math.sinh(etaP) / cos(xiP))
        return LatLon(Math.toDegrees(phi), lon0 + Math.toDegrees(lam))
    }

    private fun atanh(x: Double) = 0.5 * kotlin.math.ln((1 + x) / (1 - x))
}

/**
 * Transformación de semejanza 2D (Helmert, 4 parámetros) entre coordenadas
 * locales de obra (p.ej. sistema de túnel/progresivas) y UTM, ajustada por
 * mínimos cuadrados con ≥2 puntos de control.
 */
data class Helmert2D(val a: Double, val b: Double, val tx: Double, val ty: Double) {
    fun apply(p: Vec2) = Vec2(a * p.x - b * p.y + tx, b * p.x + a * p.y + ty)
    val scale get() = sqrt(a * a + b * b)
    val rotationDeg get() = Math.toDegrees(atan2(b, a))

    companion object {
        /** pares (local, destino). */
        fun fit(pairs: List<Pair<Vec2, Vec2>>): Helmert2D {
            require(pairs.size >= 2) { "Se requieren al menos 2 puntos de control" }
            val n = pairs.size
            val cs = Vec2(pairs.sumOf { it.first.x } / n, pairs.sumOf { it.first.y } / n)
            val cd = Vec2(pairs.sumOf { it.second.x } / n, pairs.sumOf { it.second.y } / n)
            var sxx = 0.0; var sxy = 0.0; var ss = 0.0
            for ((s, d) in pairs) {
                val p = s - cs; val q = d - cd
                sxx += p.x * q.x + p.y * q.y
                sxy += p.x * q.y - p.y * q.x
                ss += p.x * p.x + p.y * p.y
            }
            val a = sxx / ss; val b = sxy / ss
            return Helmert2D(a, b, cd.x - (a * cs.x - b * cs.y), cd.y - (b * cs.x + a * cs.y))
        }
    }
}
