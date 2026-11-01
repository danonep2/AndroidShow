package com.androidshow.alexa.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.androidshow.alexa.AlexaService.Dialog
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Fração do menor lado da tela ocupada pelo raio do círculo (a área de toque usa o mesmo valor). */
const val ORB_RADIUS = 0.14f

/**
 * Círculo central que reage à fala da Alexa, com uma onda atrás que acompanha a voz do usuário.
 * Sem escuta, a onda segue numa frequência constante e suave.
 */
@Composable
fun VoiceOrb(
    dialog: Dialog,
    micLevel: Float,
    speechLevel: Float,
    connected: Boolean,
    modifier: Modifier = Modifier,
    /** Raio do círculo; sem valor, [ORB_RADIUS] do menor lado da área. */
    radius: Dp = Dp.Unspecified,
    /** Altura do centro (0 = topo, 1 = base). Em 1 só a metade de cima aparece. */
    centerY: Float = 0.5f,
) {
    val voice by animateFloatAsState(if (dialog == Dialog.Listening) micLevel else 0f, tween(140), label = "voz")
    val speech by animateFloatAsState(if (dialog == Dialog.Speaking) speechLevel else 0f, tween(70), label = "fala")
    val listening by animateFloatAsState(if (dialog == Dialog.Listening) 1f else 0f, tween(400), label = "escuta")
    val thinking by animateFloatAsState(if (dialog == Dialog.Thinking) 1f else 0f, tween(400), label = "pensando")
    val presence by animateFloatAsState(if (connected) 1f else 0.35f, tween(600), label = "conexão")
    val time by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1e9f }
    }

    Canvas(modifier) {
        val base = if (radius.isSpecified) radius.toPx() else min(size.width, size.height) * ORB_RADIUS
        val center = Offset(size.width / 2, size.height * centerY)
        drawWaves(time, voice, listening, presence, base, center.y)
        drawOrb(time, speech, listening, thinking, presence, base, center)
    }
}

private class WaveLayer(val color: Color, val cycles: Float, val speed: Float, val phase: Float, val weight: Float)

private val waveLayers = listOf(
    WaveLayer(Palette.Secondary, cycles = 1.5f, speed = 0.9f, phase = 0f, weight = 1f),
    WaveLayer(Palette.Primary, cycles = 2.2f, speed = -1.2f, phase = 1.7f, weight = 0.8f),
    WaveLayer(Palette.Accent, cycles = 3.0f, speed = 1.6f, phase = 3.4f, weight = 0.6f),
)

private fun DrawScope.drawWaves(time: Float, voice: Float, listening: Float, presence: Float, base: Float, cy: Float) {
    // Relativa ao raio do círculo, e não à tela: em retrato a altura inteira deixaria a onda exagerada.
    val amplitude = base * (0.5f + 3.2f * voice)
    val speedUp = 1f + 1.5f * listening
    val glow = Stroke(10.dp.toPx(), cap = StrokeCap.Round)
    val line = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
    waveLayers.forEach { layer ->
        val path = Path()
        for (i in 0..WAVE_STEPS) {
            val u = i.toFloat() / WAVE_STEPS
            // Afina nas bordas: a onda nasce e morre suave nas laterais.
            val taper = sin(PI.toFloat() * u).let { it * it }
            val y = cy + amplitude * layer.weight * taper *
                sin(2 * PI.toFloat() * layer.cycles * u + time * layer.speed * speedUp + layer.phase)
            if (i == 0) path.moveTo(0f, y) else path.lineTo(size.width * u, y)
        }
        val color = Palette.bright(layer.color, 0.25f)
        drawPath(path, color.copy(alpha = 0.18f * presence), style = glow)
        drawPath(path, color.copy(alpha = 0.85f * presence), style = line)
    }
}

private fun DrawScope.drawOrb(time: Float, speech: Float, listening: Float, thinking: Float, presence: Float, base: Float, center: Offset) {
    val breathe = 1f + 0.025f * sin(time * 1.6f) * (1f - listening)
    val radius = base * breathe * (1f + 0.08f * listening + 0.3f * speech)

    // Halo: cresce e puxa para o magenta conforme a Alexa fala.
    val haloRadius = radius * (1.9f + 0.6f * speech)
    val haloColor = lerp(Palette.Primary, Palette.Accent, speech)
    val haloAlpha = presence * (0.45f + 0.35f * listening + 0.5f * speech).coerceAtMost(1f)
    drawCircle(
        Brush.radialGradient(listOf(Palette.bright(haloColor, 0.15f).copy(alpha = haloAlpha), Color.Transparent), center, haloRadius),
        haloRadius,
        center,
    )

    drawCircle(
        Brush.radialGradient(
            listOf(Palette.bright(lerp(Palette.Primary, Palette.Accent, speech * 0.6f), 0.28f), Palette.Primary, lerp(Palette.Primary, Palette.Secondary, 0.7f)),
            center + Offset(-radius * 0.35f, -radius * 0.35f),
            radius * 1.5f,
        ),
        radius,
        center,
    )

    if (listening > 0f) {
        drawCircle(Palette.bright(Palette.Secondary, 0.45f).copy(alpha = 0.8f * listening), radius * 1.12f, center, style = Stroke(2.dp.toPx()))
    }
    if (thinking > 0f) {
        rotate(time * 220f, center) {
            drawCircle(
                Brush.sweepGradient(listOf(Palette.Secondary, Palette.bright(Palette.Accent, 0.3f), Palette.bright(Palette.Primary, 0.4f), Palette.Secondary), center),
                radius * 1.1f,
                center,
                alpha = thinking,
                style = Stroke(radius * 0.07f, cap = StrokeCap.Round),
            )
        }
    }
}

private const val WAVE_STEPS = 120
