package com.cadfield.core.cad

import com.cadfield.core.cad.dxf.DxfParser
import com.cadfield.core.cad.geom.Vec2
import com.cadfield.core.cad.geom.Vec3
import com.cadfield.core.cad.model.Block
import com.cadfield.core.cad.model.BlockExpander
import com.cadfield.core.cad.model.CadColor
import com.cadfield.core.cad.model.CadDocument
import com.cadfield.core.cad.model.CadEntity
import com.cadfield.core.cad.model.Layer
import com.cadfield.core.cad.measure.Measurements
import java.io.StringReader
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealDrawingTest {

    private fun dxf(vararg pairs: Pair<Int, Any>) = pairs.joinToString("\n", postfix = "\n") { "${it.first}\n${it.second}" }

    /** DXF generado por LibreDWG a partir de un DWG 2018 real (example_2018.dwg). */
    @Test fun `dxf convertido desde dwg 2018 por libredwg`() {
        val text = javaClass.getResourceAsStream("/libredwg_example_2018.dxf")!!.reader(Charsets.UTF_8).readText()
        val doc = DxfParser().parse(StringReader(text))
        val types = doc.entityCountByType
        assertTrue(doc.entities.size > 50, "entidades: ${doc.entities.size}")
        assertTrue((types["SPLINE"] ?: 0) >= 2 && (types["ELLIPSE"] ?: 0) >= 1, "tipos: $types")
        assertTrue("VIEWPORT" !in types, "el viewport de espacio papel no debe cargarse: $types")
        assertTrue(doc.entities.filterIsInstance<CadEntity.Insert>().any { it.blockName.startsWith("*D") }, "cotas como bloques")
        // Este dibujo real contiene un bloque gigante ("bloko", ±2.7e6): el encuadre de trabajo debe ignorarlo
        assertTrue(doc.extents.width > 1e6, "extensión total: ${doc.extents}")
        val e = doc.focusExtents
        assertTrue(!e.isEmpty && e.width < 1e5 && e.height < 1e5, "zona de trabajo: $e")
        // Todo lo dibujable debe teselar sin excepciones
        val ex = BlockExpander(doc)
        var segments = 0
        for (ent in doc.entities) {
            if (ent is CadEntity.Insert) ex.expand(ent, e.width / 5000, { _, _, pl -> segments += pl.size })
            else segments += ent.toPolylines(e.width / 5000).sumOf { it.size }
        }
        assertTrue(segments > 100)
    }

    @Test fun `elipse completa y spline grado 1`() {
        val el = CadEntity.Ellipse("1", "0", CadColor.BYLAYER, Vec3(0.0, 0.0), Vec3(2.0, 0.0), 1.0, 0.0, 2 * PI)
        val pts = el.toPolylines(1e-4).single()
        assertEquals(4 * PI, Measurements.polylineLength(pts), 1e-3) // círculo r = 2
        val sp = CadEntity.Spline("2", "0", CadColor.BYLAYER, 1, listOf(0.0, 0.0, 1.0, 2.0, 2.0),
            listOf(Vec3(0.0, 0.0), Vec3(1.0, 1.0), Vec3(2.0, 0.0)))
        assertEquals(Vec2(1.0, 1.0), sp.evaluate(1.0))
        assertEquals(2 * Math.sqrt(2.0), Measurements.polylineLength(sp.toPolylines(0.0).single()), 1e-9)
    }

    @Test fun `bloques anidados con byblock y capa 0`() {
        val inner = Block("IN", Vec3(0.0, 0.0), listOf(
            CadEntity.Line("a", "0", CadColor(CadColor.BY_BLOCK), Vec3(0.0, 0.0), Vec3(1.0, 0.0)),
        ))
        val outer = Block("OUT", Vec3(0.0, 0.0), listOf(
            CadEntity.Insert("b", "0", CadColor(CadColor.BY_BLOCK), "IN", Vec3(10.0, 0.0), scaleX = 2.0, scaleY = 2.0),
        ))
        val top = CadEntity.Insert("c", "MURO", CadColor(3), "OUT", Vec3(100.0, 100.0), rotationDeg = 90.0)
        val doc = CadDocument(mapOf("0" to Layer("0"), "MURO" to Layer("MURO")), mapOf("IN" to inner, "OUT" to outer), listOf(top))
        val out = ArrayList<Triple<String, CadColor, List<Vec2>>>()
        BlockExpander(doc).expand(top, 1e-3, { l, c, pl -> out += Triple(l, c, pl) })
        val (layer, color, pl) = out.single()
        assertEquals("MURO", layer)
        assertEquals(3, color.aci)
        // (0,0)→(1,0) escalado ×2, desplazado +10 en X, rotado 90°, trasladado a (100,100)
        assertEquals(100.0, pl[0].x, 1e-9); assertEquals(110.0, pl[0].y, 1e-9)
        assertEquals(100.0, pl[1].x, 1e-9); assertEquals(112.0, pl[1].y, 1e-9)
        assertEquals(112.0, doc.extents.maxY, 1e-9)
    }

    @Test fun `espacio papel se descarta y cota es bloque`() {
        val d = dxf(
            0 to "SECTION", 2 to "ENTITIES",
            0 to "LINE", 8 to "0", 67 to 1, 10 to 0, 20 to 0, 11 to 5, 21 to 5,
            0 to "DIMENSION", 8 to "COTAS", 2 to "*D12", 10 to 0, 20 to 0,
            0 to "SOLID", 8 to "0", 10 to 0, 20 to 0, 11 to 1, 21 to 0, 12 to 0, 22 to 1, 13 to 1, 23 to 1,
            0 to "ENDSEC", 0 to "EOF",
        )
        val doc = DxfParser().parse(StringReader(d))
        assertEquals(2, doc.entities.size)
        assertEquals("*D12", (doc.entities[0] as CadEntity.Insert).blockName)
        val solid = doc.entities[1] as CadEntity.Face3D
        assertEquals(1.0, Measurements.polygonArea(solid.corners.map { it.xy() }), 1e-12) // orden 1-2-4-3 correcto
    }

    @Test fun `texto con salto de linea crudo y basura no rompe la lectura`() {
        val d = "0\nSECTION\n2\nENTITIES\n" +
            "0\nMTEXT\n8\nTXT\n10\n1\n20\n2\n40\n0.5\n" +
            "1\nLÍNEA A, MEDIANTE EL PROGRAMA DE\\P\nCA, MEDIANTE EL PROGRAMA\rDE OBRA \"VÍAS 2030\"\n" +
            "0\nLINE\n8\n0\n10\n0\n20\n0\n11\n3\n21\n4\n" +
            "basura inesperada\n" +
            "0\nLINE\n8\n0\n10\n0\n20\n0\n11\n6\n21\n8\n" +
            "0\nENDSEC\n0\nEOF\n"
        val p = DxfParser()
        val doc = p.parse(StringReader(d))
        val t = doc.entities[0] as CadEntity.Text
        assertTrue(t.value.contains("CA, MEDIANTE") && t.value.contains("VÍAS 2030"), t.value)
        assertEquals(3, doc.entities.size)
        assertEquals(10.0, (doc.entities[2] as CadEntity.Line).end.xy().length(), 1e-12)
        assertEquals(1L, p.skippedLines)
    }

    @Test fun `hatch con trayectoria polilinea y con aristas`() {
        val d = dxf(
            0 to "SECTION", 2 to "ENTITIES",
            0 to "HATCH", 8 to "RELLENO", 10 to 0, 20 to 0, 30 to 0, 2 to "SOLID", 70 to 1, 71 to 0, 91 to 2,
            // trayectoria 1: polilínea 4×3
            92 to 2, 72 to 0, 73 to 1, 93 to 4, 10 to 0, 20 to 0, 10 to 4, 20 to 0, 10 to 4, 20 to 3, 10 to 0, 20 to 3, 97 to 0,
            // trayectoria 2: aristas (2 líneas + semicírculo)
            92 to 1, 93 to 2, 72 to 1, 10 to 10, 20 to 0, 11 to 12, 21 to 0,
            72 to 2, 10 to 11, 20 to 0, 40 to 1, 50 to 0, 51 to 180, 73 to 1, 97 to 0,
            75 to 0, 76 to 1, 98 to 1, 10 to 1, 20 to 1,
            0 to "ENDSEC", 0 to "EOF",
        )
        val h = DxfParser().parse(StringReader(d)).entities.single() as CadEntity.Hatch
        assertTrue(h.solid)
        assertEquals(2, h.boundaries.size)
        assertEquals(12.0, Measurements.polygonArea(h.boundaries[0]), 1e-9)
        assertEquals(PI / 2, Measurements.polygonArea(h.boundaries[1]), 5e-3) // semicírculo r=1 cerrado por la línea
    }
}
