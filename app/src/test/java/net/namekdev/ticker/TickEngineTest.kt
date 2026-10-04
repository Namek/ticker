package net.namekdev.ticker

import org.junit.Assert.*
import org.junit.Test

class TickEngineTest {
    private class Clock {
        var time = 0L
        val jobs = mutableListOf<Pair<Long, () -> Unit>>()
        val ticks = mutableListOf<Long>()
        val counts = mutableListOf<Long>()
        val finishes = mutableListOf<Boolean>()
        var canPlay = true
        val engine = TickEngine(
            now = { time },
            schedule = { delay, action -> jobs.add(time + delay to action) },
            cancelScheduled = { jobs.clear() },
            play = { if (canPlay) ticks.add(time); canPlay },
            onTickCompleted = { counts.add(it) },
            onFinished = { finishes.add(it) },
        )

        fun advance(to: Long) {
            while (jobs.isNotEmpty() && jobs.minOf { it.first } <= to) {
                val job = jobs.minBy { it.first }
                jobs.remove(job)
                time = job.first
                job.second()
            }
            time = to
        }

        fun deliverLate(at: Long) {
            val job = jobs.minBy { it.first }
            jobs.remove(job)
            time = at
            job.second()
        }
    }

    @Test fun firstTickIsImmediateAndFiniteCountIncludesIt() {
        val clock = Clock()
        clock.engine.start(TickSettings(10, 3))
        assertEquals(listOf(0L), clock.ticks)
        assertEquals(0L, clock.engine.completed)
        clock.advance(20_000)
        assertEquals(listOf(0L, 10_000L, 20_000L), clock.ticks)
        assertTrue(clock.engine.running)
        clock.advance(20_000 + TickSound.CLOCK.completionMillis)
        assertEquals(listOf(1L, 2L, 3L), clock.counts)
        assertEquals(listOf(true), clock.finishes)
        assertFalse(clock.engine.running)
        assertTrue(clock.jobs.isEmpty())
    }

    @Test fun oneRepeatWaitsForTheSoundToFinish() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1))
        clock.advance(TickSound.CLOCK.completionMillis - 1)
        assertTrue(clock.engine.running)
        assertTrue(clock.finishes.isEmpty())
        clock.advance(TickSound.CLOCK.completionMillis)
        assertFalse(clock.engine.running)
        assertEquals(1L, clock.engine.completed)
    }

    @Test fun unlimitedIgnoresFiniteCountUntilCancelled() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1, true))
        clock.advance(100_500)
        assertEquals(101, clock.ticks.size)
        assertTrue(clock.engine.running)
        assertTrue(clock.finishes.isEmpty())
        clock.engine.stop()
        clock.advance(200_000)
        assertEquals(101, clock.ticks.size)
        assertTrue(clock.jobs.isEmpty())
    }

    @Test fun cancellationDuringSoundDoesNotCountAnInterruptedTick() {
        val clock = Clock()
        clock.engine.start(TickSettings())
        val staleCallback = clock.jobs.single().second
        clock.engine.stop()
        staleCallback()
        clock.advance(100_000)
        assertEquals(0L, clock.engine.completed)
        assertEquals(1, clock.ticks.size)
        assertTrue(clock.finishes.isEmpty())
    }

    @Test fun cancellationBetweenTicksRemovesPendingWork() {
        val clock = Clock()
        clock.engine.start(TickSettings())
        clock.advance(500)
        clock.engine.stop()
        clock.advance(100_000)
        assertEquals(listOf(0L), clock.ticks)
        assertEquals(1L, clock.engine.completed)
        assertTrue(clock.jobs.isEmpty())
    }

    @Test fun delayedTickSkipsMissedSlotsWithoutCatchUpOrLostRepeats() {
        val clock = Clock()
        clock.engine.start(TickSettings(10, 3))
        clock.advance(500)
        clock.deliverLate(35_000)
        assertEquals(listOf(0L, 35_000L), clock.ticks)
        clock.advance(40_500)
        assertEquals(listOf(0L, 35_000L, 40_000L), clock.ticks)
        assertEquals(3L, clock.engine.completed)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun delayedCompletionDoesNotEmitCatchUpTicks() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 3))
        clock.deliverLate(10_500)
        assertEquals(listOf(0L), clock.ticks)
        clock.advance(12_500)
        assertEquals(listOf(0L, 11_000L, 12_000L), clock.ticks)
    }

    @Test fun normalCallbackJitterDoesNotAccumulate() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 5))
        clock.advance(500)
        clock.deliverLate(1_035)
        clock.advance(1_500)
        clock.deliverLate(2_022)
        clock.advance(4_500)
        assertEquals(listOf(0L, 1_035L, 2_022L, 3_000L, 4_000L), clock.ticks)
    }

    @Test fun lateTickNearNextDeadlineCannotOverlapItsOwnSound() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 3))
        clock.advance(500)
        clock.deliverLate(1_999)
        clock.advance(3_500)
        assertEquals(listOf(0L, 1_999L, 3_000L), clock.ticks)
    }

    @Test fun duplicateStartsAreIgnoredAndRestartIsFresh() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 3))
        clock.engine.start(TickSettings(10, 1))
        clock.advance(500)
        assertEquals(1L, clock.engine.completed)
        val staleCallback = clock.jobs.single().second
        clock.engine.stop()
        clock.engine.start(TickSettings(2, 2))
        staleCallback()
        assertEquals(0L, clock.engine.completed)
        clock.advance(3_000)
        assertEquals(listOf(0L, 500L, 2_500L), clock.ticks)
        assertEquals(2L, clock.engine.completed)
    }

    @Test fun failedPlaybackCancelsWithoutCounting() {
        val clock = Clock()
        clock.canPlay = false
        clock.engine.start(TickSettings())
        assertFalse(clock.engine.running)
        assertTrue(clock.jobs.isEmpty())
        assertEquals(0L, clock.engine.completed)
        assertEquals(listOf(false), clock.finishes)
    }

    @Test fun maximumIntervalDoesNotOverflowMilliseconds() {
        val clock = Clock()
        clock.engine.start(TickSettings(Int.MAX_VALUE, Int.MAX_VALUE))
        clock.advance(500)
        assertEquals(Int.MAX_VALUE.toLong() * 1_000, clock.jobs.single().first)
    }

    @Test fun everySoundFinishesFullyAndKeepsOneSecondIntervals() {
        for (sound in TickSound.entries) {
            val clock = Clock()
            clock.engine.start(TickSettings(1, 3, sound = sound))
            clock.advance(sound.durationMillis - 1)
            assertTrue(clock.counts.isEmpty())
            clock.advance(2_000 + sound.completionMillis - 1)
            assertEquals(listOf(0L, 1_000L, 2_000L), clock.ticks)
            assertEquals(2L, clock.engine.completed)
            assertTrue(clock.engine.running)
            clock.advance(2_000 + sound.completionMillis)
            assertEquals(3L, clock.engine.completed)
            assertEquals(listOf(true), clock.finishes)
            assertTrue(clock.jobs.isEmpty())
        }
    }

    @Test fun stoppingAGongDuringItsTailCancelsCompletion() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 3, sound = TickSound.GONG))
        clock.advance(500)
        val callback = clock.jobs.single().second
        clock.engine.stop()
        callback()
        clock.advance(3_000)
        assertTrue(clock.counts.isEmpty())
        assertTrue(clock.finishes.isEmpty())
        assertTrue(clock.jobs.isEmpty())
    }
}
