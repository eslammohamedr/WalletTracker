import argparse
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy
from selenium.common.exceptions import NoSuchElementException, TimeoutException
from selenium.webdriver.support.ui import WebDriverWait


parser = argparse.ArgumentParser(description="Seed emulator SMS, sign up a QA user, and import the history")
parser.add_argument("--device", required=True, help="ADB serial, for example emulator-5554")
parser.add_argument("--server", default="http://127.0.0.1:4723")
parser.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk")
parser.add_argument("--fixture", default="scripts/onboarding_seed_sms.json")
parser.add_argument("--output", default="app/build/device-smoke")
parser.add_argument("--resume-seeded", action="store_true", help="Use a clean AVD where fixture SMS and APK are already present")
parser.add_argument("--continue-existing", action="store_true", help="Verify dashboard/records for an already-imported QA account")
args = parser.parse_args()

email = os.environ.get("WALLET_QA_EMAIL", "").strip()
password = os.environ.get("WALLET_QA_PASSWORD", "")
if not args.continue_existing and (not email or not password):
    parser.error("Set WALLET_QA_EMAIL and WALLET_QA_PASSWORD to a new disposable Firebase email/password")

package = "com.example.wallettrackers"
fixture_path = Path(args.fixture)
fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
run_dir = Path(args.output) / ("onboarding-" + time.strftime("%Y%m%d-%H%M%S"))
run_dir.mkdir(parents=True, exist_ok=False)
results = []
driver = None


def adb(*command, timeout=60):
    return subprocess.run(
        ["adb", "-s", args.device, *map(str, command)],
        check=True,
        capture_output=True,
        text=True,
        timeout=timeout,
        encoding="utf-8",
        errors="replace",
    ).stdout.strip()


def inbox_count():
    query = adb("shell", "content", "query", "--uri", "content://sms", "--projection", "_id")
    return 0 if "No result found" in query else len(re.findall(r"Row:\s*\d+", query))


def save_results():
    (run_dir / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")


def capture(name):
    if driver is None:
        return
    driver.save_screenshot(str(run_dir / f"{name}.png"))
    (run_dir / f"{name}.xml").write_text(driver.page_source, encoding="utf-8")


def record(name, action):
    result = {"name": name, "status": "PASS"}
    started = time.monotonic()
    try:
        action()
    except Exception as error:
        result.update(status="FAIL", error=f"{type(error).__name__}: {error}")
        try:
            capture(name)
        except Exception as capture_error:
            result["capture_error"] = f"{type(capture_error).__name__}: {capture_error}"
        results.append(result)
        result["seconds"] = round(time.monotonic() - started, 2)
        save_results()
        print(json.dumps(result), flush=True)
        raise
    result["seconds"] = round(time.monotonic() - started, 2)
    results.append(result)
    save_results()
    print(json.dumps(result), flush=True)


def wait_text(label, timeout=45):
    escaped = json.dumps(label)
    try:
        return WebDriverWait(driver, timeout, poll_frequency=0.4).until(
            lambda current: current.find_element(
                AppiumBy.ANDROID_UIAUTOMATOR,
                f"new UiSelector().text({escaped})",
            )
        )
    except (TimeoutException, NoSuchElementException) as error:
        raise AssertionError(f"Expected visible text {label!r}") from error


def visible_texts():
    root = ET.fromstring(driver.page_source)
    return [node.attrib.get("text", "") for node in root.iter() if node.attrib.get("text")]


def allow_runtime_permissions():
    for _ in range(8):
        if driver.current_package not in {
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        }:
            return
        buttons = driver.find_elements(
            AppiumBy.ANDROID_UIAUTOMATOR,
            'new UiSelector().text("Allow")',
        )
        if not buttons:
            return
        buttons[-1].click()
        time.sleep(0.5)


def seed_sms():
    booted = adb("shell", "getprop", "sys.boot_completed")
    if booted != "1":
        raise AssertionError("The emulator has not finished booting")
    installed = adb("shell", "pm", "list", "packages", package)
    if f"package:{package}" in installed:
        raise AssertionError("Wallet is already installed; use a fresh QA AVD for first-use onboarding")
    if inbox_count() != 0:
        raise AssertionError("Refusing to seed an emulator whose SMS inbox is not empty")

    for body in fixture["messages"]:
        adb("emu", "sms", "send", fixture["sender"], body)
        time.sleep(1.2)

    if inbox_count() != len(fixture["messages"]):
        raise AssertionError("Emulator SMS inbox count does not match seeded fixture count")
    apk = Path(args.apk)
    if not apk.is_file():
        raise FileNotFoundError(f"APK not found: {apk}")
    adb("install", "-r", apk.resolve())


def verify_seeded_avd():
    if adb("shell", "getprop", "sys.boot_completed") != "1":
        raise AssertionError("The emulator has not finished booting")
    installed = adb("shell", "pm", "list", "packages", package)
    if f"package:{package}" not in installed:
        raise AssertionError("Wallet is not installed on the seeded QA AVD")
    if inbox_count() != len(fixture["messages"]):
        raise AssertionError("Seeded SMS count does not match the fixture")


def start_driver():
    global driver
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


def create_user_and_start_scan():
    start_driver()
    allow_runtime_permissions()
    try:
        wait_text("Create Account", timeout=5)
    except AssertionError:
        driver.find_element(
            AppiumBy.ANDROID_UIAUTOMATOR,
            'new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text("Create Account"))',
        )
    wait_text("Create Account")
    create_links = driver.find_elements(
        AppiumBy.ANDROID_UIAUTOMATOR,
        'new UiSelector().text("Create Account")',
    )
    create_links[-1].click()
    wait_text("Your Details")

    for label, value in [
        ("Email address", email),
        ("Password", password),
        ("Confirm Password", password),
    ]:
        field = driver.find_element(
            AppiumBy.XPATH,
            f'//android.widget.EditText[.//*[@text="{label}"]]'
        )
        field.click()
        field.send_keys(value)

    if driver.is_keyboard_shown():
        driver.hide_keyboard()
    submit_buttons = driver.find_elements(
        AppiumBy.ANDROID_UIAUTOMATOR,
        'new UiSelector().text("Create Account")',
    )
    submit_buttons[-1].click()
    wait_text("Welcome to Wallet Trackers", timeout=90)

    wait_text("Scan My SMS History")
    tap = driver.find_element(
        AppiumBy.ANDROID_UIAUTOMATOR,
        'new UiSelector().text("Scan My SMS History")',
    )
    tap.click()
    wait_text("Accounts Discovered", timeout=90)


def verify_discovery():
    texts = visible_texts()
    if not any("7777" in text for text in texts):
        raise AssertionError("Expected discovered account suffix 7777")
    if "3 SMS" not in texts:
        raise AssertionError(f"Expected the three seeded messages to group together; visible texts: {texts}")
    if not any("24000.00 EGP" in text or "24,000.00 EGP" in text for text in texts):
        raise AssertionError("Expected reconstructed balance of EGP 24,000.00 on the discovered account")
    if "Import 1 Accounts" not in texts:
        raise AssertionError("Expected exactly one selected account to import")


def import_history():
    wait_text("Import 1 Accounts")
    driver.find_element(
        AppiumBy.ANDROID_UIAUTOMATOR,
        'new UiSelector().text("Import 1 Accounts")',
    ).click()
    wait_text("You're All Set!", timeout=180)
    capture("onboarding_import_complete")


def open_dashboard_and_check():
    texts = visible_texts()
    if "Enter Dashboard" in texts:
        driver.find_element(
            AppiumBy.ANDROID_UIAUTOMATOR,
            'new UiSelector().text("Enter Dashboard")',
        ).click()
    if "TOTAL BALANCE" not in visible_texts():
        for locator, value in [
            (AppiumBy.ACCESSIBILITY_ID, "Close sheet"),
            (AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Skip")'),
        ]:
            try:
                driver.find_element(locator, value).click()
                break
            except NoSuchElementException:
                continue
    wait_text("TOTAL BALANCE", timeout=90)
    source = driver.page_source.replace(",", "")
    if "24000.00" not in source:
        raise AssertionError("Dashboard did not show the imported final balance EGP 24,000.00")
    capture("dashboard_after_import")


def verify_records():
    driver.find_element(AppiumBy.ACCESSIBILITY_ID, "Records").click()
    wait_text("All Records")
    source = driver.page_source
    missing = [marker for marker in ["25000.00", "850.00", "150.00"] if marker not in source.replace(",", "")]
    if missing:
        raise AssertionError(f"Imported transaction amounts not visible in Records: {missing}")
    capture("records_after_import")


try:
    if args.continue_existing:
        record("ON-04a_verify_preseeded_existing_account", verify_seeded_avd)
        start_driver()
        record("ON-04_dashboard_balance_matches_latest_sms", open_dashboard_and_check)
        record("ON-05_records_show_seeded_transaction_amounts", verify_records)
    else:
        if args.resume_seeded:
            record("ON-00_verify_preseeded_clean_avd", verify_seeded_avd)
        else:
            record("ON-00_seed_sms_before_install", seed_sms)
        record("ON-01_create_new_email_account_and_request_permissions", create_user_and_start_scan)
        record("ON-02_group_sms_into_one_account", verify_discovery)
        record("ON-03_import_historical_transactions", import_history)
        record("ON-04_dashboard_balance_matches_latest_sms", open_dashboard_and_check)
        record("ON-05_records_show_seeded_transaction_amounts", verify_records)
finally:
    if driver is not None:
        driver.quit()

print(f"Evidence: {run_dir.resolve()}", flush=True)
