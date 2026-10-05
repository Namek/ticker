package net.namekdev.ticker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.Chronometer
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat

@SuppressLint("SetTextI18n") // Editable numbers deliberately use the parser's ungrouped ASCII format.
class MainActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var interval: EditText
    private lateinit var repeats: EditText
    private lateinit var unlimited: Switch
    private lateinit var sound: SoundSpinner
    internal val preview by lazy {
        SoundPreview(applicationContext) { Toast.makeText(this, R.string.preview_error, Toast.LENGTH_SHORT).show() }
    }
    private lateinit var button: Button
    private lateinit var counter: TextView
    private lateinit var status: TextView
    private lateinit var elapsed: Chronometer
    private lateinit var stopButton: Button
    private var service: TickService? = null
    private var bound = false
    private var startPending = false
    private var updatingFields = false
    private var session = SessionState()
    private var stopConfirmationPending = false
    private var stopDialog: AlertDialog? = null
    private val observer: (SessionState) -> Unit = { state ->
        session = state
        startPending = false
        if (state.active) {
            preview.stop()
            state.settings?.let { showSettings(it) }
        } else dismissStopConfirmation()
        render()
        if (state.active && stopConfirmationPending) showStopConfirmation()
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TickService.LocalBinder).service
            service?.observe(observer)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            session = SessionState()
            startPending = false
            dismissStopConfirmation()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val padding = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                insets
            }
        }
        store = SettingsStore(this)
        interval = findViewById(R.id.interval)
        repeats = findViewById(R.id.repeats)
        unlimited = findViewById(R.id.unlimited)
        sound = findViewById(R.id.sound)
        sound.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item,
            TickSound.entries.map { getString(it.labelRes) }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        button = findViewById(R.id.start_stop)
        counter = findViewById(R.id.counter)
        status = findViewById(R.id.status)
        elapsed = findViewById(R.id.elapsed)
        stopButton = findViewById(R.id.stop)
        stopConfirmationPending = savedInstanceState?.getBoolean("confirm_stop") ?: false
        showSettings(store.load())

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updatingFields) return
                validate(interval)
                if (!unlimited.isChecked) validate(repeats)
                saveSettings()
            }
        }
        interval.addTextChangedListener(watcher)
        repeats.addTextChangedListener(watcher)
        unlimited.setOnCheckedChangeListener { _, checked ->
            if (!updatingFields) {
                if (checked && positiveWholeNumber(repeats.text.toString()) == null) {
                    repeats.setText(store.load().repeatCount.toString())
                }
                repeats.error = null
                if (checked && repeats.hasFocus()) {
                    repeats.clearFocus()
                    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(repeats.windowToken, 0)
                }
                saveSettings()
                render()
            }
        }
        sound.onSelection = { position ->
            if (!updatingFields && !session.active && !startPending) {
                saveSettings()
                preview.play(TickSound.entries[position])
            }
        }
        button.setOnClickListener { if (!session.active && !startPending) startPlayback() }
        stopButton.setOnClickListener { showStopConfirmation() }
        render()
    }

    override fun onStart() {
        super.onStart()
        button.isEnabled = false
        stopButton.isEnabled = false
        bound = bindService(Intent(this, TickService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        showNotificationHint()
    }

    override fun onStop() {
        preview.stop()
        elapsed.stop()
        // Keep the pending choice through rotation, without retaining the old window.
        stopDialog?.setOnDismissListener(null)
        stopDialog?.dismiss()
        stopDialog = null
        service?.removeObserver(observer)
        service = null
        if (bound) unbindService(connection)
        bound = false
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("confirm_stop", stopConfirmationPending)
        super.onSaveInstanceState(outState)
    }

    private fun showStopConfirmation() {
        if ((!session.active && !startPending) || stopDialog != null) return
        stopConfirmationPending = true
        stopDialog = AlertDialog.Builder(this)
            .setTitle(R.string.confirm_stop_title)
            .setMessage(R.string.confirm_stop_message)
            .setNegativeButton(R.string.keep_ticking, null)
            .setPositiveButton(R.string.stop) { _, _ ->
                // Use the same command queue as Start to preserve ordering.
                startService(Intent(this, TickService::class.java).setAction(TickService.ACTION_STOP))
            }
            .setOnDismissListener {
                stopConfirmationPending = false
                stopDialog = null
            }
            .show()
    }

    private fun dismissStopConfirmation() {
        stopConfirmationPending = false
        stopDialog?.dismiss()
        stopDialog = null
    }

    private fun showSettings(settings: TickSettings) {
        updatingFields = true
        interval.setText(settings.intervalSeconds.toString())
        repeats.setText(settings.repeatCount.toString())
        unlimited.isChecked = settings.unlimited
        if (sound.selectedItemPosition != settings.sound.ordinal) sound.setSelection(settings.sound.ordinal)
        updatingFields = false
    }

    private fun saveSettings() {
        store.saveValid(interval.text.toString(), repeats.text.toString(), unlimited.isChecked, selectedSound())
    }

    private fun selectedSound() = TickSound.entries[sound.selectedItemPosition]

    private fun validate(field: EditText): Int? = positiveWholeNumber(field.text.toString()).also {
        field.error = if (it == null) getString(R.string.number_error) else null
    }

    private fun startPlayback() {
        val seconds = validate(interval)
        val count = if (unlimited.isChecked) store.load().repeatCount else validate(repeats)
        if (seconds == null || count == null) return
        preview.stop()
        saveSettings()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(interval.windowToken, 0)
        startPending = true
        val selectedSound = selectedSound()
        session = SessionState(Phase.LOADING, settings = TickSettings(seconds, count, unlimited.isChecked, selectedSound))
        render()
        try {
            startForegroundService(Intent(this, TickService::class.java).setAction(TickService.ACTION_START)
                .putExtra(TickService.EXTRA_INTERVAL, seconds)
                .putExtra(TickService.EXTRA_REPEATS, count)
                .putExtra(TickService.EXTRA_UNLIMITED, unlimited.isChecked)
                .putExtra(TickService.EXTRA_SOUND, selectedSound.id))
        } catch (_: RuntimeException) {
            startPending = false
            session = SessionState(Phase.FAILED)
            render()
            status.setText(R.string.start_error)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !store.notificationRequested) {
            store.notificationRequested = true
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showNotificationHint()
    }

    private fun showNotificationHint() {
        val visibility = if (
            Build.VERSION.SDK_INT >= 33 && store.notificationRequested &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) View.VISIBLE else View.GONE
        findViewById<View>(R.id.notification_hint).visibility = visibility
        findViewById<View>(R.id.playback_notification_hint).visibility = visibility
    }

    private fun render() {
        val active = session.active || startPending
        findViewById<View>(R.id.configuration_screen).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<View>(R.id.playback_screen).visibility = if (active) View.VISIBLE else View.GONE
        val canEdit = service != null && !active
        interval.isEnabled = canEdit
        repeats.isEnabled = canEdit && !unlimited.isChecked
        repeats.alpha = if (repeats.isEnabled) 1f else 0.4f
        findViewById<TextView>(R.id.count_help).setText(
            if (unlimited.isChecked) R.string.count_disabled_help else R.string.repeat_help)
        unlimited.isEnabled = canEdit
        sound.isEnabled = canEdit
        button.isEnabled = service != null
        stopButton.isEnabled = service != null && active
        if (active) {
            val base = session.startedAtMillis ?: SystemClock.elapsedRealtime()
            if (elapsed.base != base) elapsed.base = base
            if (session.startedAtMillis != null) elapsed.start() else elapsed.stop()
        } else {
            elapsed.stop()
        }
        counter.text = NumberFormat.getIntegerInstance().format(session.completed)
        counter.contentDescription = getString(R.string.counter_description, session.completed)
        findViewById<TextView>(R.id.playback_counter).text = counter.contentDescription
        val statusText = getString(when (session.phase) {
            Phase.READY -> R.string.ready
            Phase.LOADING -> R.string.loading
            Phase.PLAYING -> R.string.playing
            Phase.COMPLETE -> R.string.complete
            Phase.STOPPED -> R.string.stopped
            Phase.FAILED -> R.string.playback_error
        })
        if (status.text != statusText) status.text = statusText
        val playbackStatus = findViewById<TextView>(R.id.playback_status)
        if (playbackStatus.text != statusText) playbackStatus.text = statusText
    }
}
