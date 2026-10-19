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
import android.util.TypedValue
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
import android.widget.ScrollView
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
    private lateinit var boundarySound: SoundSpinner
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
            val updateUptime = SystemClock.uptimeMillis()
            val elapsedMillis = session.elapsedMillis(SystemClock.elapsedRealtime())
            updateTimer(elapsedMillis)
            if (visible && session.phase == Phase.PLAYING) {
                // A slow redraw must not skip the second after the value we just displayed.
                timerHandler.postAtTime(this, updateUptime + 1_000 - elapsedMillis % 1_000)
            }
        }
    }
    private val observer: (SessionState) -> Unit = { state ->
        // onStart disables Pause until a service rebind restores the full UI.
        val intervalUpdate = session.phase == Phase.PLAYING && state.phase == Phase.PLAYING && pauseButton.isEnabled
        session = state
        startPending = false
        if (intervalUpdate) {
            // Sound was just requested; update its flash without rebuilding the settings UI.
            renderBackground(true)
            updateTimer()
        } else {
            if (state.active) {
                preview.stop()
                state.settings?.let { showSettings(it) }
            } else dismissStopConfirmation()
            render()
            if (state.active && stopConfirmationPending) showStopConfirmation()
        }
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
        boundarySound = findViewById(R.id.boundary_sound)
        for (selector in listOf(sound, boundarySound)) {
            selector.adapter = ArrayAdapter(this, R.layout.sound_item,
                TickSound.entries.map { getString(it.labelRes) }).apply {
                setDropDownViewResource(R.layout.sound_item)
            }
        }
        findViewById<View>(R.id.sound_fields).setOnClickListener { sound.performClick() }
        findViewById<View>(R.id.boundary_sound_fields).setOnClickListener { boundarySound.performClick() }
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
        for ((field, decrease, increase) in listOf(
            Triple(interval, R.id.interval_decrease, R.id.interval_increase),
            Triple(repeats, R.id.count_decrease, R.id.count_increase))) {
            for ((id, change) in listOf(decrease to -1, increase to 1)) {
                findViewById<Button>(id).setOnClickListener {
                    val current = positiveWholeNumber(field.text.toString()) ?: 1
                    field.setText((current.toLong() + change).coerceIn(1, Int.MAX_VALUE.toLong()).toString())
                }
            }
        }
        limitMode.setOnCheckedChangeListener { _, _ ->
            if (!updatingFields) {
                restoreInvalidLimits()
                clearLimitFocus()
                saveSettings()
                render()
            }
        }
        for (selector in listOf(sound, boundarySound)) {
            selector.onSelection = { position ->
                if (!updatingFields && !session.active && !startPending) {
                    saveSettings()
                    preview.play(TickSound.entries[position])
                }
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
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && configuration.fontScale < 1.3f) {
            findViewById<View>(R.id.setup_subtitle).visibility = View.GONE
            findViewById<LinearLayout>(R.id.setup_header).orientation = LinearLayout.HORIZONTAL
            for (id in listOf(R.id.brand_header, R.id.limit_mode)) {
                findViewById<View>(id).layoutParams = (findViewById<View>(id).layoutParams as LinearLayout.LayoutParams).apply {
                    width = 0
                    weight = if (id == R.id.brand_header) 1f else 2f
                    topMargin = 0
                    marginStart = if (id == R.id.limit_mode) (16 * resources.displayMetrics.density).toInt() else 0
                }
            }
            findViewById<LinearLayout>(R.id.number_fields).orientation = LinearLayout.HORIZONTAL
            for (id in listOf(R.id.interval_fields, R.id.settings_details)) {
                findViewById<View>(id).layoutParams = (findViewById<View>(id).layoutParams as LinearLayout.LayoutParams).apply {
                    width = 0
                    weight = 1f
                    marginStart = if (id == R.id.settings_details) (24 * resources.displayMetrics.density).toInt() else 0
                }
            }
            for (id in listOf(R.id.sound_fields, R.id.boundary_sound_fields)) {
                val fields = (findViewById<LinearLayout>(id).getChildAt(1) as LinearLayout)
                fields.orientation = LinearLayout.HORIZONTAL
                fields.gravity = Gravity.CENTER_VERTICAL
                fields.getChildAt(0).layoutParams = LinearLayout.LayoutParams(
                    (92 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                fields.getChildAt(1).layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        findViewById<ScrollView>(R.id.configuration_screen).addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            // Mode changes keep the same dial size; only a resized viewport needs fitting.
            if (right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop) return@addOnLayoutChangeListener
            val screen = view as ScrollView
            val area = findViewById<View>(R.id.interval_fields)
            if (configuration.orientation == Configuration.ORIENTATION_PORTRAIT && configuration.fontScale < 1.3f) {
                val content = screen.getChildAt(0) as LinearLayout
                val last = (content.childCount - 1 downTo 0).map { content.getChildAt(it) }.first { it.visibility != View.GONE }
                val otherHeight = last.bottom + content.paddingBottom - area.height
                val height = (screen.height - otherHeight).coerceIn(
                    (144 * resources.displayMetrics.density).toInt(), resources.getDimensionPixelSize(R.dimen.dial_area_height))
                area.layoutParams = area.layoutParams.apply { this.height = height }
            }
            val defaultSize = resources.getDimension(R.dimen.dial_size)
            val dialSize = minOf(defaultSize, area.width - 112 * resources.displayMetrics.density,
                area.layoutParams.height - 16 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            findViewById<View>(R.id.interval_dial).layoutParams = (findViewById<View>(R.id.interval_dial).layoutParams as FrameLayout.LayoutParams).apply {
                width = dialSize
                height = dialSize
            }
            interval.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.dial_number_size) * dialSize / defaultSize)
        }
        var previousWidth = 0
        findViewById<View>(R.id.root).addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
            val availableWidth = root.width - root.paddingLeft - root.paddingRight
            if (availableWidth == previousWidth) return@addOnLayoutChangeListener
            previousWidth = availableWidth
            for (id in listOf(R.id.configuration_screen, R.id.playback_screen)) {
                val maxWidth = if (id == R.id.configuration_screen && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 840 else 600
                findViewById<View>(id).layoutParams = FrameLayout.LayoutParams(
                    minOf(availableWidth, (maxWidth * resources.displayMetrics.density).toInt()), FrameLayout.LayoutParams.MATCH_PARENT,
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
        if (boundarySound.selectedItemPosition != settings.boundarySound.ordinal) boundarySound.setSelection(settings.boundarySound.ordinal)
        updatingFields = false
    }

    private fun saveSettings() {
        store.saveValid(interval.text.toString(), repeats.text.toString(), unlimited.isChecked, selectedSound(),
            selectedMode(), minutes.text.toString(), seconds.text.toString(), selectedBoundarySound())
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
    private fun selectedBoundarySound() = TickSound.entries[boundarySound.selectedItemPosition]
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
        val selectedBoundarySound = selectedBoundarySound()
        session = SessionState(Phase.LOADING, settings = TickSettings(intervalSeconds, count, unlimited.isChecked,
            selectedSound, mode, limit, selectedBoundarySound))
        render()
        try {
            startForegroundService(Intent(this, TickService::class.java).setAction(TickService.ACTION_START)
                .putExtra(TickService.EXTRA_INTERVAL, intervalSeconds)
                .putExtra(TickService.EXTRA_REPEATS, count)
                .putExtra(TickService.EXTRA_UNLIMITED, unlimited.isChecked)
                .putExtra(TickService.EXTRA_SOUND, selectedSound.id)
                .putExtra(TickService.EXTRA_BOUNDARY_SOUND, selectedBoundarySound.id)
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
        findViewById<View>(R.id.limit_fields).visibility = if (unlimited.isChecked) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.count_limit_fields).visibility = if (timeMode) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.time_limit_fields).visibility = if (timeMode) View.VISIBLE else View.INVISIBLE
        repeats.isEnabled = canEdit && !unlimited.isChecked
        listOf(minutes, seconds).forEach {
            it.isEnabled = canEdit && !unlimited.isChecked
        }
        listOf(R.id.unlimited, R.id.count_mode, R.id.time_mode).forEach {
            findViewById<RadioButton>(it).isEnabled = canEdit
        }
        listOf(R.id.interval_decrease, R.id.interval_increase).forEach { findViewById<View>(it).isEnabled = canEdit }
        listOf(R.id.count_decrease, R.id.count_increase).forEach {
            findViewById<View>(it).isEnabled = canEdit && !unlimited.isChecked && !timeMode
        }
        sound.isEnabled = canEdit
        boundarySound.isEnabled = canEdit
        findViewById<View>(R.id.sound_fields).isEnabled = canEdit
        findViewById<View>(R.id.boundary_sound_fields).isEnabled = canEdit
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

    private fun updateTimer(elapsedMillis: Long = session.elapsedMillis(SystemClock.elapsedRealtime())) {
        val settings = session.settings ?: return
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
