package net.namekdev.ticker

/** All calls, including scheduled callbacks, run on the service's main thread. */
class TickEngine(
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
    private val play: () -> Boolean,
    private val onIntervalCompleted: (Long) -> Unit,
    private val onFinished: (Boolean) -> Unit,
) {
    var running = false
        private set
    var completed = 0L
        private set
    private var generation = 0L
    private var origin = 0L
    private var settings = TickSettings()

    fun start(settings: TickSettings) {
        if (running) return
        this.settings = settings
        completed = 0
        origin = now()
        running = true
        generation++
        tick(generation, first = true)
    }

    fun stop() {
        running = false
        generation++
        cancelScheduled()
    }

    private fun tick(session: Long, first: Boolean = false) {
        if (!running || session != generation) return
        if (!play()) {
            stop()
            onFinished(false)
            return
        }
        if (!first) {
            completed++
            onIntervalCompleted(completed)
        }
        if (!settings.unlimited && completed == settings.repeatCount.toLong()) {
            // The final boundary has its own sound; keep resources until its tail drains.
            schedule(settings.sound.completionMillis) {
                if (!running || session != generation) return@schedule
                stop()
                onFinished(true)
            }
        } else {
            // Keep the original phase and queue only one future boundary, skipping missed slots.
            // Sound tails may overlap, but never delay the next interval.
            val elapsed = now() - origin
            val delay = settings.intervalMillis - elapsed % settings.intervalMillis
            schedule(delay) { tick(session) }
        }
    }
}
