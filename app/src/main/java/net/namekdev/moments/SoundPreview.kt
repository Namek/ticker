package net.namekdev.moments

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper

/** A screen-owned preview. It never starts the playback service or takes audio focus. */
class SoundPreview(private val context: Context, private val onFailure: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var pool: SoundPool? = null
    val active: Boolean get() = pool != null
    var playing = false
        private set
    var sound: TickSound? = null
        private set

    fun play(sound: TickSound) {
        stop()
        if (sound == TickSound.NONE) return
        this.sound = sound
        try {
            val newPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            ).build()
            pool = newPool
            newPool.setOnLoadCompleteListener { loadedPool, id, status ->
                if (pool !== loadedPool) return@setOnLoadCompleteListener
                handler.removeCallbacksAndMessages(null)
                try {
                    if (status != 0 || loadedPool.play(id, 1f, 1f, 1, 0, 1f) == 0) {
                        fail()
                    } else {
                        playing = true
                        handler.postDelayed({ stop() }, sound.completionMillis)
                    }
                } catch (_: RuntimeException) {
                    fail()
                }
            }
            handler.postDelayed({ fail() }, 10_000)
            if (newPool.load(context, sound.audioRes, 1) == 0) fail()
        } catch (_: RuntimeException) {
            fail()
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        pool?.setOnLoadCompleteListener(null)
        pool?.release()
        pool = null
        playing = false
        sound = null
    }

    private fun fail() {
        stop()
        onFailure()
    }
}
