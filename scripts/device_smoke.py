import argparse
import json
import re
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy
from selenium.common.exceptions import NoSuchElementException, TimeoutException
from selenium.webdriver.support.ui import WebDriverWait


parser = argparse.ArgumentParser(description="Black-box Appium tests for the installed Wallet app")
parser.add_argument("--device", required=True, help="ADB serial, for example emulator-5554")
parser.add_argument("--server", default="http://127.0.0.1:4723")
parser.add_argument("--output", default="app/build/device-smoke")
parser.add_argument("--extended", action="store_true", help="Test budget validation and every Statistics tab")
parser.add_argument("--only", help="Run case names containing this text; launch is always checked")
args = parser.parse_args()

package = "com.example.wallettrackers"
output = Path(args.output) / time.strftime("%Y%m%d-%H%M%S")
output.mkdir(parents=True, exist_ok=True)
results = []
options = UiAutomator2Options().load_capabilities({
    "platformName": "Android",
    "appium:automationName": "UiAutomator2",
    "appium:deviceName": args.device,
    "appium:udid": args.device,
    "appium:appPackage": package,
    "appium:appActivity": ".MainActivity",
    "appium:noReset": True,
    "appium:newCommandTimeout": 180,
})
driver = webdriver.Remote(args.server.rstrip("/"), options=options)
driver.implicitly_wait(0)


def text_element(label):
    escaped = json.dumps(label)
    return driver.find_element(AppiumBy.ANDROID_UIAUTOMATOR, f"new UiSelector().text({escaped})")


def wait_text(label, timeout=30):
    try:
        return WebDriverWait(driver, timeout, poll_frequency=0.5).until(
            lambda current: text_element(label)
        )
    except (TimeoutException, NoSuchElementException) as error:
        raise AssertionError(f"Expected text {label!r} on screen") from error


def wait_accessibility(label, timeout=30):
    try:
        return WebDriverWait(driver, timeout, poll_frequency=0.5).until(
            lambda current: current.find_element(AppiumBy.ACCESSIBILITY_ID, label)
        )
    except (TimeoutException, NoSuchElementException) as error:
        raise AssertionError(f"Expected accessibility label {label!r} on screen") from error


def tap_text(label):
    wait_text(label).click()


def tap_accessibility(label):
    wait_accessibility(label).click()


def go_home():
    tap_accessibility("Home")
    wait_text("TOTAL BALANCE")


def capture(name):
    driver.save_screenshot(str(output / f"{name}.png"))
    (output / f"{name}.xml").write_text(driver.page_source, encoding="utf-8")


def case(name, action):
    if args.only and name != "01_launch_dashboard" and args.only.casefold() not in name.casefold():
        return
    started = time.monotonic()
    result = {"name": name, "status": "PASS"}
    try:
        action()
    except Exception as error:
        result.update(status="FAIL", error=f"{type(error).__name__}: {error}")
    try:
        capture(name)
    except Exception as error:
        result["capture_error"] = f"{type(error).__name__}: {error}"
    result["seconds"] = round(time.monotonic() - started, 2)
    results.append(result)
    print(json.dumps(result), flush=True)
    (output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")


def launch_dashboard():
    driver.activate_app(package)
    if not driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("TOTAL BALANCE")'):
        tap_accessibility("Home")
    wait_text("TOTAL BALANCE")


def navigation(destination, title):
    go_home()
    tap_accessibility(destination)
    wait_text(title)
    go_home()


def cancel_add_record():
    go_home()
    tap_accessibility("Add Record")
    wait_text("Add Record")
    driver.back()
    wait_text("TOTAL BALANCE")


def background_resume():
    go_home()
    driver.background_app(2)
    driver.activate_app(package)
    wait_text("TOTAL BALANCE")


def process_restart():
    driver.terminate_app(package)
    driver.activate_app(package)
    wait_text("TOTAL BALANCE")


def button_enabled(label):
    root = ET.fromstring(driver.page_source)
    parents = {child: parent for parent in root.iter() for child in parent}
    node = next((element for element in root.iter() if element.attrib.get("text") == label), None)
    while node is not None:
        if node.attrib.get("clickable") == "true":
            return node.attrib.get("enabled") == "true"
        node = parents.get(node)
    raise AssertionError(f"Could not find the enabled state for button {label!r}")


def budget_validation(amount=None):
    go_home()
    tap_accessibility("Budget")
    wait_text("Budgets")
    tap_accessibility("Add Budget")
    wait_text("Monthly Limit")
    try:
        if amount is not None:
            tap_text("Select Category")
            tap_text("Food & Drinks")
            field = driver.find_element(
                AppiumBy.XPATH,
                '//android.widget.EditText[.//*[@text="Monthly Limit"]]',
            )
            field.click()
            field.send_keys(amount)
            entered = field.get_attribute("text") or ""
            if amount not in entered:
                raise AssertionError(f"Amount {amount!r} was not entered; field contains {entered!r}")
            if driver.is_keyboard_shown():
                driver.hide_keyboard()
        capture("budget-input-" + (amount or "empty"))
        if button_enabled("Create"):
            raise AssertionError(f"Create is enabled for limit {amount!r}")
    finally:
        if "Monthly Limit" in driver.page_source:
            driver.back()
        if "TOTAL BALANCE" not in driver.page_source:
            tap_accessibility("Home")
        wait_text("TOTAL BALANCE")


def statistics_tab(label):
    go_home()
    tap_accessibility("Stats")
    wait_text("Statistics")
    tab = text_element(label)
    tab.click()
    wait_text(label)
    if not text_element(label).is_displayed():
        raise AssertionError(f"Statistics tab {label!r} is not displayed after tapping")
    if re.search(r"\b(?:NaN|Infinity)\b", driver.page_source):
        raise AssertionError("A non-finite value is visible on Statistics")
    go_home()


def run_cases():
    case("01_launch_dashboard", launch_dashboard)
    if results[-1]["status"] != "PASS":
        return
    case("02_records_round_trip", lambda: navigation("Records", "All Records"))
    case("03_statistics_round_trip", lambda: navigation("Stats", "Statistics"))
    case("04_cancel_add_record", cancel_add_record)
    case("05_background_resume", background_resume)
    case("06_process_restart", process_restart)
    if args.extended:
        case("07_empty_budget_disabled", budget_validation)
        case("08_zero_budget_disabled", lambda: budget_validation("0"))
        case("09_negative_budget_disabled", lambda: budget_validation("-1"))
        for index, label in enumerate(["Balance", "Spending", "Net Worth", "Credit", "Reports"], start=10):
            case(f"{index:02d}_statistics_{label.replace(' ', '_')}", lambda label=label: statistics_tab(label))


try:
    (output / "session.json").write_text(json.dumps({
        "device": args.device,
        "appium_server": args.server,
        "package": package,
        "noReset": True,
    }, indent=2), encoding="utf-8")
    run_cases()
finally:
    driver.quit()

(output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
print(f"Evidence: {output.resolve()}", flush=True)
raise SystemExit(0 if all(result["status"] == "PASS" for result in results) else 1)
