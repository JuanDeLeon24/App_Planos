package com.cadfield.core.cad

import com.cadfield.core.cad.geo.Helmert2D
import com.cadfield.core.cad.geo.LatLon
import com.cadfield.core.cad.geo.Utm
import com.cadfield.core.cad.geom.Affine2D
import com.cadfield.core.cad.geom.BBox
import com.cadfield.core.cad.geom.Vec2
import com.cadfield.core.cad.geom.Vec3
import com.cadfield.core.cad.index.QuadTree
import com.cadfield.core.cad.measure.Measurements
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GeometryTest {

    @Test fun `area perimetro y centroide`() {
        val sq = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 3.0), Vec2(0.0, 3.0))
        assertEquals(12.0, Measurements.polygonArea(sq), 1e-12)
        assertEquals(14.0, Measurements.perimeter(sq), 1e-12)
        assertEquals(Vec2(2.0, 1.5), Measurements.centroid(sq))
    }

    @Test fun `longitud con bulge semicircular`() {
        // bulge = 1 → semicírculo sobre cuerda 2 → longitud PI
        val len = Measurements.polylineLength(listOf(Vec2(0.0, 0.0), Vec2(2.0, 0.0)), listOf(1.0))
        assertEquals(PI, len, 1e-12)
    }

    @Test fun `pendiente y cotas`() {
        val s = Measurements.slope(Vec3(0.0, 0.0, 100.0), Vec3(100.0, 0.0, 105.0))
        assertEquals(5.0, s.percent, 1e-12)
        assertEquals(2.8624, s.degrees, 1e-4)
        assertEquals(5.0, s.deltaZ)
    }

    @Test fun `volumen de cubo por malla y areas medias`() {
        val v = listOf(
            Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), Vec3(1.0, 1.0, 0.0), Vec3(0.0, 1.0, 0.0),
            Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 1.0), Vec3(1.0, 1.0, 1.0), Vec3(0.0, 1.0, 1.0),
        )
        val idx = listOf(
            intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
            intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
        )
        val tris = idx.map { Triple(v[it[0]], v[it[1]], v[it[2]]) }
        assertEquals(1.0, Measurements.meshVolume(tris), 1e-12)
        assertEquals(6.0, Measurements.meshArea(tris), 1e-12)
        assertEquals(1250.0, Measurements.averageEndAreaVolume(listOf(0.0 to 50.0, 10.0 to 50.0, 20.0 to 100.0)))
    }

    @Test fun `utm ida y vuelta`() {
        val p = LatLon(-12.0464, -77.0428) // Lima
        val u = Utm.fromLatLon(p)
        assertEquals(18, u.zone)
        assertFalse(u.north)
        assertEquals(277_617.0, u.easting, 50.0)
        val back = Utm.toLatLon(u)
        assertEquals(p.lat, back.lat, 1e-8)
        assertEquals(p.lon, back.lon, 1e-8)
    }

    @Test fun `utm en meridiano central coincide con arco de meridiano`() {
        // Arco de meridiano WGS84 a 45° = 4 984 944.378 m; k0 = 0.9996
        val u = Utm.fromLatLon(LatLon(45.0, -75.0))
        assertEquals(500_000.0, u.easting, 1e-6)
        assertEquals(0.9996 * 4_984_944.378, u.northing, 0.01)
        assertEquals(10_000_000.0, Utm.fromLatLon(LatLon(-1e-12, -75.0)).northing, 1e-3)
    }

    @Test fun `helmert ajusta local a utm`() {
        val truth = Helmert2D(0.0, 1.0, 500000.0, 8000000.0) // rotación 90°, escala 1
        val pts = listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(0.0, 50.0)).map { it to truth.apply(it) }
        val h = Helmert2D.fit(pts)
        assertEquals(90.0, h.rotationDeg, 1e-9)
        assertEquals(1.0, h.scale, 1e-12)
        val q = h.apply(Vec2(10.0, 10.0))
        assertEquals(truth.apply(Vec2(10.0, 10.0)).x, q.x, 1e-6)
    }

    @Test fun `afin inversa y ajuste`() {
        val t = Affine2D.fit(BBox(0.0, 0.0, 100.0, 50.0), 1000.0, 500.0, 0.0)
        val p = t.apply(Vec2(100.0, 50.0))
        assertEquals(1000.0, p.x, 1e-9); assertEquals(0.0, p.y, 1e-9)
        val back = t.inverse().apply(p)
        assertEquals(100.0, back.x, 1e-9)
        val r = Affine2D.rotate(PI / 2).concat(t)
        assertEquals(t.scale, r.scale, 1e-9)
    }

    @Test fun `quadtree consulta por ventana`() {
        val pts = (0 until 10_000).map { Vec2((it % 100).toDouble(), (it / 100).toDouble()) }
        val qt = QuadTree.build(pts) { BBox.around(it, 0.1) }
        assertEquals(100, qt.query(BBox(10.0, 10.0, 19.5, 19.5)).size)
        assertEquals(1, qt.queryPoint(Vec2(50.0, 50.0), 0.05).size)
    }
}
