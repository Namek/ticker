package net.namekdev.moments

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
        assertThrows(IllegalArgumentException::class.java) { TickSettings(timeLimitSeconds = 0) }
    }

    @Test fun timeLimitAcceptsMinutesAndSecondsWithoutOverflow() {
        assertEquals(1, timeLimitSeconds("0", "1"))
        assertEquals(90, timeLimitSeconds("1", "30"))
        assertEquals(600, timeLimitSeconds(" 10 ", "00"))
        assertEquals(Int.MAX_VALUE, timeLimitSeconds("35791394", "7"))
        for ((minutes, seconds) in listOf("" to "1", "0" to "", "0" to "0", "-1" to "0",
            "1" to "-1", "1.5" to "0", "0" to "60", "2147483648" to "0", "35791394" to "8")) {
            assertNull("$minutes:$seconds", timeLimitSeconds(minutes, seconds))
        }
    }

    @Test fun finiteTimersCountDownToZeroAndClampDuringTheFinalSound() {
        val settings = TickSettings(10, 3)
        assertEquals(30L, settings.durationSeconds)
        assertEquals(30L, settings.remainingSeconds(0))
        assertEquals(30L, settings.remainingSeconds(999))
        assertEquals(29L, settings.remainingSeconds(1_000))
        assertEquals(1L, settings.remainingSeconds(29_999))
        assertEquals(0L, settings.remainingSeconds(30_000))
        assertEquals(0L, settings.remainingSeconds(40_000))
        assertEquals(3L, settings.completedIntervals(40_000))
        assertEquals(7L, TickSettings(limitMode = LimitMode.TIME, timeLimitSeconds = 7).durationSeconds)
        assertNull(TickSettings(unlimited = true).remainingSeconds(1_000))
    }

    @Test fun largestAcceptedIntervalAndCountDoNotOverflowTheCountdown() {
        val settings = TickSettings(Int.MAX_VALUE, Int.MAX_VALUE)
        val expected = Int.MAX_VALUE.toLong() * Int.MAX_VALUE
        assertEquals(expected, settings.durationSeconds)
        assertEquals(expected - 1, settings.remainingSeconds(1_000))
        assertFalse(formatTimer(settings.remainingSeconds(1_000)!!).contains('-'))
        assertEquals(Int.MAX_VALUE.toLong(), settings.intervalRemainingSeconds(0))
    }

    @Test fun intervalCountdownResetsAtBoundariesAndClampsToTheFinalPartialInterval() {
        val count = TickSettings(10, 3)
        for ((elapsed, left) in listOf(-1L to 10L, 0L to 10L, 999L to 10L, 1_000L to 9L,
            9_999L to 1L, 10_000L to 10L, 29_999L to 1L, 30_000L to 0L, 40_000L to 0L)) {
            assertEquals("elapsed=$elapsed", left, count.intervalRemainingSeconds(elapsed))
        }
        assertEquals(10L, count.copy(unlimited = true).intervalRemainingSeconds(40_000))
        val time = count.copy(limitMode = LimitMode.TIME, timeLimitSeconds = 25)
        assertEquals(5L, time.intervalRemainingSeconds(20_000))
        assertEquals(1L, time.intervalRemainingSeconds(24_999))
        assertEquals(0L, time.intervalRemainingSeconds(25_000))
        assertEquals(3L, time.copy(timeLimitSeconds = 3).intervalRemainingSeconds(0))
    }

    @Test fun timerFormattingIsConciseAndNeverNegative() {
        assertEquals("00:00", formatTimer(-1))
        assertEquals("00:00", formatTimer(0))
        assertEquals("00:09", formatTimer(9))
        assertEquals("01:30", formatTimer(90))
        assertEquals("1:00:00", formatTimer(3_600))
        assertEquals("100:00:01", formatTimer(360_001))
    }

    @Test fun bundledAudioMatchesCompletionTiming() {
        for (sound in TickSound.entries.filter { it != TickSound.NONE }) {
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
            assertTrue("SoundPool must not truncate the recording", wav.getInt(40) < 1_048_576)
            val samples = (44 until wav.capacity() step 2).map { wav.getShort(it).toDouble() / 32768 }
            val peak = samples.maxOf { kotlin.math.abs(it) }
            val attackFrames = wav.getInt(24) * 20 / 1_000
            assertTrue("${sound.id}: recorded strike must begin within 20 ms of its cue",
                samples.take(attackFrames).maxOf { kotlin.math.abs(it) } >= peak * 0.2)
            val rms = kotlin.math.sqrt(samples.sumOf { it * it } / samples.size)
            val peakRange = when (sound) {
                TickSound.GONG -> 0.4..0.46
                TickSound.LOW_BELL -> 0.7..0.76
                TickSound.TINGSHA -> 0.65..0.71
                else -> 0.9..0.96
            }
            assertTrue("${sound.id}: audible level with output headroom", peak in peakRange)
            assertTrue("${sound.id}: audible recorded body", rms > 0.1)
            if (sound in listOf(TickSound.TEMPLE_BLOCK, TickSound.TINGSHA)) {
                val trailingQuietFrames = samples.asReversed().takeWhile { kotlin.math.abs(it) < peak * 0.001 }.size
                assertTrue("${sound.id}: trim trailing silence to less than 100 ms", trailingQuietFrames < wav.getInt(24) / 10)
            }
        }
    }

    @Test fun lowBellHasFewerOscillationsThanSoftBell() {
        fun crossings(name: String): Int {
            val wav = java.nio.ByteBuffer.wrap(java.io.File("src/main/res/raw/$name.wav").readBytes())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val rate = wav.getInt(24)
            // Compare the ringing body, excluding the initial strike and tail fade.
            return (rate / 10 until rate / 2).count { frame ->
                (wav.getShort(44 + frame * 2) < 0) != (wav.getShort(44 + (frame + 1) * 2) < 0)
            }
        }
        assertTrue("Low bell must be lower-pitched than Soft bell", crossings("low_bell") < crossings("bell") / 2)
    }

    @Test fun repeatedRingingTailsDoNotClipAtShortIntervals() {
        for (sound in listOf(TickSound.GONG, TickSound.LOW_BELL, TickSound.TINGSHA)) {
            val wav = java.nio.ByteBuffer.wrap(java.io.File("src/main/res/raw/${sound.id}.wav").readBytes())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val rate = wav.getInt(24)
            val samples = DoubleArray(wav.getInt(40) / 2) { wav.getShort(44 + it * 2).toDouble() / 32768 }
            for (seconds in 1..2) {
                val interval = seconds * rate
                // A slightly early/late callback can make ringing partials reinforce each other.
                for (jitterMillis in listOf(-40, 0, 40)) {
                    val starts = listOf(0, interval, 2 * interval + jitterMillis * rate / 1_000)
                    val mix = DoubleArray(samples.size + starts.last())
                    for (start in starts) {
                        samples.forEachIndexed { index, sample -> mix[start + index] += sample }
                    }
                    assertTrue("${sound.id} tails must leave mixing headroom", mix.maxOf { kotlin.math.abs(it) } < 0.95)
                }
            }
        }
    }

    @Test fun missingOrUnknownSoundUsesClockAndKnownIdsRoundTrip() {
        assertEquals(TickSound.CLOCK, TickSound.fromId(null))
        assertEquals(TickSound.CLOCK, TickSound.fromId("removed-sound"))
        for (sound in TickSound.entries) assertEquals(sound, TickSound.fromId(sound.id))
    }
}
