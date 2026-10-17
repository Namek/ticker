package net.namekdev.moments

import org.junit.Assert.*
import org.junit.Test

class TickEngineTest {
    private class Clock {
        var time = 0L
        val jobs = mutableListOf<Pair<Long, () -> Unit>>()
        val ticks = mutableListOf<Long>()
        val sounds = mutableListOf<TickSound>()
        val counts = mutableListOf<Long>()
        val finishes = mutableListOf<Boolean>()
        var canPlay = true
        val engine = TickEngine(
            now = { time },
            schedule = { delay, action -> jobs.add(time + delay to action) },
            cancelScheduled = { jobs.clear() },
            play = { sound ->
                if (canPlay) {
                    ticks.add(time)
                    sounds.add(sound)
                }
                canPlay
            },
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

    @Test fun startAndFinishUseTheirOwnSoundInBothFiniteModes() {
        for (mode in LimitMode.entries) {
            val clock = Clock()
            clock.engine.start(TickSettings(2, 3, sound = TickSound.TEMPLE_BLOCK,
                limitMode = mode, timeLimitSeconds = 5, boundarySound = TickSound.GONG))
            val end = if (mode == LimitMode.COUNT) 6_000L else 5_000L
            clock.advance(end)
            assertEquals(listOf(0L, 2_000L, 4_000L, end), clock.ticks)
            assertEquals(listOf(TickSound.GONG, TickSound.TEMPLE_BLOCK, TickSound.TEMPLE_BLOCK, TickSound.GONG), clock.sounds)
            clock.advance(end + TickSound.GONG.completionMillis - 1)
            assertTrue(clock.engine.running)
            clock.advance(end + TickSound.GONG.completionMillis)
            assertEquals(listOf(true), clock.finishes)
        }
    }

    @Test fun oneIntervalHasOnlyStartAndFinishCues() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1, sound = TickSound.GONG, boundarySound = TickSound.BELL))
        clock.advance(1_000)
        assertEquals(listOf(TickSound.BELL, TickSound.BELL), clock.sounds)
        clock.advance(1_000 + TickSound.BELL.completionMillis)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun unlimitedUsesTheBoundarySoundOnlyAtStart() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1, true, sound = TickSound.NONE, boundarySound = TickSound.BELL))
        clock.advance(3_000)
        assertEquals(listOf(TickSound.BELL, TickSound.NONE, TickSound.NONE, TickSound.NONE), clock.sounds)
        clock.engine.stop()
        assertTrue(clock.finishes.isEmpty())
    }

    @Test fun callbackPastTheDeadlinePlaysOnlyTheFinishSound() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 3, sound = TickSound.GONG, boundarySound = TickSound.CLOCK))
        clock.deliverLate(5_000)
        assertEquals(listOf(TickSound.CLOCK, TickSound.CLOCK), clock.sounds)
        clock.advance(5_000 + TickSound.CLOCK.completionMillis)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun silentFinishPreservesTheLastIntervalTailAcrossPause() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 2, sound = TickSound.GONG, boundarySound = TickSound.NONE))
        clock.advance(2_100)
        assertEquals(listOf(TickSound.NONE, TickSound.GONG, TickSound.NONE), clock.sounds)
        clock.engine.pause()
        clock.advance(12_100)
        clock.engine.resume()
        clock.advance(11_000 + TickSound.GONG.completionMillis - 1)
        assertTrue(clock.engine.running)
        clock.advance(11_000 + TickSound.GONG.completionMillis)
        assertEquals(listOf(true), clock.finishes)
        assertEquals(3, clock.sounds.size)
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

    @Test fun delayedCallbackSkipsMissedSlotsAndKeepsTheOriginalDeadline() {
        val clock = Clock()
        clock.engine.start(TickSettings(10, 5))
        clock.deliverLate(35_000)
        assertEquals(listOf(0L, 35_000L), clock.ticks)
        assertEquals(3L, clock.engine.completed)
        assertEquals(40_000L, clock.jobs.single().first)
        clock.advance(50_000 + TickSound.CLOCK.completionMillis)
        assertEquals(listOf(0L, 35_000L, 40_000L, 50_000L), clock.ticks)
        assertEquals(5L, clock.engine.completed)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun callbackPastTheCountDeadlinePlaysOnlyTheEndingSound() {
        val clock = Clock()
        clock.engine.start(TickSettings(10, 3))
        clock.deliverLate(35_000)
        clock.advance(100_000)
        assertEquals(listOf(0L, 35_000L), clock.ticks)
        assertEquals(3L, clock.engine.completed)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun timeLimitCanEndBetweenRegularIntervals() {
        val clock = Clock()
        clock.engine.start(TickSettings(10, 1, limitMode = LimitMode.TIME, timeLimitSeconds = 25))
        clock.advance(25_000)
        assertEquals(listOf(0L, 10_000L, 20_000L, 25_000L), clock.ticks)
        assertEquals(2L, clock.engine.completed)
        assertTrue(clock.engine.running)
        clock.advance(25_000 + TickSound.CLOCK.completionMillis)
        assertEquals(listOf(true), clock.finishes)
        assertTrue(clock.jobs.isEmpty())
    }

    @Test fun timeLimitShorterThanOneIntervalStillHasAnEndSound() {
        val clock = Clock()
        clock.engine.start(TickSettings(10, 99, limitMode = LimitMode.TIME, timeLimitSeconds = 3))
        clock.advance(3_000 + TickSound.CLOCK.completionMillis)
        assertEquals(listOf(0L, 3_000L), clock.ticks)
        assertEquals(0L, clock.engine.completed)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun timeLimitOnAnIntervalBoundaryDoesNotDoubleTheEndSound() {
        val clock = Clock()
        clock.engine.start(TickSettings(2, 1, limitMode = LimitMode.TIME, timeLimitSeconds = 6))
        clock.advance(10_000)
        assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L), clock.ticks)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun delayedTimeLimitCallbackFinishesWithoutExtendingTheSession() {
        val clock = Clock()
        clock.engine.start(TickSettings(2, 99, limitMode = LimitMode.TIME, timeLimitSeconds = 7))
        clock.deliverLate(10_000)
        clock.advance(20_000)
        assertEquals(listOf(0L, 10_000L), clock.ticks)
        assertEquals(3L, clock.engine.completed)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun unlimitedIgnoresTheSelectedTimeLimit() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1, true, limitMode = LimitMode.TIME, timeLimitSeconds = 1))
        clock.advance(10_000)
        assertEquals(11, clock.ticks.size)
        assertTrue(clock.engine.running)
        assertTrue(clock.finishes.isEmpty())
    }

    @Test fun stoppingBeforeTheTimeLimitCancelsItsEndSound() {
        val clock = Clock()
        clock.engine.start(TickSettings(10, 1, limitMode = LimitMode.TIME, timeLimitSeconds = 3))
        val staleCallback = clock.jobs.single().second
        clock.advance(2_999)
        clock.engine.stop()
        staleCallback()
        clock.advance(10_000)
        assertEquals(listOf(0L), clock.ticks)
        assertTrue(clock.finishes.isEmpty())
        assertTrue(clock.jobs.isEmpty())
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

    @Test fun pausePreservesTheRemainingFractionOfTheIntervalAndFiniteDeadline() {
        val clock = Clock()
        clock.engine.start(TickSettings(2, 2))
        clock.advance(750)
        clock.engine.pause()
        assertEquals(750L, clock.engine.pausedAtMillis)
        assertTrue(clock.engine.running)
        assertTrue(clock.jobs.isEmpty())
        clock.advance(10_750)
        assertEquals(listOf(0L), clock.ticks)
        clock.engine.resume()
        assertNull(clock.engine.pausedAtMillis)
        assertEquals(10_000L, clock.engine.startedAtMillis)
        assertEquals(12_000L, clock.jobs.single().first)
        clock.advance(11_999)
        assertEquals(listOf(0L), clock.ticks)
        clock.advance(14_000 + TickSound.CLOCK.completionMillis)
        assertEquals(listOf(0L, 12_000L, 14_000L), clock.ticks)
        assertEquals(2L, clock.engine.completed)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun repeatedPausesExcludeAllPausedTimeInUnlimitedMode() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1, true))
        clock.advance(1_300)
        val staleCallback = clock.jobs.single().second
        clock.engine.pause()
        clock.advance(6_300)
        clock.engine.pause() // Duplicate commands must not replace the pause time.
        clock.engine.start(TickSettings(10, 1))
        assertEquals(1_300L, clock.engine.pausedAtMillis)
        clock.engine.resume()
        clock.engine.resume()
        staleCallback()
        assertEquals(1, clock.jobs.size)
        clock.advance(7_200)
        clock.engine.pause()
        clock.advance(9_200)
        clock.engine.resume()
        clock.advance(10_000)
        assertEquals(7_000L, clock.engine.startedAtMillis)
        assertEquals(listOf(0L, 1_000L, 7_000L, 10_000L), clock.ticks)
        assertEquals(3L, clock.engine.completed)
        assertTrue(clock.engine.running)
        assertTrue(clock.finishes.isEmpty())
    }

    @Test fun pausedTimeLimitStillEndsBetweenIntervalsWithoutAnExtraResumeSound() {
        val clock = Clock()
        clock.engine.start(TickSettings(2, 99, limitMode = LimitMode.TIME, timeLimitSeconds = 3))
        clock.advance(2_800)
        clock.engine.pause()
        clock.advance(12_800)
        clock.engine.resume()
        assertEquals(listOf(0L, 2_000L), clock.ticks)
        clock.advance(13_000 + TickSound.CLOCK.completionMillis)
        assertEquals(listOf(0L, 2_000L, 13_000L), clock.ticks)
        assertEquals(1L, clock.engine.completed)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun pausingTheEndingGongPreservesItsRemainingTail() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1, sound = TickSound.GONG))
        clock.advance(1_800)
        val staleCompletion = clock.jobs.single().second
        clock.engine.pause()
        clock.advance(11_800)
        staleCompletion()
        assertTrue(clock.finishes.isEmpty())
        clock.engine.resume()
        staleCompletion()
        val end = 11_000 + TickSound.GONG.completionMillis
        clock.advance(end - 1)
        assertTrue(clock.engine.running)
        clock.advance(end)
        assertEquals(listOf(0L, 1_000L), clock.ticks)
        assertEquals(listOf(true), clock.finishes)
        assertTrue(clock.jobs.isEmpty())
    }

    @Test fun stopWhilePausedCannotResumeAndAnotherStartIsFresh() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 1, sound = TickSound.GONG))
        clock.advance(1_500)
        val staleCompletion = clock.jobs.single().second
        clock.engine.pause()
        clock.engine.stop()
        clock.advance(20_000)
        clock.engine.resume()
        assertFalse(clock.engine.running)
        assertNull(clock.engine.pausedAtMillis)
        assertTrue(clock.jobs.isEmpty())
        clock.engine.start(TickSettings(2, 1))
        staleCompletion()
        clock.advance(22_000 + TickSound.CLOCK.completionMillis)
        assertEquals(listOf(0L, 1_000L, 20_000L, 22_000L), clock.ticks)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun pauseAndResumeBeforeStartDoNothing() {
        val clock = Clock()
        clock.engine.pause()
        clock.engine.resume()
        assertFalse(clock.engine.running)
        assertNull(clock.engine.pausedAtMillis)
        assertTrue(clock.jobs.isEmpty())
        assertTrue(clock.ticks.isEmpty())
    }

    @Test fun anOverdueCallbackAfterResumeStillSkipsMissedSlotsWithoutABurst() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 10))
        clock.time = 3_400 // The main loop has not delivered the first boundary yet.
        clock.engine.pause()
        clock.advance(8_400)
        clock.engine.resume()
        clock.deliverLate(9_200) // Resume's immediate callback is also delayed.
        assertEquals(listOf(0L, 9_200L), clock.ticks)
        assertEquals(4L, clock.engine.completed)
        assertEquals(10_000L, clock.jobs.single().first)
        clock.advance(15_000 + TickSound.CLOCK.completionMillis)
        assertEquals(10L, clock.engine.completed)
        assertEquals(listOf(true), clock.finishes)
    }

    @Test fun playbackFailureAfterResumeReleasesThePendingSession() {
        val clock = Clock()
        clock.engine.start(TickSettings(1, 2))
        clock.advance(900)
        clock.engine.pause()
        clock.advance(2_900)
        clock.canPlay = false
        clock.engine.resume()
        clock.advance(3_000)
        assertEquals(listOf(0L), clock.ticks)
        assertEquals(listOf(false), clock.finishes)
        assertFalse(clock.engine.running)
        assertTrue(clock.jobs.isEmpty())
    }
}
