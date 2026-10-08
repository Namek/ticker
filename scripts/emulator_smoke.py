#!/usr/bin/env python3
"""ADB end-to-end checks. Resets Tick's data and reboots the specified emulator."""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    parser.error("This test only runs on emulator serials, never on a physical phone.")

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "net.namekdev.ticker"
ACTIVITY = PACKAGE + "/.MainActivity"
SERVICE = PACKAGE + "/.TickService"
PERMISSION = "android.permission.POST_NOTIFICATIONS"


def adb(*command, timeout=30):
    result = subprocess.run(["adb", "-s", args.serial, *command], check=True,
                            capture_output=True, text=True, timeout=timeout)
    return result.stdout


def shell(*command):
    return adb("shell", *command)


def wait_for(check, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(0.15)
    raise AssertionError("Timed out waiting for " + check.__name__)


def tree():
    # A native test-only probe reads the tree while the timer updates every second.
    output = shell("am", "instrument", "-w", "net.namekdev.ticker.test/net.namekdev.ticker.UiProbe")
    match = re.search(r"^INSTRUMENTATION_RESULT: hierarchy=(.*)$", output, re.M)
    assert match is not None, output
    return ET.fromstring(match[1])


def node(resource=None, text=None):
    for item in tree().iter("node"):
        if resource and item.get("resource-id") == resource:
            return item
        if text and item.get("text", "").casefold() == text.casefold():
            return item
    return None


def find(resource=None, text=None):
    def lookup():
        found = node(resource, text)
        return [found] if found is not None else None
    return wait_for(lookup)[0]


def tap(item):
    left, top, right, bottom = map(int, re.findall(r"\d+", item.get("bounds")))
    shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def tap_id(name):
    tap(find(resource=PACKAGE + ":id/" + name))


def set_number(name, value):
    item = find(resource=PACKAGE + ":id/" + name)
    tap(item)
    shell("input", "keyevent", "KEYCODE_MOVE_END")
    if item.get("text"):
        shell("input", "keyevent", *("KEYCODE_DEL" for _ in item.get("text")))
    shell("input", "text", str(value))
    shell("input", "keyevent", "KEYCODE_BACK")


def dump():
    return shell("dumpsys", "activity", "service", SERVICE)


def count():
    match = re.search(r"completed=(\d+)", dump())
    return int(match[1]) if match else 0


def elapsed_millis():
    match = re.search(r"elapsedMillis=(\d+)", dump())
    assert match is not None
    return int(match[1])


def timer_at_zero():
    item = node(resource=PACKAGE + ":id/elapsed")
    return item is not None and item.get("text") == "00:00"


def is_idle():
    output = dump()
    return "foreground=true" not in output and "engineRunning=true" not in output


def assert_released():
    wait_for(is_idle)
    output = dump()
    if "phase=" in output:
        assert "wakeLockHeld=false soundPoolLoaded=false engineRunning=false foreground=false" in output, output
    # dumpsys also includes old acquire/release events; inspect only current locks.
    locks = re.search(r"Wake Locks:.*?(?=\nSuspend Blockers:)", shell("dumpsys", "power"), re.S)
    assert locks is not None
    assert "Tick:playback" not in locks[0], locks[0]


def assert_paused():
    output = dump()
    assert "phase=PAUSED" in output, output
    assert "wakeLockHeld=false soundPoolLoaded=true engineRunning=true foreground=true" in output, output
    locks = re.search(r"Wake Locks:.*?(?=\nSuspend Blockers:)", shell("dumpsys", "power"), re.S)
    assert locks is not None
    assert "Tick:playback" not in locks[0], locks[0]


def launch():
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    shell("wm", "dismiss-keyguard")
    # Boot completion can precede unlock, when normal app activities are still unavailable.
    wait_for(lambda: shell("am", "get-started-user-state", "0").strip() == "RUNNING_UNLOCKED")
    shell("cmd", "statusbar", "collapse")
    shell("am", "start", "-W", "-n", ACTIVITY)
    find(resource=PACKAGE + (":id/start_stop" if is_idle() else ":id/stop"))


def saved_settings():
    xml = ET.fromstring(shell("run-as", PACKAGE, "cat", "shared_prefs/tick_settings.xml"))
    return {item.get("name"): item.get("value", item.text) for item in xml}


def assert_unlimited():
    nodes = {item.get("resource-id"): item for item in tree().iter("node")}
    for name in ("unlimited", "count_mode", "time_mode"):
        item = nodes[PACKAGE + ":id/" + name]
        assert item.get("checked") == ("true" if name == "unlimited" else "false")
        assert item.get("enabled") == "true"
    for name in ("repeats", "time_minutes", "time_seconds", "limit_label"):
        assert PACKAGE + ":id/" + name not in nodes


print("Device:", args.serial, "Android", shell("getprop", "ro.build.version.release").strip(), flush=True)
adb("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
adb("install", "-r", str(ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
shell("am", "force-stop", PACKAGE)
shell("pm", "clear", PACKAGE)
shell("pm", "revoke", PACKAGE, PERMISSION)
shell("pm", "clear-permission-flags", PACKAGE, PERMISSION, "user-set", "user-fixed")
shell("settings", "put", "system", "accelerometer_rotation", "0")
shell("settings", "put", "system", "user_rotation", "0")
launch()
set_number("interval", 2)
set_number("repeats", 7)
find(text="Interval count")
tap_id("unlimited")
assert_unlimited()
tap_id("sound")
options = {item.get("text") for item in tree().iter("node")}
assert {"No sound", "Clock tick", "Electric wall clock", "Gong", "Singing bowl", "Soft bell"} <= options, options
tap(find(text="Gong"))
tap_id("start_stop")
find(resource="com.android.permissioncontroller:id/permission_deny_button")
# PermissionController ignores taps while its dialog is appearing.
time.sleep(0.6)
tap(find(resource="com.android.permissioncontroller:id/permission_deny_button"))
wait_for(lambda: count() >= 2)
assert "sound=gong" in dump()
assert "granted=false" in shell("dumpsys", "package", PACKAGE).split(PERMISSION + ":")[-1].splitlines()[0]
find(text="Notifications are off. You can stop playback here.")
print("PASS: Unlimited hides limits and keeps all mode choices available; dropdown offers No sound and five recordings; Gong plays after notification permission denial.", flush=True)

tap_id("pause_resume")
wait_for(lambda: "phase=PAUSED" in dump())
assert_paused()
paused_time, paused_count = elapsed_millis(), count()
paused_display = find(resource=PACKAGE + ":id/elapsed").get("text")
find(text="Resume")
shell("input", "keyevent", "KEYCODE_BACK")
shell("input", "keyevent", "KEYCODE_SLEEP")
time.sleep(3.2)
assert "mWakefulness=Asleep" in shell("dumpsys", "power")
assert_paused()
assert elapsed_millis() == paused_time
assert count() == paused_count
launch()
assert find(resource=PACKAGE + ":id/elapsed").get("text") == paused_display
find(text="Resume")
tap_id("stop")
tap(find(text="Keep paused"))
assert_paused()
tap_id("pause_resume")
wait_for(lambda: "phase=PLAYING" in dump())
assert "wakeLockHeld=true" in dump()
wait_for(lambda: count() > paused_count)
print("PASS: Pause freezes elapsed time and intervals with notifications denied, releases the wake lock, survives closing and screen lock, and resumes after reopening.", flush=True)

before = count()
elapsed_before = find(resource=PACKAGE + ":id/elapsed").get("text")
shell("input", "keyevent", "KEYCODE_SLEEP")
time.sleep(6.2)
assert "mWakefulness=Asleep" in shell("dumpsys", "power")
assert count() >= before + 2
assert "wakeLockHeld=true" in dump()
print("PASS: ticks continue with the screen off and a partial wake lock.", flush=True)
launch()
assert find(resource=PACKAGE + ":id/elapsed").get("text") != elapsed_before
tap_id("stop")
find(text="End this session and return to settings?")
assert not is_idle()
tap(find(text="Keep ticking"))
assert not is_idle()
tap_id("stop")
tap(find(resource="android:id/button1"))
assert_released()
find(resource=PACKAGE + ":id/interval")
print("PASS: elapsed time includes screen-off time; Stop confirms; Keep ticking continues; confirmation returns to settings and releases resources.", flush=True)

shell("pm", "grant", PACKAGE, PERMISSION)
launch()
tap_id("start_stop")
wait_for(lambda: count() >= 1)
shell("cmd", "statusbar", "expand-notifications")
find(text="Tick is running")
# Other system notifications may be above Tick. Expand Tick's own row.
tick_row = next(item for item in tree().iter("node")
                if item.get("resource-id") == "com.android.systemui:id/expandableNotificationRow"
                and any(child.get("text") == "Tick is running" for child in item.iter("node")))
stop = next((item for item in tick_row.iter("node") if item.get("text", "").casefold() == "stop"), None)
if stop is None:
    expand = next(item for item in tick_row.iter("node") if item.get("resource-id") == "android:id/expand_button")
    tap(expand)
    find(text="Stop")
tap(find(text="Pause"))
wait_for(lambda: "phase=PAUSED" in dump())
find(text="Tick is paused")
assert_paused()
paused_time, paused_count = elapsed_millis(), count()
time.sleep(2.2)
assert elapsed_millis() == paused_time
assert count() == paused_count
tap(find(text="Resume"))
wait_for(lambda: "phase=PLAYING" in dump())
wait_for(lambda: count() > paused_count)
find(text="Tick is running")
tap(find(text="Pause"))
wait_for(lambda: "phase=PAUSED" in dump())
tap(find(text="Stop"))
assert_released()
shell("cmd", "statusbar", "collapse")
print("PASS: notification Pause/Resume freezes and continues the session; notification Stop cancels a paused session.", flush=True)

launch()
tap_id("start_stop")
wait_for(lambda: count() >= 1)
tap_id("pause_resume")
wait_for(lambda: "phase=PAUSED" in dump())
pid = shell("pidof", PACKAGE).strip()
shell("run-as", PACKAGE, "kill", "-9", pid)
time.sleep(1)
assert_released()
launch()
assert find(resource=PACKAGE + ":id/interval").get("text") == "2"
assert saved_settings()["repeats"] == "7"
assert_unlimited()
find(text="Gong")
assert count() == 0
print("PASS: process termination ends a paused session and retains settings.", flush=True)

tap_id("start_stop")
wait_for(lambda: count() >= 1)
adb("reboot")
adb("wait-for-disconnect")
adb("wait-for-device", timeout=60)
wait_for(lambda: shell("getprop", "sys.boot_completed").strip() == "1", timeout=60)
assert_released()
launch()
assert find(resource=PACKAGE + ":id/interval").get("text") == "2"
assert saved_settings()["repeats"] == "7"
assert_unlimited()
find(text="Gong")
assert count() == 0
print("PASS: reboot ends playback and retains settings.", flush=True)

tap_id("time_mode")
set_number("interval", 4)
set_number("time_minutes", 0)
set_number("time_seconds", 11)
tap_id("start_stop")
snapshot = tree()
assert any(item.get("text") == "Time left" for item in snapshot.iter("node"))
assert not any(item.get("resource-id") == PACKAGE + ":id/playback_counter" for item in snapshot.iter("node"))
tap_id("pause_resume")
wait_for(lambda: "phase=PAUSED" in dump())
paused_time = elapsed_millis()
paused_display = find(resource=PACKAGE + ":id/elapsed").get("text")
time.sleep(2.2)
assert_paused()
assert elapsed_millis() == paused_time
assert find(resource=PACKAGE + ":id/elapsed").get("text") == paused_display
tap_id("pause_resume")
wait_for(lambda: "phase=PLAYING" in dump())
time_before = find(resource=PACKAGE + ":id/elapsed").get("text")
assert "limitMode=TIME timeLimit=11" in dump()
shell("input", "keyevent", "KEYCODE_SLEEP")
time.sleep(4.2)
assert "wakeLockHeld=true" in dump()
launch()
time_after = find(resource=PACKAGE + ":id/elapsed").get("text")
assert time_after < time_before, (time_before, time_after)
wait_for(timer_at_zero)
assert "phase=PLAYING" in dump()
assert_released()
assert count() == 2, dump()
find(resource=PACKAGE + ":id/start_stop")
print("PASS: time limit counts down with the screen off, omits intervals left, plays its off-grid ending cue, and releases resources.", flush=True)

shell("am", "force-stop", PACKAGE)
launch()
assert find(resource=PACKAGE + ":id/time_mode").get("checked") == "true"
assert find(resource=PACKAGE + ":id/time_seconds").get("text") == "11"
tap_id("count_mode")
assert find(resource=PACKAGE + ":id/repeats").get("text") == "7"
tap_id("start_stop")
time_before = find(resource=PACKAGE + ":id/elapsed").get("text")
remaining_before = find(resource=PACKAGE + ":id/playback_counter").get("text")
assert remaining_before.endswith("intervals left"), remaining_before
time.sleep(1.2)
assert find(resource=PACKAGE + ":id/elapsed").get("text") < time_before
tap_id("stop")
tap(find(resource="android:id/button1"))
assert_released()
tap_id("time_mode")
assert find(resource=PACKAGE + ":id/time_seconds").get("text") == "11"
print("PASS: count mode shows remaining time and intervals; switching modes preserves both limits.", flush=True)

# Persist every setting, including the inactive count and time limit under Unlimited.
tap_id("count_mode")
set_number("interval", 3)
set_number("repeats", 19)
tap_id("time_mode")
set_number("time_minutes", 1)
set_number("time_seconds", 23)
tap_id("sound")
tap(find(text="No sound"))
tap_id("unlimited")
expected = {"interval": "3", "repeats": "19", "time_limit": "83", "limit_mode": "time",
            "unlimited": "true", "sound": "none"}


wait_for(lambda: expected.items() <= saved_settings().items())
shell("am", "force-stop", PACKAGE)
launch()
assert expected.items() <= saved_settings().items()
assert find(resource=PACKAGE + ":id/interval").get("text") == "3"
assert_unlimited()
find(text="No sound")
print("PASS: all six settings survive force-stop without starting a session, including both limits and No sound.", flush=True)

tap_id("start_stop")
wait_for(lambda: "phase=PLAYING" in dump())
assert "wakeLockHeld=true soundPoolLoaded=false engineRunning=true foreground=true" in dump()
interval_display = find(resource=PACKAGE + ":id/interval_remaining")
session_display = find(resource=PACKAGE + ":id/elapsed")
interval_bounds = list(map(int, re.findall(r"\d+", interval_display.get("bounds"))))
timer_bounds = list(map(int, re.findall(r"\d+", session_display.get("bounds"))))
assert interval_bounds[3] <= timer_bounds[1]
before = count()
shell("input", "keyevent", "KEYCODE_SLEEP")
time.sleep(6.2)
assert count() >= before + 2
assert "soundPoolLoaded=false" in dump()
launch()
print("PASS: No sound uses no SoundPool and continues intervals with the screen off; interval time appears above the session timer.", flush=True)

adb("reboot")
adb("wait-for-disconnect")
adb("wait-for-device", timeout=60)
wait_for(lambda: shell("getprop", "sys.boot_completed").strip() == "1", timeout=60)
launch()
assert_released()
assert expected.items() <= saved_settings().items()
assert_unlimited()
find(text="No sound")
tap_id("count_mode")
assert find(resource=PACKAGE + ":id/repeats").get("text") == "19"
tap_id("time_mode")
assert find(resource=PACKAGE + ":id/time_minutes").get("text") == "1"
assert find(resource=PACKAGE + ":id/time_seconds").get("text") == "23"
print("PASS: reboot stops silent playback and restores interval, count, both time fields, limit mode, Unlimited, and No sound. All ADB checks passed.", flush=True)
