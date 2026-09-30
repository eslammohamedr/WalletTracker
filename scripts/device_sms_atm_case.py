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

parser = argparse.ArgumentParser(description="Real-app ATM SMS transfer and rollback test")
parser.add_argument("--device", default="emulator-5554", help="ADB serial / Appium device ID")
parser.add_argument("--server", default="http://127.0.0.1:4723", help="Appium server URL")
parser.add_argument("--without-printed-balance", action="store_true", help="Test ATM SMS without an available-balance field")
parser.add_argument("--home-baseline", default="16000.00")
parser.add_argument("--source-baseline", default="10000.00")
parser.add_argument("--cash-baseline", default="1000.00")
args = parser.parse_args()


PACKAGE = "com.example.wallettrackers"
SENDER = "5550001"
AMOUNT = Decimal("10.00")
HOME_BASELINE = Decimal(args.home_baseline)
SOURCE_BASELINE = Decimal(args.source_baseline)
CASH_BASELINE = Decimal(args.cash_baseline)
SPENT_BASELINE = Decimal("0.00")
MARKER = "ATMTEST" + "".join(secrets.choice(string.ascii_uppercase) for _ in range(10))
BODY = (
    f"Your bank account ****1111 cash withdrawal EGP {AMOUNT:.2f} at ATM. "
    f"{'' if args.without_printed_balance else f'Available balance EGP {SOURCE_BASELINE - AMOUNT:.2f}. '}"
    f"{MARKER}"
)
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / "live_sms_atm_withdrawal"
OUTPUT.mkdir(parents=True, exist_ok=True)
RESULT = {
    "case": "live_sms_atm_withdrawal",
    "marker": MARKER,
    "body": BODY,
    "printed_balance_included": not args.without_printed_balance,
    "amount": f"{AMOUNT:.2f} EGP",
    "status": "IN_PROGRESS",
    "steps": [],
    "cleanup": "not_started",
    "home_baseline_egp": f"{HOME_BASELINE:.2f}",
    "source_baseline_egp": f"{SOURCE_BASELINE:.2f}",
    "cash_baseline_egp": f"{CASH_BASELINE:.2f}",
}
RESULT["device"] = args.device
RESULT["appium_server"] = args.server
RECORD_MAY_EXIST = False
DELETE_ATTEMPTED = False

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
    RESULT["status"] = "FAIL"
    RESULT["error"] = f"Appium session startup failed: {type(error).__name__}: {error}"
    RESULT["cleanup"] = "not_started; no SMS was injected"
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")
    print(json.dumps(RESULT, indent=2))
    print(f"Evidence: {OUTPUT.resolve()}")
    raise SystemExit(1)


def adb(*command, timeout=10):
    return subprocess.check_output(["adb", "-s", args.device, *command], timeout=timeout).decode(
        "utf-8", errors="replace"
    )


def save_result():
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")


def tree():
    deadline = time.monotonic() + 10
    last_error = None
    while time.monotonic() < deadline:
        try:
            return ElementTree.fromstring(driver.page_source)
        except Exception as error:
            last_error = str(error)
            time.sleep(min(0.3, max(0, deadline - time.monotonic())))
    raise RuntimeError(f"Could not get UI hierarchy within 10 seconds: {last_error}")


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
    raise AssertionError(f"Timed out waiting for {attribute} {value!r}, present={present}")


def bounds(node):
    return list(map(int, re.findall(r"\d+", node.get("bounds", ""))))


def center(node):
    left, top, right, bottom = bounds(node)
    return (left + right) // 2, (top + bottom) // 2


def tap(value, attribute="content-desc"):
    if attribute == "content-desc":
        matches = driver.find_elements(AppiumBy.ACCESSIBILITY_ID, value)
    else:
        escaped = json.dumps(value)
        matches = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, f"new UiSelector().textContains({escaped})")
    if not matches:
        wait(value, attribute)
        if attribute == "content-desc":
            matches = driver.find_elements(AppiumBy.ACCESSIBILITY_ID, value)
        else:
            escaped = json.dumps(value)
            matches = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, f"new UiSelector().textContains({escaped})")
    if not matches:
        raise AssertionError(f"Could not tap {attribute} {value!r}")
    matches[-1].click()


def capture(name):
    driver.save_screenshot(str(OUTPUT / f"{name}.png"))
    (OUTPUT / f"{name}.xml").write_text(driver.page_source, encoding="utf-8")


def open_home():
    if find("TOTAL BALANCE") is None:
        home = find("Home", "content-desc")
        if home is not None:
            tap("Home", "content-desc")
        else:
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
    wait("TOTAL BALANCE", "text")


def account_balance_visible(root, name, expected):
    amount = Decimal(str(expected))
    account_nodes = [node for node in nodes(root) if node.get("text", "").strip() == name]
    for account_node in account_nodes:
        account_box = bounds(account_node)
        if len(account_box) != 4:
            continue
        for value_node in nodes(root):
            value_box = bounds(value_node)
            if len(value_box) != 4:
                continue
            if value_box[1] < account_box[3] - 8 or value_box[1] > account_box[3] + 140:
                continue
            if value_box[2] < account_box[0] or value_box[0] > account_box[2]:
                continue
            value = value_node.get("text", "").replace(",", "").replace("EGP", "").strip()
            try:
                if Decimal(value) == amount:
                    return True
            except Exception:
                continue
    return False


def scan_balance(name, expected):
    root = tree()
    if account_balance_visible(root, name, expected):
        return True
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    width, height = map(int, size.groups()) if size else (1440, 3120)
    y = int(height * 0.70)
    traces = []
    for start, end in ((0.68, 0.42), (0.42, 0.68)):
        previous = None
        unchanged = 0
        for _ in range(18):
            root = tree()
            visible = [node.get("text", "").strip() for node in nodes(root) if node.get("text", "").strip()]
            traces.append(visible)
            if account_balance_visible(root, name, expected):
                RESULT.setdefault("account_scans", []).append({"account": name, "pages": traces})
                save_result()
                return True
            page = tuple(text for text in visible if text in {"MainBank", "SecondBank", "TestCard", "CashWallet", "USDBank", "EURBank", "GoldWallet"})
            unchanged = unchanged + 1 if page == previous else 0
            if unchanged >= 2:
                break
            previous = page
            adb("shell", "input", "swipe", str(int(width * start)), str(y), str(int(width * end)), str(y), "350")
            time.sleep(0.25)
    RESULT.setdefault("account_scans", []).append({"account": name, "pages": traces, "matched": False})
    save_result()
    return False


def assert_home_total(expected):
    open_home()
    expected_text = f"{Decimal(str(expected)):,.2f}"
    if find(expected_text) is None:
        raise AssertionError(f"Expected Home total {expected_text}")


def assert_home_spent(expected):
    open_home()
    current = nodes()
    labels = [node for node in current if node.get("text", "").strip() == "SPENT"]
    for label in labels:
        label_box = bounds(label)
        for value_node in current:
            value_box = bounds(value_node)
            if len(value_box) != 4 or value_box[1] < label_box[3] or value_box[1] > label_box[3] + 160:
                continue
            if value_box[2] < label_box[0] or value_box[0] > label_box[2]:
                continue
            value = value_node.get("text", "").replace(",", "").replace("EGP", "").strip()
            try:
                if Decimal(value) == Decimal(str(expected)):
                    return
            except Exception:
                continue
    raise AssertionError(f"Home spending did not remain EGP {Decimal(str(expected)):.2f}")


def atm_record_row():
    current = nodes()
    amount_nodes = [node for node in current if node.get("text", "").strip() == f"-{AMOUNT:.2f} EGP"]
    for amount_node in amount_nodes:
        amount_box = bounds(amount_node)
        if len(amount_box) != 4:
            continue
        center_y = (amount_box[1] + amount_box[3]) // 2
        nearby = []
        for node in current:
            node_box = bounds(node)
            text = node.get("text", "").strip()
            if text and len(node_box) == 4 and abs((node_box[1] + node_box[3]) // 2 - center_y) <= 120:
                nearby.append(text)
        if "Transfer" in nearby and any("MainBank -> Cash" in text for text in nearby):
            return amount_node
    return None


def delete_atm_record():
    open_home()
    if find("All Records") is None:
        tap("Records")
    wait("All Records")
    row = atm_record_row()
    if row is None:
        return False
    x, y = center(row)
    adb("shell", "input", "swipe", str(x), str(y), str(x + 4), str(y + 4), "1000")
    tap("Delete", "text")
    wait("Delete Record", "text")
    buttons = [node for node in nodes() if node.get("text", "").strip() == "Delete"]
    x, y = center(buttons[-1])
    global DELETE_ATTEMPTED
    DELETE_ATTEMPTED = True
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(3)
    return True


def verify_baseline():
    open_home()
    assert_home_total(HOME_BASELINE)
    if not scan_balance("MainBank", SOURCE_BASELINE) or not scan_balance("CashWallet", CASH_BASELINE):
        raise AssertionError("MainBank/Cash did not return to the exact case baseline")


try:
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    driver.activate_app(PACKAGE)
    open_home()
    verify_baseline()
    assert_home_spent(SPENT_BASELINE)
    capture("01_home_before")
    tap("Records")
    wait("All Records")
    if find("No records found") is None:
        raise AssertionError("Records must be empty before ATM case to avoid ambiguous cleanup")
    save_result()

    adb("emu", "sms", "send", SENDER, BODY)
    RECORD_MAY_EXIST = True
    RESULT["steps"].append("injected ATM withdrawal SMS for EGP 10.00 from MainBank ****1111")
    save_result()
    time.sleep(5)
    open_home()
    tap("Records")
    wait("All Records")
    wait("Transfer")
    row = atm_record_row()
    if row is None:
        raise AssertionError("Expected one -EGP 10 Transfer record linked MainBank -> Cash")
    RESULT["steps"].append("verified one Transfer record linked MainBank -> Cash for -EGP 10.00")
    capture("02_atm_record")

    open_home()
    if not scan_balance("MainBank", SOURCE_BASELINE - AMOUNT):
        raise AssertionError("MainBank did not decrease by EGP 10.00")
    if not scan_balance("CashWallet", CASH_BASELINE + AMOUNT):
        raise AssertionError("CashWallet did not increase by EGP 10.00")
    assert_home_total(HOME_BASELINE)
    assert_home_spent(SPENT_BASELINE)
    RESULT["steps"].append("verified Home spending stayed at EGP 0.00")
    RESULT["steps"].append("verified MainBank -EGP 10, CashWallet +EGP 10, and unchanged Home total")
    RESULT["source_after_egp"] = f"{SOURCE_BASELINE - AMOUNT:.2f}"
    RESULT["cash_after_egp"] = f"{CASH_BASELINE + AMOUNT:.2f}"
    RESULT["home_after_egp"] = f"{HOME_BASELINE:.2f}"

    notification_dump = adb("shell", "dumpsys", "notification", "--noredact")
    (OUTPUT / "notifications.txt").write_text(notification_dump, encoding="utf-8")
    if "ATM Withdrawal Tracked" not in notification_dump or "added to Cash" not in notification_dump:
        raise AssertionError("ATM notification did not confirm source debit and cash addition")
    RESULT["steps"].append("verified ATM Withdrawal Tracked notification mentions Cash")
    save_result()

    if not delete_atm_record():
        raise AssertionError("Could not locate the uniquely matched ATM test record for cleanup")
    driver.terminate_app(PACKAGE)
    driver.activate_app(PACKAGE)
    time.sleep(4)
    verify_baseline()
    open_home()
    tap("Records")
    wait("All Records")
    if atm_record_row() is not None:
        raise AssertionError("ATM record reappeared after deletion and restart")
    RESULT["steps"].append("deleted ATM test record and verified exact account/Home rollback after restart")
    RESULT["cleanup"] = "ATM test record deleted; Home/MainBank/Cash restored after restart; Inbox event remains"
    RESULT["status"] = "PASS"
except Exception as error:
    RESULT["status"] = "FAIL"
    RESULT["error"] = str(error)
    if RECORD_MAY_EXIST and not DELETE_ATTEMPTED:
        try:
            delete_atm_record()
            driver.terminate_app(PACKAGE)
            driver.activate_app(PACKAGE)
            time.sleep(4)
            verify_baseline()
            RESULT["cleanup"] = "failure cleanup deleted ATM row and restored the case baseline"
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"REQUIRES MANUAL CLEANUP: {cleanup_error}"
    save_result()

try:
    driver.quit()
except Exception as error:
    if RESULT["status"] == "PASS":
        RESULT["status"] = "FAIL"
        RESULT["error"] = f"Appium session shutdown failed: {type(error).__name__}: {error}"
save_result()
print(json.dumps(RESULT, indent=2))
print(f"Evidence: {OUTPUT.resolve()}")
if RESULT["status"] != "PASS":
    raise SystemExit(1)
