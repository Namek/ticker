# Tick

An offline Android tick timer. Kotlin, native Android views, one app module, and no network permission. Android 8 or newer. Application ID `net.namekdev.ticker`.

## Build

Use JDK 21 and an Android SDK with platform 36 and build tools 35.0.0 installed. The project pins Gradle 8.13, Android Gradle Plugin 8.13.2, and Kotlin 2.3.21. Compile and target SDK are 36; minimum SDK is 26. Java and Kotlin bytecode target 17.

On macOS:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Alternatively, put `sdk.dir=/path/to/android-sdk` in a local, untracked `local.properties`. Android Studio can create it. The first build downloads dependencies; the app itself is entirely offline. Use `--offline` for subsequent builds if all dependencies are cached.

The installable debug APK is `app/build/outputs/apk/debug/app-debug.apk`. It is signed with the local Android debug key. Keep that key if you want future `adb install -r` updates to retain settings.

Changing the application ID makes Android treat this as a separate app. Settings from builds with the previous ID do not transfer automatically.

## Use

The defaults are a 10-second interval, Interval count mode with 10 intervals, Unlimited off, and Clock tick. Choose **Interval count** or **Time limit**. Count means full intervals: an interval of 10 seconds and a count of 3 lasts 30 seconds. Time limit uses minutes and seconds, initially 10 minutes, and can end between intervals. For example, a 25-second limit with a 10-second interval plays at 0, 10, 20, and 25 seconds.

The compact settings screen places the interval and selected limit side by side. Interval and count accept whole numbers from 1 to 2147483647. Time limit accepts whole minutes and 0–59 seconds, with a total from 1 to 2147483647 seconds. Invalid edits show an error and never overwrite saved valid values. Settings save automatically, including both finite limits and the selected mode. Unlimited disables the mode selector and limit inputs while retaining their values.

The Sound dropdown offers real recordings of Clock tick, Electric wall clock, Gong, Singing bowl, and Soft bell. Electric wall clock is a short tick from a quartz wall-clock recording. Selecting an item previews it, including selecting the same sound again. The selection saves automatically and is locked during playback. Changing sounds, starting a session, rotating, or leaving the screen cancels the preview. Restoring settings never starts a preview. The recordings are bundled offline and normalized for stronger playback; [sources, creators, and public-domain licenses](audio-sources/CREDITS.md) are included. Media volume controls playback volume; the app does not change the device volume.

Start opens a playback screen with a large centered timer, Pause, and Stop. Its background is pure black, including behind the system bars, in both light and dark themes and while paused. The timer grows up to 96 sp and scales down to fit longer durations or narrow screens. Settings follow the system theme. Finite sessions count down to zero; Interval count mode also shows a small intervals-left label. Unlimited shows elapsed time counting up and continues until stopped. The first sound plays as soon as the bundled sample loads, and the timer starts with it. Finite sessions play an ending sound at zero, hold the display at 00:00 while it rings out, then return to settings. The settings screen shows a playback error only if something goes wrong.

Pause freezes the timer, interval progress, and any sound still ringing. Resume continues the same session from that point, preserving the remaining fraction of the interval without playing an extra starting sound. Pausing the final sound also pauses completion until resumed. Pause becomes available once the sound has loaded. Paused sessions survive rotation, reopening, and screen lock; settings stay locked until the session ends.

Stop inside the app asks for confirmation. Choose Keep ticking, or Keep paused during a pause, to keep the session. Choose Stop to end it and return to configuration. Another Start resets both the timer and counter. Configuration is hidden during playback. The timer survives rotation, reopening, and screen lock because its monotonic start time belongs to the service.

Playback uses media volume and never requests audio focus, so it can mix with music or podcasts. It continues when you rotate, leave the app, dismiss its recent task, or lock the screen. Reopen Tick to see the active session. Process termination, force-stop, or reboot ends playback. Settings survive those events.

Android 13 and newer ask for notification permission on the first valid Start. Playback starts regardless of the answer. The silent ongoing notification has Pause/Resume and Stop actions. Stop in the notification ends the session immediately. If notifications are denied, these controls remain available inside the app.

## Timing and lifecycle

`TickService` owns the SoundPool, foreground notification, partial wake lock, and session state. The activity binds to it for state updates. The service is non-exported, declares `mediaPlayback`, and returns `START_NOT_STICKY`. There is no boot receiver or persisted playback session.

The timer uses `SystemClock.elapsedRealtime()`. Interval boundaries and the finite end time stay anchored to the session start, shifted forward by the duration of each pause. A late callback plays at most one sound and schedules one future boundary; missed slots are skipped. Completed and remaining interval counts follow elapsed time, including skipped slots. A callback arriving at or after the end plays the final sound once and finishes without extending the deadline or showing negative time. A time limit that falls on an interval boundary produces one ending cue. Long sound tails may overlap at short intervals without delaying the next boundary; SoundPool allows four simultaneous streams.

Pause cancels scheduled callbacks, pauses SoundPool streams with `autoPause()`, and releases the partial wake lock. The foreground service and loaded sound remain available for Resume, which reacquires the lock, uses `autoResume()`, and schedules the remaining delay. Duplicate Pause, Resume, and Start commands do not reset the session. Stale callbacks cannot play after Pause or Stop.

Recreate the edited mono 44.1 kHz PCM WAVs with `python3 scripts/prepare_sounds.py` and FFmpeg. Source recordings are in `audio-sources/`; ordinary builds need only the checked-in WAVs. Clock tick is 300 ms, Electric wall clock is 150 ms, Gong and Singing bowl are 3 seconds, and Soft bell is 2 seconds. Samples have smooth tail fades. Clock tick is a single pendulum tick from an original 24-bit grandfather-clock recording. Electric wall clock uses one tick from a quartz clock's recording. Neither clock sample receives dynamic compression, equalization, or pitch changes. The gong uses a soft studio recording, peaking at 45% of full scale to leave about 7 dB of headroom, without compression. The other sounds peak at 94%; only the bell receives light dynamic compression. Every decoded sample stays below SoundPool's one-megabyte limit. Actual loudness also depends on media volume and the speaker.

SoundPool does not provide an end-of-playback callback. After the final boundary, the app waits for the selected sample's duration plus a 200 ms output drain margin before releasing audio. Previews use the same completion margin. This is timer-based completion, not a hardware audio timestamp. Android scheduling and output devices can add latency.

Stop, finite completion, load timeout, playback failure, and service destruction cancel callbacks, invalidate old callbacks, release SoundPool and the wake lock, and remove the foreground notification. An old SoundPool load callback cannot restart a stopped session. Duplicate Start commands are ignored.

## Test on an emulator

Start an Android 16 emulator, then select its serial explicitly:

```sh
adb devices
ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest
python3 scripts/emulator_smoke.py --serial emulator-5580
```

The Python smoke test requires Python 3 and ADB on `PATH`, plus the app and test APKs built by `connectedDebugAndroidTest`. It refuses physical-device serials. It clears Tick's data on that emulator, exercises the real notification permission dialog, Pause/Resume and Stop controls in the app and notification shade, and checks both countdown modes. It checks screen-off playback and paused state, wake lock release and reacquisition, reopening, process termination, and reboot. Settings survive while process termination or reboot ends the session. A native instrumentation probe in the test APK reads the current accessibility tree without waiting for the continuously updating timer to become idle. This probe is absent from the delivered app APK.

Unit tests cover immediate playback, full interval counts and the ending sound, time limits on and between boundaries, limits shorter than an interval, unlimited mode, cancellation before the end and during its tail, stale callbacks, timing drift, delayed callbacks past the deadline, each sound's completion and one-second cadence, playback failure, numeric bounds, countdown overflow and formatting, unknown sound fallback, and every WAV's duration, decoded size, and signal level. Instrumentation covers count/time validation and persistence, disabled limit inputs, countdown direction and zero during the final sound, intervals-left visibility, native dropdown previews and same-item replay, rapid preview replacement while loading, preview cancellation on Start/rotation/reopening, sound restoration, playback and cleanup for every sound, rotation and reopening during countdown, elapsed time in Unlimited, timer reset, confirming and canceling Stop, confirmation restoration, completion during confirmation, rapid Start/Stop, duplicate starts, queued restarts after all clients unbind, stopping while SoundPool loads, screen-off playback, notification Stop, and resource cleanup.

Pause tests cover remaining interval fractions, both finite limits, repeated pauses in Unlimited, paused final sounds, stale and duplicate commands, late callbacks after resuming, Stop while paused, rotation, reopening, screen lock, notification controls, and wake lock cleanup.

Reports are written to:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/androidTests/connected/debug/index.html`
- `app/build/reports/lint-results-debug.html`

Inspect a running session with:

```sh
adb -s emulator-5580 shell dumpsys activity service net.namekdev.ticker/.TickService
adb -s emulator-5580 shell dumpsys power
```

## Install on an Android device

Enable USB debugging, connect the device, accept its debugging prompt, and use its serial from `adb devices`:

```sh
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL shell am start -n net.namekdev.ticker/.MainActivity
```

Testing for this delivery uses only the emulator.

## Android references

- [AGP 8.13 compatibility and Kotlin 2.3 support](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Media playback foreground service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#media)
- [Notification permission behavior](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [SoundPool loading, playback and release](https://developer.android.com/reference/android/media/SoundPool)
