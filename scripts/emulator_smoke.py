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
PACKAGE = "com.example.tick"
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
    # A native test-only probe reads the tree while the elapsed timer updates every second.
    output = shell("am", "instrument", "-w", "com.example.tick.test/com.example.tick.UiProbe")
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


def launch():
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    shell("wm", "dismiss-keyguard")
    shell("cmd", "statusbar", "collapse")
    shell("am", "start", "-W", "-n", ACTIVITY)
    find(resource=PACKAGE + (":id/start_stop" if is_idle() else ":id/stop"))


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
assert find(resource=PACKAGE + ":id/repeats").get("enabled") == "false"
tap_id("sound")
options = {item.get("text") for item in tree().iter("node")}
assert {"Clock tick", "Gong", "Singing bowl", "Soft bell"} <= options, options
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
print("PASS: Interval count is disabled in Unlimited; dropdown offers four sounds; Gong plays after notification permission denial.", flush=True)

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
    stop = find(text="Stop")
tap(stop)
assert_released()
shell("cmd", "statusbar", "collapse")
print("PASS: the Stop button in the notification shade cancels playback.", flush=True)

launch()
tap_id("start_stop")
wait_for(lambda: count() >= 1)
pid = shell("pidof", PACKAGE).strip()
shell("run-as", PACKAGE, "kill", "-9", pid)
time.sleep(1)
assert_released()
launch()
assert find(resource=PACKAGE + ":id/interval").get("text") == "2"
assert find(resource=PACKAGE + ":id/repeats").get("text") == "7"
assert find(resource=PACKAGE + ":id/unlimited").get("checked") == "true"
assert find(resource=PACKAGE + ":id/repeats").get("enabled") == "false"
find(text="Gong")
assert find(resource=PACKAGE + ":id/counter").get("text") == "0"
print("PASS: process termination ends playback and retains settings.", flush=True)

tap_id("start_stop")
wait_for(lambda: count() >= 1)
adb("reboot")
adb("wait-for-disconnect")
adb("wait-for-device", timeout=60)
wait_for(lambda: shell("getprop", "sys.boot_completed").strip() == "1", timeout=60)
assert_released()
launch()
assert find(resource=PACKAGE + ":id/interval").get("text") == "2"
assert find(resource=PACKAGE + ":id/repeats").get("text") == "7"
assert find(resource=PACKAGE + ":id/unlimited").get("checked") == "true"
assert find(resource=PACKAGE + ":id/repeats").get("enabled") == "false"
find(text="Gong")
assert find(resource=PACKAGE + ":id/counter").get("text") == "0"
print("PASS: reboot ends playback and retains settings. All ADB checks passed.", flush=True)
