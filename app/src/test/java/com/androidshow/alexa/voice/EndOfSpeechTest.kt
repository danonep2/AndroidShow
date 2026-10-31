package com.androidshow.alexa.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class EndOfSpeechTest {

    private fun run(levels: List<Int>): Pair<EndOfSpeech.Result, Int> {
        val vad = EndOfSpeech()
        levels.forEachIndexed { i, rms ->
            val r = vad.feed(rms)
            if (r != EndOfSpeech.Result.Continue) return r to i
        }
        return EndOfSpeech.Result.Continue to levels.size
    }

    @Test
    fun endsAfterSilenceFollowingSpeech() {
        // Perfil real do Redmi (RMS por 100 ms, aproximado do log): ruído ~100, fala ~1200-2000.
        val levels = List(5) { 100 } + List(10) { 1500 } + List(20) { 90 }
        val (result, index) = run(levels)
        assertEquals(EndOfSpeech.Result.Ended, result)
        assertEquals(5 + 10 + 8 - 1, index)
    }

    @Test
    fun givesUpWithoutSpeech() {
        assertEquals(EndOfSpeech.Result.NoSpeech, run(List(60) { 100 }).first)
    }

    @Test
    fun shortClickIsNotSpeech() {
        val levels = List(5) { 100 } + listOf(2000) + List(60) { 100 }
        assertEquals(EndOfSpeech.Result.NoSpeech, run(levels).first)
    }
}
