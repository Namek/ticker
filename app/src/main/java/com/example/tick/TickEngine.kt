package com.example.tick

/** All calls, including scheduled callbacks, run on the service's main thread. */
class TickEngine(
    private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
    private val play: () -> Boolean,
    private val onTickCompleted: (Long) -> Unit,
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
        tick(generation)
    }

    fun stop() {
        running = false
        generation++
        cancelScheduled()
    }

    private fun tick(session: Long) {
        if (!running || session != generation) return
        if (!play()) {
            stop()
            onFinished(false)
            return
        }
        schedule(settings.sound.completionMillis) {
            if (!running || session != generation) return@schedule
            completed++
            onTickCompleted(completed)
            if (!settings.unlimited && completed == settings.repeatCount.toLong()) {
                stop()
                onFinished(true)
            } else {
                // Keep the original phase, skip missed slots, and never queue catch-up ticks.
                // Waiting until audio has drained also prevents overlapping delayed ticks.
                val elapsed = now() - origin
                val delay = settings.intervalMillis - elapsed % settings.intervalMillis
                schedule(delay) { tick(session) }
            }
        }
    }

}
