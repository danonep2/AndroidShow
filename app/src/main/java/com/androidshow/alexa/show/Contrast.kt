package com.androidshow.alexa.show

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** Cores do texto e véu sobre a imagem de fundo, escolhidos pelo brilho da imagem. */
data class CardTone(
    val text: Color,
    val secondary: Color,
    val muted: Color,
    val accent: Color,
    val scrim: Brush,
) {
    companion object {
        /** Fundo escuro (ou sem imagem): texto claro. */
        val Light = CardTone(
            text = Color.White,
            secondary = Color(0xFFDCE3EC),
            muted = Color(0xFFB4BECA),
            accent = Color(0xFF9CC3FF),
            scrim = Brush.verticalGradient(0f to Color(0x14000000), 0.45f to Color(0x66000000), 1f to Color(0xE0000000)),
        )

        /** Fundo claro: texto escuro com véu claro. */
        val Dark = CardTone(
            text = Color(0xFF0E1116),
            secondary = Color(0xFF26303B),
            muted = Color(0xFF3D4856),
            accent = Color(0xFF0B4FC0),
            scrim = Brush.verticalGradient(0f to Color(0x10FFFFFF), 0.45f to Color(0x66FFFFFF), 1f to Color(0xD9FFFFFF)),
        )

        /**
         * Mede a luminância relativa (WCAG) média da região onde o texto fica: metade de baixo e
         * dois terços da esquerda da imagem, reduzida a 32×18 amostras para ser barata.
         * Acima de ~0,4 o texto escuro tem mais contraste que o claro.
         */
        fun of(bitmap: Bitmap): CardTone = if (textRegionLuminance(bitmap) > 0.4) Dark else Light

        fun textRegionLuminance(bitmap: Bitmap): Double {
            val small = Bitmap.createScaledBitmap(bitmap, 32, 18, true)
            var sum = 0.0
            var n = 0
            for (y in 9 until 18) for (x in 0 until 22) {
                val c = small.getPixel(x, y)
                sum += 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
                n++
            }
            if (small !== bitmap) small.recycle()
            return sum / n
        }

        private fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
    }
}
