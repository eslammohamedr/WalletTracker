"""Create, reconcile, and delete a manual income record in the live app."""

import json
import argparse
import base64
import csv
import io
import re
import subprocess
import time
import uuid
from decimal import Decimal
from pathlib import Path
from xml.etree import ElementTree

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy


DEVICE = "emulator-5556"
PACKAGE = "com.example.wallettrackers"
ACCOUNT = "MainBank"
parser = argparse.ArgumentParser(description="Create, reconcile, and roll back one manual Wallet record.")
parser.add_argument("--device", default="emulator-5556", help="ADB serial / Appium device ID")
parser.add_argument("--account", default="MainBank", help="Existing account to use for the transaction")
parser.add_argument("--currency", default="EGP", help="Currency of the selected account and transaction")
parser.add_argument("--type", choices=("Income", "Expense"), default="Income")
parser.add_argument("--category", default="Salary")
parser.add_argument("--category-parent", help="Parent category group when selecting a custom expense subcategory")
parser.add_argument("--amount", default="0.23")
parser.add_argument("--edit-to-amount", help="Edit the created record to this amount, then verify recalculation and rollback.")
parser.add_argument("--edit-to-category", help="Edit the created record to this category, then verify its new grouping.")
parser.add_argument("--edit-to-account", help="Move the created record to another account and verify both account balances.")
parser.add_argument("--edit-account-baseline", help="Starting balance of --edit-to-account.")
parser.add_argument("--edit-to-type", choices=("Income", "Expense"), help="Change the record type while editing; supply a matching --edit-to-category.")
parser.add_argument("--cleanup-marker", help="Delete the exact record signature described by the other record arguments and verify baseline restoration.")
parser.add_argument("--verify-statistics", action="store_true")
parser.add_argument("--account-baseline", default="10000.00", help="Starting EGP balance for the selected account")
parser.add_argument("--home-baseline", default="16000.00", help="Starting EGP Home total")
parser.add_argument("--home-delta", help="Expected Home delta; defaults to the selected account's transaction delta")
parser.add_argument("--note", default="")
parser.add_argument("--note-base64", help="UTF-8 note encoded as Base64 (avoids shell quoting ambiguity)")
parser.add_argument("--csv-export", action="store_true", help="Export the test row in-app and validate CSV round-trip fields")
parser.add_argument("--force-stop-after-save", action="store_true", help="Immediately force-stop and relaunch after submitting the record")
parser.add_argument("--verify-filters", action="store_true", help="Exercise live Records search, type, account, category, and date filters")
parser.add_argument("--inspect-receipt-picker", action="store_true", help="Create a record, open the real receipt picker, and capture its UI for APP-10 automation")
parser.add_argument("--offline-save", action="store_true", help="Disable emulator Wi-Fi/mobile data for record creation, verify local persistence, restore network, and verify after sync/restart")
parser.add_argument("--retain-record-after-sync", action="store_true", help="with --offline-save, stop after local/sync/restart assertions so an outer test can inspect Firestore before cleanup")
parser.add_argument("--expect-budget-alert", action="store_true", help="Assert the live app raises the matching budget threshold dialog after saving")
parser.add_argument("--expect-no-budget-alert", action="store_true", help="Assert no live budget dialog appears after saving")
parser.add_argument("--expect-budget-alert-category", help="Expected budget category displayed in the alert when it differs from the saved record category")
parser.add_argument("--expect-budget-alert-title", default="Budget Warning", help="Expected title for the live budget dialog")
parser.add_argument("--expect-budget-alert-percent", default="75% of budget used", help="Expected usage text in the live budget dialog")
args = parser.parse_args()
if args.expect_budget_alert and args.expect_no_budget_alert:
    parser.error("--expect-budget-alert and --expect-no-budget-alert cannot be used together")
if args.retain_record_after_sync and not args.offline_save:
    parser.error("--retain-record-after-sync requires --offline-save")
DEVICE = args.device
ACCOUNT = args.account
INITIAL_ACCOUNT = ACCOUNT
ACCOUNT_CURRENCY = args.currency.upper()
if args.note_base64:
    args.note = base64.b64decode(args.note_base64, validate=True).decode("utf-8")
ACCOUNT_BASELINE = Decimal(args.account_baseline)
HOME_BASELINE = Decimal(args.home_baseline)
RECORD_TYPE = args.type
CATEGORY = args.category
AMOUNT = Decimal(args.amount)
INITIAL_CATEGORY = CATEGORY
INITIAL_AMOUNT = AMOUNT
EDIT_AMOUNT = Decimal(args.edit_to_amount) if args.edit_to_amount else None
EDIT_CATEGORY = args.edit_to_category
EDIT_ACCOUNT = args.edit_to_account
EDIT_TYPE = args.edit_to_type
INITIAL_ACCOUNT_BASELINE = ACCOUNT_BASELINE
EDIT_ACCOUNT_BASELINE = Decimal(args.edit_account_baseline) if args.edit_account_baseline is not None else None
if (EDIT_ACCOUNT is None) != (EDIT_ACCOUNT_BASELINE is None):
    parser.error("--edit-to-account and --edit-account-baseline must be provided together")
if EDIT_ACCOUNT == ACCOUNT:
    parser.error("--edit-to-account must differ from --account")
if EDIT_TYPE is not None and EDIT_CATEGORY is None:
    parser.error("--edit-to-type requires --edit-to-category so the saved category matches the new type")
if EDIT_AMOUNT is not None and (EDIT_AMOUNT <= 0 or EDIT_AMOUNT == AMOUNT):
    parser.error("--edit-to-amount must be a different positive amount")
if EDIT_CATEGORY is not None and (EDIT_CATEGORY == CATEGORY or EDIT_CATEGORY not in {
    "Groceries", "Restaurants", "Salary", "Lending", "Renting", "Gifts"
}):
    parser.error("--edit-to-category must be a different supported built-in category")
BALANCE_DELTA = AMOUNT if RECORD_TYPE == "Income" else -AMOUNT
HOME_DELTA_OVERRIDE = Decimal(args.home_delta) if args.home_delta is not None else None
HOME_BALANCE_DELTA = HOME_DELTA_OVERRIDE if HOME_DELTA_OVERRIDE is not None else BALANCE_DELTA
AMOUNT_TEXT = f"{'+' if RECORD_TYPE == 'Income' else '-'}{AMOUNT:.2f} {ACCOUNT_CURRENCY}"
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / "manual_income_record"
OUTPUT.mkdir(parents=True, exist_ok=True)
RESULT = {
    "case": "manual_income_record_and_rollback",
    "status": "IN_PROGRESS",
    "device": DEVICE,
    "account": ACCOUNT,
    "category": CATEGORY,
    "type": RECORD_TYPE,
    "amount": f"{AMOUNT:.2f} {ACCOUNT_CURRENCY}",
    "record_signature": f"{RECORD_TYPE} / {CATEGORY} / {ACCOUNT} / {AMOUNT_TEXT}",
    "note": args.note,
    "baseline": {"account_balance": f"{ACCOUNT_BASELINE:.2f} {ACCOUNT_CURRENCY}", "home_egp": f"{HOME_BASELINE:.2f}"},
    "expected_home_delta_egp": f"{HOME_BALANCE_DELTA:.2f}",
    "steps": [],
    "cleanup": "not_started",
}
driver = None
record_created = False
offline_network = False


def adb(*command, timeout=15):
    return subprocess.check_output(["adb", "-s", DEVICE, *command], timeout=timeout).decode(
        "utf-8", errors="replace"
    )


def restore_network():
    global offline_network
    if offline_network:
        adb("shell", "svc", "wifi", "enable")
        adb("shell", "svc", "data", "enable")
        time.sleep(3)
        offline_network = False


def tree():
    return ElementTree.fromstring(driver.page_source)


def nodes():
    return [node for node in tree().iter()]


def texts():
    return [node.get("text", "").strip() for node in nodes() if node.get("text", "").strip()]


def bounds(node):
    values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(values) != 4:
        raise AssertionError(f"Node has no usable bounds: {node.attrib}")
    return (values[0] + values[2]) // 2, (values[1] + values[3]) // 2


def tap_node(node):
    x, y = bounds(node)
    adb("shell", "input", "tap", str(x), str(y))


def long_press_node(node):
    x, y = bounds(node)
    driver.execute_script("mobile: longClickGesture", {"x": x, "y": y, "duration": 900})


def tap_text(label, occurrence=-1):
    matches = [node for node in nodes() if node.get("text", "").strip() == label]
    if not matches:
        matches = [node for node in nodes() if node.get("content-desc", "").strip() == label]
    if not matches:
        raise AssertionError(f"Could not find UI text {label!r}; visible={texts()}")
    tap_node(matches[occurrence])
    time.sleep(0.5)


def wait_text(label, timeout=10, present=True):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        found = any(label.casefold() in text.casefold() for text in texts())
        if found == present:
            return
        time.sleep(0.3)
    raise AssertionError(f"Timed out waiting for text {label!r}; visible={texts()}")


def capture(name):
    (OUTPUT / f"{name}.xml").write_bytes(ElementTree.tostring(tree(), encoding="utf-8"))
    image = subprocess.check_output(["adb", "-s", DEVICE, "exec-out", "screencap", "-p"], timeout=10)
    (OUTPUT / f"{name}.png").write_bytes(image)


def save():
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")


def button_enabled(label):
    root = tree()
    parents = {child: parent for parent in root.iter() for child in parent}
    node = next((element for element in root.iter() if element.get("text", "").strip() == label), None)
    while node is not None:
        if node.get("clickable") == "true":
            return node.get("enabled") == "true"
        node = parents.get(node)
    raise AssertionError(f"Could not find enabled state for {label!r}")


def reveal_account_picker_item(label):
    screen = adb("shell", "wm", "size")
    dimensions = re.search(r"(\d+)x(\d+)", screen)
    if dimensions is None:
        raise AssertionError(f"Could not determine emulator screen dimensions: {screen}")
    width, height = map(int, dimensions.groups())
    account_heading = None
    for _ in range(3):
        account_heading = next((node for node in nodes() if node.get("text", "").strip() == "Account"), None)
        if account_heading is not None:
            break
        adb("shell", "input", "swipe", str(width // 2), str(int(height * 0.48)),
            str(width // 2), str(int(height * 0.29)), "550")
        time.sleep(0.4)
    if account_heading is None:
        raise AssertionError(f"Account picker heading is not visible before horizontal scan: {texts()}")
    heading_bounds = list(map(int, re.findall(r"\d+", account_heading.get("bounds", ""))))
    if len(heading_bounds) != 4:
        raise AssertionError(f"Account picker heading has invalid bounds: {account_heading.attrib}")
    swipe_y = min(heading_bounds[3] + 90, int(height * 0.86))
    for start_x, end_x in ((width - 200, 200), (200, width - 200)):
        for _ in range(4):
            adb("shell", "input", "swipe", str(start_x), str(swipe_y), str(end_x), str(swipe_y), "600")
            time.sleep(0.4)
            if label in texts():
                return
    raise AssertionError(f"Account {label!r} is not visible after scanning the account picker: {texts()}")


def go_home():
    for _ in range(3):
        if "TOTAL BALANCE" in texts():
            return
        driver.back()
        time.sleep(0.5)
    if "TOTAL BALANCE" not in texts():
        tap_text("Home")
    wait_text("TOTAL BALANCE")


def swipe_accounts(start=0.9, end=0.15):
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    width, height = map(int, size.groups()) if size else (1440, 3120)
    adb("shell", "input", "swipe", str(int(width * start)), str(int(height * 0.70)),
        str(int(width * end)), str(int(height * 0.70)), "350")
    time.sleep(0.35)


def find_account_balance(account, expected):
    expected = Decimal(expected)
    width, height = map(int, re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size")).groups())
    for direction in ((0.9, 0.15), (0.15, 0.9)):
        for _ in range(14):
            current = nodes()
            account_nodes = [node for node in current if node.get("text", "").strip() == account]
            for account_node in account_nodes:
                ax, ay = bounds(account_node)
                for candidate in current:
                    x, y = bounds(candidate) if re.search(r"\[\d+,\d+\]\[\d+,\d+\]", candidate.get("bounds", "")) else (-10000, -10000)
                    if abs(x - ax) > 320 or not 0 <= y - ay <= 400:
                        continue
                    value = candidate.get("text", "").replace(",", "").replace("EGP", "").replace(ACCOUNT_CURRENCY, "").strip()
                    try:
                        if Decimal(value) == expected:
                            return True
                    except Exception:
                        pass
            swipe_accounts(*direction)
    return False


def exact_record_nodes(category=None, amount_text=None):
    category = category or CATEGORY
    amount_text = amount_text or AMOUNT_TEXT
    root = tree()
    all_nodes = list(root.iter())
    parents = {child: parent for parent in all_nodes for child in parent}
    found = []
    for amount_node in all_nodes:
        if amount_node.get("text", "").strip() != amount_text:
            continue
        current = amount_node
        while current is not None:
            row_text = " ".join(node.get("text", "") for node in current.iter())
            box = list(map(int, re.findall(r"\d+", current.get("bounds", ""))))
            if len(box) == 4 and box[3] - box[1] < 500 and all(
                token in row_text for token in (category, ACCOUNT, amount_text)
            ):
                found.append(current)
                break
            current = parents.get(current)
    if len(found) > 1:
        raise AssertionError(f"Found {len(found)} records with the unique marker")
    return found[0] if found else None


def account_and_home(expected_account, expected_home, account_name=None):
    go_home()
    if f"{expected_home:,.2f}" not in texts():
        raise AssertionError(f"Home expected EGP {expected_home:.2f}; visible={texts()}")
    account_name = account_name or ACCOUNT
    if not find_account_balance(account_name, expected_account):
        raise AssertionError(f"{account_name} did not show {ACCOUNT_CURRENCY} {expected_account:.2f}")


def delete_exact_record(category=None, amount_text=None):
    go_home()
    tap_text("Records")
    wait_text("All Records")
    row = exact_record_nodes(category, amount_text)
    if row is None:
        raise AssertionError(f"The exact {RESULT['record_signature']} row was not found for deletion")
    long_press_node(row)
    wait_text("Delete")
    tap_text("Delete")
    wait_text("Delete Record")
    delete_buttons = [node for node in nodes() if node.get("text", "").strip() == "Delete"]
    tap_node(delete_buttons[-1])
    time.sleep(2)
    RESULT["cleanup"] = "exact marked manual record deleted; verifying after process restart"
    save()


options = UiAutomator2Options().load_capabilities({
    "platformName": "Android",
    "appium:automationName": "UiAutomator2",
    "appium:deviceName": DEVICE,
    "appium:udid": DEVICE,
    "appium:appPackage": PACKAGE,
    "appium:appActivity": ".MainActivity",
    "appium:noReset": True,
    "appium:newCommandTimeout": 120,
})

try:
    driver = webdriver.Remote("http://127.0.0.1:4723", options=options)
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(2)
    if args.cleanup_marker:
        account_and_home(ACCOUNT_BASELINE + BALANCE_DELTA, HOME_BASELINE + HOME_BALANCE_DELTA)
        tap_text("Records")
        wait_text("All Records")
        if exact_record_nodes() is None:
            raise AssertionError(f"Cleanup signature not found: {RESULT['record_signature']}")
        delete_exact_record()
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        time.sleep(3)
        account_and_home(ACCOUNT_BASELINE, HOME_BASELINE)
        tap_text("Records")
        wait_text("All Records")
        if exact_record_nodes() is not None:
            raise AssertionError(f"Cleanup signature reappeared: {RESULT['record_signature']}")
        RESULT["status"] = "PASS"
        RESULT["cleanup"] = "exact tagged manual record deleted; baseline restored after restart"
        RESULT["steps"].append("cleanup-only mode deleted the exact record and verified its absence after restart")
        save()
        capture("cleanup_only_verified")
        print(json.dumps(RESULT, indent=2))
        print(f"Evidence: {OUTPUT.resolve()}")
        raise SystemExit(0)
    account_and_home(ACCOUNT_BASELINE, HOME_BASELINE)
    RESULT["steps"].append(f"verified exact {ACCOUNT} and Home starting balances")
    tap_text("Records")
    wait_text("All Records")
    if exact_record_nodes() is not None:
        raise AssertionError(f"Refusing to add while a {RESULT['record_signature']} row already exists")
    go_home()

    tap_text("Add Record")
    wait_text("Add Record")
    tap_text(RECORD_TYPE)
    tap_text("Select Category")
    wait_text("Categories")
    root_texts = texts()
    if RECORD_TYPE == "Income":
        if CATEGORY not in root_texts:
            for _ in range(8):
                if RECORD_TYPE in texts():
                    break
                adb("shell", "input", "swipe", "700", "2450", "700", "650", "500")
                time.sleep(0.3)
            if RECORD_TYPE not in texts():
                raise AssertionError(f"Could not locate income category group {RECORD_TYPE!r}: {texts()}")
            tap_text(RECORD_TYPE)
            time.sleep(0.3)
        for _ in range(8):
            if CATEGORY in texts():
                break
            adb("shell", "input", "swipe", "700", "2450", "700", "650", "500")
            time.sleep(0.3)
        if CATEGORY not in texts():
            raise AssertionError(f"Could not locate income category {CATEGORY!r}: {texts()}")
        tap_text(CATEGORY)
    else:
        parent_category = args.category_parent or ("Food & Drinks" if CATEGORY == "Groceries" else CATEGORY)
        for _ in range(8):
            if parent_category in texts():
                break
            adb("shell", "input", "swipe", "700", "2450", "700", "650", "500")
            time.sleep(0.3)
        if parent_category not in texts():
            raise AssertionError(f"Could not locate category group {parent_category!r}: {texts()}")
        tap_text(parent_category)
        for _ in range(8):
            if CATEGORY in texts():
                break
            adb("shell", "input", "swipe", "700", "2450", "700", "650", "500")
            time.sleep(0.3)
        tap_text(CATEGORY, occurrence=0 if CATEGORY == "Others" else -1)
    wait_text("Confirm Record")
    if not any(node.get("text", "").strip() == ACCOUNT for node in nodes()):
        reveal_account_picker_item(ACCOUNT)
    tap_text(ACCOUNT)
    adb("shell", "input", "swipe", "700", "800", "700", "2500", "450")
    time.sleep(0.5)
    tap_text(RECORD_TYPE)
    for _ in range(10):
        tap_text("Backspace")
    RESULT["steps"].append("cleared residual numeric-pad amount before entering the target value")
    for digit in f"{AMOUNT:.2f}":
        candidates = [node for node in nodes() if node.get("text", "").strip() == digit]
        keypad = [node for node in candidates if bounds(node)[1] > 1500]
        if not keypad:
            raise AssertionError(f"Could not locate keypad digit {digit!r}")
        tap_node(keypad[-1])
        time.sleep(0.2)
    adb("shell", "input", "swipe", "700", "450", "700", "950", "500")
    time.sleep(0.5)
    if not any(f"{AMOUNT:.2f}" in text for text in texts()) or not button_enabled("Confirm Record"):
        raise AssertionError(f"Amount entry was not EGP {AMOUNT:.2f}; visible={texts()}")
    capture("01_manual_income_form")
    if args.note:
        note_label = None
        for _ in range(3):
            note_label = next((node for node in nodes() if node.get("text", "").strip() == "Note (optional)"), None)
            if note_label is not None:
                break
            adb("shell", "input", "swipe", "700", "1500", "700", "900", "450")
            time.sleep(0.3)
        if note_label is None:
            raise AssertionError(f"Manual record form did not expose Note (optional): {texts()}")
        tap_node(note_label)
        note_fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
        if not note_fields:
            raise AssertionError("The note input did not expose an editable Android text field")
        driver.execute_script(
            "mobile: replaceElementValue",
            {"elementId": note_fields[-1].id, "text": args.note},
        )
        time.sleep(0.3)
        if args.note not in " ".join(texts()):
            raise AssertionError(f"Note value was not retained: {texts()}")
        try:
            driver.hide_keyboard()
        except Exception:
            adb("shell", "input", "keyevent", "4")
        time.sleep(0.3)
        RESULT["steps"].append("entered the exact comma/quote test note")
    if args.offline_save:
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        offline_network = True
        time.sleep(2)
        RESULT["steps"].append("disabled emulator Wi-Fi and mobile data immediately before saving the test record")
    tap_text("Confirm Record")
    record_created = True
    RESULT["steps"].append(f"created a manual {RECORD_TYPE} / {CATEGORY} record for {ACCOUNT_CURRENCY} {AMOUNT:.2f} on {ACCOUNT}")
    RESULT["cleanup"] = "manual record exists; exact tagged deletion pending"
    save()
    if args.force_stop_after_save and offline_network:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        time.sleep(2)
        RESULT["steps"].append("force-stopped and relaunched immediately after record submission while Wi-Fi/mobile data remained disabled")
        save()
    if args.expect_budget_alert:
        wait_text(args.expect_budget_alert_title)
        alert_text = " ".join(texts())
        expected_alert_values = (args.expect_budget_alert_category or CATEGORY, f"{AMOUNT:.2f} {ACCOUNT_CURRENCY}", args.expect_budget_alert_percent)
        if not all(value in alert_text for value in expected_alert_values):
            raise AssertionError(f"Budget alert did not show the exact custom category/spent/threshold; visible={texts()}")
        capture("budget_alert_for_custom_subcategory")
        tap_text("Got it")
        RESULT["steps"].append(f"verified {args.expect_budget_alert_title} for {args.expect_budget_alert_category or CATEGORY} at {args.expect_budget_alert_percent}")
        save()
    elif args.expect_no_budget_alert:
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            alert_title = next((title for title in ("Budget Warning", "Budget Exceeded!") if title in texts()), None)
            if alert_title is not None:
                raise AssertionError(f"Unexpected {alert_title} dialog below the configured budget threshold")
            time.sleep(0.2)
        RESULT["steps"].append("verified no budget dialog appeared below the configured threshold")
        save()
    if args.offline_save:
        tap_text("Records")
        wait_text("All Records")
        if exact_record_nodes() is None:
            raise AssertionError("Offline record was not readable from the local Records view")
        capture("offline_record_before_reconnect")
        RESULT["steps"].append("verified the exact record in the local ledger while network was disabled")
        save()
        restore_network()
        RESULT["steps"].append("restored emulator connectivity and allowed Firebase sync to resume")
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        time.sleep(4)
        tap_text("Records")
        wait_text("All Records")
        if exact_record_nodes() is None:
            raise AssertionError("Offline-created record did not survive connectivity restore and process restart")
        RESULT["steps"].append("verified the same exact record after sync opportunity and process restart")
        save()
        go_home()
        if args.retain_record_after_sync:
            account_and_home(ACCOUNT_BASELINE + BALANCE_DELTA, HOME_BASELINE + HOME_BALANCE_DELTA)
            RESULT["account_after_add"] = f"{ACCOUNT_BASELINE + BALANCE_DELTA:.2f} {ACCOUNT_CURRENCY}"
            RESULT["home_after_add"] = f"{HOME_BASELINE + HOME_BALANCE_DELTA:.2f} EGP"
            RESULT["status"] = "PASS"
            RESULT["cleanup"] = "record intentionally retained for independent server-side sync verification"
            RESULT["steps"].append("verified the exact account and Home balance after local save, connectivity restoration, and process restart; retained the row for independent Firestore inspection")
            save()
            capture("offline_sync_ready_for_remote_assertion")
            print(json.dumps(RESULT, indent=2))
            print(f"Evidence: {OUTPUT.resolve()}")
            raise SystemExit(0)
    if args.force_stop_after_save and not args.offline_save:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        time.sleep(2)
        RESULT["steps"].append("force-stopped and relaunched immediately after record submission")
        save()

    time.sleep(2)
    go_home()
    account_and_home(ACCOUNT_BASELINE + BALANCE_DELTA, HOME_BASELINE + HOME_BALANCE_DELTA)
    RESULT["account_after_add"] = f"{ACCOUNT_BASELINE + BALANCE_DELTA:.2f} {ACCOUNT_CURRENCY}"
    RESULT["home_after_add"] = f"{HOME_BASELINE + HOME_BALANCE_DELTA:.2f} EGP"
    RESULT["steps"].append(
        f"verified {ACCOUNT} delta {ACCOUNT_CURRENCY} {BALANCE_DELTA:+.2f} and Home delta EGP {HOME_BALANCE_DELTA:+.2f}"
    )

    tap_text("Records")
    wait_text("All Records")
    row = exact_record_nodes()
    if row is None:
        raise AssertionError(f"The exact {RESULT['record_signature']} record was not found in All Records")
    row_text = " ".join(node.get("text", "") for node in row.iter())
    if not all(token in row_text for token in (CATEGORY, ACCOUNT, AMOUNT_TEXT)):
        raise AssertionError(f"Record signature was incorrect: {row_text}")
    capture("02_manual_income_record")
    RESULT["steps"].append(f"verified exact {RECORD_TYPE}, amount, category, and account in Records")
    save()

    if args.inspect_receipt_picker:
        long_press_node(row)
        wait_text("Attach Receipt")
        tap_text("Attach Receipt")
        time.sleep(2)
        capture("receipt_picker_open")
        RESULT["receipt_picker_texts"] = texts()
        RESULT["steps"].append("opened the system receipt/photo picker and captured its accessibility hierarchy")
        save()
        raise AssertionError("Receipt picker inspection stage complete; use captured hierarchy to implement selection and cancellation")

    if args.verify_filters:
        search_fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
        if not search_fields:
            raise AssertionError("Records search field was not available")
        search_field = search_fields[0]
        search_field.send_keys(args.note)
        wait_text(args.note)
        if exact_record_nodes() is None:
            raise AssertionError("Searching for the exact record note did not retain the test record")
        search_field.clear()
        search_field.send_keys("NO_RECORD_MATCH_90817")
        wait_text("No records found")
        if exact_record_nodes() is not None:
            raise AssertionError("A non-matching search still displayed the test record")
        search_field.clear()
        RESULT["steps"].append("search matched the exact record note and excluded a deliberately unmatched query")

        tap_text("Income")
        wait_text("No records found")
        if exact_record_nodes() is not None:
            raise AssertionError("Income type filter displayed an Expense record")
        tap_text("Expense")
        if exact_record_nodes() is None:
            raise AssertionError("Expense type filter excluded the matching Expense record")
        RESULT["steps"].append("type filters excluded Income and retained the matching Expense")

        tap_text("Week")
        if exact_record_nodes() is None:
            raise AssertionError("Current-week date filter excluded a record created during this run")
        tap_text("Month")
        if exact_record_nodes() is None:
            raise AssertionError("Current-month date filter excluded a record created during this run")
        RESULT["steps"].append("current-week and current-month date filters retained the in-period record")

        tap_text("Filter")
        wait_text("Filter Records")
        tap_text("All Accounts")
        tap_text("SecondBank")
        tap_text("All Categories")
        tap_text("Groceries")
        tap_text("Apply")
        wait_text("No records found")
        if exact_record_nodes() is not None:
            raise AssertionError("Account/category filter retained a record for the wrong account")
        tap_text("Filter")
        wait_text("Filter Records")
        tap_text("SecondBank")
        tap_text(ACCOUNT)
        tap_text("Apply")
        if exact_record_nodes() is None:
            raise AssertionError("Account/category filter excluded the matching account/category record")
        tap_text("Clear Filters")
        if exact_record_nodes() is None:
            raise AssertionError("Clearing account/type filters did not restore the record")
        RESULT["steps"].append("account/category filters excluded SecondBank and matched MainBank/Groceries; clear restored the record")
        save()

    if args.csv_export:
        tap_text("Export")
        wait_text("Export Records")
        capture("csv_export_dialog")
        tap_text("Export")
        time.sleep(2)
        exported_files = adb("shell", "ls", "-t", "/sdcard/Download/wallet_records_export*.csv").splitlines()
        if not exported_files:
            raise AssertionError("No wallet_records_export CSV file was found in Downloads")
        remote_csv = exported_files[0].strip()
        local_csv = OUTPUT / "wallet_records_export.csv"
        adb("pull", remote_csv, str(local_csv))
        rows = list(csv.reader(io.StringIO(local_csv.read_text(encoding="utf-8-sig"))))
        if not rows or len(rows[0]) != 8:
            raise AssertionError(f"CSV header/column count is invalid: {rows[:2]}")
        exported = [
            row for row in rows[1:]
            if len(row) == 8
            and row[1] == ACCOUNT
            and row[2] == CATEGORY
            and row[3] == RECORD_TYPE
            and row[4] == f"{AMOUNT:.2f}"
        ]
        if len(exported) != 1:
            raise AssertionError(f"Expected one matching CSV record; rows={rows[:4]}")
        if len(exported[0]) != 8 or exported[0][6] != args.note:
            raise AssertionError(f"CSV escaping/round-trip failed for note {args.note!r}: {exported[0]}")
        RESULT["csv_export"] = {"file": str(local_csv), "record_columns": len(exported[0]), "comment": exported[0][6]}
        RESULT["steps"].append("exported CSV from All Records and verified the exact 8-column row and note round-trip")
        capture("csv_export_complete")
        save()

    if EDIT_AMOUNT is not None or EDIT_CATEGORY is not None or EDIT_ACCOUNT is not None or EDIT_TYPE is not None:
        long_press_node(row)
        wait_text("Edit")
        tap_text("Edit")
        wait_text("Edit Record")
        capture("03_edit_record_form")
        old_amount = AMOUNT
        old_category = CATEGORY
        old_account = ACCOUNT
        old_type = RECORD_TYPE
        if EDIT_TYPE is not None:
            tap_text(EDIT_TYPE)
            capture("03_edit_type_selected")
        if EDIT_ACCOUNT is not None:
            tap_text(ACCOUNT)
            wait_text(EDIT_ACCOUNT)
            tap_text(EDIT_ACCOUNT)
            wait_text("Edit Record")
            capture("03_edit_account_selected")
        if EDIT_AMOUNT is not None:
            amount_field = next(
                (field for field in driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
                 if (field.get_attribute("text") or "").strip() == f"{AMOUNT:.2f}"),
                None,
            )
            if amount_field is None:
                raise AssertionError(f"Could not locate the editable amount field for EGP {AMOUNT:.2f}")
            amount_field.clear()
            amount_field.send_keys(f"{EDIT_AMOUNT:.2f}")
            driver.hide_keyboard()
            wait_text("Edit Record")
            entered_amount = (amount_field.get_attribute("text") or "").strip()
            if entered_amount != f"{EDIT_AMOUNT:.2f}":
                raise AssertionError(
                    f"Amount draft was not retained after keyboard dismissal: {entered_amount!r}"
                )
            capture("03_edit_amount_entered")
        if EDIT_CATEGORY is not None:
            tap_text("Select Category" if EDIT_TYPE is not None else CATEGORY)
            wait_text("Categories")
            parent_category = "Income" if EDIT_TYPE == "Income" else "Food & Drinks"
            if parent_category == "Income":
                for _ in range(8):
                    if parent_category in texts():
                        break
                    adb("shell", "input", "swipe", "700", "2450", "700", "650", "500")
                    time.sleep(0.3)
                if parent_category not in texts():
                    raise AssertionError(f"Could not scroll to income categories: {texts()}")
            tap_text(parent_category)
            wait_text(parent_category)
            capture("04_category_subcategories")
            tap_text(EDIT_CATEGORY)
            wait_text("Edit Record")
            time.sleep(0.8)
            edit_texts = texts()
            edit_amounts = [
                (field.get_attribute("text") or "").strip()
                for field in driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
            ]
            if EDIT_CATEGORY not in edit_texts:
                raise AssertionError(
                    f"Selected category {EDIT_CATEGORY!r} did not return to the edit form; visible={edit_texts}"
                )
            if EDIT_AMOUNT is not None and f"{EDIT_AMOUNT:.2f}" not in edit_amounts:
                raise AssertionError(
                    f"Amount draft was lost after category navigation: fields={edit_amounts}"
                )
            capture("05_edit_form_after_category")
        tap_text("Update")
        time.sleep(1)
        time.sleep(2)
        if EDIT_AMOUNT is not None:
            AMOUNT = EDIT_AMOUNT
        if EDIT_CATEGORY is not None:
            CATEGORY = EDIT_CATEGORY
        if EDIT_ACCOUNT is not None:
            ACCOUNT = EDIT_ACCOUNT
            ACCOUNT_BASELINE = EDIT_ACCOUNT_BASELINE
        if EDIT_TYPE is not None:
            RECORD_TYPE = EDIT_TYPE
            RESULT["type"] = RECORD_TYPE
        BALANCE_DELTA = AMOUNT if RECORD_TYPE == "Income" else -AMOUNT
        HOME_BALANCE_DELTA = HOME_DELTA_OVERRIDE if EDIT_TYPE is None and HOME_DELTA_OVERRIDE is not None else BALANCE_DELTA
        RESULT["expected_home_delta_egp"] = f"{HOME_BALANCE_DELTA:.2f}"
        AMOUNT_TEXT = f"{'+' if RECORD_TYPE == 'Income' else '-'}{AMOUNT:.2f} {ACCOUNT_CURRENCY}"
        if EDIT_AMOUNT is not None:
            RESULT["amount_before_edit"] = f"{old_amount:.2f} {ACCOUNT_CURRENCY}"
            RESULT["amount_after_edit"] = f"{AMOUNT:.2f} {ACCOUNT_CURRENCY}"
            RESULT["amount"] = f"{AMOUNT:.2f} {ACCOUNT_CURRENCY}"
        if EDIT_CATEGORY is not None:
            RESULT["category_before_edit"] = old_category
            RESULT["category_after_edit"] = CATEGORY
            RESULT["category"] = CATEGORY
        if EDIT_ACCOUNT is not None:
            RESULT["account_before_edit"] = old_account
            RESULT["edited_account_name"] = ACCOUNT
            RESULT["initial_account_after_edit"] = f"{INITIAL_ACCOUNT_BASELINE:.2f} {ACCOUNT_CURRENCY}"
            RESULT["destination_account_after_edit"] = f"{ACCOUNT_BASELINE + BALANCE_DELTA:.2f} {ACCOUNT_CURRENCY}"
        if EDIT_TYPE is not None:
            RESULT["type_before_edit"] = old_type
            RESULT["type_after_edit"] = RECORD_TYPE
        RESULT["record_signature"] = f"{RECORD_TYPE} / {CATEGORY} / {ACCOUNT} / {AMOUNT_TEXT}"
        account_and_home(ACCOUNT_BASELINE + BALANCE_DELTA, HOME_BASELINE + HOME_BALANCE_DELTA)
        if EDIT_ACCOUNT is not None and not find_account_balance(INITIAL_ACCOUNT, INITIAL_ACCOUNT_BASELINE):
            raise AssertionError(f"Original account {INITIAL_ACCOUNT} did not return to its EGP {INITIAL_ACCOUNT_BASELINE:.2f} baseline after reassignment")
        RESULT["account_after_edit"] = f"{ACCOUNT_BASELINE + BALANCE_DELTA:.2f} {ACCOUNT_CURRENCY}"
        RESULT["home_after_edit"] = f"{HOME_BASELINE + HOME_BALANCE_DELTA:.2f} EGP"
        edit_details = []
        if EDIT_AMOUNT is not None:
            edit_details.append(f"amount {ACCOUNT_CURRENCY} {old_amount:.2f} to {AMOUNT_TEXT}")
        if EDIT_CATEGORY is not None:
            edit_details.append(f"category {old_category} to {CATEGORY}")
        if EDIT_ACCOUNT is not None:
            edit_details.append(f"account {old_account} to {ACCOUNT}")
        if EDIT_TYPE is not None:
            edit_details.append(f"type {old_type} to {RECORD_TYPE}")
        RESULT["steps"].append(f"edited the exact record ({'; '.join(edit_details)}); account and Home reconciled")
        save()
        tap_text("Records")
        wait_text("All Records")
        row = exact_record_nodes()
        if row is None:
            raise AssertionError("The edited record was not visible with its new exact amount")
        capture("03_manual_record_after_edit")

    if args.verify_statistics:
        tap_text("Stats")
        wait_text("Statistics")
        expected_statistics_amount = f"{AMOUNT:,.2f} EGP"
        expected_net_amount = f"{'+' if RECORD_TYPE == 'Income' else '-'}{AMOUNT:,.2f} EGP"
        initial_statistics_text = texts()
        if expected_statistics_amount not in initial_statistics_text or expected_net_amount not in initial_statistics_text:
            raise AssertionError(
                f"Statistics totals were not {expected_statistics_amount} and {expected_net_amount}; "
                f"visible={initial_statistics_text}"
            )
        capture("statistics_numeric_summary")
        for _ in range(5):
            if CATEGORY in texts():
                break
            adb("shell", "input", "swipe", "700", "2400", "700", "800", "450")
            time.sleep(0.3)
        wait_text(CATEGORY)
        if expected_statistics_amount not in texts():
            raise AssertionError(
                f"Statistics did not show {CATEGORY} totaling {expected_statistics_amount}; visible={texts()}"
            )
        capture("statistics_numeric_category")
        RESULT["statistics"] = {
            "tab": "Spending",
            "category": CATEGORY,
            "expected_amount": expected_statistics_amount,
            "observed_amount": expected_statistics_amount,
            "net_balance": expected_net_amount,
        }
        RESULT["steps"].append(
            f"verified Statistics Spending total and net at {expected_statistics_amount} and {expected_net_amount}; "
            f"scrolled to verify {CATEGORY} grouping"
        )
        save()
        go_home()

    delete_exact_record()
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(3)
    account_and_home(ACCOUNT_BASELINE, HOME_BASELINE)
    if EDIT_ACCOUNT is not None and not find_account_balance(INITIAL_ACCOUNT, INITIAL_ACCOUNT_BASELINE):
        raise AssertionError(f"Original account {INITIAL_ACCOUNT} was not restored after record deletion")
    RESULT["steps"].append("deleted the exact record and verified both balances after app restart")
    tap_text("Records")
    wait_text("All Records")
    if exact_record_nodes() is not None:
        raise AssertionError(f"The deleted manual {RESULT['record_signature']} record reappeared after restart")
    RESULT["status"] = "PASS"
    RESULT["cleanup"] = f"exact manual record absent after restart; {ACCOUNT} and Home baselines restored"
    save()
    capture("03_cleanup_verified")
    print(json.dumps(RESULT, indent=2))
    print(f"Evidence: {OUTPUT.resolve()}")
except Exception as error:
    try:
        restore_network()
    except Exception as network_error:
        RESULT["network_restore_error"] = f"{type(network_error).__name__}: {network_error}"
    RESULT["status"] = "FAIL" if record_created else "INCONCLUSIVE"
    RESULT["error"] = f"{type(error).__name__}: {error}"
    RESULT["cleanup"] = "requires exact manual review" if record_created else "no record submission confirmed"
    if record_created:
        try:
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
            time.sleep(3)
            go_home()
            tap_text("Records")
            wait_text("All Records")
            candidate_signatures = []
            for category in (CATEGORY, INITIAL_CATEGORY):
                for amount in (AMOUNT, INITIAL_AMOUNT):
                    signature = (category, f"{'+' if RECORD_TYPE == 'Income' else '-'}{amount:.2f} {ACCOUNT_CURRENCY}")
                    if signature not in candidate_signatures:
                        candidate_signatures.append(signature)
            found = [
                signature for signature in candidate_signatures
                if exact_record_nodes(*signature) is not None
            ]
            if len(found) != 1:
                raise AssertionError(f"Cleanup expected one test-row signature, found {found}")
            delete_exact_record(*found[0])
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
            time.sleep(3)
            account_and_home(ACCOUNT_BASELINE, HOME_BASELINE)
            RESULT["cleanup"] = "failed-case record deleted; account and Home baselines restored after restart"
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"cleanup failed: {type(cleanup_error).__name__}: {cleanup_error}"
    save()
    try:
        capture("failure_state")
    except Exception:
        pass
    raise
finally:
    try:
        restore_network()
    except Exception as network_error:
        RESULT["network_restore_error"] = f"{type(network_error).__name__}: {network_error}"
        save()
    if driver is not None:
        driver.quit()
