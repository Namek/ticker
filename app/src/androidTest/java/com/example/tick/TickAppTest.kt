package com.example.tick

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Chronometer
import android.widget.EditText
import android.widget.Switch
import android.widget.Spinner
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
            activity.findViewById<Switch>(R.id.unlimited).isChecked = true
            assertFalse(repeats.isEnabled)
            assertTrue(repeats.alpha < 1f)
        }
        assertEquals(TickSettings(7, 13, true), SettingsStore(context).load())
        scenario.recreate()
        assertEquals("7", activityValue { it.findViewById<EditText>(R.id.interval).text.toString() })
        assertEquals("13", activityValue { it.findViewById<EditText>(R.id.repeats).text.toString() })
        assertTrue(activityValue { it.findViewById<Switch>(R.id.unlimited).isChecked })
        assertFalse(activityValue { it.findViewById<EditText>(R.id.repeats).isEnabled })
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        assertEquals("13", activityValue { it.findViewById<EditText>(R.id.repeats).text.toString() })
        assertFalse(activityValue { it.findViewById<EditText>(R.id.repeats).isEnabled })
        scenario.onActivity { it.findViewById<Switch>(R.id.unlimited).isChecked = false }
        assertTrue(activityValue { it.findViewById<EditText>(R.id.repeats).isEnabled })
        assertEquals(1f, activityValue { it.findViewById<EditText>(R.id.repeats).alpha })
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
        assertFalse(activityValue { it.findViewById<Switch>(R.id.unlimited).isEnabled })
        await { currentState().phase == Phase.COMPLETE }
        assertEquals(3L, currentState().completed)
        assertEquals("3", activityValue { it.findViewById<TextView>(R.id.counter).text.toString() })
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
        assertEquals(startedAt, activityValue { it.findViewById<Chronometer>(R.id.elapsed).base })
        assertTrue(currentState().completed >= before)
        scenario.close()
        val closedCount = currentState().completed
        await { currentState().completed > closedCount }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await { activityValue { it.findViewById<Button>(R.id.stop).isShown } }
        assertEquals(startedAt, activityValue { it.findViewById<Chronometer>(R.id.elapsed).base })
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
        instrumentation.runOnMainSync {
            service.onStartCommand(startIntent(1, 10, true), 0, 0)
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
        val dump = shell("dumpsys activity service com.example.tick/.TickService")
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
        val dump = shell("dumpsys activity service com.example.tick/.TickService")
        assertTrue(dump, dump.contains("wakeLockHeld=true"))
        assertTrue(dump, dump.contains("foreground=true"))
        // Exercise the notification's actual PendingIntent while the screen is off.
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single()
        assertTrue(notification.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        notification.notification.actions.single().actionIntent.send()
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
        assertEquals("Stop", notification.actions.single().title.toString())
        notification.actions.single().actionIntent.send()
        await { currentState().phase == Phase.STOPPED }
        assertReleased()
    }

    @Test fun selectingUnlimitedPreservesTheLastValidFiniteCount() {
        configure(1, 17, false)
        scenario.onActivity {
            it.findViewById<EditText>(R.id.repeats).setText("")
            it.findViewById<Switch>(R.id.unlimited).isChecked = true
        }
        assertEquals(TickSettings(1, 17, true), SettingsStore(context).load())
        scenario.onActivity { it.findViewById<Switch>(R.id.unlimited).isChecked = false }
        assertEquals("17", activityValue { it.findViewById<EditText>(R.id.repeats).text.toString() })
        assertTrue(activityValue { it.findViewById<EditText>(R.id.repeats).isEnabled })
    }

    @Test fun elapsedTimeAdvancesBetweenTicksAndStopRequiresConfirmation() {
        configure(10, 7, true)
        click()
        await { currentState().completed == 1L }
        val startedAt = currentState().startedAtMillis!!
        val initialTime = activityValue { it.findViewById<Chronometer>(R.id.elapsed).text.toString() }
        await { activityValue { it.findViewById<Chronometer>(R.id.elapsed).text.toString() != initialTime } }
        assertEquals(1L, currentState().completed)
        assertEquals(startedAt, activityValue { it.findViewById<Chronometer>(R.id.elapsed).base })

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
        assertEquals(currentState().startedAtMillis, activityValue { it.findViewById<Chronometer>(R.id.elapsed).base })
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
        assertReleased()
    }

    @Test fun everySoundLoadsPlaysAndFinishesWithoutLeaking() {
        configure(1, 1, false)
        for (sound in TickSound.entries) {
            assertEquals(sound.id, context.resources.getResourceEntryName(sound.audioRes))
            scenario.onActivity { it.findViewById<Spinner>(R.id.sound).setSelection(sound.ordinal) }
            await { SettingsStore(context).load().sound == sound }
            click()
            await { currentState().phase == Phase.PLAYING }
            assertEquals(sound, currentState().settings?.sound)
            assertFalse(activityValue { it.findViewById<Spinner>(R.id.sound).isEnabled })
            val started = currentState().startedAtMillis!!
            await { currentState().phase == Phase.COMPLETE }
            assertTrue(SystemClock.elapsedRealtime() - started >= sound.durationMillis)
            assertEquals(1L, currentState().completed)
            assertTrue(activityValue { it.findViewById<Spinner>(R.id.sound).isEnabled })
            assertReleased()
        }
    }

    @Test fun soundSelectionSurvivesRotationReopeningAndUnlimited() {
        configure(10, 17, true)
        scenario.onActivity { it.findViewById<Spinner>(R.id.sound).setSelection(TickSound.GONG.ordinal) }
        await { SettingsStore(context).load().sound == TickSound.GONG }
        scenario.recreate()
        assertEquals(TickSound.GONG.ordinal, activityValue { it.findViewById<Spinner>(R.id.sound).selectedItemPosition })
        click()
        await { currentState().phase == Phase.PLAYING }
        scenario.recreate()
        await { activityValue { it.findViewById<Button>(R.id.stop).isShown } }
        assertEquals(TickSound.GONG, currentState().settings?.sound)
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await { activityValue { it.findViewById<Button>(R.id.stop).isShown } }
        click()
        await { currentState().phase == Phase.STOPPED }
        assertEquals(TickSettings(10, 17, true, TickSound.GONG), SettingsStore(context).load())
        assertEquals(TickSound.GONG.ordinal, activityValue { it.findViewById<Spinner>(R.id.sound).selectedItemPosition })
        assertFalse(activityValue { it.findViewById<EditText>(R.id.repeats).isEnabled })
        scenario.onActivity { it.findViewById<Switch>(R.id.unlimited).isChecked = false }
        assertEquals("17", activityValue { it.findViewById<EditText>(R.id.repeats).text.toString() })
        assertTrue(activityValue { it.findViewById<EditText>(R.id.repeats).isEnabled })
    }

    private fun configure(seconds: Int, count: Int, unlimited: Boolean) {
        scenario.onActivity {
            it.findViewById<EditText>(R.id.interval).setText(seconds.toString())
            it.findViewById<EditText>(R.id.repeats).setText(count.toString())
            it.findViewById<Switch>(R.id.unlimited).isChecked = unlimited
        }
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
        val dump = shell("dumpsys activity service com.example.tick/.TickService")
        assertTrue(dump, dump.contains("wakeLockHeld=false soundPoolLoaded=false engineRunning=false foreground=false"))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
}
