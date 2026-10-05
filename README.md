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

The defaults are 10 seconds, an interval count of 10, Unlimited off, and Clock tick. Interval count means the number of full intervals, with a sound at Start and another at every interval boundary, including the end. The compact settings screen places interval and count side by side. Enter positive whole numbers up to 2147483647. Invalid edits show an error and never overwrite saved valid values. Each valid field saves automatically. Unlimited disables and dims the count input while retaining its last finite value.

The Sound dropdown offers real recordings of Clock tick, Gong, Singing bowl, and Soft bell. Selecting an item previews it, including selecting the same sound again. The selection saves automatically and is locked during playback. Changing sounds, starting a session, rotating, or leaving the screen cancels the preview. Restoring settings never starts a preview. The recordings are bundled offline and normalized for stronger playback; [sources, creators, and public-domain licenses](audio-sources/CREDITS.md) are included. Media volume controls playback volume; the app does not change the device volume.

Start opens a playback screen with a large centered elapsed timer, a completed interval count, and Stop. The first sound plays as soon as the bundled sample loads, and the elapsed timer starts with it. A 10-second interval with an interval count of 3 plays at 0, 10, 20, and 30 seconds. The counter starts at zero and increments at each interval boundary. After the final sound rings out, the app returns to settings. Unlimited continues until stopped.

Stop inside the app asks for confirmation. Choose Keep ticking to continue, or Stop to end the session and return to configuration. Finite completion also returns to configuration. Another Start resets both the timer and counter. Configuration is hidden during playback. Elapsed time survives rotation, reopening, and screen lock because its monotonic start time belongs to the service.

Playback uses media volume and never requests audio focus, so it can mix with music or podcasts. It continues when you rotate, leave the app, dismiss its recent task, or lock the screen. Reopen Tick to see the active session. Process termination, force-stop, or reboot ends playback. Settings survive those events.

Android 13 and newer ask for notification permission on the first valid Start. Playback starts regardless of the answer. The silent ongoing notification has a Stop action that stops immediately. If notifications are denied, use Stop inside the app.

## Timing and lifecycle

`TickService` owns the SoundPool, foreground notification, partial wake lock, and session state. The activity binds to it for state updates. The service is non-exported, declares `mediaPlayback`, and returns `START_NOT_STICKY`. There is no boot receiver or persisted playback session.

The timer uses `SystemClock.elapsedRealtime()`. Deadlines stay on the original interval grid. A late callback plays at most one sound and schedules one future boundary; missed slots are skipped instead of replayed. Finite mode still delivers the requested number of interval cues plus the initial sound, so a delayed callback can extend the session. Long sound tails may overlap at short intervals without delaying the next boundary; SoundPool allows four simultaneous streams.

Recreate the edited mono 44.1 kHz PCM WAVs with `python3 scripts/prepare_sounds.py` and FFmpeg. Original recordings are in `audio-sources/`; ordinary builds need only the checked-in WAVs. Clock tick is 300 ms, Gong and Singing bowl are 3 seconds, and Soft bell is 2 seconds. Samples have smooth tail fades and peak at 94% of full scale. Clock and bell receive light dynamic compression to make their bodies more audible. Every decoded sample stays below SoundPool's one-megabyte limit. Actual loudness also depends on media volume and the speaker.

SoundPool does not provide an end-of-playback callback. After the final boundary, the app waits for the selected sample's duration plus a 200 ms output drain margin before releasing audio. Previews use the same completion margin. This is timer-based completion, not a hardware audio timestamp. Android scheduling and output devices can add latency.

Stop, finite completion, load timeout, playback failure, and service destruction cancel callbacks, invalidate old callbacks, release SoundPool and the wake lock, and remove the foreground notification. An old SoundPool load callback cannot restart a stopped session. Duplicate Start commands are ignored.

## Test on an emulator

Start an Android 16 emulator, then select its serial explicitly:

```sh
adb devices
ANDROID_SERIAL=emulator-5580 ./gradlew connectedDebugAndroidTest
python3 scripts/emulator_smoke.py --serial emulator-5580
```

The Python smoke test requires Python 3 and ADB on `PATH`, plus the app and test APKs built by `connectedDebugAndroidTest`. It refuses physical-device serials. It clears Tick's data on that emulator, exercises the real notification permission dialog, Stop confirmation and notification shade, checks elapsed time, screen-off playback and resource cleanup, kills the app process, and reboots the emulator to verify that settings survive while playback stops. A native instrumentation probe in the test APK reads the current accessibility tree without waiting for the continuously updating timer to become idle. This probe is absent from the delivered app APK.

Unit tests cover immediate playback, full interval counts and the ending sound, unlimited mode, cancellation before the end and during its tail, stale callbacks, timing drift, delayed callbacks, each sound's completion and one-second cadence, playback failure, numeric bounds, unknown sound fallback, and every WAV's duration, decoded size, and signal level. Instrumentation covers field validation, disabled count input, automatic settings persistence, native dropdown previews and same-item replay, rapid preview replacement while loading, preview cancellation on Start/rotation/reopening, sound selection and restoration, final gong ring-out, playback and cleanup for every sound, rotation, reopening, elapsed time between ticks, timer reset, confirming and canceling Stop, confirmation restoration, completion during confirmation, rapid Start/Stop, duplicate starts, queued restarts after all clients unbind, stopping while SoundPool loads, screen-off playback, notification Stop, and resource cleanup.

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
- [Native elapsed-time display](https://developer.android.com/reference/android/widget/Chronometer)
