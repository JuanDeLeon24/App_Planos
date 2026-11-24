package com.cadfield.core.cad

import com.cadfield.core.cad.analysis.DrawingAnalyzer
import com.cadfield.core.cad.dxf.CadFormat
import com.cadfield.core.cad.dxf.DxfParser
import com.cadfield.core.cad.dxf.DxfWriter
import com.cadfield.core.cad.dxf.FormatDetector
import com.cadfield.core.cad.model.CadEntity
import com.cadfield.core.cad.model.DrawingUnits
import java.io.StringReader
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DxfParserTest {

    /** Construye un DXF a partir de pares (código, valor). */
    private fun dxf(vararg pairs: Pair<Int, Any>) = pairs.joinToString("\n", postfix = "\n") { "${it.first}\n${it.second}" }

    private val sample = dxf(
        0 to "SECTION", 2 to "HEADER",
        9 to "\$ACADVER", 1 to "AC1027",
        9 to "\$INSUNITS", 70 to 6,
        0 to "ENDSEC",
        0 to "SECTION", 2 to "TABLES",
        0 to "TABLE", 2 to "LAYER",
        0 to "LAYER", 2 to "EJE_TUNEL", 62 to 1, 70 to 0,
        0 to "LAYER", 2 to "OCULTA", 62 to -3, 70 to 4,
        0 to "ENDTAB", 0 to "ENDSEC",
        0 to "SECTION", 2 to "BLOCKS",
        0 to "BLOCK", 2 to "PI", 10 to 0, 20 to 0, 30 to 0,
        0 to "CIRCLE", 8 to "0", 10 to 0, 20 to 0, 40 to 0.5,
        0 to "ENDBLK",
        0 to "ENDSEC",
        0 to "SECTION", 2 to "ENTITIES",
        0 to "LINE", 5 to "1A", 8 to "EJE_TUNEL", 10 to 0, 20 to 0, 30 to 100, 11 to 300, 21 to 400, 31 to 110,
        0 to "LWPOLYLINE", 5 to "2B", 8 to "EXCAVACION", 90 to 4, 70 to 1,
        10 to 0, 20 to 0, 10 to 10, 20 to 0, 10 to 10, 20 to 5, 10 to 0, 20 to 5,
        0 to "ARC", 8 to "0", 10 to 0, 20 to 0, 40 to 2, 50 to 0, 51 to 90,
        0 to "INSERT", 8 to "INSPECCION", 66 to 1, 2 to "PI", 10 to 5, 20 to 5,
        0 to "ATTRIB", 2 to "COD", 1 to "PI-001",
        0 to "SEQEND",
        0 to "INSERT", 8 to "INSPECCION", 2 to "PI", 10 to 6, 20 to 6,
        0 to "MTEXT", 8 to "TXT", 10 to 1, 20 to 1, 40 to 0.25, 1 to "Línea 1\\PLínea 2",
        0 to "HATCH", 8 to "0",
        0 to "ENDSEC",
        0 to "EOF",
    )

    private fun parse() = DxfParser().parse(StringReader(sample))

    @Test fun `lee header capas y unidades`() {
        val doc = parse()
        assertEquals("AC1027", doc.acadVersion)
        assertEquals(DrawingUnits.METERS, doc.units)
        assertTrue(doc.layers.getValue("EJE_TUNEL").visible)
        assertFalse(doc.layers.getValue("OCULTA").visible)
        assertTrue(doc.layers.getValue("OCULTA").locked)
        assertEquals(1, doc.blocks.getValue("PI").entities.size)
    }

    @Test fun `lee entidades y preserva desconocidas`() {
        val doc = parse()
        assertEquals(7, doc.entities.size)
        val line = doc.entities.first() as CadEntity.Line
        assertEquals("1A", line.handle)
        assertEquals(110.0, line.end.z)
        val poly = doc.entities[1] as CadEntity.Polyline
        assertTrue(poly.closed); assertEquals(4, poly.vertices.size)
        val ins = doc.entities[3] as CadEntity.Insert
        assertEquals("PI-001", ins.attributes["COD"])
        val txt = doc.entities[5] as CadEntity.Text
        assertEquals("Línea 1\nLínea 2", txt.value)
        assertTrue(doc.entities.last() is CadEntity.Unknown)
    }

    @Test fun `asistente responde longitud area y conteo`() {
        val a = DrawingAnalyzer(parse())
        assertEquals(500.0, a.totalLength(Regex("EJE_.*")), 1e-9)
        assertEquals(50.0, a.closedArea(Regex("EXCAVACION")), 1e-9)
        assertEquals(2, a.countBlocks(Regex("PI")))
    }

    @Test fun `analizador detecta duplicados y longitud cero`() {
        val d = dxf(
            0 to "SECTION", 2 to "ENTITIES",
            0 to "LINE", 5 to "A", 8 to "0", 10 to 0, 20 to 0, 11 to 1, 21 to 1,
            0 to "LINE", 5 to "B", 8 to "0", 10 to 1, 20 to 1, 11 to 0, 21 to 0,
            0 to "LINE", 5 to "C", 8 to "0", 10 to 2, 20 to 2, 11 to 2, 21 to 2,
            0 to "ENDSEC", 0 to "EOF",
        )
        val codes = DrawingAnalyzer(DxfParser().parse(StringReader(d))).findIssues().map { it.code }.toSet()
        assertEquals(setOf("ZERO_LENGTH", "DUPLICATE"), codes)
    }

    @Test fun `filtro de capas carga bajo demanda`() {
        val doc = DxfParser(layerFilter = { it == "INSPECCION" }).parse(StringReader(sample))
        assertEquals(2, doc.entities.size)
    }

    @Test fun `escritura y relectura conserva geometria`() {
        val doc = parse()
        val sw = StringWriter()
        DxfWriter(sw).write(doc.layers.values, doc.entities)
        val back = DxfParser().parse(StringReader(sw.toString()))
        val l = back.entities.filterIsInstance<CadEntity.Line>().single()
        assertEquals(300.0, l.end.x, 1e-9)
        assertEquals(4, back.entities.filterIsInstance<CadEntity.Polyline>().single().vertices.size)
    }

    @Test fun `detecta formatos por firma`() {
        assertEquals(CadFormat.DWG, FormatDetector.detect("AC1032xxxxxx".toByteArray()))
        assertEquals(CadFormat.PDF, FormatDetector.detect("%PDF-1.7".toByteArray()))
        assertEquals(CadFormat.DXF_ASCII, FormatDetector.detect("  0\nSECTION\n".toByteArray()))
        assertEquals(CadFormat.IFC, FormatDetector.detect("ISO-10303-21;".toByteArray()))
        assertEquals("2018+", FormatDetector.dwgRelease("AC1032".toByteArray()))
    }

    @Test fun `archivo grande sintetico se procesa en streaming`() {
        val sb = StringBuilder("0\nSECTION\n2\nENTITIES\n")
        repeat(200_000) { i -> sb.append("0\nLINE\n8\nL${i % 50}\n10\n$i\n20\n0\n11\n${i + 1}\n21\n1\n") }
        sb.append("0\nENDSEC\n0\nEOF\n")
        var last = 0.0
        val doc = DxfParser(totalChars = sb.length.toLong(), onProgress = { last = it }).parse(StringReader(sb.toString()))
        assertEquals(200_000, doc.entities.size)
        assertEquals(1.0, last)
    }
}
