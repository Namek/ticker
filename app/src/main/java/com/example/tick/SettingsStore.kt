package com.example.tick

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("tick_settings", Context.MODE_PRIVATE)

    fun load() = TickSettings(
        preferences.getInt("interval", 10).coerceAtLeast(1),
        preferences.getInt("repeats", 10).coerceAtLeast(1),
        preferences.getBoolean("unlimited", false),
        TickSound.fromId(preferences.getString("sound", null)),
    )

    fun saveValid(interval: String, repeats: String, unlimited: Boolean, sound: TickSound) {
        preferences.edit().apply {
            positiveWholeNumber(interval)?.let { putInt("interval", it) }
            positiveWholeNumber(repeats)?.let { putInt("repeats", it) }
            putBoolean("unlimited", unlimited)
            putString("sound", sound.id)
        }.apply()
    }

    var notificationRequested: Boolean
        get() = preferences.getBoolean("notification_requested", false)
        set(value) { preferences.edit().putBoolean("notification_requested", value).apply() }
}
