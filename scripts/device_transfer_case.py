"""Real-app account transfer, balance reconciliation, and rollback scenario."""

import argparse
import json
import re
import subprocess
import time
import uuid
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from xml.etree import ElementTree

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy
from selenium.common.exceptions import NoSuchElementException, TimeoutException
from selenium.webdriver.support.ui import WebDriverWait


parser = argparse.ArgumentParser(description="Test a real in-app transfer and exact rollback.")
parser.add_argument("--execute", action="store_true", help="Allow transfer and record deletion on the emulator.")
parser.add_argument("--device", default="emulator-5554")
parser.add_argument("--server", default="http://127.0.0.1:4723")
parser.add_argument("--amount", help="Source-currency amount; defaults to 0.07 EGP or 0.10 USD with --fx.")
parser.add_argument("--edit-amount", help="Edit the created transfer to this source-currency amount before verifying deletion rollback.")
parser.add_argument("--fx", action="store_true", help="Exercise a live USD-to-EUR transfer using the displayed app quote.")
parser.add_argument("--verify-statistics", action="store_true", help="Assert a transfer is not counted as an ordinary expense.")
parser.add_argument("--expected-ordinary-expense", default="0.00",
                    help="Existing expense total that must remain unchanged when transfer is added.")
parser.add_argument("--source-baseline", help="Expected source balance before the test; defaults to the documented fixture value.")
parser.add_argument("--destination-baseline", help="Expected destination balance before the test; defaults to the documented fixture value.")
parser.add_argument("--home-baseline", help="Expected EGP Home total before the test; defaults to the documented fixture value.")
parser.add_argument("--expected-spent-today", default="0.00",
                    help="Expected ordinary expenses during the case; transfer must not add to this value.")
args = parser.parse_args()
if not args.execute:
    parser.error("Device mutations are disabled by default; pass --execute to run this case.")

PACKAGE = "com.example.wallettrackers"
SOURCE = "USDBank" if args.fx else "MainBank"
DESTINATION = "EURBank" if args.fx else "SecondBank"
SOURCE_CURRENCY = "USD" if args.fx else "EGP"
DESTINATION_CURRENCY = "EUR" if args.fx else "EGP"
SOURCE_SUFFIX = "4444" if args.fx else "1111"
SOURCE_BASELINE = Decimal(args.source_baseline or ("1000.00" if args.fx else "9999.83"))
DESTINATION_BASELINE = Decimal(args.destination_baseline or "500.00" if args.fx else args.destination_baseline or "5000.00")
HOME_BASELINE = Decimal(args.home_baseline or ("15999.83" if args.fx else "15999.83"))
EXPECTED_SPENT_TODAY = Decimal(args.expected_spent_today)
EXPECTED_ORDINARY_EXPENSE = Decimal(args.expected_ordinary_expense)
AMOUNT = Decimal(args.amount or ("0.10" if args.fx else "0.07"))
if AMOUNT <= 0:
    parser.error("Transfer amount must be positive.")
EDIT_AMOUNT = Decimal(args.edit_amount) if args.edit_amount is not None else None
if EDIT_AMOUNT is not None and EDIT_AMOUNT <= 0:
    parser.error("Edited transfer amount must be positive.")
MARKER = "AUTOTRANSFER" + uuid.uuid4().hex[:12].upper()
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / "live_in_app_transfer"
OUTPUT.mkdir(parents=True, exist_ok=True)
RESULT = {
    "case": "live_in_app_transfer_and_rollback",
    "status": "IN_PROGRESS",
    "device": args.device,
    "source": SOURCE,
    "destination": DESTINATION,
    "amount": f"{AMOUNT:.2f} {SOURCE_CURRENCY}",
    "edit_amount": f"{EDIT_AMOUNT:.2f} {SOURCE_CURRENCY}" if EDIT_AMOUNT is not None else None,
    "marker": MARKER,
    "baseline": {
        "home_egp": f"{HOME_BASELINE:.2f}",
        "source": f"{SOURCE_BASELINE:.2f} {SOURCE_CURRENCY}",
        "destination": f"{DESTINATION_BASELINE:.2f} {DESTINATION_CURRENCY}",
    },
    "steps": [],
    "cleanup": "not_started",
}
driver = None
transfer_submitted = False
delete_attempted = False


def adb(*command, timeout=10):
    return subprocess.check_output(["adb", "-s", args.device, *command], timeout=timeout).decode(
        "utf-8", errors="replace"
    )


def tree():
    return ElementTree.fromstring(driver.page_source)


def nodes():
    return [node for node in tree().iter()]


def find_text(label, exact=True):
    return next((node for node in nodes()
                 if (node.get("text", "") == label if exact else label in node.get("text", ""))), None)


def wait_text(label, timeout=10, present=True, exact=True):
    try:
        return WebDriverWait(driver, timeout, poll_frequency=0.3).until(
            lambda _: (find_text(label, exact) is not None) == present
        )
    except (TimeoutException, NoSuchElementException) as error:
        raise AssertionError(f"Timed out waiting for text {label!r} present={present}") from error


def tap_text(label, exact=True, occurrence=-1):
    wait_text(label, exact=exact)
    matches = [node for node in nodes()
               if (node.get("text", "") == label if exact else label in node.get("text", ""))]
    if not matches:
        raise AssertionError(f"Text {label!r} not found")
    bounds = list(map(int, re.findall(r"\d+", matches[occurrence].get("bounds", ""))))
    if len(bounds) != 4:
        raise AssertionError(f"Text {label!r} has no screen bounds")
    x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
    adb("shell", "input", "tap", str(x), str(y))


def capture(name):
    driver.save_screenshot(str(OUTPUT / f"{name}.png"))
    (OUTPUT / f"{name}.xml").write_text(driver.page_source, encoding="utf-8")


def save():
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")


def home():
    for _ in range(6):
        if find_text("TOTAL BALANCE") is not None:
            return
        back = driver.find_elements(AppiumBy.ACCESSIBILITY_ID, "Home")
        if back:
            back[0].click()
        else:
            driver.back()
        time.sleep(0.3)
    wait_text("TOTAL BALANCE")


def account_balance_visible(root, account_name, expected):
    account_nodes = [node for node in root.iter() if node.get("text", "").strip() == account_name]
    for account_node in account_nodes:
        account_bounds = list(map(int, re.findall(r"\d+", account_node.get("bounds", ""))))
        if len(account_bounds) != 4:
            continue
        _, _, account_right, account_bottom = account_bounds
        for value_node in root.iter():
            value_bounds = list(map(int, re.findall(r"\d+", value_node.get("bounds", ""))))
            if len(value_bounds) != 4:
                continue
            left, top, right, _ = value_bounds
            if not account_bottom - 8 <= top <= account_bottom + 140 or right < account_bounds[0] or left > account_right:
                continue
            value = value_node.get("text", "").replace(",", "").replace("EGP", "").strip()
            try:
                if Decimal(value) == expected:
                    return True
            except Exception:
                continue
    return False


def scan_account(account_name, expected):
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    width, height = map(int, size.groups()) if size else (1440, 3120)
    swipe_y = int(height * 0.70)
    root = tree()
    for start, end in ((0.68, 0.42), (0.42, 0.68)):
        previous = None
        for _ in range(16):
            if account_balance_visible(root, account_name, expected):
                return True
            page = tuple(node.get("text", "") for node in root.iter()
                         if node.get("text", "") and swipe_y - 250 <=
                         int(re.findall(r"\d+", node.get("bounds", "0 0 0 0"))[1]) <= swipe_y + 400)
            if page == previous:
                break
            previous = page
            adb("shell", "input", "swipe", str(int(width * start)), str(swipe_y),
                str(int(width * end)), str(swipe_y), "500")
            time.sleep(0.3)
            root = tree()
    return account_balance_visible(root, account_name, expected)


def assert_home_and_accounts(source, destination, total):
    home()
    total_text = f"{total:,.2f}"
    if not any(node.get("text", "").strip() == total_text for node in nodes()):
        raise AssertionError(f"Home total did not equal EGP {total_text}")
    if not scan_account(SOURCE, source):
        raise AssertionError(f"{SOURCE} balance did not equal EGP {source:.2f}")
    if not scan_account(DESTINATION, destination):
        raise AssertionError(f"{DESTINATION} balance did not equal EGP {destination:.2f}")


def assert_spent_today(expected, evidence_name):
    root = tree()
    labels = [node for node in root.iter() if node.get("text", "").strip().casefold() == "spent today"]
    if len(labels) != 1:
        raise AssertionError(f"Expected one visible Spent Today label; found {len(labels)}")
    label = labels[0]
    label_bounds = list(map(int, re.findall(r"\d+", label.get("bounds", ""))))
    if len(label_bounds) != 4:
        raise AssertionError("Spent Today label has invalid screen bounds")
    candidates = []
    for node in root.iter():
        text = node.get("text", "").strip()
        if not text or node is label:
            continue
        bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        if len(bounds) != 4:
            continue
        left, top, right, _ = bounds
        if top < label_bounds[3] - 8 or top > label_bounds[3] + 160:
            continue
        if right < label_bounds[0] or left > label_bounds[2]:
            continue
        try:
            value = Decimal(text.replace(",", "").replace("EGP", "").strip())
        except Exception:
            continue
        candidates.append((top - label_bounds[3], abs(left - label_bounds[0]), text, value))
    if not candidates:
        raise AssertionError("Could not locate numeric Spent Today value")
    _, _, observed_text, observed = min(candidates, key=lambda item: (item[0], item[1]))
    RESULT.setdefault("spent_today_checks", []).append({
        "observed": observed_text,
        "expected": str(expected),
        "phase": evidence_name,
    })
    save()
    if observed != expected:
        raise AssertionError(f"Spent Today was {observed_text!r}; expected {expected}")


def find_transfer_record():
    root = tree()
    all_nodes = list(root.iter())
    parents = {child: parent for parent in root.iter() for child in parent}
    hits = []
    for amount_node in all_nodes:
        amount_text = re.sub(r"[^0-9.-]", "", amount_node.get("text", "").replace(",", ""))
        try:
            valid_amounts = {AMOUNT, -AMOUNT}
            if EDIT_AMOUNT is not None:
                valid_amounts.update({EDIT_AMOUNT, -EDIT_AMOUNT})
            if Decimal(amount_text) not in valid_amounts:
                continue
        except Exception:
            continue
        current = amount_node
        while current is not None:
            row_bounds = list(map(int, re.findall(r"\d+", current.get("bounds", ""))))
            if len(row_bounds) == 4 and row_bounds[3] - row_bounds[1] < 500:
                row_text = " ".join(node.get("text", "") for node in current.iter())
                if "Transfer" in row_text and SOURCE in row_text and DESTINATION in row_text and MARKER in row_text:
                    hits.append(amount_node)
                    break
            current = parents.get(current)
    if len(hits) > 1:
        raise AssertionError(f"Found {len(hits)} duplicate transfer records with marker {MARKER}")
    return hits[0] if hits else None


def button_enabled(label):
    root = tree()
    parents = {child: parent for parent in root.iter() for child in parent}
    node = next((item for item in root.iter() if item.get("text") == label), None)
    while node is not None:
        if node.get("clickable") == "true":
            return node.get("enabled") == "true"
        node = parents.get(node)
    raise AssertionError(f"Could not resolve enabled state for button {label!r}")


def open_records():
    home()
    element = driver.find_elements(AppiumBy.ACCESSIBILITY_ID, "Records")
    if not element:
        raise AssertionError("Records navigation is not available")
    element[0].click()
    wait_text("All Records")


def delete_exact_transfer():
    global delete_attempted
    open_records()
    target = find_transfer_record()
    if target is None:
        raise AssertionError("Exact marked transfer row was not found; refusing deletion")
    capture("03_transfer_record")
    bounds = list(map(int, re.findall(r"\d+", target.get("bounds", ""))))
    x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "1000")
    wait_text("Delete")
    tap_text("Delete", occurrence=-1)
    wait_text("Delete Record")
    delete_attempted = True
    save()
    tap_text("Delete", occurrence=-1)
    wait_text("Delete Record", present=False)
    time.sleep(1)
    if find_transfer_record() is not None:
        raise AssertionError("Transfer record remained after delete confirmation")


try:
    options = UiAutomator2Options().load_capabilities({
        "platformName": "Android",
        "appium:automationName": "UiAutomator2",
        "appium:deviceName": args.device,
        "appium:udid": args.device,
        "appium:appPackage": PACKAGE,
        "appium:appActivity": ".MainActivity",
        "appium:noReset": True,
        "appium:newCommandTimeout": 10,
    })
    driver = webdriver.Remote(args.server.rstrip("/"), options=options)
    driver.implicitly_wait(0)
    home()
    capture("01_transfer_preflight")
    assert_home_and_accounts(SOURCE_BASELINE, DESTINATION_BASELINE, HOME_BASELINE)
    assert_spent_today(EXPECTED_SPENT_TODAY, "before transfer")
    open_records()
    if find_transfer_record() is not None:
        raise AssertionError("Ambiguous transfer marker existed before test; refusing write")
    RESULT["steps"].append("verified exact Home, MainBank, and SecondBank baselines and unique marker")
    home()
    driver.find_element(AppiumBy.ACCESSIBILITY_ID, "Menu").click()
    tap_text("Transfer")
    wait_text("Transfer Funds")
    capture("02_transfer_form")
    tap_text("Source Account")
    tap_text(SOURCE, exact=False)
    tap_text("Destination Account")
    tap_text(DESTINATION, exact=False)
    exchange_rate = None
    if args.fx:
        wait_text("1 USD =", exact=False)
        rate_text = next(node.get("text", "") for node in nodes() if "1 USD =" in node.get("text", ""))
        rate_match = re.search(r"1 USD = ([0-9.]+) EUR", rate_text)
        if not rate_match:
            raise AssertionError(f"Transfer screen showed no parseable USD/EUR quote: {rate_text!r}")
        exchange_rate = Decimal(rate_match.group(1))
    tap_text("Transfer Amount")
    edit_fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
    if len(edit_fields) < 3:
        raise AssertionError(f"Expected amount EditText, found {len(edit_fields)} editable fields")
    edit_fields[2].send_keys(f"{SOURCE_BASELINE + Decimal('0.01'):.2f}")
    driver.press_keycode(4)
    wait_text("Amount exceeds available balance", exact=False)
    if button_enabled("Execute Transfer"):
        raise AssertionError("Transfer was enabled for an amount exceeding MainBank's available balance")
    RESULT["steps"].append("verified an over-balance transfer is rejected with validation feedback before submission")
    tap_text("Transfer Amount")
    edit_fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
    edit_fields[2].clear()
    edit_fields[2].send_keys(str(AMOUNT))
    destination_amount = AMOUNT
    note_field_index = 3
    if args.fx:
        driver.press_keycode(4)
        wait_text("Destination Receives")
        destination_amount = (AMOUNT * exchange_rate).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        edit_fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
        if len(edit_fields) < 4:
            raise AssertionError("Cross-currency transfer did not expose its destination amount field")
        received_amount = Decimal(edit_fields[3].get_attribute("text") or "0")
        if received_amount != destination_amount:
            raise AssertionError(
                f"Destination Receives showed {received_amount}; displayed rate predicts {destination_amount}"
            )
        RESULT["exchange_rate"] = str(exchange_rate)
        RESULT["destination_amount"] = f"{destination_amount:.2f} {DESTINATION_CURRENCY}"
        RESULT["steps"].append(
            f"verified app quote 1 {SOURCE_CURRENCY} = {exchange_rate} {DESTINATION_CURRENCY} and receive amount {destination_amount:.2f}"
        )
        note_field_index = 4
    tap_text("Note (optional)")
    edit_fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
    if len(edit_fields) <= note_field_index:
        raise AssertionError("Optional note input was not available for an exact unique record marker")
    edit_fields[note_field_index].send_keys(MARKER)
    driver.press_keycode(4)
    tap_text("Execute Transfer")
    transfer_submitted = True
    wait_text("TOTAL BALANCE")
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if any(node.get("text", "").strip() == f"{HOME_BASELINE:,.2f}" for node in nodes()):
            break
        time.sleep(0.3)
    assert_home_and_accounts(SOURCE_BASELINE - AMOUNT, DESTINATION_BASELINE + destination_amount, HOME_BASELINE)
    assert_spent_today(EXPECTED_SPENT_TODAY, "after transfer")
    RESULT["destination_amount"] = f"{destination_amount:.2f} {DESTINATION_CURRENCY}"
    RESULT["steps"].append(
        f"executed {SOURCE_CURRENCY} {AMOUNT:.2f} {SOURCE}-to-{DESTINATION} transfer; exact source/destination deltas and unchanged Home total verified"
    )
    save()
    open_records()
    if find_transfer_record() is None:
        raise AssertionError("The app did not create the unique Transfer row for the submitted operation")
    RESULT["steps"].append("verified one exact marked Transfer record in All Records")
    save()
    if EDIT_AMOUNT is not None:
        target = find_transfer_record()
        bounds = list(map(int, re.findall(r"\d+", target.get("bounds", ""))))
        x, y = (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
        driver.execute_script("mobile: longClickGesture", {"x": x, "y": y, "duration": 900})
        tap_text("Edit")
        wait_text("Edit Record")
        amount_field = next((field for field in driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
                             if (field.get_attribute("text") or "").strip() == f"{AMOUNT:.2f}"), None)
        if amount_field is None:
            raise AssertionError(f"Could not locate transfer amount {AMOUNT:.2f} for editing")
        amount_field.clear()
        amount_field.send_keys(f"{EDIT_AMOUNT:.2f}")
        driver.hide_keyboard()
        wait_text("Update")
        tap_text("Update")
        wait_text("Edit Record", present=False)
        assert_home_and_accounts(SOURCE_BASELINE - EDIT_AMOUNT, DESTINATION_BASELINE + EDIT_AMOUNT, HOME_BASELINE)
        assert_spent_today(EXPECTED_SPENT_TODAY, "after transfer edit")
        open_records()
        if find_transfer_record() is None:
            raise AssertionError("Edited transfer row was not present after update")
        RESULT["steps"].append(
            f"edited transfer from {AMOUNT:.2f} to {EDIT_AMOUNT:.2f} {SOURCE_CURRENCY}; verified source/destination balances and unchanged Home total"
        )
        RESULT["amount_after_edit"] = f"{EDIT_AMOUNT:.2f} {SOURCE_CURRENCY}"
        save()
    if args.verify_statistics:
        home()
        driver.find_element(AppiumBy.ACCESSIBILITY_ID, "Stats").click()
        wait_text("Statistics")
        stats_text = [node.get("text", "").strip() for node in nodes() if node.get("text", "").strip()]
        expected_expense_text = f"{EXPECTED_ORDINARY_EXPENSE:,.2f} EGP"
        expected_expense_value = f"{EXPECTED_ORDINARY_EXPENSE:.2f}"
        visible_expense_values = {
            text.replace(",", "").replace(" EGP", "").strip()
            for text in stats_text
        }
        if "Expense" not in stats_text or expected_expense_value not in visible_expense_values:
            raise AssertionError(
                f"Transfer changed ordinary expense total from {expected_expense_text}: {stats_text}"
            )
        for _ in range(4):
            if "Transfers" in [node.get("text", "").strip() for node in nodes() if node.get("text", "").strip()]:
                break
            adb("shell", "input", "swipe", "700", "2400", "700", "800", "450")
            time.sleep(0.3)
        stats_text = [node.get("text", "").strip() for node in nodes() if node.get("text", "").strip()]
        if "Transfers" not in stats_text:
            raise AssertionError(f"Statistics did not show the transfer summary card: {stats_text}")
        capture("04_transfer_statistics_exclusion")
        RESULT["statistics"] = {
            "ordinary_expense": expected_expense_text,
            "transfer_summary_visible": True,
        }
        RESULT["steps"].append(
            f"verified the transfer appears in Statistics Transfers while ordinary Expense remains {expected_expense_text}"
        )
        save()
    delete_exact_transfer()
    RESULT["cleanup"] = "exact marked transfer deleted; verifying both account balances after restart"
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(3)
    assert_home_and_accounts(SOURCE_BASELINE, DESTINATION_BASELINE, HOME_BASELINE)
    open_records()
    if find_transfer_record() is not None:
        raise AssertionError("Marked transfer row reappeared after deletion/restart")
    RESULT["steps"].append("deleted the exact marked transfer; restart restored both account balances and Home total")
    RESULT["cleanup"] = "transfer test removed; both account and Home baselines restored after restart"
    RESULT["status"] = "PASS"
except Exception as error:
    RESULT["status"] = "FAIL" if transfer_submitted or delete_attempted else "INCONCLUSIVE"
    RESULT["error"] = f"{type(error).__name__}: {error}"
    if transfer_submitted and not delete_attempted:
        try:
            delete_exact_transfer()
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
            time.sleep(3)
            assert_home_and_accounts(SOURCE_BASELINE, DESTINATION_BASELINE, HOME_BASELINE)
            RESULT["cleanup"] = "failed-case transfer deleted; account and Home baselines restored after restart"
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"cleanup failed: {type(cleanup_error).__name__}: {cleanup_error}"
finally:
    save()
    if driver is not None:
        try:
            driver.quit()
        except Exception:
            pass
print(json.dumps(RESULT, indent=2))
print(f"Evidence: {OUTPUT.resolve()}")
if RESULT["status"] != "PASS":
    raise SystemExit(1)
