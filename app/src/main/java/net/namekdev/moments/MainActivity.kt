package net.namekdev.moments

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

@SuppressLint("SetTextI18n") // Editable numbers deliberately use the parser's ungrouped ASCII format.
class MainActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var interval: EditText
    private lateinit var repeats: EditText
    private lateinit var minutes: EditText
    private lateinit var seconds: EditText
    private lateinit var limitMode: RadioGroup
    private lateinit var unlimited: RadioButton
    private lateinit var sound: SoundSpinner
    internal val preview by lazy {
        SoundPreview(applicationContext) { Toast.makeText(this, R.string.preview_error, Toast.LENGTH_SHORT).show() }
    }
    private lateinit var button: Button
    private lateinit var error: TextView
    private lateinit var elapsed: TextView
    private lateinit var stopButton: Button
    private lateinit var pauseButton: Button
    private var service: TickService? = null
    private var bound = false
    private var startPending = false
    private var updatingFields = false
    private var session = SessionState()
    private var stopConfirmationPending = false
    private var stopDialog: AlertDialog? = null
    private var visible = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private val clearFlash = Runnable { renderBackground(session.active || startPending) }
    private val timerTick = object : Runnable {
        override fun run() {
            updateTimer()
            if (visible && session.phase == Phase.PLAYING) {
                timerHandler.postDelayed(this, 1_000 - session.elapsedMillis(SystemClock.elapsedRealtime()) % 1_000)
            }
        }
    }
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
        minutes = findViewById(R.id.time_minutes)
        seconds = findViewById(R.id.time_seconds)
        limitMode = findViewById(R.id.limit_mode)
        configureLayout()
        unlimited = findViewById(R.id.unlimited)
        sound = findViewById(R.id.sound)
        sound.adapter = ArrayAdapter(this, R.layout.sound_item,
            TickSound.entries.map { getString(it.labelRes) }).apply {
            setDropDownViewResource(R.layout.sound_item)
        }
        button = findViewById(R.id.start_stop)
        error = findViewById(R.id.playback_error)
        elapsed = findViewById(R.id.elapsed)
        stopButton = findViewById(R.id.stop)
        pauseButton = findViewById(R.id.pause_resume)
        stopConfirmationPending = savedInstanceState?.getBoolean("confirm_stop") ?: false
        showSettings(store.load())

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updatingFields) return
                validate(interval)
                if (!unlimited.isChecked) {
                    if (selectedMode() == LimitMode.COUNT) validate(repeats) else validateTime()
                }
                saveSettings()
            }
        }
        interval.addTextChangedListener(watcher)
        repeats.addTextChangedListener(watcher)
        minutes.addTextChangedListener(watcher)
        seconds.addTextChangedListener(watcher)
        limitMode.setOnCheckedChangeListener { _, _ ->
            if (!updatingFields) {
                restoreInvalidLimits()
                clearLimitFocus()
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
        pauseButton.setOnClickListener {
            val action = when (session.phase) {
                Phase.PLAYING -> TickService.ACTION_PAUSE
                Phase.PAUSED -> TickService.ACTION_RESUME
                else -> return@setOnClickListener
            }
            startService(Intent(this, TickService::class.java).setAction(action))
        }
        render()
    }

    private fun configureLayout() {
        val configuration = resources.configuration
        if (configuration.fontScale >= 1.3f) {
            for (group in listOf(limitMode, findViewById<LinearLayout>(R.id.number_fields))) {
                group.orientation = LinearLayout.VERTICAL
                for (index in 0 until group.childCount) {
                    group.getChildAt(index).layoutParams = (group.getChildAt(index).layoutParams as LinearLayout.LayoutParams).apply {
                        width = LinearLayout.LayoutParams.MATCH_PARENT
                        weight = 0f
                    }
                }
            }
            findViewById<View>(R.id.limit_fields).apply {
                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = 0
                    topMargin = (16 * resources.displayMetrics.density).toInt()
                }
            }
        }
        if (configuration.screenWidthDp > 600) {
            for (id in listOf(R.id.configuration_screen, R.id.playback_screen)) {
                findViewById<View>(id).layoutParams = FrameLayout.LayoutParams(
                    (600 * resources.displayMetrics.density).toInt(), FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER_HORIZONTAL)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        button.isEnabled = false
        stopButton.isEnabled = false
        pauseButton.isEnabled = false
        bound = bindService(Intent(this, TickService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        showNotificationHint()
    }

    override fun onStop() {
        visible = false
        timerHandler.removeCallbacks(timerTick)
        renderBackground(session.active || startPending)
        preview.stop()
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
        if (!session.active && !startPending) return
        val keep = if (session.phase == Phase.PAUSED) R.string.keep_paused else R.string.keep_ticking
        stopDialog?.let {
            it.getButton(AlertDialog.BUTTON_NEGATIVE).setText(keep)
            return
        }
        stopConfirmationPending = true
        stopDialog = AlertDialog.Builder(this)
            .setTitle(R.string.confirm_stop_title)
            .setMessage(R.string.confirm_stop_message)
            .setNegativeButton(keep, null)
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
        minutes.setText((settings.timeLimitSeconds / 60).toString())
        seconds.setText((settings.timeLimitSeconds % 60).toString())
        limitMode.check(when {
            settings.unlimited -> R.id.unlimited
            settings.limitMode == LimitMode.TIME -> R.id.time_mode
            else -> R.id.count_mode
        })
        if (sound.selectedItemPosition != settings.sound.ordinal) sound.setSelection(settings.sound.ordinal)
        updatingFields = false
    }

    private fun saveSettings() {
        store.saveValid(interval.text.toString(), repeats.text.toString(), unlimited.isChecked, selectedSound(),
            selectedMode(), minutes.text.toString(), seconds.text.toString())
        updateDurationPreview()
    }

    private fun updateDurationPreview() {
        val intervalSeconds = positiveWholeNumber(interval.text.toString())
        val duration = when {
            selectedMode() == LimitMode.TIME -> timeLimitSeconds(minutes.text.toString(), seconds.text.toString())?.toLong()
            else -> positiveWholeNumber(repeats.text.toString())?.let { count -> intervalSeconds?.toLong()?.times(count) }
        }
        findViewById<TextView>(R.id.duration_preview).text = when {
            intervalSeconds == null -> getString(R.string.duration_invalid)
            unlimited.isChecked -> getString(R.string.no_time_limit)
            duration == null -> getString(R.string.duration_invalid)
            else -> formatTimer(duration)
        }
    }

    private fun selectedSound() = TickSound.entries[sound.selectedItemPosition]
    private fun selectedMode() = when (limitMode.checkedRadioButtonId) {
        R.id.time_mode -> LimitMode.TIME
        R.id.count_mode -> LimitMode.COUNT
        else -> store.load().limitMode // Keep the last finite mode while Unlimited is selected.
    }

    private fun restoreInvalidLimits() {
        val saved = store.load()
        updatingFields = true
        if (positiveWholeNumber(repeats.text.toString()) == null) repeats.setText(saved.repeatCount.toString())
        if (timeLimitSeconds(minutes.text.toString(), seconds.text.toString()) == null) {
            minutes.setText((saved.timeLimitSeconds / 60).toString())
            seconds.setText((saved.timeLimitSeconds % 60).toString())
        }
        listOf(repeats, minutes, seconds).forEach { it.error = null }
        updatingFields = false
    }

    private fun clearLimitFocus() {
        listOf(repeats, minutes, seconds).firstOrNull { it.hasFocus() }?.let {
            it.clearFocus()
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun validate(field: EditText): Int? = positiveWholeNumber(field.text.toString()).also {
        field.error = if (it == null) getString(R.string.number_error) else null
    }

    private fun validateTime(): Int? = timeLimitSeconds(minutes.text.toString(), seconds.text.toString()).also {
        val error = if (it == null) getString(R.string.time_error) else null
        minutes.error = error
        seconds.error = error
    }

    private fun startPlayback() {
        val intervalSeconds = validate(interval)
        val saved = store.load()
        val mode = selectedMode()
        val count = if (!unlimited.isChecked && mode == LimitMode.COUNT) validate(repeats) else saved.repeatCount
        val limit = if (!unlimited.isChecked && mode == LimitMode.TIME) validateTime() else saved.timeLimitSeconds
        if (intervalSeconds == null || count == null || limit == null) return
        preview.stop()
        saveSettings()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(interval.windowToken, 0)
        startPending = true
        val selectedSound = selectedSound()
        session = SessionState(Phase.LOADING, settings = TickSettings(intervalSeconds, count, unlimited.isChecked, selectedSound, mode, limit))
        render()
        try {
            startForegroundService(Intent(this, TickService::class.java).setAction(TickService.ACTION_START)
                .putExtra(TickService.EXTRA_INTERVAL, intervalSeconds)
                .putExtra(TickService.EXTRA_REPEATS, count)
                .putExtra(TickService.EXTRA_UNLIMITED, unlimited.isChecked)
                .putExtra(TickService.EXTRA_SOUND, selectedSound.id)
                .putExtra(TickService.EXTRA_TIME_MODE, mode == LimitMode.TIME)
                .putExtra(TickService.EXTRA_TIME_LIMIT, limit))
        } catch (_: RuntimeException) {
            startPending = false
            session = SessionState(Phase.FAILED)
            render()
            error.setText(R.string.start_error)
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
        renderBackground(active)
        findViewById<View>(R.id.configuration_screen).visibility = if (active) View.GONE else View.VISIBLE
        findViewById<View>(R.id.playback_screen).visibility = if (active) View.VISIBLE else View.GONE
        val canEdit = service != null && !active
        val timeMode = selectedMode() == LimitMode.TIME
        interval.isEnabled = canEdit
        interval.imeOptions = if (unlimited.isChecked) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        interval.nextFocusForwardId = when {
            unlimited.isChecked -> R.id.sound
            timeMode -> R.id.time_minutes
            else -> R.id.repeats
        }
        findViewById<View>(R.id.limit_fields).visibility = if (unlimited.isChecked) View.GONE else View.VISIBLE
        repeats.visibility = if (timeMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.time_limit_fields).visibility = if (timeMode) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.limit_label).apply {
            visibility = if (unlimited.isChecked) View.GONE else View.VISIBLE
            setText(if (timeMode) R.string.time_limit_label else R.string.repeats_label)
            labelFor = if (timeMode) View.NO_ID else R.id.repeats
        }
        repeats.isEnabled = canEdit && !unlimited.isChecked
        listOf(minutes, seconds).forEach {
            it.isEnabled = canEdit && !unlimited.isChecked
        }
        listOf(R.id.unlimited, R.id.count_mode, R.id.time_mode).forEach {
            findViewById<RadioButton>(it).isEnabled = canEdit
        }
        findViewById<TextView>(R.id.count_help).apply {
            visibility = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) View.GONE else View.VISIBLE
            setText(if (unlimited.isChecked) R.string.unlimited_help else if (timeMode) R.string.time_limit_help else R.string.repeat_help)
        }
        sound.isEnabled = canEdit
        updateDurationPreview()
        button.isEnabled = service != null
        stopButton.isEnabled = service != null && active
        pauseButton.isEnabled = service != null && (session.phase == Phase.PLAYING || session.phase == Phase.PAUSED)
        pauseButton.setText(if (session.phase == Phase.PAUSED) R.string.resume else R.string.pause)
        timerHandler.removeCallbacks(timerTick)
        if (active) timerTick.run()
        error.visibility = if (session.phase == Phase.FAILED) View.VISIBLE else View.GONE
        error.setText(R.string.playback_error)
        val statusText = when (session.phase) {
            Phase.LOADING -> getString(R.string.loading)
            Phase.PLAYING -> getString(R.string.playing)
            Phase.PAUSED -> getString(R.string.paused)
            else -> ""
        }
        val playbackStatus = findViewById<TextView>(R.id.playback_status)
        if (playbackStatus.text != statusText) playbackStatus.text = statusText
    }

    @Suppress("DEPRECATION") // Older Android versions use bar colors and decor flags.
    private fun renderBackground(active: Boolean) {
        timerHandler.removeCallbacks(clearFlash)
        val now = SystemClock.elapsedRealtime()
        val flashRemaining = if (visible && session.phase == Phase.PLAYING) {
            session.lastIntervalAtMillis?.let { INTERVAL_FLASH_MILLIS - (now - it) } ?: 0L
        } else 0L
        val background = when {
            !active -> getColor(R.color.background)
            flashRemaining > 0 -> getColor(
                if (session.settings?.remainingSeconds(session.elapsedMillis(now)) == 0L) R.color.completion_flash
                else R.color.interval_flash)
            else -> Color.BLACK
        }
        if (flashRemaining > 0) timerHandler.postDelayed(clearFlash, flashRemaining)
        // The root also covers the system bar insets on edge-to-edge Android versions.
        findViewById<View>(R.id.root).setBackgroundColor(background)
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = background
            window.navigationBarColor = background
        }
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = !active
        val lightBars = !active && resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (lightBars) mask else 0, mask)
        } else {
            val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = (window.decorView.systemUiVisibility and mask.inv()) or if (lightBars) mask else 0
        }
    }

    private fun updateTimer() {
        val settings = session.settings ?: return
        val elapsedMillis = session.elapsedMillis(SystemClock.elapsedRealtime())
        val remaining = settings.remainingSeconds(elapsedMillis)
        findViewById<TextView>(R.id.interval_remaining).text = getString(
            R.string.interval_remaining, formatTimer(settings.intervalRemainingSeconds(elapsedMillis)))
        elapsed.text = formatTimer(remaining ?: (elapsedMillis / 1_000))
        findViewById<TextView>(R.id.timer_label).setText(if (settings.unlimited) R.string.elapsed_label else R.string.remaining_label)
        findViewById<TextView>(R.id.playback_counter).apply {
            visibility = if (!settings.unlimited && settings.limitMode == LimitMode.COUNT) View.VISIBLE else View.GONE
            if (visibility == View.VISIBLE) {
                val left = (settings.repeatCount - settings.completedIntervals(elapsedMillis)).toInt()
                text = resources.getQuantityString(R.plurals.intervals_left, left, left)
            }
        }
    }
}
