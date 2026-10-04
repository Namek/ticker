package net.namekdev.ticker

enum class TickSound(val id: String, val labelRes: Int, val audioRes: Int, val durationMillis: Long) {
    CLOCK("tick", R.string.sound_clock, R.raw.tick, 180),
    GONG("gong", R.string.sound_gong, R.raw.gong, 750),
    BOWL("bowl", R.string.sound_bowl, R.raw.bowl, 750),
    BELL("bell", R.string.sound_bell, R.raw.bell, 600);

    // SoundPool has no completion callback. Leave time for the output buffer to drain.
    val completionMillis: Long get() = durationMillis + 200L

    companion object {
        fun fromId(id: String?): TickSound = entries.firstOrNull { it.id == id } ?: CLOCK
    }
}

data class TickSettings(
    val intervalSeconds: Int = 10,
    val repeatCount: Int = 10,
    val unlimited: Boolean = false,
    val sound: TickSound = TickSound.CLOCK,
) {
    init {
        require(intervalSeconds > 0 && repeatCount > 0)
    }

    val intervalMillis: Long get() = intervalSeconds.toLong() * 1_000L
}

fun positiveWholeNumber(text: String): Int? {
    val number = text.trim()
    if (number.isEmpty() || number.any { it !in '0'..'9' }) return null
    return number.toIntOrNull()?.takeIf { it > 0 }
}
