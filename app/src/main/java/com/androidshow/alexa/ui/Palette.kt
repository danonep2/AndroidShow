package com.androidshow.alexa.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Paleta inicial do app: roxo (primária), azul (secundária) e magenta (destaque). */
object Palette {
    val Primary = Color(0xFF32127A)
    val Secondary = Color(0xFF122678)
    val Accent = Color(0xFF631278)
    val Background = Color(0xFF06050C)

    /** As cores puras são escuras demais para texto e traços finos sobre o fundo preto. */
    fun bright(color: Color, amount: Float = 0.3f) = lerp(color, Color.White, amount)

    val colorScheme = darkColorScheme(
        primary = bright(Primary),
        onPrimary = Color.White,
        primaryContainer = Primary,
        onPrimaryContainer = Color.White,
        secondary = bright(Secondary),
        onSecondary = Color.White,
        secondaryContainer = Secondary,
        onSecondaryContainer = Color.White,
        tertiary = bright(Accent),
        onTertiary = Color.White,
        background = Background,
        surface = Background,
    )
}
