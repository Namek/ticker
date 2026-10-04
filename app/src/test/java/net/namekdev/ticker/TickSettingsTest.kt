package net.namekdev.ticker

import org.junit.Assert.*
import org.junit.Test

class TickSettingsTest {
    @Test fun acceptsPositiveWholeNumbers() {
        listOf("1" to 1, "10" to 10, "0010" to 10, " 42 " to 42, "2147483647" to Int.MAX_VALUE)
            .forEach { (text, expected) -> assertEquals(text, expected, positiveWholeNumber(text)) }
    }

    @Test fun rejectsEmptyZeroNegativeFractionsAndOverflow() {
        listOf("", " ", "0", "000", "-1", "+1", "1.5", "1e3", "12x", "2147483648",
            "99999999999999999999999999999999", "١", "1 2").forEach {
            assertNull(it, positiveWholeNumber(it))
        }
    }

    @Test fun invalidSettingsCannotReachTheEngine() {
        listOf(0 to 1, 1 to 0, -1 to 1, 1 to -1).forEach { (interval, count) ->
            assertThrows(IllegalArgumentException::class.java) { TickSettings(interval, count) }
        }
    }

    @Test fun bundledAudioMatchesCompletionTiming() {
        for (sound in TickSound.entries) {
            val wav = java.nio.ByteBuffer.wrap(java.io.File("src/main/res/raw/${sound.id}.wav").readBytes())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(wav.array(), 0, 4, Charsets.US_ASCII))
            assertEquals("data", String(wav.array(), 36, 4, Charsets.US_ASCII))
            assertEquals(1, wav.getShort(20).toInt()) // PCM
            assertEquals(1, wav.getShort(22).toInt()) // mono
            assertEquals(16, wav.getShort(34).toInt())
            val duration = wav.getInt(40).toLong() * 1_000 / wav.getInt(28)
            assertEquals(sound.durationMillis, duration)
            assertTrue(sound.completionMillis > duration)
            assertTrue(sound.completionMillis < 1_000)
            val samples = (44 until wav.capacity() step 2).map { wav.getShort(it).toDouble() / 32768 }
            val peak = samples.maxOf { kotlin.math.abs(it) }
            val rms = kotlin.math.sqrt(samples.sumOf { it * it } / samples.size)
            assertTrue("${sound.id}: strong level without clipping", peak in 0.9..0.96)
            assertTrue("${sound.id}: enough sustained energy", rms > 0.2)
        }
    }

    @Test fun missingOrUnknownSoundUsesClockAndKnownIdsRoundTrip() {
        assertEquals(TickSound.CLOCK, TickSound.fromId(null))
        assertEquals(TickSound.CLOCK, TickSound.fromId("removed-sound"))
        for (sound in TickSound.entries) assertEquals(sound, TickSound.fromId(sound.id))
    }
}
