import argparse
import json
import os
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path


PACKAGE = "com.example.wallettrackers"
parser = argparse.ArgumentParser(description="Live signed-out SMS reception and session-restoration test")
parser.add_argument("--device", default="emulator-5556")
parser.add_argument("--execute", action="store_true", help="Required to sign out, inject SMS, and sign back in")
parser.add_argument("--home-baseline", default="16000.00")
parser.add_argument("--mainbank-baseline", default="10000.00")
args = parser.parse_args()

if not args.execute:
    parser.error("Refusing device mutation without --execute")
if not args.device.startswith("emulator-"):
    parser.error("Only an Android emulator is allowed")
email = os.environ.get("WALLET_QA_EMAIL", "").strip()
password = os.environ.get("WALLET_QA_PASSWORD", "")
if not email or not password:
    parser.error("Set WALLET_QA_EMAIL and WALLET_QA_PASSWORD for the disposable QA identity")

marker = "LOGOUTTEST" + uuid.uuid4().hex[:12].upper()
amount = "37.13"
body = f"Your bank account ****1111 was debited EGP {amount} at Carrefour. {marker}"
output = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / "live_sms_signed_out_ignored"
output.mkdir(parents=True, exist_ok=True)
result = {
    "case": "SMS-10",
    "marker": marker,
    "status": "IN_PROGRESS",
    "device": args.device,
    "baseline_home_egp": f"{float(args.home_baseline):,.2f}",
    "baseline_mainbank_egp": f"{float(args.mainbank_baseline):.2f}",
    "steps": [],
    "cleanup": "synthetic inbox row retained; no account or app data reset",
}


def adb(*command, timeout=10):
    return subprocess.check_output(["adb", "-s", args.device, *map(str, command)], timeout=timeout).decode(
        "utf-8", errors="replace"
    ).strip()


def save():
    (output / "results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")


def tree():
    adb("shell", "uiautomator", "dump", "--compressed", "/sdcard/wallet-sms10.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/wallet-sms10.xml"))


def nodes():
    return [node for node in tree().iter() if node.attrib]


def find_text(value, attribute="text"):
    return next((node for node in nodes() if value in node.get(attribute, "")), None)


def bounds(node):
    values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(values) != 4:
        raise AssertionError(f"Invalid Android UI bounds: {node.attrib}")
    return ((values[0] + values[2]) // 2, (values[1] + values[3]) // 2)


def tap_node(node):
    x, y = bounds(node)
    adb("shell", "input", "tap", x, y)


def wait_text(value, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        found = find_text(value)
        if found is not None:
            return found
        time.sleep(0.4)
    raise AssertionError(f"Timed out waiting for UI text {value!r}")


def save_ui(name):
    root = tree()
    (output / f"{name}.xml").write_text(ET.tostring(root, encoding="unicode"), encoding="utf-8")
    screenshot = subprocess.check_output(["adb", "-s", args.device, "exec-out", "screencap", "-p"], timeout=10)
    (output / f"{name}.png").write_bytes(screenshot)


try:
    root = tree()
    visible = [node.get("text", "") for node in root.iter()]
    if "TOTAL BALANCE" not in visible:
        raise AssertionError("Precondition failed: Wallet is not on its signed-in Home screen")
    profile = find_text("Profile", "content-desc")
    if profile is None:
        raise AssertionError("Profile button not found")
    tap_node(profile)
    time.sleep(0.5)
    for _ in range(4):
        if find_text("Sign Out") is not None:
            break
        adb("shell", "input", "swipe", "720", "2650", "720", "750", "350")
        time.sleep(0.3)
    tap_node(wait_text("Sign Out"))
    wait_text("Sign In", timeout=10)
    result["steps"].append("signed out through Profile; Login screen confirmed")
    save_ui("01_signed_out")

    prior_logs = adb("logcat", "-d", "-s", "SmsReceiver:W")
    adb("emu", "sms", "send", "5550001", body)
    deadline = time.monotonic() + 10
    ignored = False
    while time.monotonic() < deadline:
        current_logs = adb("logcat", "-d", "-s", "SmsReceiver:W")
        ignored = current_logs.count("ignored because no Firebase user is signed in") > prior_logs.count(
            "ignored because no Firebase user is signed in"
        )
        if ignored:
            break
        time.sleep(0.5)
    if not ignored:
        raise AssertionError("SmsReceiver did not log that the signed-out SMS was ignored")
    result["steps"].append("injected one SMS while signed out; receiver logged that no Firebase user was signed in")
    save_ui("02_sms_ignored_login_screen")

    edit_fields = [node for node in nodes() if node.get("class") == "android.widget.EditText"]
    if len(edit_fields) < 2:
        raise AssertionError("Email and password fields were not both visible after sign-out")
    tap_node(edit_fields[0])
    adb("shell", "input", "text", email)
    edit_fields = [node for node in nodes() if node.get("class") == "android.widget.EditText"]
    tap_node(edit_fields[1])
    adb("shell", "input", "text", password)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap_node(wait_text("Sign In"))
    wait_text("TOTAL BALANCE", timeout=30)
    result["steps"].append("signed back into the same QA identity")
    save_ui("03_restored_session")

    expected_home = f"{float(args.home_baseline):,.2f}"
    expected_mainbank = f"{float(args.mainbank_baseline):.2f}"
    if find_text(expected_home) is None:
        raise AssertionError(f"Home total changed after signed-out SMS; expected EGP {expected_home}")
    if find_text("MainBank") is None or find_text(expected_mainbank) is None:
        raise AssertionError(f"MainBank did not retain its baseline EGP {expected_mainbank} balance")
    records = find_text("Records", "content-desc")
    if records is None:
        raise AssertionError("Records navigation control is missing after sign-in")
    tap_node(records)
    wait_text("All Records")
    if find_text(f"-{amount} EGP") is not None:
        raise AssertionError("Signed-out SMS was recorded after signing back in")
    result["steps"].append(f"verified no -{amount} EGP record appeared after sign-in; Home remained EGP {expected_home}")
    result["status"] = "PASS"
    save()
    print(json.dumps(result, indent=2))
    print(f"Evidence: {output.resolve()}")
except Exception as error:
    result["status"] = "PARTIAL" if result["steps"] else "FAIL"
    result["error"] = f"{type(error).__name__}: {error}"
    save()
    print(json.dumps(result, indent=2))
    print(f"Evidence: {output.resolve()}")
    raise SystemExit(1)
