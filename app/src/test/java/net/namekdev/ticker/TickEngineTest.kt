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
            onIntervalCompleted = { counts.add(it) },
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

    @Test fun immediateStartAndSoundAtEveryBoundaryIncludingTheEnd() {
        val clock = Clock()
        clock.engine.start(TickSettings(10, 3))
        assertEquals(listOf(0L), clock.ticks)
        assertEquals(0L, clock.engine.completed)
        clock.advance(30_000)
        assertEquals(listOf(0L, 10_000L, 20_000L, 30_000L), clock.ticks)
        assertEquals(listOf(1L, 2L, 3L), clock.counts)
        assertTrue(clock.engine.running)
        clock.advance(30_000 + TickSound.CLOCK.completionMillis)
        assertEquals(listOf(true), clock.finishes)
        assertFalse(clock.engine.running)
        assertTrue(clock.jobs.isEmpty())
    }

    @Test fun oneIntervalIncludesTheStartAndEndSoundsThenWaitsForTheTail() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1))
        clock.advance(999)
        assertEquals(0L, clock.engine.completed)
        assertEquals(listOf(0L), clock.ticks)
        clock.advance(1_000 + TickSound.CLOCK.completionMillis - 1)
        assertEquals(listOf(0L, 1_000L), clock.ticks)
        assertEquals(1L, clock.engine.completed)
        assertTrue(clock.engine.running)
        assertTrue(clock.finishes.isEmpty())
        clock.advance(1_000 + TickSound.CLOCK.completionMillis)
        assertFalse(clock.engine.running)
    }

    @Test fun unlimitedIgnoresFiniteCountUntilCancelled() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1, true))
        clock.advance(100_500)
        assertEquals(101, clock.ticks.size)
        assertEquals(100L, clock.engine.completed)
        assertTrue(clock.engine.running)
        assertTrue(clock.finishes.isEmpty())
        clock.engine.stop()
        clock.advance(200_000)
        assertEquals(101, clock.ticks.size)
        assertTrue(clock.jobs.isEmpty())
    }

    @Test fun cancellationBeforeTheFirstBoundaryDoesNotCountAnInterval() {
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

    @Test fun cancellationBetweenIntervalsRemovesPendingWork() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 3))
        clock.advance(1_500)
        clock.engine.stop()
        clock.advance(100_000)
        assertEquals(listOf(0L, 1_000L), clock.ticks)
        assertEquals(1L, clock.engine.completed)
        assertTrue(clock.jobs.isEmpty())
    }

    @Test fun delayedCallbackSkipsMissedSlotsWithoutCatchUpOrLosingTheEndSound() {
        val clock = Clock()
        clock.engine.start(TickSettings(10, 3))
        clock.deliverLate(35_000)
        assertEquals(listOf(0L, 35_000L), clock.ticks)
        assertEquals(40_000L, clock.jobs.single().first)
        clock.advance(50_000 + TickSound.CLOCK.completionMillis)
        assertEquals(listOf(0L, 35_000L, 40_000L, 50_000L), clock.ticks)
        assertEquals(3L, clock.engine.completed)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun delayedFinalDrainCallbackCannotPlayAnotherSound() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1))
        clock.advance(1_000)
        clock.deliverLate(10_500)
        assertEquals(listOf(0L, 1_000L), clock.ticks)
        assertEquals(listOf(true), clock.finishes)
        assertTrue(clock.jobs.isEmpty())
    }

    @Test fun normalCallbackJitterDoesNotAccumulate() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 4))
        clock.deliverLate(1_035)
        clock.deliverLate(2_022)
        clock.advance(4_500)
        assertEquals(listOf(0L, 1_035L, 2_022L, 3_000L, 4_000L), clock.ticks)
    }

    @Test fun duplicateStartsAreIgnoredAndRestartIsFresh() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 3))
        clock.engine.start(TickSettings(10, 1))
        clock.advance(1_500)
        assertEquals(1L, clock.engine.completed)
        val staleCallback = clock.jobs.single().second
        clock.engine.stop()
        clock.engine.start(TickSettings(2, 2))
        staleCallback()
        assertEquals(0L, clock.engine.completed)
        clock.advance(6_000)
        assertEquals(listOf(0L, 1_000L, 1_500L, 3_500L, 5_500L), clock.ticks)
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

    @Test fun failedEndSoundReportsFailureAndReleasesPendingWork() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1))
        clock.canPlay = false
        clock.advance(5_000)
        assertEquals(listOf(0L), clock.ticks)
        assertEquals(listOf(false), clock.finishes)
        assertFalse(clock.engine.running)
        assertTrue(clock.jobs.isEmpty())
    }

    @Test fun maximumIntervalDoesNotOverflowMilliseconds() {
        val clock = Clock()
        clock.engine.start(TickSettings(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE.toLong() * 1_000, clock.jobs.single().first)
    }

    @Test fun everySoundKeepsOneSecondCadenceAndTheFinalTailFinishesFully() {
        for (sound in TickSound.entries) {
            val clock = Clock()
            clock.engine.start(TickSettings(1, 3, sound = sound))
            clock.advance(3_000 + sound.completionMillis - 1)
            assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), clock.ticks)
            assertEquals(3L, clock.engine.completed)
            assertTrue(clock.engine.running)
            clock.advance(3_000 + sound.completionMillis)
            assertEquals(listOf(true), clock.finishes)
            assertTrue(clock.jobs.isEmpty())
        }
    }

    @Test fun stoppingDuringTheFinalGongTailCancelsCompletion() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1, sound = TickSound.GONG))
        clock.advance(1_500)
        val callback = clock.jobs.single().second
        clock.engine.stop()
        callback()
        clock.advance(10_000)
        assertEquals(listOf(0L, 1_000L), clock.ticks)
        assertTrue(clock.finishes.isEmpty())
        assertTrue(clock.jobs.isEmpty())
    }

    @Test fun stoppingJustBeforeTheEndSuppressesTheEndSound() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1))
        clock.advance(999)
        clock.engine.stop()
        clock.advance(5_000)
        assertEquals(listOf(0L), clock.ticks)
        assertTrue(clock.finishes.isEmpty())
    }
}
