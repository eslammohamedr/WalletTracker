import argparse
import json
import re
import secrets
import string
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree
from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy

PACKAGE = "com.example.wallettrackers"
SENDER = "5550001"
BASELINE_HOME = "15,999.83"
BASELINE_MAIN_BANK = "9999.83"
parser = argparse.ArgumentParser(description="Live-app no-record SMS scenarios.")
parser.add_argument("--scenario", choices=("missing-amount", "declined", "unknown-format"), default="missing-amount")
parser.add_argument("--device", default="emulator-5556", help="ADB serial / Appium device ID")
parser.add_argument("--server", default="http://127.0.0.1:4723", help="Appium server URL")
parser.add_argument("--baseline-home", default="16000.00", help="Expected Home EGP total before the SMS")
parser.add_argument("--baseline-mainbank", default="10000.00", help="Expected MainBank EGP balance")
args = parser.parse_args()
BASELINE_HOME = f"{float(args.baseline_home):,.2f}"
BASELINE_MAIN_BANK = f"{float(args.baseline_mainbank):.2f}"
SCENARIO_NAME = {
    "missing-amount": "live_sms_missing_amount_no_record",
    "declined": "live_sms_declined_no_record",
    "unknown-format": "live_sms_unknown_format_rejected",
}[args.scenario]
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / SCENARIO_NAME
OUTPUT.mkdir(parents=True, exist_ok=True)
MARKER = ({"missing-amount": "NOAMOUNT", "declined": "DECLINED", "unknown-format": "UNKNOWNFMT"}[args.scenario]) + "".join(
    secrets.choice(string.ascii_uppercase) for _ in range(10)
)
if args.scenario == "missing-amount":
    BODY = (
        f"Your bank account ****1111 was used at the Quality Assurance test merchant {MARKER}. "
        "This is an account alert. Contact your bank if you did not authorize it."
    )
elif args.scenario == "declined":
    BODY = (
        f"Transaction declined: Your bank account ****1111 payment of EGP 23.45 was declined due to "
        f"insufficient funds. Available balance EGP {BASELINE_MAIN_BANK}. {MARKER}"
    )
else:
    BODY = (
        f"{MARKER}: Account ****1111 service notice. Your request was reviewed. "
        "Please contact support for more information."
    )
RESULT = {
    "case": SCENARIO_NAME,
    "marker": MARKER,
    "body": BODY,
    "status": "IN_PROGRESS",
    "steps": [],
    "baseline_home_egp": BASELINE_HOME,
    "baseline_mainbank_egp": BASELINE_MAIN_BANK,
    "sms_cleanup": "synthetic Inbox row is retained; this case creates no Wallet record",
    "sms_injection_attempted": False,
}


def adb(*command, timeout=10):
    return subprocess.check_output(["adb", "-s", args.device, *command], timeout=timeout).decode(
        "utf-8", errors="replace"
    )


def save_result():
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")


options = UiAutomator2Options().load_capabilities({
    "platformName": "Android",
    "appium:automationName": "UiAutomator2",
    "appium:deviceName": args.device,
    "appium:udid": args.device,
    "appium:appPackage": PACKAGE,
    "appium:appActivity": ".MainActivity",
    "appium:noReset": True,
    "appium:newCommandTimeout": 10,
    "appium:uiautomator2ServerLaunchTimeout": 10000,
})
try:
    driver = webdriver.Remote(args.server.rstrip("/"), options=options)
    driver.implicitly_wait(0)
    try:
        driver.command_executor.set_timeout(10)
    except AttributeError:
        pass
except Exception as error:
    RESULT["status"] = "INCONCLUSIVE"
    RESULT["error"] = f"Appium session startup failed: {type(error).__name__}: {error}"
    RESULT["steps"].append("no SMS was injected because the UI session could not start")
    save_result()
    print(json.dumps(RESULT, indent=2))
    print(f"Evidence: {OUTPUT.resolve()}")
    raise SystemExit(1)


def tree():
    deadline = time.monotonic() + 10
    last_error = None
    while time.monotonic() < deadline:
        try:
            return ElementTree.fromstring(driver.page_source)
        except Exception as error:
            last_error = str(error)
            time.sleep(min(0.3, max(0, deadline - time.monotonic())))
    raise RuntimeError(f"Could not capture Appium hierarchy within 10 seconds: {last_error}")


def nodes():
    root = tree()
    return [node for node in root.iter() if node is not root and node.attrib]


def matching_node(value, attribute="content-desc"):
    return next((node for node in nodes() if value in node.get(attribute, "")), None)


def wait_for(value, attribute="text", timeout=10, present=True):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        node = matching_node(value, attribute)
        if (node is not None) == present:
            return node
        time.sleep(0.5)
    raise AssertionError(f"Timed out waiting for {attribute} {value!r} present={present}")


def center(node):
    import re

    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return (left + right) // 2, (top + bottom) // 2


def tap(value, attribute="content-desc"):
    if attribute == "content-desc":
        elements = driver.find_elements(AppiumBy.ACCESSIBILITY_ID, value)
    else:
        escaped = json.dumps(value)
        elements = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, f"new UiSelector().textContains({escaped})")
    if not elements:
        wait_for(value, attribute)
        if attribute == "content-desc":
            elements = driver.find_elements(AppiumBy.ACCESSIBILITY_ID, value)
        else:
            escaped = json.dumps(value)
            elements = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, f"new UiSelector().textContains({escaped})")
    if not elements:
        raise AssertionError(f"Could not tap {attribute} {value!r}")
    elements[-1].click()


def capture(name):
    driver.save_screenshot(str(OUTPUT / f"{name}.png"))
    (OUTPUT / f"{name}.xml").write_text(driver.page_source, encoding="utf-8")


def all_text(root=None):
    if root is None:
        root = tree()
    return [node.get("text", "").strip() for node in root.iter() if node.get("text", "").strip()]


def assert_home_balances():
    root = tree()
    texts = all_text(root)
    if "TOTAL BALANCE" not in texts or BASELINE_HOME not in texts:
        raise AssertionError(f"Home dashboard did not preserve EGP {BASELINE_HOME}: {texts}")
    if "MainBank" not in texts or BASELINE_MAIN_BANK not in texts:
        screen = adb("shell", "wm", "size")
        dimensions = re.search(r"(\d+)x(\d+)", screen)
        if dimensions is None:
            raise AssertionError(f"Could not determine emulator screen dimensions: {screen}")
        width, height = map(int, dimensions.groups())
        accounts_heading = next((node for node in root.iter() if node.get("text", "").strip() == "Accounts"), None)
        if accounts_heading is None:
            raise AssertionError(f"Accounts carousel was not visible: {texts}")
        _, _, _, heading_bottom = center_bounds(accounts_heading)
        swipe_y = min(heading_bottom + max(120, int(height * 0.07)), int(height * 0.86))
        for start_x, end_x in ((200, width - 200), (width - 200, 200)):
            adb("shell", "input", "swipe", str(start_x), str(swipe_y), str(end_x), str(swipe_y), "550")
            time.sleep(0.4)
            root = tree()
            texts = all_text(root)
            if "MainBank" in texts and BASELINE_MAIN_BANK in texts:
                break
    if "MainBank" not in texts or BASELINE_MAIN_BANK not in texts:
        raise AssertionError(f"MainBank did not preserve EGP {BASELINE_MAIN_BANK} after scanning its carousel page: {texts}")


def center_bounds(node):
    import re

    return tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))


def cleanup_exact_marker_record():
    marker_node = matching_node(MARKER, "text")
    if marker_node is None:
        return False
    capture("03_unexpected_record")
    RESULT["unexpected_record_text"] = marker_node.get("text", "")
    x, y = center(marker_node)
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "1000")
    wait_for("Delete", "text")
    tap("Delete", "text")
    wait_for("Delete Record", "text")
    tap("Delete", "text")
    wait_for("Delete Record", "text", present=False)
    RESULT["cleanup"] = "exact unexpected marker record deleted; verifying baselines after restart"
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(3)
    tap("Records")
    wait_for("All Records", "text")
    wait_for("No records found", "text")
    tap("Home")
    wait_for("TOTAL BALANCE", "text")
    assert_home_balances()
    RESULT["cleanup"] = "exact unexpected marker record deleted; Records empty and Home/MainBank baselines restored after restart"
    return True


try:
    driver.activate_app(PACKAGE)
    if matching_node("TOTAL BALANCE", "text") is None:
        tap("Home")
    wait_for("TOTAL BALANCE", "text")
    assert_home_balances()
    capture("01_home_before")
    RESULT["steps"].append("verified current Home and MainBank baseline")

    inbox_before = adb(
        "shell", "content", "query", "--uri", "content://sms/inbox", "--projection", "_id,body",
        "--where", f"address='{SENDER}'", timeout=10,
    )
    if MARKER in inbox_before:
        raise AssertionError("Refusing to run with a duplicate SMS marker")

    tap("Records")
    wait_for("All Records", "text")
    wait_for("No records found", "text")
    capture("02_records_before")
    RESULT["steps"].append("confirmed the Records view is empty before injection")
    save_result()

    RESULT["sms_injection_attempted"] = True
    save_result()
    adb("emu", "sms", "send", SENDER, BODY, timeout=10)
    RESULT["steps"].append({
        "missing-amount": "injected one amount-less bank alert",
        "declined": "injected one declined EGP 23.45 payment alert with the unchanged printed balance",
        "unknown-format": "injected one unknown-format service notice with no transaction amount or confirmation",
    }[args.scenario])
    save_result()
    time.sleep(5)

    if matching_node("Home", "content-desc") is not None:
        tap("Records")
    wait_for("All Records", "text")
    if matching_node(MARKER, "text") is not None:
        raise AssertionError(f"App created a Wallet record from a non-transaction SMS: {matching_node(MARKER, 'text').get('text', '')}")
    wait_for("No records found", "text")
    capture("03_records_after")
    RESULT["steps"].append({
        "missing-amount": "confirmed no financial record was created from the amount-less SMS",
        "declined": "confirmed no financial transaction was created from the declined payment",
        "unknown-format": "confirmed no financial record was fabricated from the unknown-format non-transaction notice",
    }[args.scenario])

    tap("Home")
    wait_for("TOTAL BALANCE", "text")
    assert_home_balances()
    capture("04_home_after")
    RESULT["steps"].append("confirmed Home and MainBank balances are unchanged")

    inbox_after = adb(
        "shell", "content", "query", "--uri", "content://sms/inbox", "--projection", "_id,body",
        "--where", f"address='{SENDER}'", timeout=10,
    )
    matching_rows = [line for line in inbox_after.splitlines() if MARKER in line]
    RESULT["inbox_row"] = matching_rows[0] if matching_rows else "not visible in provider query"
    RESULT["status"] = "PASS"
except Exception as error:
    RESULT["status"] = "FAIL" if RESULT["sms_injection_attempted"] else "INCONCLUSIVE"
    RESULT["error"] = str(error)
    if not RESULT["sms_injection_attempted"]:
        RESULT["steps"].append("no SMS was injected; failure occurred during session setup or preflight")
    else:
        try:
            if cleanup_exact_marker_record():
                RESULT["steps"].append("removed the exact unexpected marker row and verified baseline restoration")
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"cleanup/reconciliation failed: {type(cleanup_error).__name__}: {cleanup_error}"
finally:
    try:
        save_result()
    except OSError:
        pass
    try:
        driver.quit()
    except Exception:
        pass

print(json.dumps(RESULT, indent=2))
print(f"Evidence: {OUTPUT.resolve()}")
if RESULT["status"] != "PASS":
    raise SystemExit(1)
