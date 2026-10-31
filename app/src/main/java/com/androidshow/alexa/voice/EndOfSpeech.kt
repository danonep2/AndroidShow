package com.androidshow.alexa.voice

/**
 * Detector de fim de fala por energia, em quadros de 80 ms. Complementa o StopCapture da nuvem:
 * fecha o microfone após [silenceChunks] blocos de silêncio depois de haver fala.
 */
class EndOfSpeech(
    private val calibrationChunks: Int = 3,
    private val minSpeechChunks: Int = 2,
    private val silenceChunks: Int = 10, // 800 ms
    private val noSpeechChunks: Int = 62, // ~5 s
) {
    enum class Result { Continue, Ended, NoSpeech }

    private var seen = 0
    private var noiseFloor = Int.MAX_VALUE
    private var speech = 0
    private var silence = 0

    fun feed(rms: Int): Result {
        seen++
        if (seen <= calibrationChunks) {
            noiseFloor = minOf(noiseFloor, maxOf(rms, 50))
            return Result.Continue
        }
        val threshold = maxOf(noiseFloor * 3, 300)
        if (rms >= threshold) {
            speech++
            silence = 0
        } else if (speech >= minSpeechChunks) {
            silence++
        }
        return when {
            speech >= minSpeechChunks && silence >= silenceChunks -> Result.Ended
            speech < minSpeechChunks && seen >= noSpeechChunks -> Result.NoSpeech
            else -> Result.Continue
        }
    }
}
