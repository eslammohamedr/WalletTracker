"""Live-app regression for budget alerts on custom expense subcategories."""

import json
import os
import argparse
import subprocess
import sys
import time
import uuid
from pathlib import Path

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy

parser = argparse.ArgumentParser()
parser.add_argument("--cleanup-category", help="Delete only this exact QA custom subcategory")
parser.add_argument("--built-in-category-threshold", action="store_true", help="Test a built-in Groceries expense against the parent budget without creating a custom category")
parser.add_argument("--expense-amount", default="0.75", help="Case expense amount in EGP; default tests the 75% threshold")
parser.add_argument("--budget-limit", default="1.00", help="Parent budget limit in EGP")
parser.add_argument("--expected-alert-title", default="Budget Warning", help="Expected title in the Home budget dialog")
parser.add_argument("--expect-no-budget-alert", action="store_true", help="Assert no in-app budget dialog appears for the manual expense")
ARGS = parser.parse_args()

ROOT = Path(__file__).resolve().parents[1]
DEVICE = os.environ.get("ANDROID_SERIAL", "emulator-5554")
PACKAGE = "com.example.wallettrackers"
BASE_URL = os.environ.get("APPIUM_SERVER_URL", "http://127.0.0.1:4723")
RUN_ID = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6].upper()
OUT = ROOT / "app/build/device-smoke" / RUN_ID / "custom_category_budget_alert"
OUT.mkdir(parents=True, exist_ok=True)
CUSTOM_CATEGORY = ARGS.cleanup_category or ("Groceries" if ARGS.built_in_category_threshold else "QA Food " + RUN_ID)
PARENT_CATEGORY = "Food & Drinks"
DRIVER = None
CREATED_CATEGORY = False
CREATED_BUDGET = False
RESULT = {
    "case": "BUD-02_exact_budget_threshold" if ARGS.built_in_category_threshold else "BUD-01_custom_subcategory_budget_alert",
    "status": "IN_PROGRESS",
    "device": DEVICE,
    "category": CUSTOM_CATEGORY,
    "budget_category": PARENT_CATEGORY,
    "budget_limit": f"{ARGS.budget_limit} EGP",
    "expense": f"{ARGS.expense_amount} EGP",
    "steps": [],
    "cleanup": "not_started",
}


def adb(*args):
    return subprocess.check_output(["adb", "-s", DEVICE, *map(str, args)], timeout=15).decode(errors="replace").strip()


def connect():
    global DRIVER
    DRIVER = webdriver.Remote(BASE_URL, options=UiAutomator2Options().load_capabilities({
        "platformName": "Android",
        "appium:automationName": "UiAutomator2",
        "appium:deviceName": DEVICE,
        "appium:udid": DEVICE,
        "appium:appPackage": PACKAGE,
        "appium:appActivity": ".MainActivity",
        "appium:noReset": True,
        "appium:newCommandTimeout": 120,
    }))
    DRIVER.implicitly_wait(0)
    DRIVER.update_settings({"waitForIdleTimeout": 300})


def nodes():
    from xml.etree import ElementTree
    return list(ElementTree.fromstring(DRIVER.page_source).iter())


def texts():
    return [node.get("text", "").strip() for node in nodes() if node.get("text", "").strip()]


def wait_text(label, present=True, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if (label in texts()) == present:
            return
        time.sleep(0.25)
    raise AssertionError(f"Timed out waiting for {label!r} present={present}; visible={texts()}")


def tap_text(label):
    locator = (AppiumBy.ANDROID_UIAUTOMATOR, f'new UiSelector().text({json.dumps(label)})')
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        matches = DRIVER.find_elements(*locator)
        if matches:
            matches[-1].click()
            time.sleep(0.3)
            if label == "Add" and "New Subcategory" in texts():
                add_node = next((node for node in nodes() if node.get("text", "").strip() == "Add"), None)
                if add_node is None:
                    raise AssertionError(f"Add action did not close the subcategory form; visible={texts()}")
                values = list(map(int, __import__("re").findall(r"\d+", add_node.get("bounds", ""))))
                if len(values) != 4:
                    raise AssertionError("Add action has no usable screen bounds")
                adb("shell", "input", "tap", (values[0] + values[2]) // 2, (values[1] + values[3]) // 2)
                wait_text("New Subcategory", present=False)
            return
        time.sleep(0.2)
    raise AssertionError(f"Could not tap {label!r}; visible={texts()}")


def tap_desc(label):
    node = next((item for item in nodes() if item.get("content-desc", "").strip() == label), None)
    if node is None:
        raise AssertionError(f"Could not locate accessibility label {label!r}; visible={texts()}")
    values = list(map(int, __import__("re").findall(r"\d+", node.get("bounds", ""))))
    if len(values) != 4:
        raise AssertionError(f"Accessibility label {label!r} has no bounds")
    adb("shell", "input", "tap", (values[0] + values[2]) // 2, (values[1] + values[3]) // 2)
    time.sleep(0.3)


def fill(index, value):
    fields = DRIVER.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
    if index >= len(fields):
        raise AssertionError(f"Missing input #{index}; visible={texts()}")
    fields[index].click()
    fields[index].clear()
    fields[index].send_keys(str(value))


def capture(label):
    DRIVER.save_screenshot(str(OUT / f"{label}.png"))
    (OUT / f"{label}.xml").write_text(DRIVER.page_source, encoding="utf-8")


def go_home():
    for _ in range(5):
        if "TOTAL BALANCE" in texts():
            return
        candidates = DRIVER.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Home")')
        if candidates:
            candidates[-1].click()
        else:
            DRIVER.back()
        time.sleep(0.3)
    wait_text("TOTAL BALANCE")


def create_custom_category():
    global CREATED_CATEGORY
    go_home()
    tap_desc("Menu")
    wait_text("Savings Goals")
    tap_text("Categories")
    wait_text("Categories")
    tap_text(PARENT_CATEGORY)
    wait_text(PARENT_CATEGORY)
    tap_desc("Add subcategory")
    wait_text("New Subcategory")
    fill(0, CUSTOM_CATEGORY)
    capture("custom_category_form_filled")
    RESULT["category_input_value"] = DRIVER.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")[0].get_attribute("text")
    tap_text("Add")
    CREATED_CATEGORY = True
    wait_text("New Subcategory", present=False)
    size = DRIVER.get_window_size()
    deadline = time.monotonic() + 10
    while CUSTOM_CATEGORY not in texts() and time.monotonic() < deadline:
        adb("shell", "input", "swipe", size["width"] // 2, int(size["height"] * .84),
            size["width"] // 2, int(size["height"] * .30), 450)
        time.sleep(0.25)
    wait_text(CUSTOM_CATEGORY)
    capture("custom_category_created")
    RESULT["steps"].append("created the exact custom subcategory through the app UI")


def create_budget():
    global CREATED_BUDGET
    go_home()
    tap_text("Budget")
    wait_text("Budgets")
    if PARENT_CATEGORY in texts():
        raise AssertionError(f"A pre-existing {PARENT_CATEGORY!r} budget is visible; refusing to modify it")
    tap_desc("Add Budget")
    wait_text("Select Category")
    capture("budget_dialog_open")
    tap_text("Select Category")
    wait_text(PARENT_CATEGORY)
    tap_text(PARENT_CATEGORY)
    wait_text("Monthly Limit")
    fill(1, ARGS.budget_limit)
    capture("budget_limit_entered")
    capture("budget_form")
    tap_text("Create")
    CREATED_BUDGET = True
    wait_text(PARENT_CATEGORY)
    capture("budget_created")
    RESULT["steps"].append(f"created a fresh EGP {ARGS.budget_limit} parent-category budget through the app UI")


def delete_budget():
    go_home()
    tap_text("Budget")
    wait_text("Budgets")
    matches = DRIVER.find_elements(AppiumBy.ACCESSIBILITY_ID, "Delete")
    if not matches:
        raise AssertionError("Could not find the Delete action on the case-owned budget")
    matches[-1].click()
    wait_text("Delete Budget")
    wait_text(f'Remove budget for "{PARENT_CATEGORY}"?')
    tap_text("Delete")
    wait_text(f'Remove budget for "{PARENT_CATEGORY}"?', present=False)
    wait_text(PARENT_CATEGORY, present=False)
    RESULT["steps"].append("deleted only the case-created parent budget through its confirmation")


def delete_custom_category():
    go_home()
    tap_desc("Menu")
    wait_text("Savings Goals")
    tap_text("Categories")
    wait_text("Categories")
    tap_text(PARENT_CATEGORY)
    wait_text(PARENT_CATEGORY)
    deadline = time.monotonic() + 10
    while CUSTOM_CATEGORY not in texts() and time.monotonic() < deadline:
        size = DRIVER.get_window_size()
        adb("shell", "input", "swipe", size["width"] // 2, int(size["height"] * .84),
            size["width"] // 2, int(size["height"] * .30), 450)
    size = DRIVER.get_window_size()
    deadline = time.monotonic() + 10
    while CUSTOM_CATEGORY not in texts() and time.monotonic() < deadline:
        adb("shell", "input", "swipe", size["width"] // 2, int(size["height"] * .84),
            size["width"] // 2, int(size["height"] * .30), 450)
        time.sleep(0.25)
    wait_text(CUSTOM_CATEGORY)
    custom_node = next(node for node in nodes() if node.get("text", "").strip() == CUSTOM_CATEGORY)
    row_center = sum(map(int, __import__("re").findall(r"\d+", custom_node.get("bounds", ""))[1::2])) // 2
    delete_nodes = [node for node in nodes() if node.get("content-desc", "").strip() == "Delete"]
    if not delete_nodes:
        raise AssertionError("Could not find custom-subcategory delete control")
    def center_y(node):
        values = list(map(int, __import__("re").findall(r"\d+", node.get("bounds", ""))))
        return (values[1] + values[3]) // 2
    delete_node = min(delete_nodes, key=lambda node: abs(center_y(node) - row_center))
    values = list(map(int, __import__("re").findall(r"\d+", delete_node.get("bounds", ""))))
    if len(values) != 4:
        raise AssertionError("Custom-subcategory Delete control has no usable bounds")
    adb("shell", "input", "tap", (values[0] + values[2]) // 2, (values[1] + values[3]) // 2)
    wait_text(CUSTOM_CATEGORY, present=False)
    RESULT["steps"].append("deleted only the case-created custom subcategory through the app UI")


def main():
    global DRIVER, CREATED_CATEGORY, CREATED_BUDGET
    try:
        avd_name = adb("emu", "avd", "name").splitlines()[0]
        if avd_name not in {"Wallet_23Cases_Temp", "Wallet_Onboarding_QA"}:
            raise AssertionError(f"Refusing non-QA AVD {avd_name!r}")
        connect()
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        go_home()
        RESULT["avd"] = avd_name
        capture("preflight_home")
        if ARGS.cleanup_category:
            delete_custom_category()
            RESULT["status"] = "PASS"
            RESULT["cleanup"] = f"deleted and verified exact QA category {CUSTOM_CATEGORY!r}"
            return
        if not ARGS.built_in_category_threshold:
            create_custom_category()
        create_budget()
        DRIVER.quit()
        DRIVER = None
        started = time.time()
        command = [
            sys.executable, str(ROOT / "scripts/device_manual_record_case.py"),
            "--device", DEVICE, "--type", "Expense", "--account", "MainBank",
            "--category", "Groceries" if ARGS.built_in_category_threshold else CUSTOM_CATEGORY,
            "--amount", ARGS.expense_amount, "--account-baseline", "10000.00",
            "--home-baseline", "16000.00",
        ]
        if ARGS.expect_no_budget_alert:
            command.append("--expect-no-budget-alert")
        else:
            percent = float(ARGS.expense_amount) / float(ARGS.budget_limit) * 100
            percent_text = f"{max(1, int(percent) - 100)}% over limit" if float(ARGS.expense_amount) > float(ARGS.budget_limit) else f"{int(percent)}% of budget used"
            command.extend([
                "--expect-budget-alert", "--expect-budget-alert-category", PARENT_CATEGORY,
                "--expect-budget-alert-title", ARGS.expected_alert_title,
                "--expect-budget-alert-percent", percent_text,
            ])
        if not ARGS.built_in_category_threshold:
            command.extend(["--category-parent", PARENT_CATEGORY])
        completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=360)
        RESULT["expense_test_exit_code"] = completed.returncode
        RESULT["expense_test_output"] = completed.stdout[-2000:]
        if completed.returncode != 0:
            raise AssertionError(f"Live custom-category expense/budget assertion failed: {completed.stderr[-2000:]}")
        RESULT["expense_case_evidence"] = completed.stdout.split("Evidence: ")[-1].strip()
        RESULT["steps"].append(
            "live manual expense confirmed no dialog below threshold and rollback"
            if ARGS.expect_no_budget_alert else
            "live manual expense displayed the exact budget threshold dialog and runner verified record/balance rollback"
        )
        adb("logcat", "-c")
        sms_command = [
            sys.executable, str(ROOT / "scripts/device_sms_expense_case.py"),
            "--ui-backend", "appium", "--device", DEVICE, "--account-name", "MainBank",
            "--account-suffix", "1111", "--account-type", "debit",
            "--baseline-balance", "10000.00", "--baseline-dashboard-total", "16000.00",
            "--amount", ARGS.expense_amount, "--currency", "EGP", "--merchant", "Carrefour",
            "--category", "Groceries",
        ]
        amount = float(ARGS.expense_amount)
        limit = float(ARGS.budget_limit)
        ratio = amount / limit
        if 0.75 <= ratio < 1.0:
            sms_command.extend([
                "--expect-notification-title", "Budget Warning: Food & Drinks",
                "--expect-notification-body", f"Spent {amount:.2f} / {limit:.2f} EGP ({int(ratio * 100)}%)",
            ])
        elif ratio >= 1.0:
            notification_body = (
                f"Spent {amount:.2f} / {limit:.2f} EGP (100% of budget used)"
                if amount == limit else
                f"Spent {amount:.2f} EGP ({max(1, int(ratio * 100) - 100)}% over limit)"
            )
            sms_command.extend([
                "--expect-notification-title", "Budget Exceeded: Food & Drinks",
                "--expect-notification-body", notification_body,
            ])
        else:
            sms_command.extend([
                "--forbid-notification-text", f"Spent {amount:.2f} / {limit:.2f} EGP",
            ])
        if not ARGS.built_in_category_threshold:
            sms_command.extend(["--rule-category", CUSTOM_CATEGORY, "--rule-parent", PARENT_CATEGORY])
        sms_case = subprocess.run(sms_command, cwd=ROOT, capture_output=True, text=True, timeout=480)
        RESULT["sms_test_exit_code"] = sms_case.returncode
        RESULT["sms_test_output"] = sms_case.stdout[-2000:]
        if sms_case.returncode != 0:
            raise AssertionError(f"Live custom-category SMS case failed: {sms_case.stderr[-2000:]}")
        budget_logs = adb("logcat", "-d", "-s", "BudgetAlertHelper")
        (OUT / "sms_budget_alert_logcat.txt").write_text(budget_logs, encoding="utf-8")
        if ratio < 0.75:
            expected_sms_alert = None
        elif ratio >= 1.0:
            expected_sms_alert = f"Budget EXCEEDED: Food & Drinks spent={amount} limit={limit}"
        else:
            expected_sms_alert = f"Budget WARNING: Food & Drinks spent={amount} limit={limit} ({int(ratio * 100)}%)"
        if expected_sms_alert is None and "Budget WARNING:" in budget_logs or expected_sms_alert is None and "Budget EXCEEDED:" in budget_logs:
            raise AssertionError(f"SMS path unexpectedly logged a budget alert below 75%: {budget_logs[-2500:]}")
        if expected_sms_alert is not None and expected_sms_alert not in budget_logs:
            raise AssertionError(f"SMS path did not log the custom-subcategory parent budget alert: {budget_logs[-2500:]}")
        RESULT["steps"].append("real SMS receiver created the expense; BudgetAlertHelper logged the exact parent budget threshold")
        connect()
        RESULT["steps"].append("SMS-runner evidence includes notification assertion before force-stop and exact financial rollback")
        go_home()
        delete_budget()
        CREATED_BUDGET = False
        if CREATED_CATEGORY:
            delete_custom_category()
            CREATED_CATEGORY = False
        RESULT["status"] = "PASS"
        RESULT["cleanup"] = (
            "expense record and parent budget removed and validated"
            if ARGS.built_in_category_threshold else
            "expense record, parent budget, and custom subcategory each removed and validated"
        )
    except Exception as error:
        RESULT["status"] = "FAIL"
        RESULT["error"] = f"{type(error).__name__}: {error}"
        RESULT["cleanup"] = "case-owned cleanup attempted; review evidence before rerun"
    finally:
        if CREATED_BUDGET or CREATED_CATEGORY:
            try:
                if DRIVER is None:
                    connect()
                if CREATED_BUDGET:
                    delete_budget()
                    CREATED_BUDGET = False
                if CREATED_CATEGORY:
                    if CUSTOM_CATEGORY in texts():
                        delete_custom_category()
                    CREATED_CATEGORY = False
                RESULT["cleanup"] = "case-owned budget/category removed through UI cleanup"
            except Exception as cleanup_error:
                RESULT["cleanup"] = f"MANUAL REVIEW REQUIRED: {cleanup_error}"
                RESULT["status"] = "FAIL"
        if DRIVER is not None:
            DRIVER.quit()
        (OUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
