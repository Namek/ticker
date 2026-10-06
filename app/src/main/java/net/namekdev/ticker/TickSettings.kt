package net.namekdev.ticker

enum class TickSound(val id: String, val labelRes: Int, val audioRes: Int, val durationMillis: Long) {
    CLOCK("tick", R.string.sound_clock, R.raw.tick, 300),
    ELECTRIC_CLOCK("electric_clock", R.string.sound_electric_clock, R.raw.electric_clock, 150),
    GONG("gong", R.string.sound_gong, R.raw.gong, 3_000),
    BOWL("bowl", R.string.sound_bowl, R.raw.bowl, 3_000),
    BELL("bell", R.string.sound_bell, R.raw.bell, 2_000);

    // SoundPool has no completion callback. Leave time for the output buffer to drain.
    val completionMillis: Long get() = durationMillis + 200L

    companion object {
        fun fromId(id: String?): TickSound = entries.firstOrNull { it.id == id } ?: CLOCK
    }
}

enum class LimitMode { COUNT, TIME }

data class TickSettings(
    val intervalSeconds: Int = 10,
    val repeatCount: Int = 10,
    val unlimited: Boolean = false,
    val sound: TickSound = TickSound.CLOCK,
    val limitMode: LimitMode = LimitMode.COUNT,
    val timeLimitSeconds: Int = 600,
) {
    init {
        require(intervalSeconds > 0 && repeatCount > 0 && timeLimitSeconds > 0)
    }

    val intervalMillis: Long get() = intervalSeconds.toLong() * 1_000L

    // The product of two positive Ints fits in Long seconds, but not always milliseconds.
    val durationSeconds: Long? get() = when {
        unlimited -> null
        limitMode == LimitMode.TIME -> timeLimitSeconds.toLong()
        else -> intervalSeconds.toLong() * repeatCount
    }

    fun remainingSeconds(elapsedMillis: Long): Long? =
        durationSeconds?.let { (it - elapsedMillis.coerceAtLeast(0) / 1_000).coerceAtLeast(0) }

    fun completedIntervals(elapsedMillis: Long): Long {
        val seconds = elapsedMillis.coerceAtLeast(0) / 1_000
        return minOf(seconds, durationSeconds ?: seconds) / intervalSeconds
    }
}

fun positiveWholeNumber(text: String): Int? {
    val number = text.trim()
    if (number.isEmpty() || number.any { it !in '0'..'9' }) return null
    return number.toIntOrNull()?.takeIf { it > 0 }
}

fun timeLimitSeconds(minutes: String, seconds: String): Int? {
    fun part(text: String): Int? = text.trim().takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
    val m = part(minutes) ?: return null
    val s = part(seconds)?.takeIf { it < 60 } ?: return null
    return (m.toLong() * 60 + s).takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
}

fun formatTimer(seconds: Long): String {
    val value = seconds.coerceAtLeast(0)
    val minutes = (value / 60 % 60).toString().padStart(2, '0')
    val remainder = (value % 60).toString().padStart(2, '0')
    return if (value < 3_600) "$minutes:$remainder" else "${value / 3_600}:$minutes:$remainder"
}
