package com.example.tick

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.io.FileDescriptor
import java.io.PrintWriter

enum class Phase { READY, LOADING, PLAYING, COMPLETE, STOPPED, FAILED }

data class SessionState(
    val phase: Phase = Phase.READY,
    val completed: Long = 0,
    val settings: TickSettings? = null,
    val startedAtMillis: Long? = null,
) {
    val active: Boolean get() = phase == Phase.LOADING || phase == Phase.PLAYING
}

class TickService : Service() {
    inner class LocalBinder : Binder() {
        val service: TickService get() = this@TickService
    }

    var state = SessionState()
        private set
    private val listeners = mutableSetOf<(SessionState) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var pool: SoundPool? = null
    private var soundId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var lastStartId = 0
    private val loadTimeout = Runnable { finish(Phase.FAILED) }
    private val engine = TickEngine(
        now = SystemClock::elapsedRealtime,
        schedule = { delay, action -> handler.postDelayed(action, delay) },
        cancelScheduled = { handler.removeCallbacksAndMessages(null) },
        play = {
            try {
                (pool?.play(soundId, 1f, 1f, 1, 0, 1f) ?: 0) != 0
            } catch (_: RuntimeException) {
                false
            }
        },
        onTickCompleted = { count ->
            publish(state.copy(completed = count))
            // The service must keep running even when notification permission is denied.
            if (getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
        },
        onFinished = { success -> finish(if (success) Phase.COMPLETE else Phase.FAILED) },
    )

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    override fun onBind(intent: Intent): IBinder = LocalBinder()

    fun observe(listener: (SessionState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeObserver(listener: (SessionState) -> Unit) {
        listeners.remove(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                if (!state.active) {
                    val interval = intent.getIntExtra(EXTRA_INTERVAL, 0)
                    val repeats = intent.getIntExtra(EXTRA_REPEATS, 0)
                    if (interval > 0 && repeats > 0) {
                        begin(TickSettings(interval, repeats, intent.getBooleanExtra(EXTRA_UNLIMITED, false),
                            TickSound.fromId(intent.getStringExtra(EXTRA_SOUND))))
                    } else {
                        finish(Phase.FAILED)
                    }
                }
            }
            ACTION_STOP -> finish(Phase.STOPPED)
            else -> if (!state.active) stopSelf(startId)
        }
        // Process death and reboot intentionally end the session.
        return START_NOT_STICKY
    }

    @SuppressLint("WakelockTimeout") // Unlimited playback owns this lock until finish/onDestroy.
    private fun begin(settings: TickSettings) {
        publish(SessionState(Phase.LOADING, settings = settings))
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            foreground = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tick:playback").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            val newPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).build()
            pool = newPool
            newPool.setOnLoadCompleteListener { loadedPool, loadedSound, status ->
                // SoundPool was created on the main looper. Identity rejects old session loads.
                if (pool !== loadedPool || state.phase != Phase.LOADING) return@setOnLoadCompleteListener
                handler.removeCallbacks(loadTimeout)
                if (status != 0 || loadedSound != soundId) {
                    finish(Phase.FAILED)
                } else {
                    publish(state.copy(phase = Phase.PLAYING, startedAtMillis = SystemClock.elapsedRealtime()))
                    engine.start(settings)
                }
            }
            handler.postDelayed(loadTimeout, 10_000)
            soundId = newPool.load(this, settings.sound.audioRes, 1)
            if (soundId == 0) finish(Phase.FAILED)
        } catch (_: RuntimeException) {
            finish(Phase.FAILED)
        }
    }

    private fun finish(phase: Phase) {
        releasePlayback()
        publish(state.copy(phase = phase))
        // A newer Start may already be queued in Android but not delivered to us yet.
        stopSelf(lastStartId)
    }

    private fun releasePlayback() {
        engine.stop()
        pool?.setOnLoadCompleteListener(null)
        pool?.release()
        pool = null
        soundId = 0
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    private fun publish(newState: SessionState) {
        state = newState
        listeners.toList().forEach { it(newState) }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TickService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_count, state.completed, state.settings?.intervalSeconds ?: 10))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .apply {
                if (Build.VERSION.SDK_INT >= 31) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    override fun onDestroy() {
        releasePlayback()
        listeners.clear()
        super.onDestroy()
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writer.println("phase=${state.phase} completed=${state.completed} interval=${state.settings?.intervalSeconds} sound=${state.settings?.sound?.id}")
        writer.println("wakeLockHeld=${wakeLock?.isHeld == true} soundPoolLoaded=${pool != null} engineRunning=${engine.running} foreground=$foreground")
    }

    companion object {
        const val ACTION_START = "com.example.tick.START"
        const val ACTION_STOP = "com.example.tick.STOP"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_REPEATS = "repeats"
        const val EXTRA_UNLIMITED = "unlimited"
        const val EXTRA_SOUND = "sound"
        const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "playback"
    }
}
