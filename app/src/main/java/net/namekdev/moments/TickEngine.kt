package net.namekdev.moments

/** All calls, including scheduled callbacks, run on the service's main thread. */
class TickEngine(
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
    private val play: (TickSound) -> Boolean,
    private val onIntervalCompleted: (Long) -> Unit,
    private val onFinished: (Boolean) -> Unit,
) {
    var running = false
        private set
    var completed = 0L
        private set
    // Timer base shifts on Resume so elapsed time excludes pauses.
    var startedAtMillis = 0L
        private set
    var pausedAtMillis: Long? = null
        private set
    private var generation = 0L
    private var nextAt = 0L
    private var audioEndsAt = 0L
    private var pending: (() -> Unit)? = null
    private var settings = TickSettings()

    fun start(settings: TickSettings) {
        if (running) return
        this.settings = settings
        completed = 0
        startedAtMillis = now()
        audioEndsAt = startedAtMillis
        pausedAtMillis = null
        running = true
        generation++
        tick(first = true)
    }

    fun pause() {
        if (!running || pausedAtMillis != null) return
        pausedAtMillis = now()
        generation++
        cancelScheduled()
    }

    fun resume() {
        val pausedAt = pausedAtMillis ?: return
        val time = now()
        val pauseDuration = time - pausedAt
        startedAtMillis += pauseDuration
        nextAt += pauseDuration
        audioEndsAt += pauseDuration
        pausedAtMillis = null
        pending?.let { queue((nextAt - time).coerceAtLeast(0), it) }
    }

    fun stop() {
        running = false
        pausedAtMillis = null
        pending = null
        generation++
        cancelScheduled()
    }

    private fun queue(delay: Long, action: () -> Unit) {
        val session = generation
        nextAt = now() + delay
        pending = action
        schedule(delay) {
            if (!running || pausedAtMillis != null || session != generation) return@schedule
            pending = null
            action()
        }
    }

    private fun tick(first: Boolean = false) {
        val elapsed = (now() - startedAtMillis).coerceAtLeast(0)
        val remaining = settings.remainingSeconds(elapsed)
        val sound = if (first || remaining == 0L) settings.boundarySound else settings.sound
        if (!play(sound)) {
            stop()
            onFinished(false)
            return
        }
        if (sound != TickSound.NONE) audioEndsAt = maxOf(audioEndsAt, now() + sound.completionMillis)
        if (!first) {
            completed = settings.completedIntervals(elapsed)
            onIntervalCompleted(completed)
        }
        if (remaining == 0L) {
            // A shorter finish sound must not cut off a previous interval's ringing tail.
            queue(maxOf(sound.completionMillis, audioEndsAt - now())) {
                stop()
                onFinished(true)
            }
        } else {
            // Keep the original phase and queue only one future boundary, skipping missed slots.
            // Sound tails may overlap, but never delay the next interval.
            val gridDelay = settings.intervalMillis - elapsed % settings.intervalMillis
            // Only convert the small remaining span to milliseconds, avoiding overflow for
            // very large interval counts. A time limit can end between regular boundaries.
            val endDelay = if (remaining != null && remaining <= settings.intervalSeconds) {
                remaining * 1_000 - elapsed % 1_000
            } else gridDelay
            val delay = minOf(gridDelay, endDelay)
            queue(delay) { tick() }
        }
    }
}
