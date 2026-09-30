"""APP-11: exercise biometric locking on a signed-in QA Android emulator.

Run with an enrolled fingerprint:
  python scripts/device_biometric_lock_case.py --execute --fingerprint-id 1
Prepare an unsecured emulator, enroll a temporary fingerprint, test, and remove it:
  python scripts/device_biometric_lock_case.py --execute --prepare-security

Preparation requires zero enrolled fingerprints and no existing lock credential.
The temporary PIN, enrolled fingerprint, original None/Swipe setting, and app
toggle are restored in finally. No app data is cleared and no AVD is restored.
An unavailable enrollment flow is reported as BLOCKED, never a passing test.
"""

import argparse
import json
import re
import subprocess
import time
import traceback
import uuid
from pathlib import Path
from xml.etree import ElementTree

PACKAGE = "com.example.wallettrackers"
TIMEOUT = 10
TEMPORARY_PIN = "728491"
LOCK_LABEL = "Security Lock Active"
HOME_LABEL = "TOTAL BALANCE"


class PrerequisiteError(RuntimeError):
    pass


def enrolled_count(dump, user_id):
    decoder = json.JSONDecoder()
    counts = []
    for offset, character in enumerate(dump):
        if character != "{":
            continue
        try:
            payload, _ = decoder.raw_decode(dump[offset:])
        except ValueError:
            continue
        if isinstance(payload, dict) and isinstance(payload.get("prints"), list):
            counts.extend(
                int(item["count"]) for item in payload["prints"]
                if int(item.get("id", -1)) == user_id and "count" in item
            )
    if not counts:
        raise PrerequisiteError("Cannot read fingerprint enrollment count; inspect fingerprint_before.txt")
    return sum(counts)


class BiometricCase:
    def __init__(self, args):
        self.args = args
        self.driver = None
        self.original_toggle = None
        self.original_disabled = None
        self.pin_attempted = False
        self.user_id = None
        self.output = Path("app/build/device-smoke") / (
            time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
        ) / "app_11_biometric_lock"
        self.output.mkdir(parents=True)
        self.result = {
            "case": "APP-11", "status": "IN_PROGRESS", "device": args.device,
            "tests": [], "evidence": [], "cleanup": {}, "ui_timeout_seconds": TIMEOUT,
            "security_preparation_requested": args.prepare_security,
        }

    def adb(self, *command):
        completed = subprocess.run(
            ["adb", "-s", self.args.device, *command], capture_output=True, timeout=TIMEOUT,
        )
        output = completed.stdout.decode("utf-8", errors="replace")
        if completed.returncode:
            raise RuntimeError(output + completed.stderr.decode("utf-8", errors="replace"))
        return output

    def save(self):
        (self.output / "results.json").write_text(json.dumps(self.result, indent=2), encoding="utf-8")

    def tree(self):
        if self.driver is None:
            self.adb("shell", "uiautomator", "dump", "/sdcard/qa_window.xml")
            return ElementTree.fromstring(self.adb("shell", "cat", "/sdcard/qa_window.xml"))
        return ElementTree.fromstring(self.driver.page_source)

    def find(self, label, root=None):
        root = self.tree() if root is None else root
        return next((node for node in root.iter() if label in (
            node.get("text", "").strip(), node.get("content-desc", "").strip(),
        )), None)

    def wait(self, label, present=True):
        deadline = time.monotonic() + TIMEOUT
        while time.monotonic() < deadline:
            node = self.find(label)
            if (node is not None) == present:
                return node
            time.sleep(0.2)
        raise AssertionError(f"Timed out after 10 seconds: {label!r}, present={present}")

    def tap_node(self, node):
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
        self.adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def tap(self, label):
        self.tap_node(self.wait(label))

    def capture(self, name):
        paths = []
        for extension, action in (
            ("xml", lambda: ElementTree.tostring(self.tree(), encoding="utf-8")),
            ("png", lambda: subprocess.run(
                ["adb", "-s", self.args.device, "exec-out", "screencap", "-p"],
                capture_output=True, timeout=TIMEOUT, check=True,
            ).stdout),
        ):
            path = self.output / f"{name}.{extension}"
            try:
                path.write_bytes(action())
                paths.append(str(path))
            except Exception as error:
                self.result.setdefault("capture_errors", []).append(f"{name}: {error}")
        self.result["evidence"].extend(paths)

    def fingerprint_count(self, artifact):
        dump = self.adb("shell", "dumpsys", "fingerprint")
        (self.output / artifact).write_text(dump, encoding="utf-8")
        self.result["evidence"].append(str(self.output / artifact))
        return enrolled_count(dump, self.user_id)

    def start_session(self):
        from appium import webdriver
        from appium.options.android import UiAutomator2Options
        options = UiAutomator2Options().load_capabilities({
            "platformName": "Android", "appium:automationName": "UiAutomator2",
            "appium:deviceName": self.args.device, "appium:udid": self.args.device,
            "appium:noReset": True, "appium:autoLaunch": False,
            "appium:newCommandTimeout": 10, "appium:uiautomator2ServerLaunchTimeout": 10000,
        })
        self.driver = webdriver.Remote(self.args.server, options=options)
        self.driver.implicitly_wait(0)
        try:
            self.driver.command_executor.set_timeout(TIMEOUT)
        except AttributeError:
            pass

    def launch(self):
        self.adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")

    def authenticate(self):
        self.wait(LOCK_LABEL)
        self.tap("Unlock Dashboard")
        self.wait_biometric_prompt()
        self.capture("authentication_prompt")
        self.adb("emu", "finger", "touch", str(self.args.fingerprint_id))
        self.wait(HOME_LABEL)
        assert self.find(LOCK_LABEL) is None, "Lock overlay remains after fingerprint authentication"

    def home(self):
        self.launch()
        deadline = time.monotonic() + TIMEOUT
        while time.monotonic() < deadline:
            root = self.tree()
            if self.find(LOCK_LABEL, root) is not None:
                self.authenticate()
                return
            if self.find(HOME_LABEL, root) is not None:
                return
            time.sleep(0.2)
        raise AssertionError("Signed-in Home or lock screen did not appear within 10 seconds")

    def open_profile(self):
        self.home()
        self.tap("Profile")
        deadline = time.monotonic() + TIMEOUT
        while time.monotonic() < deadline:
            if self.find("Biometric Lock") is not None:
                return
            size = self.driver.get_window_size()
            self.adb("shell", "input", "swipe", str(size["width"] // 2),
                     str(int(size["height"] * 0.8)), str(size["width"] // 2),
                     str(int(size["height"] * 0.5)), "250")
        raise AssertionError("Biometric Lock setting not visible within 10 seconds")

    def switch(self):
        root = self.tree()
        label = self.find("Biometric Lock", root)
        if label is None:
            raise AssertionError("Biometric Lock label missing")
        label_top, label_bottom = map(int, re.findall(r"\d+", label.get("bounds", ""))[1::2])
        root_top, root_bottom = label_top, label_bottom
        switches = []
        for node in root.iter():
            if node.get("checkable") != "true" or node.get("package") != PACKAGE:
                continue
            bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if len(bounds) == 4 and bounds[1] < root_bottom and bounds[3] > root_top:
                switches.append(node)
        if len(switches) == 1:
            return switches[0]
        raise AssertionError("Could not uniquely associate Biometric Lock with its same-row switch")

    def toggle(self, enabled):
        self.open_profile()
        control = self.switch()
        if (control.get("checked") == "true") != enabled:
            self.tap_node(control)
        deadline = time.monotonic() + TIMEOUT
        while time.monotonic() < deadline:
            if (self.switch().get("checked") == "true") == enabled:
                self.capture("toggle_enabled" if enabled else "toggle_disabled")
                self.adb("shell", "input", "keyevent", "4")
                deadline = time.monotonic() + TIMEOUT
                while time.monotonic() < deadline:
                    root = self.tree()
                    if self.find(HOME_LABEL, root) is not None or self.find(LOCK_LABEL, root) is not None:
                        return
                    time.sleep(0.2)
                raise AssertionError("Neither dashboard nor security lock appeared after closing Profile")
            time.sleep(0.2)
        raise AssertionError("Biometric Lock toggle did not reach requested state")

    def prepare(self):
        if self.driver is not None:
            self.driver.quit()
            self.driver = None
        self.original_disabled = self.adb(
            "shell", "locksettings", "get-disabled", "--user", str(self.user_id),
        ).strip()
        if self.original_disabled != "true":
            raise PrerequisiteError("Preparation requires an emulator with no existing secure credential")
        self.result["original_lock_screen_disabled"] = self.original_disabled
        self.pin_attempted = True
        self.save()
        self.adb(
            "shell", "am", "start", "-a", "android.settings.BIOMETRIC_ENROLL",
            "--ei", "android.provider.extra.BIOMETRIC_AUTHENTICATORS_ALLOWED", "32783",
        )
        deadline = time.monotonic() + TIMEOUT
        while time.monotonic() < deadline:
            focus = self.adb("shell", "dumpsys", "window")
            if "com.android.settings" in focus:
                break
            time.sleep(0.2)
        else:
            raise PrerequisiteError("Android Settings did not open fingerprint enrollment")
        for stage in range(12):
            self.capture(f"enrollment_{stage:02d}")
            root = self.tree()
            if not any(node.get("package") == "com.android.settings" for node in root.iter()):
                if self.adb("shell", "locksettings", "get-disabled", "--user", str(self.user_id)).strip() != "false":
                    raise PrerequisiteError("Android Settings exited before creating the temporary lock credential")
                self.adb(
                    "shell", "am", "start", "-a", "android.settings.BIOMETRIC_ENROLL",
                    "--ei", "android.provider.extra.BIOMETRIC_AUTHENTICATORS_ALLOWED", "32783",
                )
                deadline = time.monotonic() + TIMEOUT
                while time.monotonic() < deadline:
                    if "com.android.settings" in self.adb("shell", "dumpsys", "window"):
                        break
                    time.sleep(0.2)
                else:
                    raise PrerequisiteError("Android Settings did not resume fingerprint enrollment")
                continue
            edit = next((node for node in root.iter() if node.get("class") == "android.widget.EditText"), None)
            if edit is not None:
                if edit.get("package") != "com.android.settings":
                    raise PrerequisiteError(
                        "PIN entry is visible outside Android Settings; refusing to type into an unrelated field"
                    )
                self.tap_node(edit)
                self.adb("shell", "input", "text", TEMPORARY_PIN)
                self.adb("shell", "input", "keyevent", "66")
                time.sleep(0.3)
                continue
            texts = " ".join(node.get("text", "") for node in root.iter()).casefold()
            if any(phrase in texts for phrase in ("touch the sensor", "touch & hold", "lift, then touch", "fingerprint sensor", "start with the center")):
                for _ in range(6):
                    self.adb("emu", "finger", "touch", str(self.args.fingerprint_id))
                    time.sleep(0.25)
            if self.fingerprint_count(f"fingerprint_enrollment_{stage:02d}.txt") > 0:
                self.capture("fingerprint_enrolled")
                self.adb("shell", "input", "keyevent", "3")
                return
            root = self.tree()
            action = next((self.find(label, root) for label in (
                "Pixel Imprint + PIN", "I agree", "I AGREE", "More", "MORE", "Next", "NEXT", "Continue", "CONTINUE", "Got it", "Done",
            ) if self.find(label, root) is not None), None)
            if action is not None:
                self.tap_node(action)
            elif not any(phrase in texts for phrase in ("sensor", "fingerprint")):
                raise PrerequisiteError("Unrecognized fingerprint enrollment screen; see enrollment artifacts")
            time.sleep(0.3)
        raise PrerequisiteError("Fingerprint enrollment did not complete; see enrollment artifacts")

    def assert_locked(self):
        deadline = time.monotonic() + TIMEOUT
        while time.monotonic() < deadline:
            root = self.tree()
            assert self.find(HOME_LABEL, root) is None, "Financial dashboard exposed without authentication"
            if self.find(LOCK_LABEL, root) is not None:
                return
            time.sleep(0.2)
        raise AssertionError("Lock overlay did not appear within 10 seconds")

    def wait_biometric_prompt(self):
        deadline = time.monotonic() + TIMEOUT
        last_focus = ""
        while time.monotonic() < deadline:
            window_state = self.adb("shell", "dumpsys", "window")
            last_focus = next((line.strip() for line in window_state.splitlines()
                               if "mCurrentFocus=" in line), "")
            if "biometric" in last_focus.casefold() or "systemui" in last_focus.casefold():
                self.result.setdefault("biometric_prompt_windows", []).append(last_focus)
                return
            time.sleep(0.2)
        raise AssertionError(f"Android biometric prompt did not become foreground; focus={last_focus!r}")

    def background_return(self):
        self.home()
        self.adb("shell", "input", "keyevent", "3")
        time.sleep(0.5)
        self.launch()
        self.assert_locked()

    def cancel(self):
        self.background_return()
        self.tap("Unlock Dashboard")
        self.wait_biometric_prompt()
        self.adb("shell", "input", "keyevent", "4")
        self.wait(LOCK_LABEL)
        for _ in range(3):
            self.assert_locked()
            time.sleep(0.3)
        self.adb("shell", "input", "keyevent", "4")
        self.launch()
        self.assert_locked()

    def successful_unlock(self):
        self.background_return()
        self.authenticate()

    def cold_start(self):
        self.home()
        self.adb("shell", "am", "force-stop", PACKAGE)
        self.launch()
        self.assert_locked()
        self.authenticate()

    def test(self, name, action):
        item = {"name": name, "status": "RUNNING"}
        self.result["tests"].append(item)
        try:
            action()
            item["status"] = "PASSED"
        except Exception as error:
            item.update(
                status="FAILED",
                error=f"{type(error).__name__}: {error}",
                traceback=traceback.format_exc(),
            )
        self.capture(name)
        self.save()

    def cleanup(self):
        if self.driver is not None and self.original_toggle is not None:
            try:
                self.toggle(self.original_toggle)
                self.result["cleanup"]["app_toggle"] = "RESTORED"
            except Exception as error:
                self.result["cleanup"]["app_toggle"] = f"FAILED: {error}"
        if self.pin_attempted:
            try:
                current_disabled = self.adb("shell", "locksettings", "get-disabled", "--user", str(self.user_id)).strip()
                if current_disabled != self.original_disabled:
                    self.adb("shell", "locksettings", "clear", "--old", TEMPORARY_PIN, "--user", str(self.user_id))
                    self.adb("shell", "locksettings", "set-disabled", "--user", str(self.user_id), self.original_disabled)
                deadline = time.monotonic() + TIMEOUT
                while self.fingerprint_count("fingerprint_after_cleanup.txt") != 0:
                    if time.monotonic() >= deadline:
                        raise AssertionError("Test-created fingerprint still enrolled after PIN removal")
                    time.sleep(0.3)
                restored = self.adb("shell", "locksettings", "get-disabled", "--user", str(self.user_id)).strip()
                assert restored == self.original_disabled, "None/Swipe setting was not restored"
                self.result["cleanup"]["device_security"] = "RESTORED"
            except Exception as error:
                self.result["cleanup"]["device_security"] = f"FAILED: {error}"
        if self.driver is not None:
            self.capture("final_cleanup")
            try:
                self.driver.quit()
            except Exception as error:
                self.result["cleanup"]["session"] = str(error)

    def run(self):
        try:
            if not self.args.device.startswith("emulator-"):
                raise PrerequisiteError("This runner requires a dedicated Android emulator")
            self.user_id = int(self.adb("shell", "am", "get-current-user").strip())
            count = self.fingerprint_count("fingerprint_before.txt")
            self.result["initial_fingerprint_count"] = count
            if count == 0 and not self.args.prepare_security:
                raise PrerequisiteError("No enrolled fingerprint. Rerun with --prepare-security on the unsecured QA emulator")
            if count and self.args.prepare_security:
                raise PrerequisiteError("Preparation requires zero existing fingerprints; use ordinary mode instead")
            self.start_session()
            if self.args.prepare_security:
                self.prepare()
                self.start_session()
            self.open_profile()
            self.original_toggle = self.switch().get("checked") == "true"
            self.result["original_biometric_toggle"] = self.original_toggle
            self.capture("initial_toggle")
            self.adb("shell", "input", "keyevent", "4")
            self.toggle(True)
            self.test("background_return_requires_authentication", self.background_return)
            self.test("cancel_and_back_preserve_lock", self.cancel)
            self.test("enrolled_fingerprint_unlocks_dashboard", self.successful_unlock)
            self.test("cold_start_requires_authentication", self.cold_start)
            self.result["status"] = "FAILED" if any(
                item["status"] != "PASSED" for item in self.result["tests"]
            ) else "PASSED"
        except PrerequisiteError as error:
            self.result.update(status="BLOCKED", error=str(error))
        except Exception as error:
            self.result.update(
                status="INCONCLUSIVE",
                error=f"{type(error).__name__}: {error}",
                traceback=traceback.format_exc(),
            )
            if self.driver is not None:
                self.capture("unexpected_failure")
        finally:
            self.cleanup()
            if any(value.startswith("FAILED") for value in self.result["cleanup"].values()):
                self.result["status"] = "FAILED_CLEANUP"
            self.save()
        print(json.dumps(self.result, indent=2))
        print(f"Evidence: {self.output.resolve()}")
        return 0 if self.result["status"] == "PASSED" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--device", default="emulator-5556")
    parser.add_argument("--server", default="http://127.0.0.1:4723")
    parser.add_argument("--fingerprint-id", type=int, default=1)
    parser.add_argument("--prepare-security", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        parser.error("Pass --execute to run APP-11; no device changes made")
    return BiometricCase(args).run()


if __name__ == "__main__":
    raise SystemExit(main())
