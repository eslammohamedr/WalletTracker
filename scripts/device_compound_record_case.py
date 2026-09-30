import argparse
import json
import re
import subprocess
import sys
import time
from decimal import Decimal
from pathlib import Path
from xml.etree import ElementTree

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy


DEVICE = "emulator-5554"
PACKAGE = "com.example.wallettrackers"
BASE_ACCOUNT = Decimal("10000.00")
BASE_HOME = Decimal("16000.00")
INCOME = Decimal("2500.00")
EXPENSE = Decimal("750.00")
EDITED_EXPENSE = Decimal("900.00")
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / "compound_income_expense_case"
OUTPUT.mkdir(parents=True, exist_ok=True)
parser = argparse.ArgumentParser(description="Run compound live-app record/statistics cases.")
parser.add_argument("--scenario", choices=("NUM-04", "NUM-15"), default="NUM-04")
SCENARIO = parser.parse_args().scenario
RESULT = {
    "case": f"{SCENARIO}_compound_income_expense_and_rollback",
    "status": "IN_PROGRESS",
    "baseline": {"MainBank": f"{BASE_ACCOUNT:.2f}", "Home": f"{BASE_HOME:.2f}"},
    "income": f"{INCOME:.2f} EGP",
    "expense": f"{EXPENSE:.2f} EGP",
    "edited_expense": f"{EDITED_EXPENSE:.2f} EGP",
    "steps": [],
    "cleanup": "not_started",
}
driver = None
created = []


def adb(*command, timeout=10):
    return subprocess.check_output(["adb", "-s", DEVICE, *command], timeout=timeout).decode("utf-8", errors="replace")


def root():
    return ElementTree.fromstring(driver.page_source)


def nodes():
    return list(root().iter())


def texts():
    return [node.get("text", "").strip() for node in nodes() if node.get("text", "").strip()]


def bounds(node):
    values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(values) != 4:
        raise AssertionError(f"Unusable UI bounds: {node.attrib}")
    return (values[0] + values[2]) // 2, (values[1] + values[3]) // 2


def tap_node(node):
    x, y = bounds(node)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.3)


def tap_text(label, occurrence=-1):
    matches = [node for node in nodes() if node.get("text", "").strip() == label]
    if not matches:
        matches = [node for node in nodes() if node.get("content-desc", "").strip() == label]
    if not matches:
        raise AssertionError(f"Missing UI element {label!r}; visible={texts()}")
    tap_node(matches[occurrence])


def wait_text(label, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if any(label.casefold() in text.casefold() for text in texts()):
            return
        time.sleep(0.3)
    raise AssertionError(f"Timed out waiting for {label!r}; visible={texts()}")


def capture(name):
    (OUTPUT / f"{name}.xml").write_bytes(ElementTree.tostring(root(), encoding="utf-8"))
    (OUTPUT / f"{name}.png").write_bytes(subprocess.check_output(["adb", "-s", DEVICE, "exec-out", "screencap", "-p"], timeout=10))


def home():
    for _ in range(3):
        if "TOTAL BALANCE" in texts():
            return
        driver.back()
        time.sleep(0.3)
    if "TOTAL BALANCE" not in texts():
        tap_text("Home")
    wait_text("TOTAL BALANCE")


def account_balance(name, expected):
    expected = Decimal(expected)
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    width, height = map(int, size.groups()) if size else (1440, 3120)
    for start, end in ((0.9, 0.15), (0.15, 0.9)):
        for _ in range(12):
            current = nodes()
            accounts = [node for node in current if node.get("text", "").strip() == name]
            for account_node in accounts:
                ax, ay = bounds(account_node)
                for value_node in current:
                    value = value_node.get("text", "").replace(",", "").replace("EGP", "").strip()
                    try:
                        amount = Decimal(value)
                    except Exception:
                        continue
                    x, y = bounds(value_node)
                    if abs(x - ax) <= 320 and 0 <= y - ay <= 400 and amount == expected:
                        return True
            adb("shell", "input", "swipe", str(int(width * start)), str(int(height * 0.7)),
                str(int(width * end)), str(int(height * 0.7)), "350")
            time.sleep(0.3)
    return False


def assert_totals(account, total):
    home()
    if f"{total:,.2f}" not in texts():
        raise AssertionError(f"Home expected {total:,.2f}; visible={texts()}")
    if not account_balance("MainBank", account):
        raise AssertionError(f"MainBank expected {account:.2f}")


def create_record(record_type, category, amount):
    home()
    tap_text("Add Record")
    wait_text("Add Record")
    tap_text(record_type)
    tap_text("Select Category")
    wait_text("Categories")
    visible = texts()
    if record_type == "Income":
        if record_type not in visible:
            adb("shell", "input", "swipe", "700", "2450", "700", "650", "500")
            wait_text(record_type)
        tap_text(record_type)
    else:
        parent = "Food & Drinks" if category in {"Groceries", "Restaurants"} else category
        if parent not in visible:
            adb("shell", "input", "swipe", "700", "650", "700", "2450", "500")
            wait_text(parent)
        tap_text(parent)
    tap_text(category)
    wait_text("Confirm Record")
    if not any(node.get("text", "").strip() == "MainBank" for node in nodes()):
        adb("shell", "input", "swipe", "700", "950", "700", "450", "500")
        wait_text("MainBank")
    tap_text("MainBank")
    adb("shell", "input", "swipe", "700", "800", "700", "2500", "450")
    time.sleep(0.3)
    tap_text(record_type)
    for _ in range(10):
        tap_text("Backspace")
    for digit in f"{amount:.2f}":
        matches = [node for node in nodes() if node.get("text", "").strip() == digit]
        keypad = [node for node in matches if bounds(node)[1] > 1500]
        if not keypad:
            raise AssertionError(f"Numeric pad key {digit!r} not found")
        tap_node(keypad[-1])
    adb("shell", "input", "swipe", "700", "450", "700", "950", "450")
    time.sleep(0.3)
    if not any(f"{amount:.2f}" in text for text in texts()):
        raise AssertionError(f"Amount {amount:.2f} was not entered; visible={texts()}")
    capture(f"form_{record_type.lower()}")
    tap_text("Confirm Record")
    created.append((record_type, category, amount))
    wait_text("TOTAL BALANCE")
    RESULT["steps"].append(f"created {record_type} / {category} / {amount:.2f} EGP / MainBank")
    time.sleep(1)


def matching_row(category, amount_text):
    current = nodes()
    parent = {child: ancestor for ancestor in current for child in ancestor}
    found = []
    for amount_node in current:
        if amount_node.get("text", "").strip() != amount_text:
            continue
        candidate = amount_node
        while candidate is not None:
            row_text = " ".join(node.get("text", "") for node in candidate.iter())
            box = list(map(int, re.findall(r"\d+", candidate.get("bounds", ""))))
            if len(box) == 4 and box[3] - box[1] < 500 and all(word in row_text for word in (category, "MainBank", amount_text)):
                found.append(candidate)
                break
            candidate = parent.get(candidate)
    if len(found) > 1:
        raise AssertionError(f"Expected a unique {category}/{amount_text} row, found {len(found)}")
    return found[0] if found else None


def delete_record(category, amount_text):
    home()
    tap_text("Records")
    wait_text("All Records")
    row = matching_row(category, amount_text)
    if row is None:
        raise AssertionError(f"Exact record {category}/{amount_text} not found")
    x, y = bounds(row)
    adb("shell", "input", "swipe", str(x), str(y), str(x + 3), str(y + 3), "1000")
    wait_text("Delete")
    tap_text("Delete")
    wait_text("Delete Record")
    buttons = [node for node in nodes() if node.get("text", "").strip() == "Delete"]
    tap_node(buttons[-1])
    time.sleep(2)
    RESULT["cleanup"] = f"deleted exact {category}/{amount_text} row"
    RESULT["steps"].append(f"deleted only the {category} record")


def edit_expense():
    home()
    tap_text("Records")
    wait_text("All Records")
    row = matching_row("Groceries", f"-{EXPENSE:.2f} EGP")
    if row is None:
        raise AssertionError("Original Groceries record was not found for edit")
    x, y = bounds(row)
    adb("shell", "input", "swipe", str(x), str(y), str(x + 3), str(y + 3), "1000")
    wait_text("Edit")
    tap_text("Edit")
    wait_text("Edit Record")
    amount_field = next(
        (field for field in driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
         if (field.get_attribute("text") or "").strip() == f"{EXPENSE:.2f}"),
        None,
    )
    if amount_field is None:
        raise AssertionError("Could not locate the expense amount field for edit")
    amount_field.clear()
    amount_field.send_keys(f"{EDITED_EXPENSE:.2f}")
    driver.hide_keyboard()
    wait_text("Edit Record")
    if (amount_field.get_attribute("text") or "").strip() != f"{EDITED_EXPENSE:.2f}":
        raise AssertionError("Edited expense amount was not retained after keyboard dismissal")
    tap_text("Groceries")
    wait_text("Categories")
    tap_text("Food & Drinks")
    wait_text("Food & Drinks")
    tap_text("Restaurants")
    wait_text("Edit Record")
    if "Restaurants" not in texts():
        raise AssertionError(f"Edited category was not retained; visible={texts()}")
    tap_text("Update")
    time.sleep(2)
    created[-1] = ("Expense", "Restaurants", EDITED_EXPENSE)
    RESULT["steps"].append("edited the Groceries expense from EGP 750 to EGP 900 and changed its category to Restaurants")


def run_num15():
    global driver
    create_record("Income", "Salary", INCOME)
    assert_totals(BASE_ACCOUNT + INCOME, BASE_HOME + INCOME)
    create_record("Expense", "Groceries", EXPENSE)
    assert_totals(BASE_ACCOUNT + INCOME - EXPENSE, BASE_HOME + INCOME - EXPENSE)
    create_record("Expense", "Restaurants", Decimal("250.00"))
    expected_account = BASE_ACCOUNT + INCOME - EXPENSE - Decimal("250.00")
    expected_home = BASE_HOME + INCOME - EXPENSE - Decimal("250.00")
    assert_totals(expected_account, expected_home)

    tap_text("Records")
    wait_text("All Records")
    expected_rows = (
        ("Salary", f"+{INCOME:.2f} EGP"),
        ("Groceries", f"-{EXPENSE:.2f} EGP"),
        ("Restaurants", "-250.00 EGP"),
    )
    if any(matching_row(category, amount) is None for category, amount in expected_rows):
        raise AssertionError("One or more combined NUM-15 records are missing or incorrect")
    RESULT["steps"].append("verified one exact Salary, Groceries, and Restaurants record with the expected signed amount")
    capture("num15_combined_records")

    def run_nested_case(script_name, result_folder, arguments):
        global driver
        if driver is not None:
            driver.quit()
            driver = None
        results_root = Path("app/build/device-smoke")
        previous_results = set(results_root.glob(f"*/{result_folder}/results.json"))
        script_path = Path(__file__).resolve().parent / script_name
        try:
            completed = subprocess.run(
                [sys.executable, str(script_path), *arguments],
                cwd=Path(__file__).resolve().parents[1],
                capture_output=True,
                text=True,
                timeout=300,
            )
            if completed.returncode != 0:
                raise AssertionError(
                    f"{script_name} failed ({completed.returncode}): "
                    f"{completed.stdout[-2000:]} {completed.stderr[-1000:]}"
                )
            new_results = set(results_root.glob(f"*/{result_folder}/results.json")) - previous_results
            if len(new_results) != 1:
                raise AssertionError(f"Could not identify one new {script_name} result: {sorted(map(str, new_results))}")
            child_result_path = new_results.pop()
            child_result = json.loads(child_result_path.read_text(encoding="utf-8"))
            if child_result.get("status") != "PASS":
                raise AssertionError(f"{script_name} was not PASS: {child_result.get('status')}")
            child_result.setdefault("evidence", str(child_result_path.parent.resolve()))
            return child_result
        finally:
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
            driver = webdriver.Remote("http://127.0.0.1:4723", options=options)
            driver.implicitly_wait(0)
            adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
            time.sleep(2)
            home()

    home()
    transfer_result = run_nested_case(
        "device_transfer_case.py",
        "live_in_app_transfer",
        [
            "--execute", "--device", DEVICE, "--amount", "1200.00",
            "--source-baseline", "11500.00", "--destination-baseline", "5000.00",
            "--home-baseline", "17500.00", "--expected-spent-today", "1000.00",
            "--expected-ordinary-expense", "1000.00", "--verify-statistics",
        ],
    )
    RESULT["transfer_evidence"] = transfer_result.get("evidence")
    RESULT["steps"].append(
        "executed and rolled back EGP 1,200 MainBank-to-SecondBank transfer while verifying Spent Today and Statistics Expense stayed at EGP 1,000"
    )

    home()
    payment_result = run_nested_case(
        "device_credit_payment_case.py",
        "credit_payment",
        [
            "--execute", "--device", DEVICE, "--order", "credit-first",
            "--source-account", "SecondBank", "--source-suffix", "2222",
            "--source-baseline", "5000.00", "--card-baseline", "3000.00",
            "--home-baseline", "17500.00", "--amount", "1200.00",
            "--expected-spent-today", "1000.00", "--expected-ordinary-expense", "1000.00",
            "--verify-statistics",
        ],
    )
    RESULT["credit_payment_evidence"] = payment_result.get("evidence")
    RESULT["steps"].append(
        "executed and rolled back EGP 1,200 credit payment while verifying Spent Today and Statistics Expense stayed at EGP 1,000"
    )

    home()
    tap_text("Stats")
    wait_text("Statistics")
    expected_values = ("1,000.00 EGP", "+1,500.00 EGP")
    summary_text = set()
    for _ in range(6):
        summary_text.update(texts())
        if all(value in summary_text for value in expected_values):
            break
        adb("shell", "input", "swipe", "700", "800", "700", "2400", "450")
        time.sleep(0.3)
    if any(value not in summary_text for value in expected_values):
        raise AssertionError(f"Combined Statistics expected expense/net {expected_values}; scanned={sorted(summary_text)}")

    visible_category_text = set(summary_text)
    for _ in range(6):
        visible_category_text.update(texts())
        if {"Groceries", "Restaurants"}.issubset(visible_category_text):
            break
        adb("shell", "input", "swipe", "700", "2400", "700", "800", "450")
        time.sleep(0.3)
    if not {"Groceries", "Restaurants"}.issubset(visible_category_text):
        raise AssertionError(f"Statistics did not show both categories; scanned={sorted(visible_category_text)}")
    if "750.00 EGP" not in visible_category_text or "250.00 EGP" not in visible_category_text:
        raise AssertionError(f"Statistics category amounts were not both observed; scanned={sorted(visible_category_text)}")
    RESULT["statistics"] = {
        "income": f"{INCOME:.2f} EGP",
        "expense": "1,000.00 EGP",
        "net": "+1,500.00 EGP",
        "Groceries": "750.00 EGP",
        "Restaurants": "250.00 EGP",
    }
    RESULT["steps"].append("verified combined current-month expense, net, and Groceries/Restaurants category totals")
    capture("num15_combined_statistics")

    for category, amount in reversed(expected_rows):
        delete_record(category, amount)
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        time.sleep(2)
    assert_totals(BASE_ACCOUNT, BASE_HOME)
    tap_text("Records")
    wait_text("All Records")
    if any(matching_row(category, amount) is not None for category, amount in expected_rows):
        raise AssertionError("A NUM-15 test record remained after cleanup and restart")
    RESULT["steps"].append("deleted only the three test-owned records and verified exact fixture restoration after restart")
    RESULT["cleanup"] = "all three test records absent; MainBank/Home baseline restored after restart"
    RESULT["status"] = "PASS"


try:
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
    driver = webdriver.Remote("http://127.0.0.1:4723", options=options)
    driver.implicitly_wait(0)
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(2)
    assert_totals(BASE_ACCOUNT, BASE_HOME)
    RESULT["steps"].append("verified clean MainBank and Home baseline")

    if SCENARIO == "NUM-15":
        run_num15()
        raise SystemExit(0)

    create_record("Income", "Salary", INCOME)
    assert_totals(BASE_ACCOUNT + INCOME, BASE_HOME + INCOME)
    capture("income_added")
    create_record("Expense", "Groceries", EXPENSE)
    assert_totals(BASE_ACCOUNT + INCOME - EXPENSE, BASE_HOME + INCOME - EXPENSE)
    capture("expense_added")
    edit_expense()
    assert_totals(BASE_ACCOUNT + INCOME - EDITED_EXPENSE, BASE_HOME + INCOME - EDITED_EXPENSE)
    tap_text("Records")
    wait_text("All Records")
    salary = matching_row("Salary", f"+{INCOME:.2f} EGP")
    restaurants = matching_row("Restaurants", f"-{EDITED_EXPENSE:.2f} EGP")
    groceries = matching_row("Groceries", f"-{EXPENSE:.2f} EGP")
    if salary is None or restaurants is None or groceries is not None:
        raise AssertionError(
            f"Expected one Salary and one Restaurants row, with no original Groceries row; "
            f"salary={salary is not None}, restaurants={restaurants is not None}, groceries={groceries is not None}"
        )
    RESULT["steps"].append("verified exact salary and edited Restaurants records; original Groceries record is absent")
    capture("edited_expense_records_verified")
    tap_text("Stats")
    wait_text("Statistics")
    expected_expense = f"{EDITED_EXPENSE:,.2f} EGP"
    expected_net = f"+{INCOME - EDITED_EXPENSE:,.2f} EGP"
    stats_text = texts()
    if expected_expense not in stats_text or expected_net not in stats_text:
        raise AssertionError(f"Statistics totals do not show expense {expected_expense} and net {expected_net}; visible={stats_text}")
    for _ in range(5):
        if "Restaurants" in texts():
            break
        adb("shell", "input", "swipe", "700", "2400", "700", "800", "450")
        time.sleep(0.3)
    stats_text = texts()
    if "Restaurants" not in stats_text or expected_expense not in stats_text:
        raise AssertionError(f"Statistics did not show Restaurants totaling {expected_expense}; visible={stats_text}")
    if "Groceries" in stats_text:
        raise AssertionError(f"Statistics still show the old Groceries grouping after edit; visible={stats_text}")
    RESULT["statistics"] = {
        "expense": expected_expense,
        "net": expected_net,
        "Restaurants": expected_expense,
        "Groceries": "absent",
    }
    RESULT["steps"].append("verified Statistics expense/net and Restaurants grouping; old Groceries grouping is absent")
    capture("statistics_after_expense_edit")
    home()

    delete_record("Restaurants", f"-{EDITED_EXPENSE:.2f} EGP")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(3)
    assert_totals(BASE_ACCOUNT + INCOME, BASE_HOME + INCOME)
    tap_text("Records")
    wait_text("All Records")
    salary = matching_row("Salary", f"+{INCOME:.2f} EGP")
    expense = matching_row("Restaurants", f"-{EDITED_EXPENSE:.2f} EGP")
    if salary is None or expense is not None:
        raise AssertionError(f"After deleting Restaurants, Salary must remain once; salary={salary is not None}, expense={expense is not None}")
    RESULT["steps"].append("verified Salary remains once, edited Restaurants is absent, and totals are 12,500 / 18,500 after restart")
    capture("expense_deleted_salary_retained")

    delete_record("Salary", f"+{INCOME:.2f} EGP")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(3)
    assert_totals(BASE_ACCOUNT, BASE_HOME)
    tap_text("Records")
    wait_text("All Records")
    if (matching_row("Salary", f"+{INCOME:.2f} EGP") is not None
            or matching_row("Groceries", f"-{EXPENSE:.2f} EGP") is not None
            or matching_row("Restaurants", f"-{EDITED_EXPENSE:.2f} EGP") is not None):
        raise AssertionError("Compound test records remained after final cleanup")
    RESULT["status"] = "PASS"
    RESULT["cleanup"] = "both test records absent; exact baseline restored after restart"
    RESULT["steps"].append("deleted retained Salary only after verifying its post-expense state; restored fixture baseline")
except Exception as error:
    RESULT["status"] = "FAIL"
    RESULT["error"] = f"{type(error).__name__}: {error}"
    if created:
        try:
            for record_type, category, amount in reversed(created):
                amount_text = f"{'+' if record_type == 'Income' else '-'}{amount:.2f} EGP"
                home()
                tap_text("Records")
                wait_text("All Records")
                if matching_row(category, amount_text) is not None:
                    delete_record(category, amount_text)
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
            time.sleep(3)
            assert_totals(BASE_ACCOUNT, BASE_HOME)
            RESULT["cleanup"] = "failure-path test rows removed; fixture baseline restored after restart"
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"manual review required; cleanup failed: {type(cleanup_error).__name__}: {cleanup_error}; rows={json.dumps(created, default=str)}"
    raise
finally:
    RESULT["evidence"] = str(OUTPUT.resolve())
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")
    if driver is not None:
        driver.quit()

print(json.dumps(RESULT, indent=2))
print(f"Evidence: {OUTPUT.resolve()}")
