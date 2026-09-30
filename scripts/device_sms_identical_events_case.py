import json
import argparse
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
HOME_BEFORE = "15,999.83"
ACCOUNT_BEFORE = "9999.83"
HOME_AFTER = "15,999.79"
ACCOUNT_AFTER = "9999.79"
AMOUNT = "-0.02 EGP"
CATEGORY = "Groceries"
ACCOUNT = "MainBank"
MERCHANT = "Carrefour"
parser = argparse.ArgumentParser(description="Live identical-body distinct SMS event test")
parser.add_argument("--device", default="emulator-5554")
parser.add_argument("--server", default="http://127.0.0.1:4723")
parser.add_argument("--home-baseline", default="16000.00")
parser.add_argument("--account-baseline", default="10000.00")
parser.add_argument("--duplicate-identity", action="store_true", help="Replay the exact same SMS identity immediately and expect one effect")
args = parser.parse_args()
HOME_BEFORE = f"{float(args.home_baseline):,.2f}"
ACCOUNT_BEFORE = f"{float(args.account_baseline):.2f}"
event_count = 1 if args.duplicate_identity else 2
HOME_AFTER = f"{float(args.home_baseline) - 0.02 * event_count:,.2f}"
ACCOUNT_AFTER = f"{float(args.account_baseline) - 0.02 * event_count:.2f}"
AMOUNT = "-0.02 EGP"
MARKER = "SAMETRANSACTION" + "".join(secrets.choice(string.ascii_uppercase) for _ in range(8))
BODY = f"Your bank account ****1111 was debited EGP 0.02 at {MERCHANT} {MARKER}."
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / "live_sms_identical_body_distinct_events"
OUTPUT.mkdir(parents=True, exist_ok=True)
RESULT = {
    "case": "live_sms_identical_body_distinct_events",
    "marker": MARKER,
    "amount_each": "0.02 EGP",
    "identical_body": BODY,
    "status": "IN_PROGRESS",
    "steps": [],
    "baseline_home_egp": HOME_BEFORE,
    "baseline_mainbank_egp": ACCOUNT_BEFORE,
    "cleanup": "not_started",
    "sms_injection_attempted": False,
    "device": args.device,
    "appium_server": args.server,
}


def adb(*command, timeout=10):
    return subprocess.check_output(["adb", "-s", args.device, *command], timeout=timeout).decode(
        "utf-8", errors="replace"
    )


def save_result():
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")


options = UiAutomator2Options().load_capabilities({
    "platformName": "Android", "appium:automationName": "UiAutomator2",
    "appium:deviceName": args.device, "appium:udid": args.device,
    "appium:appPackage": PACKAGE, "appium:appActivity": ".MainActivity",
    "appium:noReset": True, "appium:newCommandTimeout": 10,
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


def nodes(root=None):
    root = root if root is not None else tree()
    return [node for node in root.iter() if node is not root and node.attrib]


def find(value, attribute="text", root=None):
    return next((node for node in nodes(root) if value in node.get(attribute, "")), None)


def wait(value, attribute="text", timeout=10, present=True):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        node = find(value, attribute)
        if (node is not None) == present:
            return node
        time.sleep(0.5)
    raise AssertionError(f"Timed out waiting for {attribute} {value!r} present={present}")


def bounds(node):
    return list(map(int, re.findall(r"\d+", node.get("bounds", ""))))


def center(node):
    left, top, right, bottom = bounds(node)
    return (left + right) // 2, (top + bottom) // 2


def tap(value, attribute="content-desc"):
    if attribute == "content-desc":
        elements = driver.find_elements(AppiumBy.ACCESSIBILITY_ID, value)
    else:
        escaped = json.dumps(value)
        elements = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, f"new UiSelector().textContains({escaped})")
    if not elements:
        wait(value, attribute)
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


def matching_test_rows():
    current = nodes()
    candidates = [node for node in current if node.get("text", "").strip() == AMOUNT]
    matches = []
    for candidate in candidates:
        candidate_bounds = bounds(candidate)
        if len(candidate_bounds) != 4:
            continue
        amount_y = (candidate_bounds[1] + candidate_bounds[3]) // 2
        nearby = []
        for node in current:
            text = node.get("text", "").strip()
            node_bounds = bounds(node)
            if not text or len(node_bounds) != 4:
                continue
            node_y = (node_bounds[1] + node_bounds[3]) // 2
            if abs(node_y - amount_y) <= 110:
                nearby.append(text)
        if any(MARKER in text for text in nearby):
            matches.append((candidate, nearby))
    unique = {}
    for candidate, nearby in matches:
        unique[tuple(bounds(candidate))] = (candidate, nearby)
    return list(unique.values())


def assert_home(home, account):
    current_nodes = nodes()
    has_account_balance = lambda: (
        any(node.get("text", "").strip() == "MainBank" for node in nodes())
        and any(node.get("text", "").strip().replace(",", "") == account.replace(",", "")
                for node in nodes())
    )
    if not has_account_balance():
        dimensions = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
        width, height = map(int, dimensions.groups()) if dimensions else (1440, 3120)
        heading = next((node for node in current_nodes if node.get("text", "").strip() == "Accounts"), None)
        swipe_y = int(height * 0.70)
        if heading is not None:
            swipe_y = min(bounds(heading)[3] + 250, int(height * 0.82))
        for start_fraction, end_fraction in ((0.68, 0.42), (0.42, 0.68)):
            for _ in range(16):
                if has_account_balance():
                    break
                adb("shell", "input", "swipe", str(int(width * start_fraction)), str(swipe_y),
                    str(int(width * end_fraction)), str(swipe_y), "500")
                time.sleep(0.2)
            if has_account_balance():
                break
    values = [node.get("text", "").strip() for node in nodes()]
    numeric_values = []
    for value in values:
        cleaned = value.replace(",", "")
        try:
            numeric_values.append(float(cleaned))
        except ValueError:
            continue
    expected_home = float(home.replace(",", ""))
    expected_account = float(account.replace(",", ""))
    if ("TOTAL BALANCE" not in values
            or not any(abs(value - expected_home) < 0.005 for value in numeric_values)
            or not any(abs(value - expected_account) < 0.005 for value in numeric_values)):
        raise AssertionError(f"Unexpected Home/account values; expected {home}/{account}: {values}")


def ensure_records_screen():
    if find("All Records") is None:
        tap("Records")
    wait("All Records", "text")


def delete_one_test_record():
    ensure_records_screen()
    rows = matching_test_rows()
    if not rows:
        return False
    x, y = center(rows[0][0])
    adb("shell", "input", "swipe", str(x), str(y), str(x + 4), str(y + 4), "1000")
    tap("Delete", "text")
    wait("Delete Record", "text")
    buttons = [node for node in nodes() if node.get("text", "").strip() == "Delete"]
    x, y = center(buttons[-1])
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(4)
    return True


def cleanup_matching_records():
    deleted = 0
    while True:
        if not delete_one_test_record():
            break
        deleted += 1
    RESULT["cleanup"] = f"deleted {deleted} matching test record(s)"
    return deleted


def visible_sms_rows(marker):
    query = adb("shell", "content", "query", "--uri", "content://sms/inbox", "--projection", "_id,body",
                "--where", f"address='{SENDER}'", timeout=10)
    return [line for line in query.splitlines() if marker in line]


try:
    driver.activate_app(PACKAGE)
    if find("TOTAL BALANCE") is None:
        tap("Home")
    wait("TOTAL BALANCE", "text")
    assert_home(HOME_BEFORE, ACCOUNT_BEFORE)
    capture("01_home_before")
    RESULT["steps"].append("verified the current pre-case Home and MainBank balance")

    tap("Records")
    wait("All Records", "text")
    wait("No records found", "text")
    capture("02_records_before")
    RESULT["steps"].append("verified Records was empty before injecting identical messages")

    if visible_sms_rows(MARKER):
        raise AssertionError("Refusing to inject a duplicate SMS body from an earlier run")
    RESULT["sms_injection_attempted"] = True
    save_result()
    adb("emu", "sms", "send", SENDER, BODY, timeout=10)
    if not args.duplicate_identity:
        time.sleep(1)
    adb("emu", "sms", "send", SENDER, BODY, timeout=10)
    RESULT["steps"].append(
        "immediately replayed the exact same sender/body to test duplicate identity suppression"
        if args.duplicate_identity else
        "sent the exact same sender and SMS body twice as separate Inbox events"
    )
    save_result()

    tap("Home")
    wait("TOTAL BALANCE", "text")
    tap("Records")
    wait("All Records", "text")

    deadline = time.monotonic() + 10
    rows = []
    while time.monotonic() < deadline:
        rows = matching_test_rows()
        if len(rows) >= event_count:
            break
        time.sleep(0.5)
    if len(rows) != event_count:
        raise AssertionError(f"Expected {event_count} matching record(s), found {len(rows)}")
    for _, nearby in rows:
        if CATEGORY not in nearby or not any(MERCHANT in text for text in nearby):
            raise AssertionError(f"Incorrect category/merchant for repeated SMS: {nearby}")
    RESULT["duplicate_warning"] = any(
        "Possible duplicate charge" in text for _, nearby in rows for text in nearby
    )
    RESULT["record_count"] = len(rows)
    RESULT["steps"].append(
        "verified one Groceries/Carrefour record and one financial effect for the replayed identity"
        if args.duplicate_identity else
        "verified two distinct Groceries/Carrefour records for identical SMS bodies"
    )
    if RESULT["duplicate_warning"]:
        RESULT["steps"].append("observed the app's non-blocking Possible duplicate charge anomaly label")
    capture("03_two_records")

    tap("Home")
    wait("TOTAL BALANCE", "text")
    assert_home(HOME_AFTER, ACCOUNT_AFTER)
    RESULT["after_two_events_home_egp"] = HOME_AFTER
    RESULT["after_two_events_mainbank_egp"] = ACCOUNT_AFTER
    RESULT["steps"].append(f"verified {event_count} event(s) changed MainBank/Home by exactly EGP {0.02 * event_count:.2f}")
    capture("04_after_two_events")

    tap("Records")
    cleanup_matching_records()
    driver.terminate_app(PACKAGE)
    driver.activate_app(PACKAGE)
    time.sleep(5)
    if find("TOTAL BALANCE") is None:
        tap("Home")
    wait("TOTAL BALANCE", "text")
    assert_home(HOME_BEFORE, ACCOUNT_BEFORE)
    tap("Records")
    wait("All Records", "text")
    wait("No records found", "text")
    RESULT["steps"].append("confirmed both records remained deleted and exact balances restored after restart")
    RESULT["cleanup"] = f"{event_count} matching test record(s) deleted; Home and MainBank restored after restart"
    RESULT["inbox_rows"] = visible_sms_rows(MARKER)
    RESULT["status"] = "PASS"
except Exception as error:
    RESULT["status"] = "FAIL" if RESULT["sms_injection_attempted"] else "INCONCLUSIVE"
    RESULT["error"] = str(error)
    if not RESULT["sms_injection_attempted"]:
        RESULT["steps"].append("no SMS was injected; failure occurred during session setup or preflight")
    if RESULT["sms_injection_attempted"]:
        try:
            deleted = cleanup_matching_records()
            RESULT["cleanup"] = f"failure cleanup deleted {deleted} matching test record(s)"
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"REQUIRES MANUAL CLEANUP: {cleanup_error}"
finally:
    save_result()
    try:
        driver.quit()
    except Exception:
        pass

print(json.dumps(RESULT, indent=2))
print(f"Evidence: {OUTPUT.resolve()}")
if RESULT["status"] != "PASS":
    raise SystemExit(1)
