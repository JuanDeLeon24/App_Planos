package com.cadfield.core.cad.dxf

import com.cadfield.core.cad.model.CadDocument
import java.io.InputStream

enum class CadFormat { DXF_ASCII, DXF_BINARY, DWG, DWF, DGN, IFC, PDF, OBJ, STL, GLTF, GLB, IMAGE, UNKNOWN }

/** Detección por firma binaria (magic bytes), no por extensión. */
object FormatDetector {
    fun detect(head: ByteArray, fileName: String = ""): CadFormat {
        val s = String(head, 0, minOf(head.size, 64), Charsets.ISO_8859_1)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            s.startsWith("AC10") || s.startsWith("AC1.") -> CadFormat.DWG
            s.startsWith("AutoCAD Binary DXF") -> CadFormat.DXF_BINARY
            s.startsWith("(DWF V") -> CadFormat.DWF
            s.startsWith("%PDF") -> CadFormat.PDF
            s.startsWith("ISO-10303-21") -> CadFormat.IFC
            s.startsWith("glTF") -> CadFormat.GLB
            head.size >= 2 && head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && ext == "dgn" -> CadFormat.DGN
            head.size >= 4 && (head[0] == 0x89.toByte() && s.substring(1, 4) == "PNG" ||
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() || s.startsWith("II*") || s.startsWith("MM")) -> CadFormat.IMAGE
            ext == "dxf" || Regex("""^\s*0\s*\r?\n\s*SECTION""").containsMatchIn(s) -> CadFormat.DXF_ASCII
            ext == "stl" -> CadFormat.STL
            ext == "obj" -> CadFormat.OBJ
            ext == "gltf" -> CadFormat.GLTF
            ext == "dgn" -> CadFormat.DGN
            else -> CadFormat.UNKNOWN
        }
    }

    /** Versión DWG legible (p.ej. AC1032 → AutoCAD 2018+). */
    fun dwgRelease(head: ByteArray): String = when (String(head, 0, 6, Charsets.ISO_8859_1)) {
        "AC1009" -> "R11/R12"; "AC1012" -> "R13"; "AC1014" -> "R14"
        "AC1015" -> "2000"; "AC1018" -> "2004"; "AC1021" -> "2007"
        "AC1024" -> "2010"; "AC1027" -> "2013"; "AC1032" -> "2018+"
        else -> "desconocida"
    }
}

/**
 * Punto de extensión para motores CAD externos (ODA Drawings SDK para DWG/DGN,
 * IfcOpenShell para IFC, etc.). La app registra implementaciones en tiempo de ejecución.
 */
interface CadImporter {
    val supported: Set<CadFormat>
    fun import(input: InputStream, sizeBytes: Long, onProgress: (Double) -> Unit): CadDocument
}

class UnsupportedFormatException(val format: CadFormat, detail: String) :
    Exception("Formato $format no soportado por los motores instalados: $detail")
