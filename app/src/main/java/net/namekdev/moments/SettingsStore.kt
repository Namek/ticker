package net.namekdev.moments

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("tick_settings", Context.MODE_PRIVATE)

    fun load() = TickSettings(
        preferences.getInt("interval", 10).coerceAtLeast(1),
        preferences.getInt("repeats", 10).coerceAtLeast(1),
        preferences.getBoolean("unlimited", false),
        TickSound.fromId(preferences.getString("sound", null)),
        if (preferences.getString("limit_mode", null) == "time") LimitMode.TIME else LimitMode.COUNT,
        preferences.getInt("time_limit", 600).coerceAtLeast(1),
    )

    fun saveValid(interval: String, repeats: String, unlimited: Boolean, sound: TickSound,
                  limitMode: LimitMode, minutes: String, seconds: String) {
        preferences.edit().apply {
            positiveWholeNumber(interval)?.let { putInt("interval", it) }
            positiveWholeNumber(repeats)?.let { putInt("repeats", it) }
            putBoolean("unlimited", unlimited)
            putString("sound", sound.id)
            putString("limit_mode", if (limitMode == LimitMode.TIME) "time" else "count")
            timeLimitSeconds(minutes, seconds)?.let { putInt("time_limit", it) }
        }.apply()
    }

    var notificationRequested: Boolean
        get() = preferences.getBoolean("notification_requested", false)
        set(value) { preferences.edit().putBoolean("notification_requested", value).apply() }
}
