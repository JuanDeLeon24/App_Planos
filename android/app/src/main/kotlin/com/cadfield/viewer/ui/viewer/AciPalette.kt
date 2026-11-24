package com.cadfield.viewer.ui.viewer

import android.graphics.Color

/** Colores ACI (AutoCAD Color Index). 1-9 exactos; 10-249 por fórmula HSV estándar; 250-255 grises. */
object AciPalette {
    private val base = intArrayOf(
        0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(),
        0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFFC0C0C0.toInt(),
    )

    fun argb(aci: Int): Int = when (aci) {
        in 0..9 -> base[aci]
        in 10..249 -> {
            val hue = ((aci - 10) / 10) * 15f
            val variant = (aci - 10) % 10
            val value = floatArrayOf(1f, 1f, 0.8f, 0.8f, 0.6f, 0.6f, 0.5f, 0.5f, 0.3f, 0.3f)[variant]
            val sat = if (variant % 2 == 0) 1f else 0.5f
            Color.HSVToColor(floatArrayOf(hue, sat, value))
        }
        in 250..255 -> { val g = 51 + (aci - 250) * 41; Color.rgb(g, g, g) }
        else -> Color.WHITE
    }

    /** Resuelve BYLAYER/true color; en fondo oscuro el ACI 7 es blanco. */
    fun resolve(entityAci: Int, entityRgb: Int?, layerAci: Int, layerRgb: Int?): Int = when {
        entityRgb != null -> entityRgb or 0xFF000000.toInt()
        entityAci == 256 || entityAci == 0 -> layerRgb?.let { it or 0xFF000000.toInt() } ?: argb(layerAci)
        else -> argb(entityAci)
    }
}
