package net.namekdev.moments

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TickAppTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var service: TickService
    private var bound = false
    private val connected = CountDownLatch(1)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TickService.LocalBinder).service
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    @Before fun setUp() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        context.getSharedPreferences("tick_settings", Context.MODE_PRIVATE).edit().clear().commit()
        SettingsStore(context).notificationRequested = true
        scenario = ActivityScenario.launch(MainActivity::class.java)
        bound = context.bindService(Intent(context, TickService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue(connected.await(5, TimeUnit.SECONDS))
        await { activityValue { it.findViewById<Button>(R.id.start_stop).isEnabled } }
    }

    @After fun tearDown() {
        if (::service.isInitialized) instrumentation.runOnMainSync {
            service.onStartCommand(Intent().setAction(TickService.ACTION_STOP), 0, 0)
        }
        context.stopService(Intent(context, TickService::class.java))
        if (::scenario.isInitialized) scenario.close()
        if (bound) context.unbindService(connection)
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @Test fun defaultsValidationAndAutomaticSettingsRestoration() {
        assertEquals(TickSettings(), SettingsStore(context).load())
        assertEquals(R.id.count_mode, activityValue { it.findViewById<RadioGroup>(R.id.limit_mode).checkedRadioButtonId })
        assertFalse(activityValue { it.findViewById<View>(R.id.playback_error).isShown })
        scenario.onActivity { activity ->
            val interval = activity.findViewById<EditText>(R.id.interval)
            val repeats = activity.findViewById<EditText>(R.id.repeats)
            for (invalid in listOf("", "0", "-1", "2147483648")) {
                interval.setText(invalid)
                activity.findViewById<Button>(R.id.start_stop).performClick()
                assertNotNull(interval.error)
                assertFalse(service.state.active)
            }
            interval.setText("7")
            repeats.setText("13")
            activity.findViewById<RadioButton>(R.id.unlimited).isChecked = true
            assertFalse(repeats.isEnabled)
            assertFalse(repeats.isShown)
        }
        assertEquals(TickSettings(7, 13, true), SettingsStore(context).load())
        scenario.recreate()
        assertEquals("7", activityValue { it.findViewById<EditText>(R.id.interval).text.toString() })
        assertEquals("13", activityValue { it.findViewById<EditText>(R.id.repeats).text.toString() })
        assertTrue(activityValue { it.findViewById<RadioButton>(R.id.unlimited).isChecked })
        assertFalse(activityValue { it.findViewById<EditText>(R.id.repeats).isEnabled })
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertEquals("13", activityValue { it.findViewById<EditText>(R.id.repeats).text.toString() })
        assertFalse(activityValue { it.findViewById<EditText>(R.id.repeats).isEnabled })
        scenario.onActivity { it.findViewById<RadioGroup>(R.id.limit_mode).check(R.id.count_mode) }
        assertTrue(activityValue { it.findViewById<EditText>(R.id.repeats).isEnabled })
        assertTrue(activityValue { it.findViewById<EditText>(R.id.repeats).isShown })
    }

    @Test fun settingsFitAndSoundRowsStayFixedAcrossModesInBothOrientations() {
        for ((requested, expected) in listOf(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE,
        )) {
            scenario.onActivity { it.requestedOrientation = requested }
            await { activityValue { it.resources.configuration.orientation == expected } }
            var soundPositions: List<Int>? = null
            for (mode in listOf(R.id.count_mode, R.id.time_mode, R.id.unlimited)) {
                scenario.onActivity { it.findViewById<RadioGroup>(R.id.limit_mode).check(mode) }
                instrumentation.waitForIdleSync()
                assertFalse("Settings require scrolling in orientation $expected, mode $mode", activityValue {
                    val screen = it.findViewById<ScrollView>(R.id.configuration_screen)
                    screen.canScrollVertically(1) || screen.canScrollVertically(-1)
                })
                val positions = activityValue { activity ->
                    listOf(R.id.boundary_sound, R.id.sound).map { id ->
                        IntArray(2).also { activity.findViewById<View>(id).getLocationOnScreen(it) }[1]
                    }
                }
                if (soundPositions == null) soundPositions = positions
                assertEquals("Sound rows moved in orientation $expected, mode $mode", soundPositions, positions)
            }
        }
    }

    @Test fun steppersRespectBoundsSaveAndLockDuringPlayback() {
        scenario.onActivity { activity ->
            val interval = activity.findViewById<EditText>(R.id.interval)
            val count = activity.findViewById<EditText>(R.id.repeats)
            activity.findViewById<Button>(R.id.interval_increase).performClick()
            activity.findViewById<Button>(R.id.count_decrease).performClick()
            assertEquals(TickSettings(11, 9), SettingsStore(context).load())
            assertEquals("01:39", activity.findViewById<TextView>(R.id.duration_preview).text.toString())
            for ((field, decrease, increase) in listOf(
                Triple(interval, R.id.interval_decrease, R.id.interval_increase),
                Triple(count, R.id.count_decrease, R.id.count_increase))) {
                field.setText("1")
                activity.findViewById<Button>(decrease).performClick()
                assertEquals("1", field.text.toString())
                field.setText(Int.MAX_VALUE.toString())
                activity.findViewById<Button>(increase).performClick()
                assertEquals(Int.MAX_VALUE.toString(), field.text.toString())
                field.setText("")
                activity.findViewById<Button>(decrease).performClick()
                assertEquals("1", field.text.toString())
                assertNull(field.error)
            }
        }
        configure(10, 9, true)
        selectSound(TickSound.NONE)
        click()
        await { currentState().phase == Phase.PLAYING }
        for (id in listOf(R.id.interval_decrease, R.id.interval_increase, R.id.count_decrease, R.id.count_increase)) {
            assertFalse(activityValue { it.findViewById<View>(id).isEnabled })
        }
        click()
        await { currentState().phase == Phase.STOPPED }
        assertTrue(activityValue { it.findViewById<View>(R.id.interval_increase).isEnabled })
        assertFalse(activityValue { it.findViewById<View>(R.id.count_increase).isEnabled })
    }

    @Test fun durationPreviewFollowsValidEditsModesAndRestoration() {
        fun preview() = activityValue { it.findViewById<TextView>(R.id.duration_preview).text.toString() }
        assertEquals("01:40", preview())
        configure(7, 13, false)
        assertEquals("01:31", preview())
        configureTime(2, 5)
        assertEquals("02:05", preview())
        scenario.onActivity { it.findViewById<EditText>(R.id.time_seconds).setText("60") }
        assertEquals(context.getString(R.string.duration_invalid), preview())
        scenario.onActivity { it.findViewById<RadioGroup>(R.id.limit_mode).check(R.id.unlimited) }
        assertEquals(context.getString(R.string.no_time_limit), preview())
        scenario.onActivity { it.findViewById<EditText>(R.id.interval).setText("") }
        assertEquals(context.getString(R.string.duration_invalid), preview())
        configure(Int.MAX_VALUE, Int.MAX_VALUE, false)
        assertEquals(formatTimer(Int.MAX_VALUE.toLong() * Int.MAX_VALUE), preview())
        scenario.recreate()
        assertEquals(formatTimer(Int.MAX_VALUE.toLong() * Int.MAX_VALUE), preview())
    }

    @Test fun invalidEditsNeverReplaceSavedValidValues() {
        configure(4, 17, false)
        scenario.onActivity {
            it.findViewById<EditText>(R.id.repeats).setText("0")
            it.findViewById<EditText>(R.id.interval).setText("")
        }
        assertEquals(TickSettings(4, 17, false), SettingsStore(context).load())
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertEquals("4", activityValue { it.findViewById<EditText>(R.id.interval).text.toString() })
        assertEquals("17", activityValue { it.findViewById<EditText>(R.id.repeats).text.toString() })
    }

    @Test fun finitePlaybackCountsExactlyAndReleasesResources() {
        configure(1, 3, false)
        click()
        await { currentState().phase == Phase.PLAYING }
        assertTrue(activityValue { it.findViewById<View>(R.id.playback_screen).isShown })
        assertFalse(activityValue { it.findViewById<View>(R.id.configuration_screen).isShown })
        assertFalse(activityValue { it.findViewById<EditText>(R.id.interval).isEnabled })
        assertFalse(activityValue { it.findViewById<RadioButton>(R.id.unlimited).isEnabled })
        await { currentState().phase == Phase.COMPLETE }
        assertEquals(3L, currentState().completed)
        assertFalse(activityValue { it.findViewById<View>(R.id.playback_error).isShown })
        assertTrue(activityValue { it.findViewById<EditText>(R.id.interval).isEnabled })
        assertTrue(activityValue { it.findViewById<View>(R.id.configuration_screen).isShown })
        assertReleased()
    }

    @Test fun rotationAndReopeningKeepActiveSession() {
        configure(1, 2, true)
        click()
        await { currentState().completed >= 1 }
        val before = currentState().completed
        val startedAt = currentState().startedAtMillis
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await { activityValue { it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE } }
        scenario.recreate()
        await { activityValue { it.findViewById<Button>(R.id.stop).isShown } }
        assertTimerMatchesSession()
        assertTrue(currentState().completed >= before)
        scenario.close()
        val closedCount = currentState().completed
        await { currentState().completed > closedCount }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await { activityValue { it.findViewById<Button>(R.id.stop).isShown } }
        assertTimerMatchesSession()
        assertEquals(startedAt, currentState().startedAtMillis)
        assertFalse(activityValue { it.findViewById<EditText>(R.id.interval).isEnabled })
        click()
        await { currentState().phase == Phase.STOPPED }
        assertReleased()
    }

    @Test fun rapidStartStopAndDuplicateStartsDoNotLeakOrRestart() {
        configure(1, 1, true)
        repeat(12) {
            click()
            click()
            await { currentState().phase == Phase.STOPPED }
            assertReleased()
        }
        click()
        await { currentState().completed >= 1 }
        val count = currentState().completed
        instrumentation.runOnMainSync {
            service.onStartCommand(startIntent(10, 10, false), 0, 0)
        }
        assertTrue(currentState().completed >= count)
        assertEquals(TickSettings(1, 1, true), currentState().settings)
        click()
        await { currentState().phase == Phase.STOPPED }
        configure(1, 1, false)
        click()
        await { currentState().phase == Phase.COMPLETE }
        assertEquals(1L, currentState().completed)
        assertReleased()
    }

    @Test fun stopWhileSoundLoadsCannotBeUndoneByLoadCallback() {
        // Same main-loop turn guarantees Stop precedes SoundPool's asynchronous load callback.
        scenario.onActivity { activity ->
            service.onStartCommand(startIntent(1, 10, true), 0, 0)
            assertEquals(Phase.LOADING, service.state.phase)
            assertFalse(activity.findViewById<Button>(R.id.pause_resume).isEnabled)
            service.onStartCommand(Intent().setAction(TickService.ACTION_PAUSE), 0, 0)
            service.onStartCommand(Intent().setAction(TickService.ACTION_RESUME), 0, 0)
            assertEquals(Phase.LOADING, service.state.phase)
            service.onStartCommand(Intent().setAction(TickService.ACTION_STOP), 0, 0)
        }
        SystemClock.sleep(500)
        assertEquals(Phase.STOPPED, currentState().phase)
        assertEquals(0L, currentState().completed)
        assertReleased()
        configure(1, 1, false)
        click()
        await { currentState().phase == Phase.COMPLETE }
        assertEquals(1L, currentState().completed)
    }

    @Test fun queuedRestartSurvivesRemovingEveryActivityAndTestBinding() {
        scenario.onActivity {
            context.startForegroundService(startIntent(1, 1, true))
            context.startService(Intent(context, TickService::class.java).setAction(TickService.ACTION_STOP))
            context.startForegroundService(startIntent(1, 7, true))
        }
        await { currentState().settings == TickSettings(1, 7, true) && currentState().completed >= 1 }
        scenario.close()
        context.unbindService(connection)
        bound = false
        SystemClock.sleep(1_500)
        val dump = shell("dumpsys activity service net.namekdev.moments/.TickService")
        assertTrue(dump, dump.contains("engineRunning=true foreground=true"))
    }

    @Test fun screenOffPlaybackHoldsWakeLockAndStopsCleanly() {
        configure(1, 2, true)
        click()
        await { currentState().completed >= 1 }
        val before = currentState().completed
        shell("input keyevent KEYCODE_SLEEP")
        await { !context.getSystemService(PowerManager::class.java).isInteractive }
        await { currentState().completed >= before + 3 }
        val dump = shell("dumpsys activity service net.namekdev.moments/.TickService")
        assertTrue(dump, dump.contains("wakeLockHeld=true"))
        assertTrue(dump, dump.contains("foreground=true"))
        // Exercise the notification's actual PendingIntent while the screen is off.
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single()
        assertTrue(notification.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        notification.notification.actions.single { it.title == "Stop" }.actionIntent.send()
        await { currentState().phase == Phase.STOPPED }
        assertReleased()
    }

    @Test fun notificationIsSilentAndStopActionCancelsPlayback() {
        configure(1, 2, true)
        click()
        await { currentState().completed >= 1 }
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications.single().notification
        val channel = manager.getNotificationChannel(notification.channelId)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(listOf("Pause", "Stop"), notification.actions.map { it.title.toString() })
        notification.actions.single { it.title == "Stop" }.actionIntent.send()
        await { currentState().phase == Phase.STOPPED }
        assertReleased()
    }

    @Test fun selectingUnlimitedPreservesTheLastValidFiniteCount() {
        configure(1, 17, false)
        scenario.onActivity {
            it.findViewById<EditText>(R.id.repeats).setText("")
            it.findViewById<RadioButton>(R.id.unlimited).isChecked = true
        }
        assertEquals(TickSettings(1, 17, true), SettingsStore(context).load())
        scenario.onActivity { it.findViewById<RadioGroup>(R.id.limit_mode).check(R.id.count_mode) }
        assertEquals("17", activityValue { it.findViewById<EditText>(R.id.repeats).text.toString() })
        assertTrue(activityValue { it.findViewById<EditText>(R.id.repeats).isEnabled })
    }

    @Test fun slowTimerRenderingCatchesUpWithoutWaitingAnotherSecond() {
        selectSound(TickSound.NONE)
        for (unlimited in listOf(false, true)) {
            configure(10, 2, unlimited)
            click()
            await { currentState().phase == Phase.PLAYING }
            val checked = CountDownLatch(1)
            var delayed = false
            var expected = ""
            var actual = ""
            lateinit var timer: TextView
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (delayed) return
                    delayed = true
                    // Simulate rendering work that crosses the next second boundary.
                    SystemClock.sleep(1_150)
                    timer.post {
                        val state = service.state
                        val elapsedMillis = state.elapsedMillis(SystemClock.elapsedRealtime())
                        expected = formatTimer(state.settings!!.remainingSeconds(elapsedMillis) ?: elapsedMillis / 1_000)
                        actual = timer.text.toString()
                        checked.countDown()
                    }
                }
            }
            scenario.onActivity {
                timer = it.findViewById(R.id.elapsed)
                timer.addTextChangedListener(watcher)
            }
            try {
                assertTrue("Delayed timer update did not complete", checked.await(8, TimeUnit.SECONDS))
                assertEquals("Timer must catch up immediately after rendering, unlimited=$unlimited", expected, actual)
            } finally {
                scenario.onActivity { timer.removeTextChangedListener(watcher) }
            }
            click()
            await { currentState().phase == Phase.STOPPED }
        }
    }

    @Test fun elapsedTimeAdvancesBetweenTicksAndStopRequiresConfirmation() {
        configure(10, 7, true)
        click()
        await { currentState().phase == Phase.PLAYING }
        val startedAt = currentState().startedAtMillis!!
        val initialTime = displayedSeconds()
        await { displayedSeconds() > initialTime }
        assertEquals(0L, currentState().completed)
        assertEquals("Elapsed", activityValue { it.findViewById<TextView>(R.id.timer_label).text.toString() })
        assertFalse(activityValue { it.findViewById<View>(R.id.playback_counter).isShown })
        assertTimerMatchesSession()

        requestStop()
        await { dialogButton("Keep ticking") != null }
        assertTrue(currentState().active)
        chooseDialog("Keep ticking")
        assertTrue(currentState().active)
        assertFalse(activityValue { it.findViewById<View>(R.id.configuration_screen).isShown })
        assertEquals(startedAt, currentState().startedAtMillis)

        requestStop()
        chooseDialog("Stop")
        await { currentState().phase == Phase.STOPPED }
        assertTrue(activityValue { it.findViewById<View>(R.id.configuration_screen).isShown })
        assertFalse(activityValue { it.findViewById<View>(R.id.playback_screen).isShown })
        assertEquals(TickSettings(10, 7, true), SettingsStore(context).load())
        assertReleased()

        click()
        await { currentState().phase == Phase.PLAYING }
        assertTrue(currentState().startedAtMillis!! > startedAt)
        assertTimerMatchesSession()
    }

    @Test fun bothLimitsAndSelectedModeSurviveRotationReopeningAndUnlimited() {
        configure(7, 13, false)
        configureTime(1, 25)
        selectSound(TickSound.NONE)
        val saved = TickSettings(7, 13, false, TickSound.NONE, LimitMode.TIME, 85)
        assertEquals(saved, SettingsStore(context).load())
        scenario.recreate()
        assertEquals(R.id.time_mode, activityValue { it.findViewById<RadioGroup>(R.id.limit_mode).checkedRadioButtonId })
        assertEquals("1", activityValue { it.findViewById<EditText>(R.id.time_minutes).text.toString() })
        assertEquals("25", activityValue { it.findViewById<EditText>(R.id.time_seconds).text.toString() })
        assertFalse(activityValue { it.findViewById<View>(R.id.repeats).isShown })
        scenario.onActivity { it.findViewById<RadioButton>(R.id.unlimited).isChecked = true }
        for (id in listOf(R.id.time_minutes, R.id.time_seconds)) {
            assertFalse(activityValue { it.findViewById<View>(id).isEnabled })
        }
        for (id in listOf(R.id.repeats, R.id.time_limit_fields, R.id.limit_label)) {
            assertFalse(activityValue { it.findViewById<View>(id).isShown })
        }
        for (id in listOf(R.id.unlimited, R.id.count_mode, R.id.time_mode)) {
            assertTrue(activityValue { it.findViewById<View>(id).isEnabled })
            assertEquals(id == R.id.unlimited, activityValue { it.findViewById<RadioButton>(id).isChecked })
        }
        scenario.recreate()
        assertEquals(R.id.unlimited, activityValue { it.findViewById<RadioGroup>(R.id.limit_mode).checkedRadioButtonId })
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertEquals(saved.copy(unlimited = true), SettingsStore(context).load())
        assertFalse(activityValue { it.findViewById<View>(R.id.time_seconds).isEnabled })
        scenario.onActivity { it.findViewById<RadioGroup>(R.id.limit_mode).check(R.id.count_mode) }
        assertFalse(activityValue { it.findViewById<RadioButton>(R.id.unlimited).isChecked })
        assertEquals("13", activityValue { it.findViewById<EditText>(R.id.repeats).text.toString() })
        assertTrue(activityValue { it.findViewById<View>(R.id.repeats).isEnabled })
        assertTrue(activityValue { it.findViewById<View>(R.id.repeats).isShown })
        scenario.onActivity { it.findViewById<RadioGroup>(R.id.limit_mode).check(R.id.time_mode) }
        assertEquals(saved, SettingsStore(context).load())
        assertEquals("25", activityValue { it.findViewById<EditText>(R.id.time_seconds).text.toString() })
        assertTrue(activityValue { it.findViewById<View>(R.id.time_seconds).isEnabled })
    }

    @Test fun invalidTimeLimitsCannotStartOrReplaceTheLastValidLimit() {
        configure(2, 7, false)
        configureTime(0, 9)
        scenario.onActivity { activity ->
            val minutes = activity.findViewById<EditText>(R.id.time_minutes)
            val seconds = activity.findViewById<EditText>(R.id.time_seconds)
            for ((m, s) in listOf("0" to "", "0" to "0", "0" to "-1", "0" to "60",
                "0" to "2147483648", "" to "9", "-1" to "9", "35791394" to "9")) {
                seconds.setText("")
                minutes.setText(m)
                seconds.setText(s)
                activity.findViewById<Button>(R.id.start_stop).performClick()
                assertNotNull(minutes.error)
                assertNotNull(seconds.error)
                assertFalse(service.state.active)
            }
        }
        assertEquals(9, SettingsStore(context).load().timeLimitSeconds)
        scenario.onActivity { it.findViewById<RadioButton>(R.id.unlimited).isChecked = true }
        assertEquals("0", activityValue { it.findViewById<EditText>(R.id.time_minutes).text.toString() })
        assertEquals("9", activityValue { it.findViewById<EditText>(R.id.time_seconds).text.toString() })
        click()
        await { currentState().phase == Phase.PLAYING }
        assertTrue(currentState().settings!!.unlimited)
        assertEquals("Elapsed", activityValue { it.findViewById<TextView>(R.id.timer_label).text.toString() })
        assertFalse(activityValue { it.findViewById<View>(R.id.playback_counter).isShown })
        click()
        assertReleased()
    }

    @Test fun countModeCountsDownTimeAndIntervalsThroughTheFinalSound() {
        configure(2, 2, false)
        selectSound(TickSound.GONG)
        click()
        await { currentState().phase == Phase.PLAYING }
        assertEquals(4L, displayedSeconds())
        assertEquals("Time left", activityValue { it.findViewById<TextView>(R.id.timer_label).text.toString() })
        assertEquals("2 intervals left", activityValue { it.findViewById<TextView>(R.id.playback_counter).text.toString() })
        await { displayedSeconds() == 3L }
        assertEquals(0L, currentState().completed)
        await { currentState().completed == 1L }
        assertEquals("1 interval left", activityValue { it.findViewById<TextView>(R.id.playback_counter).text.toString() })
        await { displayedSeconds() == 0L }
        assertEquals("0 intervals left", activityValue { it.findViewById<TextView>(R.id.playback_counter).text.toString() })
        assertEquals(Phase.PLAYING, currentState().phase)
        SystemClock.sleep(1_100)
        assertEquals(0L, displayedSeconds())
        assertEquals(Phase.PLAYING, currentState().phase)
        await { currentState().phase == Phase.COMPLETE }
        assertTrue(activityValue { it.findViewById<View>(R.id.configuration_screen).isShown })
        assertReleased()
    }

    @Test fun timeLimitCountsDownWithoutIntervalsAndEndsBetweenTicks() {
        configure(2, 99, false)
        configureTime(0, 5)
        selectSound(TickSound.GONG)
        click()
        await { currentState().phase == Phase.PLAYING }
        val started = currentState().startedAtMillis!!
        assertEquals(5L, displayedSeconds())
        assertEquals(LimitMode.TIME, currentState().settings!!.limitMode)
        assertEquals("Time left", activityValue { it.findViewById<TextView>(R.id.timer_label).text.toString() })
        assertFalse(activityValue { it.findViewById<View>(R.id.playback_counter).isShown })
        for (id in listOf(R.id.time_minutes, R.id.time_seconds, R.id.count_mode, R.id.time_mode)) {
            assertFalse(activityValue { it.findViewById<View>(id).isEnabled })
        }
        await { displayedSeconds() == 4L }
        await { displayedSeconds() == 0L }
        assertEquals(2L, currentState().completed)
        assertEquals(Phase.PLAYING, currentState().phase)
        SystemClock.sleep(1_100)
        assertEquals(0L, displayedSeconds())
        assertEquals(Phase.PLAYING, currentState().phase)
        await { currentState().phase == Phase.COMPLETE }
        val duration = SystemClock.elapsedRealtime() - started
        assertTrue("Unexpected duration: $duration", duration in 8_200..9_500)
        assertTrue(activityValue { it.findViewById<View>(R.id.configuration_screen).isShown })
        assertReleased()
    }

    @Test fun finiteCountdownSurvivesRotationAndReopeningWithoutResetting() {
        configure(2, 30, false)
        configureTime(0, 30)
        click()
        await { currentState().phase == Phase.PLAYING }
        val started = currentState().startedAtMillis
        await { displayedSeconds() < 30 }
        val before = displayedSeconds()
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await { activityValue { it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE } }
        scenario.recreate()
        await { activityValue { it.findViewById<Button>(R.id.stop).isShown } }
        assertEquals(started, currentState().startedAtMillis)
        assertTrue(displayedSeconds() <= before)
        assertTimerMatchesSession()
        scenario.close()
        SystemClock.sleep(1_200)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await { activityValue { it.findViewById<Button>(R.id.stop).isShown } }
        assertEquals(started, currentState().startedAtMillis)
        assertTrue(displayedSeconds() < before)
        assertTimerMatchesSession()
        click()
        await { currentState().phase == Phase.STOPPED }
        assertEquals(LimitMode.TIME, SettingsStore(context).load().limitMode)
        assertReleased()
    }

    @Test fun stopConfirmationSurvivesRotationWithoutStoppingTheSession() {
        configure(1, 1, true)
        click()
        await { currentState().completed >= 1 }
        val startedAt = currentState().startedAtMillis
        requestStop()
        await { dialogButton("Keep ticking") != null }
        scenario.recreate()
        await { dialogButton("Keep ticking") != null }
        assertTrue(currentState().active)
        assertEquals(startedAt, currentState().startedAtMillis)
        chooseDialog("Stop")
        await { currentState().phase == Phase.STOPPED }
        assertTrue(activityValue { it.findViewById<View>(R.id.configuration_screen).isShown })
    }

    @Test fun finiteCompletionDismissesAPendingStopConfirmation() {
        configure(1, 3, false)
        click()
        await { currentState().completed >= 1 }
        requestStop()
        await { dialogButton("Keep ticking") != null }
        await { currentState().phase == Phase.COMPLETE }
        assertEquals(3L, currentState().completed)
        assertTrue(activityValue { it.findViewById<View>(R.id.configuration_screen).isShown })
        await { dialogButton("Keep ticking") == null }
        assertReleased()
    }

    @Test fun invalidServiceCommandFailsWithoutAcquiringResources() {
        instrumentation.runOnMainSync { service.onStartCommand(startIntent(0, -1, false), 0, 0) }
        assertEquals(Phase.FAILED, currentState().phase)
        assertTrue(activityValue { it.findViewById<View>(R.id.playback_error).isShown })
        assertEquals(context.getString(R.string.playback_error),
            activityValue { it.findViewById<TextView>(R.id.playback_error).text.toString() })
        assertReleased()
        instrumentation.runOnMainSync {
            service.onStartCommand(startIntent(1, 1, false).putExtra(TickService.EXTRA_TIME_MODE, true)
                .putExtra(TickService.EXTRA_TIME_LIMIT, 0), 0, 0)
        }
        assertEquals(Phase.FAILED, currentState().phase)
        assertReleased()
        configure(1, 1, false)
        click()
        await { currentState().phase == Phase.COMPLETE }
        assertFalse(activityValue { it.findViewById<View>(R.id.playback_error).isShown })
        assertReleased()
    }

    @Test fun legacySoundRestoresBothChoicesWithoutPreviewing() {
        val preferences = context.getSharedPreferences("tick_settings", Context.MODE_PRIVATE)
        preferences.edit().putString("sound", TickSound.GONG.id).remove("boundary_sound").commit()
        scenario.recreate()
        assertEquals(TickSound.GONG, SettingsStore(context).load().sound)
        assertEquals(TickSound.GONG, SettingsStore(context).load().boundarySound)
        for (id in listOf(R.id.sound, R.id.boundary_sound)) {
            assertEquals(TickSound.GONG.ordinal, activityValue { it.findViewById<Spinner>(id).selectedItemPosition })
        }
        assertFalse(activityValue { it.preview.active })
        scenario.onActivity { it.findViewById<Spinner>(R.id.boundary_sound).setSelection(TickSound.NONE.ordinal) }
        assertEquals(TickSound.GONG, SettingsStore(context).load().sound)
        assertEquals(TickSound.NONE, SettingsStore(context).load().boundarySound)
        scenario.recreate()
        assertEquals(TickSound.NONE.ordinal, activityValue { it.findViewById<Spinner>(R.id.boundary_sound).selectedItemPosition })
        assertFalse(activityValue { it.preview.active })
        preferences.edit().putString("boundary_sound", "unknown-sound").commit()
        assertEquals(TickSound.CLOCK, SettingsStore(context).load().boundarySound)
    }

    @Test fun separateSoundsPreviewPersistAndLoadTogetherAcrossPauseAndReopening() {
        configure(2, 2, false)
        scenario.onActivity { it.findViewById<Spinner>(R.id.boundary_sound).setSelection(TickSound.GONG.ordinal) }
        await { activityValue { it.preview.playing && it.preview.sound == TickSound.GONG } }
        scenario.onActivity { it.findViewById<Spinner>(R.id.sound).setSelection(TickSound.TEMPLE_BLOCK.ordinal) }
        await { activityValue { it.preview.playing && it.preview.sound == TickSound.TEMPLE_BLOCK } }
        val saved = TickSettings(2, 2, sound = TickSound.TEMPLE_BLOCK, boundarySound = TickSound.GONG)
        assertEquals(saved, SettingsStore(context).load())
        scenario.recreate()
        assertFalse(activityValue { it.preview.active })
        assertEquals(TickSound.GONG.ordinal, activityValue { it.findViewById<Spinner>(R.id.boundary_sound).selectedItemPosition })
        assertEquals(TickSound.TEMPLE_BLOCK.ordinal, activityValue { it.findViewById<Spinner>(R.id.sound).selectedItemPosition })
        click()
        await { currentState().phase == Phase.PLAYING }
        assertEquals(saved, currentState().settings)
        val loaded = shell("dumpsys activity service net.namekdev.moments/.TickService")
        assertTrue(loaded, loaded.contains("loadedSounds=temple_block, gong"))
        for (id in listOf(R.id.sound, R.id.boundary_sound)) {
            assertFalse(activityValue { it.findViewById<Spinner>(id).isEnabled })
        }
        pauseOrResume()
        await { currentState().phase == Phase.PAUSED }
        scenario.recreate()
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await { activityValue { it.findViewById<Button>(R.id.pause_resume).isEnabled } }
        assertEquals(saved, currentState().settings)
        pauseOrResume()
        await { currentState().phase == Phase.COMPLETE }
        assertReleased()
        assertEquals(saved, SettingsStore(context).load())
    }

    @Test fun eitherSoundCanBeSilentWithoutDisablingTheOther() {
        for ((intervalSound, boundarySound) in listOf(TickSound.NONE to TickSound.GONG, TickSound.GONG to TickSound.NONE)) {
            configure(1, 2, false)
            scenario.onActivity {
                it.findViewById<Spinner>(R.id.sound).setSelection(intervalSound.ordinal)
                it.findViewById<Spinner>(R.id.boundary_sound).setSelection(boundarySound.ordinal)
            }
            click()
            await { currentState().phase == Phase.PLAYING }
            val started = currentState().startedAtMillis!!
            assertTrue(shell("dumpsys activity service net.namekdev.moments/.TickService").contains("loadedSounds=gong"))
            await { currentState().phase == Phase.COMPLETE }
            val expected = if (boundarySound == TickSound.NONE) 1_000 else 2_000
            assertTrue(SystemClock.elapsedRealtime() - started >= expected + TickSound.GONG.completionMillis)
            assertReleased()
        }
    }

    @Test fun everySoundLoadsPlaysAndFinishesWithoutLeaking() {
        configure(1, 1, false)
        for (sound in TickSound.entries.filter { it != TickSound.NONE }) {
            assertEquals(sound.id, context.resources.getResourceEntryName(sound.audioRes))
            selectSound(sound)
            await { SettingsStore(context).load().sound == sound }
            click()
            await { currentState().phase == Phase.PLAYING }
            assertEquals(sound, currentState().settings?.sound)
            assertFalse(activityValue { it.findViewById<Spinner>(R.id.sound).isEnabled })
            val started = currentState().startedAtMillis!!
            await { currentState().phase == Phase.COMPLETE }
            assertTrue(SystemClock.elapsedRealtime() - started >= 1_000 + sound.completionMillis)
            assertEquals(1L, currentState().completed)
            assertTrue(activityValue { it.findViewById<Spinner>(R.id.sound).isEnabled })
            assertReleased()
        }
    }

    @Test fun soundSelectionSurvivesRotationReopeningAndUnlimited() {
        for (sound in listOf(TickSound.ELECTRIC_CLOCK, TickSound.LOW_BELL, TickSound.TEMPLE_BLOCK, TickSound.TINGSHA)) {
            configure(10, 17, true)
            selectSound(sound)
            await { SettingsStore(context).load().sound == sound }
            scenario.recreate()
            assertEquals(sound.ordinal, activityValue { it.findViewById<Spinner>(R.id.sound).selectedItemPosition })
            click()
            await { currentState().phase == Phase.PLAYING }
            scenario.recreate()
            await { activityValue { it.findViewById<Button>(R.id.stop).isShown } }
            assertEquals(sound, currentState().settings?.sound)
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            await { activityValue { it.findViewById<Button>(R.id.stop).isShown } }
            click()
            await { currentState().phase == Phase.STOPPED }
            assertEquals(TickSettings(10, 17, true, sound), SettingsStore(context).load())
            assertEquals(sound.ordinal, activityValue { it.findViewById<Spinner>(R.id.sound).selectedItemPosition })
            assertFalse(activityValue { it.findViewById<EditText>(R.id.repeats).isEnabled })
            scenario.onActivity { it.findViewById<RadioGroup>(R.id.limit_mode).check(R.id.count_mode) }
            assertEquals("17", activityValue { it.findViewById<EditText>(R.id.repeats).text.toString() })
            assertTrue(activityValue { it.findViewById<EditText>(R.id.repeats).isEnabled })
        }
    }

    @Test fun noSoundCancelsLoadingAndPlayingPreviewsAndRestoresWithoutAudio() {
        scenario.onActivity {
            it.findViewById<Spinner>(R.id.sound).setSelection(TickSound.GONG.ordinal)
            assertTrue(it.preview.active)
            it.findViewById<Spinner>(R.id.sound).setSelection(TickSound.NONE.ordinal)
            assertFalse(it.preview.active)
        }
        selectSound(TickSound.BOWL)
        await { activityValue { it.preview.playing } }
        selectSound(TickSound.NONE)
        assertFalse(activityValue { it.preview.active })
        assertFalse(activityValue { it.preview.playing })
        scenario.recreate()
        assertEquals(TickSound.NONE, SettingsStore(context).load().sound)
        assertEquals(TickSound.NONE.ordinal, activityValue { it.findViewById<Spinner>(R.id.sound).selectedItemPosition })
        assertFalse(activityValue { it.preview.active })
        assertReleased()
    }

    @Test fun silentCountAndTimeSessionsFlashRedThenGreenWithoutLoadingAudio() {
        selectSound(TickSound.NONE)
        for (mode in LimitMode.entries) {
            configure(2, 2, false)
            if (mode == LimitMode.TIME) configureTime(0, 3)
            click()
            await { currentState().phase == Phase.PLAYING }
            assertEquals(Color.BLACK, playbackBackground())
            assertIntervalRemaining(2)
            assertTrue(shell("dumpsys activity service net.namekdev.moments/.TickService")
                .contains("wakeLockHeld=true soundPoolLoaded=false engineRunning=true foreground=true"))
            await { displayedSeconds() == if (mode == LimitMode.COUNT) 3L else 2L }
            assertIntervalRemaining(1)
            await { playbackBackground() == context.getColor(R.color.interval_flash) }
            assertEquals(1L, currentState().completed)
            assertIntervalRemaining(if (mode == LimitMode.COUNT) 2 else 1)
            await { playbackBackground() == Color.BLACK }
            await { playbackBackground() == context.getColor(R.color.completion_flash) }
            assertEquals(0L, displayedSeconds())
            assertIntervalRemaining(0)
            await { currentState().phase == Phase.COMPLETE }
            assertTrue(activityValue { it.findViewById<View>(R.id.configuration_screen).isShown })
            assertReleased()
        }
    }

    @Test fun audibleIntervalsUpdateTheirFlashInTheSameCueCallback() {
        for (sound in listOf(TickSound.CLOCK, TickSound.BOWL)) {
            for (mode in LimitMode.entries) {
                configure(2, 2, false)
                if (mode == LimitMode.TIME) configureTime(0, 3)
                selectSound(sound)
                val flashes = mutableListOf<Int>()
                lateinit var root: View
                scenario.onActivity { root = it.findViewById(R.id.root) }
                val observer: (SessionState) -> Unit = { state ->
                    if (state.phase == Phase.PLAYING && state.lastIntervalAtMillis != null) {
                        flashes.add((root.background as ColorDrawable).color)
                    }
                }
                instrumentation.runOnMainSync { service.observe(observer) }
                try {
                    click()
                    await { currentState().phase == Phase.PLAYING }
                    await { currentState().phase == Phase.COMPLETE }
                    instrumentation.runOnMainSync {
                        assertEquals("${sound.id}, $mode", listOf(context.getColor(R.color.interval_flash),
                            context.getColor(R.color.completion_flash)), flashes)
                    }
                    assertReleased()
                } finally {
                    instrumentation.runOnMainSync { service.removeObserver(observer) }
                }
            }
        }
    }

    @Test fun silentUnlimitedPausesBothTimersAndReopensWithoutReplayingAFlash() {
        configure(2, 2, true)
        selectSound(TickSound.NONE)
        click()
        await { playbackBackground() == context.getColor(R.color.interval_flash) }
        pauseOrResume()
        await { currentState().phase == Phase.PAUSED }
        assertEquals(Color.BLACK, playbackBackground())
        val displayed = displayedSeconds()
        val interval = activityValue { it.findViewById<TextView>(R.id.interval_remaining).text.toString() }
        SystemClock.sleep(1_100)
        assertEquals(displayed, displayedSeconds())
        assertEquals(interval, activityValue { it.findViewById<TextView>(R.id.interval_remaining).text.toString() })
        assertTrue(shell("dumpsys activity service net.namekdev.moments/.TickService")
            .contains("wakeLockHeld=false soundPoolLoaded=false engineRunning=true foreground=true"))
        scenario.recreate()
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await { activityValue { it.findViewById<Button>(R.id.pause_resume).isEnabled } }
        assertEquals(interval, activityValue { it.findViewById<TextView>(R.id.interval_remaining).text.toString() })
        assertEquals(Color.BLACK, playbackBackground())
        pauseOrResume()
        await { currentState().phase == Phase.PLAYING }
        assertEquals(Color.BLACK, playbackBackground())
        await { currentState().completed == 2L }
        assertEquals(Phase.PLAYING, currentState().phase)
        click()
        await { currentState().phase == Phase.STOPPED }
        assertReleased()
    }

    @Test fun dropdownPreviewsTheSoundAndCanReplayTheSameSelection() {
        assertFalse(activityValue { it.preview.active })
        for (id in listOf(R.id.sound, R.id.boundary_sound)) {
            for (sound in listOf(TickSound.TEMPLE_BLOCK, TickSound.TINGSHA)) {
                repeat(2) { attempt ->
                    scenario.onActivity {
                        val target = if (attempt == 0) {
                            if (id == R.id.sound) R.id.sound_fields else R.id.boundary_sound_fields
                        } else id
                        it.findViewById<View>(target).performClick()
                    }
                    val label = context.getString(sound.labelRes)
                    await {
                        instrumentation.uiAutomation.rootInActiveWindow
                            ?.findAccessibilityNodeInfosByText(label)?.any { it.text?.toString() == label } == true
                    }
                    val option = instrumentation.uiAutomation.rootInActiveWindow
                        .findAccessibilityNodeInfosByText(label).first { it.text?.toString() == label }
                    assertTrue(option.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await { activityValue { it.preview.playing && it.preview.sound == sound } }
                    val saved = SettingsStore(context).load()
                    assertEquals(sound, if (id == R.id.sound) saved.sound else saved.boundarySound)
                    assertFalse(currentState().active)
                    assertReleased()
                    await { activityValue { !it.preview.active } }
                }
            }
        }
    }

    @Test fun replacingALoadingPreviewAndStartingPlaybackReleaseTheOldPreview() {
        configure(1, 1, true)
        scenario.onActivity {
            it.findViewById<Spinner>(R.id.sound).setSelection(TickSound.GONG.ordinal)
            assertTrue(it.preview.active)
            assertFalse(it.preview.playing)
            it.findViewById<Spinner>(R.id.sound).setSelection(TickSound.BOWL.ordinal)
            assertEquals(TickSound.BOWL, it.preview.sound)
        }
        await { activityValue { it.preview.playing } }
        scenario.onActivity {
            it.findViewById<Spinner>(R.id.sound).setSelection(TickSound.BELL.ordinal)
            assertFalse(it.preview.playing)
            it.findViewById<Button>(R.id.start_stop).performClick()
            assertFalse(it.preview.active)
        }
        await { currentState().phase == Phase.PLAYING }
        assertEquals(TickSound.BELL, currentState().settings?.sound)
        assertFalse(activityValue { it.preview.active })
        click()
        await { currentState().phase == Phase.STOPPED }
        assertReleased()
    }

    @Test fun rotationAndReopeningReleasePreviewWithoutAutoplaying() {
        selectSound(TickSound.GONG)
        await { activityValue { it.preview.playing } }
        val oldPreview = activityValue { it.preview }
        scenario.recreate()
        assertFalse(oldPreview.active)
        assertFalse(activityValue { it.preview.active })
        assertEquals(TickSound.GONG.ordinal, activityValue { it.findViewById<Spinner>(R.id.sound).selectedItemPosition })
        selectSound(TickSound.BOWL)
        await { activityValue { it.preview.playing } }
        val closingPreview = activityValue { it.preview }
        scenario.close()
        assertFalse(closingPreview.active)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.waitForIdleSync()
        assertFalse(activityValue { it.preview.active })
        assertEquals(TickSound.BOWL.ordinal, activityValue { it.findViewById<Spinner>(R.id.sound).selectedItemPosition })
        assertFalse(currentState().active)
    }

    @Test fun finalGongRingsBeforeCompletionAndReleasesResourcesAfterwards() {
        configure(1, 2, false)
        selectSound(TickSound.GONG)
        click()
        await { currentState().phase == Phase.PLAYING }
        val started = currentState().startedAtMillis!!
        await { currentState().completed == 2L }
        assertEquals(Phase.PLAYING, currentState().phase)
        assertTrue(shell("dumpsys activity service net.namekdev.moments/.TickService").contains("wakeLockHeld=true"))
        await { currentState().phase == Phase.COMPLETE }
        assertTrue(SystemClock.elapsedRealtime() - started >= 2_000 + TickSound.GONG.completionMillis)
        assertReleased()
    }

    @Test fun pauseFreezesBothFiniteLimitsAndResumeCompletesTheRemainingTime() {
        for (mode in LimitMode.entries) {
            configure(2, 2, false)
            if (mode == LimitMode.TIME) configureTime(0, 3)
            click()
            await { currentState().phase == Phase.PLAYING }
            await { currentState().elapsedMillis(SystemClock.elapsedRealtime()) >= 500 }
            pauseOrResume()
            await { currentState().phase == Phase.PAUSED }
            val paused = currentState()
            val displayed = displayedSeconds()
            assertFalse(activityValue { it.findViewById<EditText>(R.id.interval).isEnabled })
            assertEquals("Resume", activityValue { it.findViewById<Button>(R.id.pause_resume).text.toString() })
            assertTrue(shell("dumpsys activity service net.namekdev.moments/.TickService")
                .contains("wakeLockHeld=false soundPoolLoaded=true engineRunning=true foreground=true"))
            SystemClock.sleep(1_200)
            assertEquals(paused, currentState())
            assertEquals(displayed, displayedSeconds())
            assertTimerMatchesSession()
            val resumedAt = SystemClock.elapsedRealtime()
            pauseOrResume()
            await { currentState().phase == Phase.PLAYING }
            assertTrue(shell("dumpsys activity service net.namekdev.moments/.TickService").contains("wakeLockHeld=true"))
            await { currentState().phase == Phase.COMPLETE }
            val remaining = paused.settings!!.durationSeconds!! * 1_000 - paused.elapsedMillis(resumedAt)
            assertTrue(SystemClock.elapsedRealtime() - resumedAt >= remaining + TickSound.CLOCK.completionMillis)
            assertEquals(if (mode == LimitMode.COUNT) 2L else 1L, currentState().completed)
            assertReleased()
        }
    }

    @Test fun pausedUnlimitedSessionSurvivesRotationReopeningAndScreenLock() {
        configure(1, 3, true)
        click()
        await { currentState().completed >= 1 }
        pauseOrResume()
        await { currentState().phase == Phase.PAUSED }
        val paused = currentState()
        val displayed = displayedSeconds()
        scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        await { activityValue { it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE } }
        scenario.recreate()
        await { activityValue { it.findViewById<Button>(R.id.pause_resume).isEnabled } }
        assertEquals("Resume", activityValue { it.findViewById<Button>(R.id.pause_resume).text.toString() })
        scenario.close()
        shell("input keyevent KEYCODE_SLEEP")
        SystemClock.sleep(1_500)
        assertEquals(paused, currentState())
        assertTrue(shell("dumpsys activity service net.namekdev.moments/.TickService").contains("wakeLockHeld=false"))
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await { activityValue { it.findViewById<Button>(R.id.pause_resume).isEnabled } }
        assertEquals(displayed, displayedSeconds())
        assertTimerMatchesSession()
        requestStop()
        chooseDialog("Keep paused")
        assertEquals(paused, currentState())
        pauseOrResume()
        await { currentState().completed > paused.completed }
        assertTimerMatchesSession()
        pauseOrResume()
        await { currentState().phase == Phase.PAUSED }
        requestStop()
        scenario.recreate()
        await { dialogButton("Keep paused") != null }
        chooseDialog("Stop")
        await { currentState().phase == Phase.STOPPED }
        assertTrue(activityValue { it.findViewById<View>(R.id.configuration_screen).isShown })
        assertReleased()
    }

    @Test fun notificationPauseResumeAreImmediateAndDuplicateCommandsDoNotRestart() {
        configure(10, 2, true)
        click()
        await { currentState().phase == Phase.PLAYING }
        val manager = context.getSystemService(NotificationManager::class.java)
        // Pause must appear as soon as the sample loads, before the first interval ends.
        await {
            manager.activeNotifications.singleOrNull()?.notification?.actions?.any { it.title == "Pause" } == true
        }
        val pause = manager.activeNotifications.single().notification.actions.single { it.title == "Pause" }.actionIntent
        pause.send()
        await { currentState().phase == Phase.PAUSED }
        val paused = currentState()
        pause.send()
        instrumentation.runOnMainSync { service.onStartCommand(startIntent(1, 99, true), 0, 0) }
        assertEquals(paused, currentState())
        await {
            manager.activeNotifications.singleOrNull()?.notification?.actions?.any { it.title == "Resume" } == true
        }
        val notification = manager.activeNotifications.single().notification
        assertEquals("Moments is paused", notification.extras.getString(android.app.Notification.EXTRA_TITLE))
        val resume = notification.actions.single { it.title == "Resume" }.actionIntent
        resume.send()
        await { currentState().phase == Phase.PLAYING }
        val resumedBase = currentState().startedAtMillis
        resume.send()
        assertEquals(resumedBase, currentState().startedAtMillis)
        assertEquals(paused.completed, currentState().completed)
        pause.send()
        await { currentState().phase == Phase.PAUSED }
        notification.actions.single { it.title == "Stop" }.actionIntent.send()
        await { currentState().phase == Phase.STOPPED }
        resume.send()
        pause.send()
        SystemClock.sleep(200)
        assertEquals(Phase.STOPPED, currentState().phase)
        assertReleased()
        click()
        await { currentState().phase == Phase.PLAYING }
        assertEquals(0L, currentState().completed)
        assertNull(currentState().pausedAtMillis)
    }

    @Test fun pausingTheFinalGongWaitsForResumeBeforeCompleting() {
        configure(1, 1, false)
        selectSound(TickSound.GONG)
        click()
        await { currentState().completed == 1L }
        pauseOrResume()
        await { currentState().phase == Phase.PAUSED }
        val pausedAt = currentState().pausedAtMillis!!
        assertEquals(0L, displayedSeconds())
        SystemClock.sleep(TickSound.GONG.completionMillis + 100)
        assertEquals(Phase.PAUSED, currentState().phase)
        assertEquals(0L, displayedSeconds())
        val resumedAt = SystemClock.elapsedRealtime()
        pauseOrResume()
        await { currentState().phase == Phase.PLAYING }
        await { currentState().phase == Phase.COMPLETE }
        assertTrue(SystemClock.elapsedRealtime() - currentState().startedAtMillis!! >= 1_000 + TickSound.GONG.completionMillis)
        assertTrue(resumedAt - pausedAt >= TickSound.GONG.completionMillis)
        assertEquals(1L, currentState().completed)
        assertReleased()
    }

    @Test fun rapidPauseResumeCommandsThenStopCannotLeavePendingPlayback() {
        configure(1, 20, true)
        click()
        await { currentState().phase == Phase.PLAYING }
        instrumentation.runOnMainSync {
            repeat(20) {
                service.onStartCommand(Intent().setAction(TickService.ACTION_PAUSE), 0, 0)
                service.onStartCommand(Intent().setAction(TickService.ACTION_RESUME), 0, 0)
            }
            service.onStartCommand(Intent().setAction(TickService.ACTION_PAUSE), 0, 0)
            service.onStartCommand(Intent().setAction(TickService.ACTION_STOP), 0, 0)
            service.onStartCommand(Intent().setAction(TickService.ACTION_RESUME), 0, 0)
        }
        val completed = currentState().completed
        SystemClock.sleep(1_100)
        assertEquals(Phase.STOPPED, currentState().phase)
        assertEquals(completed, currentState().completed)
        assertReleased()
    }

    private fun selectSound(sound: TickSound) = scenario.onActivity { activity ->
        for (id in listOf(R.id.sound, R.id.boundary_sound)) {
            activity.findViewById<Spinner>(id).setSelection(sound.ordinal)
        }
    }

    private fun configure(seconds: Int, count: Int, unlimited: Boolean) {
        scenario.onActivity {
            it.findViewById<EditText>(R.id.interval).setText(seconds.toString())
            it.findViewById<EditText>(R.id.repeats).setText(count.toString())
            it.findViewById<RadioGroup>(R.id.limit_mode).check(if (unlimited) R.id.unlimited else R.id.count_mode)
        }
    }

    private fun configureTime(minutes: Int, seconds: Int) = scenario.onActivity {
        it.findViewById<RadioGroup>(R.id.limit_mode).check(R.id.time_mode)
        it.findViewById<EditText>(R.id.time_minutes).setText(minutes.toString())
        it.findViewById<EditText>(R.id.time_seconds).setText(seconds.toString())
    }

    private fun displayedSeconds(): Long = activityValue {
        it.findViewById<TextView>(R.id.elapsed).text.split(':').fold(0L) { total, part -> total * 60 + part.toLong() }
    }

    private fun playbackBackground() = activityValue { (it.findViewById<View>(R.id.root).background as ColorDrawable).color }

    private fun assertIntervalRemaining(seconds: Long) {
        assertEquals(context.getString(R.string.interval_remaining, formatTimer(seconds)),
            activityValue { it.findViewById<TextView>(R.id.interval_remaining).text.toString() })
    }

    private fun assertTimerMatchesSession() {
        val state = currentState()
        val elapsedSeconds = state.elapsedMillis(SystemClock.elapsedRealtime()) / 1_000
        val duration = state.settings!!.durationSeconds
        val expected = duration?.let { (it - elapsedSeconds).coerceAtLeast(0) } ?: elapsedSeconds
        assertTrue("Timer does not match service start time", kotlin.math.abs(displayedSeconds() - expected) <= 1)
    }

    private fun startIntent(seconds: Int, count: Int, unlimited: Boolean) =
        Intent(context, TickService::class.java).setAction(TickService.ACTION_START)
            .putExtra(TickService.EXTRA_INTERVAL, seconds)
            .putExtra(TickService.EXTRA_REPEATS, count)
            .putExtra(TickService.EXTRA_UNLIMITED, unlimited)

    private fun click() {
        if (activityValue { it.findViewById<View>(R.id.playback_screen).isShown }) {
            requestStop()
            chooseDialog("Stop")
        } else {
            scenario.onActivity { it.findViewById<Button>(R.id.start_stop).performClick() }
        }
    }

    private fun requestStop() = scenario.onActivity { it.findViewById<Button>(R.id.stop).performClick() }

    private fun pauseOrResume() = scenario.onActivity { it.findViewById<Button>(R.id.pause_resume).performClick() }

    private fun dialogButton(text: String): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        if (root.findAccessibilityNodeInfosByText("Stop ticking?").none { it.text?.toString() == "Stop ticking?" }) return null
        return root.findAccessibilityNodeInfosByText(text)
            .firstOrNull { it.className == "android.widget.Button" && it.text?.toString().equals(text, ignoreCase = true) }
    }

    private fun chooseDialog(text: String) {
        await { dialogButton(text) != null }
        assertTrue(dialogButton(text)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun currentState(): SessionState {
        var state = SessionState()
        instrumentation.runOnMainSync { state = service.state }
        return state
    }

    private fun <T> activityValue(block: (MainActivity) -> T): T {
        var value: T? = null
        scenario.onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) fail("Timed out waiting for app state")
            SystemClock.sleep(30)
        }
    }

    private fun assertReleased() {
        val dump = shell("dumpsys activity service net.namekdev.moments/.TickService")
        assertTrue(dump, dump.contains("wakeLockHeld=false soundPoolLoaded=false engineRunning=false foreground=false"))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
}
