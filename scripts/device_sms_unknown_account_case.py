import json
import argparse
import re
import secrets
import string
import subprocess
import time
from decimal import Decimal
from pathlib import Path
from xml.etree import ElementTree
from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy

PACKAGE = "com.example.wallettrackers"
SENDER = "5550001"
AMOUNT_TEXT = "-0.02 EGP"
CATEGORY = "Groceries"
MERCHANT = "Carrefour"
HOME_BALANCE = "15,999.83"
MAINBANK_BALANCE = "9999.83"
parser = argparse.ArgumentParser(description="Live unknown-account SMS and cleanup test")
parser.add_argument("--device", default="emulator-5554")
parser.add_argument("--server", default="http://127.0.0.1:4723")
parser.add_argument("--baseline-home", default="16000.00")
parser.add_argument("--baseline-mainbank", default="10000.00")
parser.add_argument("--amount", default="0.02", help="Test transaction amount in EGP")
parser.add_argument("--unknown-suffix", default="9998", help="Unmatched card/account suffix")
parser.add_argument("--ambiguous-account-name", action="append", default=[], help="Account name sharing the test suffix; fail if the transaction links to it")
parser.add_argument("--retain-unlinked-record", action="store_true", help="hold the verified unlinked row for an outer server-side assertion")
parser.add_argument("--cleanup-marker", help="delete one previously verified unlinked test row without injecting another SMS")
parser.add_argument("--verify-notification-navigation", action="store_true", help="Tap the matching Action Required alert and verify Records opens")
args = parser.parse_args()
HOME_BALANCE = f"{float(args.baseline_home):,.2f}"
MAINBANK_BALANCE = f"{float(args.baseline_mainbank):.2f}"
AMOUNT = Decimal(args.amount)
AMOUNT_TEXT = f"-{AMOUNT:.2f} EGP"
NOTIFICATION_TEXT = f"Groceries: {AMOUNT_TEXT}"
UNKNOWN_SUFFIX = args.unknown_suffix
MARKER = args.cleanup_marker or "UNLINKED" + "".join(secrets.choice(string.ascii_uppercase) for _ in range(10))
BODY = f"Your bank account ****{UNKNOWN_SUFFIX} was debited EGP {AMOUNT:.2f} at {MERCHANT} {MARKER}."
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / "live_sms_unknown_account_unlinked_record"
OUTPUT.mkdir(parents=True, exist_ok=True)
RESULT = {
    "case": "live_sms_unknown_account_unlinked_record",
    "marker": MARKER,
    "unknown_suffix": UNKNOWN_SUFFIX,
    "amount": f"{AMOUNT:.2f} EGP",
    "category": CATEGORY,
    "merchant": MERCHANT,
    "status": "IN_PROGRESS",
    "steps": [],
    "baseline_home_egp": HOME_BALANCE,
    "baseline_mainbank_egp": MAINBANK_BALANCE,
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
    "appium:noReset": True, "appium:newCommandTimeout": 120,
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


def system_tree():
    remote_path = "/sdcard/action-required-shade.xml"
    adb("shell", "rm", "-f", remote_path, timeout=10)
    output = adb("shell", "uiautomator", "dump", "--compressed", remote_path, timeout=10)
    if "dumped to:" not in output:
        raise RuntimeError(f"Could not capture system notification hierarchy: {output.strip()}")
    return ElementTree.fromstring(adb("shell", "cat", remote_path, timeout=10))


def reconnect_appium():
    global driver
    driver = webdriver.Remote(args.server.rstrip("/"), options=options)
    driver.implicitly_wait(0)


def close_appium():
    global driver
    if driver is not None:
        driver.quit()
        driver = None


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


def capture_system(name, current_root):
    (OUTPUT / f"{name}.png").write_bytes(
        subprocess.check_output(["adb", "-s", args.device, "exec-out", "screencap", "-p"], timeout=10)
    )
    (OUTPUT / f"{name}.xml").write_bytes(ElementTree.tostring(current_root, encoding="utf-8"))


def assert_home_balances():
    values = [node.get("text", "").strip() for node in nodes()]
    has_mainbank_balance = lambda: (
        any(node.get("text", "").strip() == "MainBank" for node in nodes())
        and any(node.get("text", "").strip().replace(",", "") == MAINBANK_BALANCE.replace(",", "")
                for node in nodes())
    )
    if not has_mainbank_balance():
        dimensions = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
        width, height = map(int, dimensions.groups()) if dimensions else (1440, 3120)
        heading = find("Accounts")
        swipe_y = int(height * 0.70)
        if heading is not None:
            swipe_y = min(bounds(heading)[3] + 250, int(height * 0.82))
        for start_fraction, end_fraction in ((0.68, 0.42), (0.42, 0.68)):
            for _ in range(16):
                if has_mainbank_balance():
                    break
                adb("shell", "input", "swipe", str(int(width * start_fraction)), str(swipe_y),
                    str(int(width * end_fraction)), str(swipe_y), "500")
                time.sleep(0.2)
            if has_mainbank_balance():
                break
    root = tree()
    values = [node.get("text", "").strip() for node in nodes(root)]
    numeric_values = []
    for value in values:
        try:
            numeric_values.append(float(value.replace(",", "")))
        except ValueError:
            continue
    expected_home = float(HOME_BALANCE.replace(",", ""))
    expected_mainbank = float(MAINBANK_BALANCE.replace(",", ""))
    if ("TOTAL BALANCE" not in values
            or not any(abs(value - expected_home) < 0.005 for value in numeric_values)
            or not any(abs(value - expected_mainbank) < 0.005 for value in numeric_values)):
        raise AssertionError(f"Unexpected dashboard/account balances: {values}")
    return root


def ensure_home():
    for _ in range(5):
        if find("TOTAL BALANCE") is not None:
            return
        adb("shell", "input", "keyevent", "KEYCODE_BACK", timeout=10)
        time.sleep(0.5)
    if find("TOTAL BALANCE") is None:
        raise AssertionError(f"Could not return to Home for preflight; visible={[node.get('text', '') for node in nodes()]}")


def find_record_amount():
    current = nodes()
    amount_nodes = [node for node in current if node.get("text", "").strip() == AMOUNT_TEXT]
    for amount_node in amount_nodes:
        _, amount_top, _, amount_bottom = bounds(amount_node)
        amount_y = (amount_top + amount_bottom) // 2
        nearby = [
            node.get("text", "").strip()
            for node in current
            if node.get("text", "").strip()
            and len(bounds(node)) == 4
            and abs(sum(bounds(node)[1::2]) // 2 - amount_y) <= 260
        ]
        if CATEGORY in nearby and any(MERCHANT in text for text in nearby):
            return amount_node, nearby
    return None, []


def delete_exact_unlinked_record():
    record_node, nearby = find_record_amount()
    if record_node is None:
        raise AssertionError("Could not locate the unique tagged unlinked record for cleanup")
    left, top, right, bottom = bounds(record_node)
    x, y = (left + right) // 2, (top + bottom) // 2
    adb("shell", "input", "swipe", str(x), str(y), str(x + 4), str(y + 4), "1000")
    tap("Delete", "text")
    wait("Delete Record", "text")
    delete_nodes = [node for node in nodes() if node.get("text", "").strip() == "Delete"]
    left, top, right, bottom = bounds(delete_nodes[-1])
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(4)
    RESULT["cleanup"] = "unlinked test record deleted; verifying after app restart"
    save_result()


def tap_action_required_notification():
    global driver
    close_appium()
    adb("shell", "cmd", "statusbar", "expand-notifications", timeout=10)
    root = system_tree()
    parents = {child: parent for parent in root.iter() for child in parent}

    def matching_title(current_root, parent_map):
        for candidate in nodes(current_root):
            if candidate.get("text", "").strip() != "Action Required: Match Account":
                continue
            row = candidate
            while row in parent_map and row.get("resource-id") != "com.android.systemui:id/expandableNotificationRow":
                row = parent_map[row]
            if any(NOTIFICATION_TEXT in node.get("text", "") for node in row.iter()):
                return candidate
        return None

    title = matching_title(root, parents)
    if title is None:
        raise AssertionError(f"Matching Action Required notification was not visible in the shade: {NOTIFICATION_TEXT}")
    capture_system("05_action_required_notification_shade", root)
    row = title
    while row in parents and row.get("resource-id") != "com.android.systemui:id/expandableNotificationRow":
        row = parents[row]
    outer_row = parents.get(row)
    expand_button = next((
        node for node in outer_row.iter()
        if node.get("resource-id") == "android:id/expand_button"
    ), None) if outer_row is not None else None
    if expand_button is not None:
        left, top, right, bottom = bounds(expand_button)
        adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2), timeout=10)
        time.sleep(0.5)
        root = system_tree()
        parents = {child: parent for parent in root.iter() for child in parent}
        title = matching_title(root, parents)
        if title is None:
            raise AssertionError("Matching Action Required alert disappeared when its group expanded")
        capture_system("05_action_required_group_expanded", root)
    parents = {child: parent for parent in root.iter() for child in parent}
    click_target = title
    while click_target in parents and click_target.get("clickable") != "true":
        click_target = parents[click_target]
    left, top, right, bottom = bounds(click_target)
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2), timeout=10)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        try:
            current_root = system_tree()
            if any(node.get("text", "").strip() == "All Records" for node in nodes(current_root)):
                capture_system("06_action_required_opened_records", current_root)
                RESULT["notification_navigation"] = {"title": "Action Required: Match Account", "body": NOTIFICATION_TEXT}
                RESULT["steps"].append("expanded the grouped WalletTrackers alerts, tapped the exact Action Required notification, and verified All Records opened")
                save_result()
                reconnect_appium()
                return
        except Exception:
            pass
        time.sleep(0.3)
    reconnect_appium()
    raise AssertionError("Tapping the exact Action Required notification did not open All Records within 10 seconds")


try:
    driver.activate_app(PACKAGE)
    ensure_home()
    wait("TOTAL BALANCE", "text")
    assert_home_balances()
    capture("01_home_before")
    RESULT["steps"].append("verified baseline Home and MainBank values")

    tap("Records")
    wait("All Records", "text")
    if args.cleanup_marker:
        record_node, nearby = find_record_amount()
        if record_node is None or MARKER not in " ".join(nearby):
            raise AssertionError(f"Could not find exact retained unlinked row {MARKER}: {nearby}")
        delete_exact_unlinked_record()
        adb("shell", "am", "force-stop", PACKAGE, timeout=10)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", timeout=10)
        time.sleep(3)
        if find("TOTAL BALANCE") is None:
            tap("Home")
        assert_home_balances()
        tap("Records")
        wait("All Records", "text")
        wait("No records found", "text")
        RESULT["status"] = "PASS"
        RESULT["cleanup"] = "exact retained ambiguous-suffix row deleted; balances and empty Records verified after restart"
        RESULT["steps"].append("cleanup mode deleted only the marker-owned unlinked row and verified its absence and unchanged balances after restart")
        raise SystemExit(0)
    wait("No records found", "text")
    capture("02_records_before")
    RESULT["steps"].append("verified the ledger was empty before this test")
    save_result()

    RESULT["sms_injection_attempted"] = True
    save_result()
    adb("emu", "sms", "send", SENDER, BODY, timeout=10)
    RESULT["steps"].append(f"injected EGP {AMOUNT:.2f} debit SMS for unknown suffix {UNKNOWN_SUFFIX}")
    save_result()
    time.sleep(5)

    deadline = time.monotonic() + 10
    alert_dump = ""
    while time.monotonic() < deadline:
        alert_dump = adb("shell", "dumpsys", "notification", "--noredact", timeout=10)
        if MARKER in alert_dump and "Action Required: Match Account" in alert_dump and NOTIFICATION_TEXT in alert_dump:
            break
        time.sleep(0.5)
    else:
        raise AssertionError("No Action Required notification appeared for the unknown account")
    RESULT["notification"] = f"Action Required: Match Account / {NOTIFICATION_TEXT}"

    deadline = time.monotonic() + 10
    record_node = None
    nearby = []
    while time.monotonic() < deadline:
        record_node, nearby = find_record_amount()
        if record_node is not None:
            break
        time.sleep(0.5)
    if record_node is None:
        raise AssertionError(f"Expected an unlinked Groceries/Carrefour EGP 0.02 record; saw {nearby}")
    linked_accounts = ("MainBank", "SecondBank", "USDBank", "EURBank", "TestCard", "CashWallet", "GoldWallet", *args.ambiguous_account_name)
    if any(account in item for item in nearby for account in linked_accounts):
        raise AssertionError(f"Unknown suffix was linked to an account: {nearby}")
    RESULT["record_context"] = nearby
    capture("03_unlinked_record")
    RESULT["steps"].append("verified one Groceries/Carrefour record with no matched account")

    tap("Home")
    banner = wait("need account assignment", "text", timeout=10)
    RESULT["unlinked_banner"] = banner.get("text", "")
    deadline = time.monotonic() + 10
    alert_dump = ""
    while time.monotonic() < deadline:
        alert_dump = adb("shell", "dumpsys", "notification", "--noredact", timeout=10)
        if "Action Required: Match Account" in alert_dump and NOTIFICATION_TEXT in alert_dump:
            break
        time.sleep(0.5)
    else:
        raise AssertionError("Expected Action Required notification title/body was not active in Android notification service")
    RESULT["steps"].append("verified Home unlinked-record banner and Action Required notification")

    assert_home_balances()
    RESULT["steps"].append("verified unknown transaction did not change Home/MainBank balances")
    capture("04_home_with_unlinked_alert")

    if args.retain_unlinked_record:
        RESULT["status"] = "PASS"
        RESULT["cleanup"] = "unlinked row intentionally retained for independent Firestore inspection"
        RESULT["steps"].append("retained the verified unlinked row for independent server-side inspection before cleanup")
        save_result()
        raise SystemExit(0)

    if args.verify_notification_navigation:
        tap_action_required_notification()

    tap("Records")
    wait("All Records", "text")
    delete_exact_unlinked_record()
    adb("shell", "am", "force-stop", PACKAGE, timeout=10)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", timeout=10)
    time.sleep(3)
    if find("TOTAL BALANCE") is None:
        tap("Home")
    wait("TOTAL BALANCE", "text")
    assert_home_balances()
    tap("Records")
    wait("All Records", "text")
    wait("No records found", "text")
    RESULT["steps"].append("confirmed the unlinked test record stayed deleted after restart")
    RESULT["cleanup"] = "test record deleted; Home/MainBank unchanged after restart; synthetic Inbox row may remain"

    inbox = adb("shell", "content", "query", "--uri", "content://sms/inbox", "--projection", "_id,body",
                "--where", f"address='{SENDER}'", timeout=10)
    RESULT["inbox_row"] = next((line for line in inbox.splitlines() if MARKER in line), "not visible")
    RESULT["status"] = "PASS"
except Exception as error:
    RESULT["status"] = "FAIL" if RESULT["sms_injection_attempted"] else "INCONCLUSIVE"
    RESULT["error"] = str(error)
    if not RESULT["sms_injection_attempted"]:
        RESULT["steps"].append("no SMS was injected; failure occurred during session setup or preflight")
    injected = any("debit SMS for unknown suffix" in step for step in RESULT["steps"])
    if injected and RESULT.get("cleanup") == "not_started":
        try:
            adb("shell", "cmd", "statusbar", "collapse", timeout=10)
            if driver is None:
                reconnect_appium()
            if find("TOTAL BALANCE") is None:
                tap("Records")
            if find("All Records") is None:
                tap("Records")
            record_node, _ = find_record_amount()
            if record_node is not None:
                delete_exact_unlinked_record()
                RESULT["cleanup"] = "matching test record deleted after failure"
            else:
                RESULT["cleanup"] = "no matching unlinked test record found during failure cleanup"
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"REQUIRES MANUAL CLEANUP: {cleanup_error}"
    elif RESULT.get("cleanup") == "unlinked test record deleted; verifying after app restart":
        RESULT["cleanup"] += "; verify the exact balance snapshot and Records screen before continuing"
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
