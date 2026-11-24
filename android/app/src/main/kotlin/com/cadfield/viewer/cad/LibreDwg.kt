package com.cadfield.viewer.cad

import java.io.File

/**
 * Motor DWG basado en LibreDWG (GPLv3), compilado para Android (arm64-v8a).
 * Convierte DWG (R13–2018+) a DXF ASCII; luego el parser DXF del núcleo lo lee en streaming.
 */
object LibreDwg {
    val available: Boolean = try {
        System.loadLibrary("cadfield_dwg")
        nativeAvailable()
    } catch (_: Throwable) {
        false
    }

    /** Convierte [dwg] en [dxf]. Lanza excepción con mensaje legible si falla. */
    @Synchronized // LibreDWG usa estado global: una conversión a la vez
    fun convert(dwg: File, dxf: File) {
        check(available) { "Motor DWG no disponible en este dispositivo (requiere procesador ARM de 64 bits)" }
        val tmp = File(dxf.parentFile, dxf.name + ".tmp")
        tmp.delete()
        val error = nativeDwgToDxf(dwg.absolutePath, tmp.absolutePath)
        if (error != null) { tmp.delete(); throw IllegalStateException(error) }
        if (!tmp.renameTo(dxf)) { dxf.delete(); check(tmp.renameTo(dxf)) { "No se pudo guardar el DXF convertido" } }
    }

    @JvmStatic private external fun nativeAvailable(): Boolean
    @JvmStatic private external fun nativeDwgToDxf(inPath: String, outPath: String): String?
}
