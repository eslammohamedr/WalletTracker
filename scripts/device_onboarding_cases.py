import argparse
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.example.wallettrackers"
DEFAULT_FIXTURE = ROOT / "scripts" / "onboarding_case_fixtures.json"
parser = argparse.ArgumentParser(
    description="Run one isolated, real-app SMS discovery/onboarding acceptance case."
)
parser.add_argument("--device", help="Emulator serial, e.g. emulator-5556")
parser.add_argument("--server", default="http://127.0.0.1:4723")
parser.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk")
parser.add_argument("--fixture", default=str(DEFAULT_FIXTURE))
parser.add_argument("--output", default="app/build/device-smoke")
parser.add_argument("--case", help="Case ID, e.g. ON-03")
parser.add_argument("--execute", action="store_true", help="Required to inject SMS, install, or register a test user.")
parser.add_argument("--delete-created-test-user", action="store_true", help="Required confirmation to delete only the disposable identity created by this run and attempt exact fixture-SMS cleanup.")
parser.add_argument("--list", action="store_true", help="List supported cases and documented blockers without device access.")
args = parser.parse_args()

fixture_path = Path(args.fixture).resolve()
catalog = json.loads(fixture_path.read_text(encoding="utf-8"))
cases = catalog["cases"]


def case_status(case_id, case):
    return {
        "case": case_id,
        "title": case.get("title", ""),
        "status": "SUPPORTED" if case.get("supported") else "BLOCKED",
        "blocker": case.get("blocker"),
    }


if args.list:
    print(json.dumps({"fixture_catalog": catalog["fixture_catalog"], "cases": [case_status(key, value) for key, value in cases.items()]}, indent=2))
    raise SystemExit(0)

if not args.case:
    parser.error("--case is required (use --list to inspect case coverage)")
if args.case not in cases:
    parser.error(f"Unknown case {args.case!r}; available IDs: {', '.join(cases)}")
case = cases[args.case]
if not case.get("supported"):
    parser.error(f"{args.case} is not automated: {case.get('blocker', 'no supported scenario')}")
if not args.execute:
    parser.error("Refusing device mutation without --execute; use --list for a read-only coverage view")
if not args.delete_created_test_user:
    parser.error("Refusing to leave a newly created test identity behind; add --delete-created-test-user to confirm cleanup")
if not args.device or not args.device.startswith("emulator-"):
    parser.error("--device must identify an Android emulator (physical devices are refused)")

email = os.environ.get("WALLET_QA_EMAIL", "").strip()
password = os.environ.get("WALLET_QA_PASSWORD", "")
if not email or not password:
    parser.error("Set WALLET_QA_EMAIL and WALLET_QA_PASSWORD to a disposable Firebase identity")

try:
    from appium import webdriver
    from appium.options.android import UiAutomator2Options
    from appium.webdriver.common.appiumby import AppiumBy
    from selenium.common.exceptions import NoSuchElementException, TimeoutException
    from selenium.webdriver.support.ui import WebDriverWait
except ImportError as error:
    parser.error(f"Device execution requires Appium Python client and Selenium: {error}")

apk_path = (ROOT / args.apk).resolve()
if not apk_path.is_file():
    parser.error(f"APK not found: {apk_path}")
run_dir = (ROOT / args.output / f"onboarding-{args.case}-{time.strftime('%Y%m%d-%H%M%S')}").resolve()
driver = None
created_identity = False
fixture_sms_ids = []
results = {
    "case": args.case,
    "title": case.get("title"),
    "device": args.device,
    "fixture_catalog": catalog["fixture_catalog"],
    "fixture_review_basis": catalog["review_basis"],
    "status": "IN_PROGRESS",
    "steps": [],
    "expected": {key: value for key, value in case.items() if key not in {"messages", "supported", "title"}},
    "limitations": ["Firebase signup requires network access.", "The inbox must be empty because onboarding scans all device SMS.", "Created Firebase test identities are not automatically deleted; use disposable credentials."],
}


def save_results():
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")


def adb(*command, timeout=30):
    return subprocess.run(
        ["adb", "-s", args.device, *map(str, command)], check=True,
        capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace",
    ).stdout.strip()


def inbox_count():
    output = adb("shell", "content", "query", "--uri", "content://sms", "--projection", "_id")
    return 0 if "No result found" in output else len(re.findall(r"Row:\s*\d+", output))


def inbox_bodies():
    return adb("shell", "content", "query", "--uri", "content://sms", "--projection", "body")


def inbox_row_ids():
    output = adb("shell", "content", "query", "--uri", "content://sms", "--projection", "_id")
    return sorted({int(value) for value in re.findall(r"_id=(\d+)", output)})


def capture(name):
    if driver is not None:
        driver.save_screenshot(str(run_dir / f"{name}.png"))
        (run_dir / f"{name}.xml").write_text(driver.page_source, encoding="utf-8")


def wait_text(text, timeout=10):
    try:
        return WebDriverWait(driver, timeout, poll_frequency=0.35).until(
            lambda current: current.find_element(
                AppiumBy.ANDROID_UIAUTOMATOR,
                f"new UiSelector().text({json.dumps(text)})",
            )
        )
    except (TimeoutException, NoSuchElementException) as error:
        raise AssertionError(f"Expected UI text {text!r}; visible={visible_texts()[:80]}") from error


def visible_texts():
    root = ET.fromstring(driver.page_source)
    return [node.attrib.get("text", "") for node in root.iter() if node.attrib.get("text")]


def click_text(text, timeout=10, occurrence=-1):
    wait_text(text, timeout)
    candidates = driver.find_elements(
        AppiumBy.ANDROID_UIAUTOMATOR,
        f"new UiSelector().text({json.dumps(text)})",
    )
    if not candidates:
        raise AssertionError(f"Could not click visible text {text!r}")
    candidates[occurrence].click()


def preflight_and_seed():
    if adb("shell", "getprop", "sys.boot_completed") != "1":
        raise AssertionError("Emulator is not fully booted")
    if inbox_count() != 0:
        raise AssertionError("Refusing to mix this fixture with existing SMS; clear/recreate the disposable emulator inbox first")
    installed = f"package:{PACKAGE}" in adb("shell", "pm", "list", "packages", PACKAGE)
    results["preflight"] = {"booted": True, "inbox_count_before": 0, "app_preinstalled": installed}
    if not installed:
        adb("install", "-r", apk_path)
    start_driver_and_verify_signed_out()
    for index, body in enumerate(case.get("messages", []), start=1):
        adb("emu", "sms", "send", "5551001", body)
        delivery_deadline = time.monotonic() + 45
        while time.monotonic() < delivery_deadline:
            if body in inbox_bodies():
                break
            time.sleep(1.0)
        else:
            raise AssertionError(f"Emulator did not deliver exact fixture SMS {index}/{len(case.get('messages', []))} to the inbox")
    expected_count = len(case.get("messages", []))
    delivery_deadline = time.monotonic() + 60
    actual_count = inbox_count()
    while actual_count < expected_count and time.monotonic() < delivery_deadline:
        time.sleep(0.5)
        actual_count = inbox_count()
    if actual_count != expected_count:
        raise AssertionError(f"SMS inbox count mismatch: fixture={expected_count}, observed={actual_count}")
    results["preflight"]["inbox_count_after_seed"] = actual_count
    global fixture_sms_ids
    fixture_sms_ids = inbox_row_ids()
    if len(fixture_sms_ids) != actual_count:
        raise AssertionError(f"Could not identify every fixture inbox row exactly: row_ids={fixture_sms_ids}, count={actual_count}")
    results["preflight"]["fixture_sms_ids"] = fixture_sms_ids
    results["steps"].append("verified clean emulator preconditions and injected only fixture SMS")
    save_results()


def start_driver_and_verify_signed_out():
    global driver
    options = UiAutomator2Options().load_capabilities({
        "platformName": "Android", "appium:automationName": "UiAutomator2",
        "appium:deviceName": args.device, "appium:udid": args.device,
        "appium:appPackage": PACKAGE, "appium:appActivity": ".MainActivity",
        "appium:noReset": True, "appium:newCommandTimeout": 180,
        "appium:uiautomator2ServerLaunchTimeout": 60000,
        "appium:uiautomator2ServerReadTimeout": 60000,
    })
    driver = webdriver.Remote(args.server.rstrip("/"), options=options)
    driver.implicitly_wait(0)
    permission_controllers = {"com.android.permissioncontroller", "com.google.android.permissioncontroller"}
    for _ in range(8):
        allows = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Allow")')
        if driver.current_package not in permission_controllers and not allows:
            break
        if allows:
            allows[-1].click()
            time.sleep(0.3)
        else:
            time.sleep(0.3)
    if "TOTAL BALANCE" in visible_texts():
        raise AssertionError("App is already signed in; refusing to add fixture data to an existing user")

    for _ in range(5):
        if "Create Account" in visible_texts():
            break
        width, height = driver.get_window_size().values()
        adb("shell", "input", "swipe", int(width * 0.5), int(height * 0.78), int(width * 0.5), int(height * 0.35), 350)
        time.sleep(0.25)

    try:
        wait_text("Create Account", timeout=5)
    except AssertionError:
        raise AssertionError("Expected unauthenticated login/create-account screen; refusing to delete or reuse a signed-in profile")


def start_app_and_signup():
    global created_identity
    links = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Create Account")')
    if not links:
        raise AssertionError("Create Account action missing after signed-out preflight")
    links[0].click()
    wait_text("Your Details")
    fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
    if len(fields) < 3:
        raise AssertionError(f"Signup form exposed {len(fields)} of 3 expected fields")
    for index, value in enumerate([email, password, password]):
        fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
        if len(fields) < 3:
            raise AssertionError(f"Signup form exposed {len(fields)} fields while entering field {index + 1}")
        field = fields[index]
        field.click()
        field.send_keys(value)
    if driver.is_keyboard_shown():
        driver.press_keycode(4)
    buttons = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Create Account")')
    if not buttons:
        raise AssertionError("Signup submit button not found")
    buttons[-1].click()
    wait_text("Welcome to Wallet Trackers", timeout=10)
    created_identity = True
    results["identity_created"] = True
    save_results()
    wait_text("Scan My SMS History", timeout=10)
    click_text("Scan My SMS History")
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        texts = visible_texts()
        if "Accounts Discovered" in texts or "No Accounts Found" in texts:
            break
        time.sleep(0.5)
    else:
        raise AssertionError(f"Discovery did not complete; last visible text={visible_texts()[:70]}")
    capture("discovery_initial")
    results["steps"].append("registered disposable Firebase user and completed real-app SMS discovery")


def capture_discovery_scrolls():
    texts = []
    width, height = driver.get_window_size().values()
    for index in range(5):
        page_texts = visible_texts()
        texts.extend(page_texts)
        capture(f"discovery_scroll_{index}")
        if index == 4:
            break
        adb("shell", "input", "swipe", int(width * 0.5), int(height * 0.78), int(width * 0.5), int(height * 0.39), 350)
        time.sleep(0.25)
    adb("shell", "input", "swipe", int(width * 0.5), int(height * 0.35), int(width * 0.5), int(height * 0.8), 350)
    return texts


def verify_discovery():
    texts = capture_discovery_scrolls()
    source = " ".join(texts)
    if case.get("expect_empty"):
        if "No Accounts Found" not in source or "Accounts Discovered" in source:
            raise AssertionError(f"Empty inbox created/discovered accounts unexpectedly: {texts[:90]}")
        capture("empty_inbox_no_account")
        results["steps"].append("verified empty inbox produces no invented bank accounts")
        return

    missing = []
    for expected in case.get("expected_groups", []):
        if expected["suffix"] not in source:
            missing.append(f"suffix {expected['suffix']}")
        if expected["bank"] not in source:
            missing.append(f"bank {expected['bank']}")
        if expected["type"] not in source:
            missing.append(f"type {expected['type']}")
    expected_count = case.get("expected_group_count")
    if expected_count is not None:
        suffix = case["expected_groups"][0]["suffix"]
        account_labels = {
            text.strip() for text in texts
            if suffix in text and ("••••" in text or "****" in text)
        }
        observed_count = len(account_labels)
        if observed_count != expected_count:
            missing.append(f"group count {expected_count} for suffix {suffix} (observed {observed_count})")
    if missing:
        raise AssertionError(f"Discovery oracle mismatch: {missing}; observed={texts[:100]}")
    if case.get("expected_balance") and case["expected_balance"] not in source.replace(",", ""):
        raise AssertionError(f"Expected inferred balance {case['expected_balance']}; visible={texts[:100]}")
    capture("discovery_oracle_passed")
    results["steps"].append("matched discovered suffixes, banks, account types, and balance oracle")


def import_and_verify():
    def dismiss_feature_tour():
        if "Discover What's Inside" not in visible_texts():
            return
        if "Skip" in visible_texts():
            click_text("Skip", timeout=10)
        elif "Got It!" in visible_texts():
            click_text("Got It!", timeout=10)
        else:
            width, height = driver.get_window_size().values()
            adb("shell", "input", "tap", int(width * 0.5), int(height * 0.2))
            time.sleep(0.5)
            if "Discover What's Inside" in visible_texts():
                raise AssertionError(f"Feature tour did not dismiss from the upper-screen tap; visible={visible_texts()[:60]}")

    if case.get("expect_empty"):
        click_text("Skip to Manual Setup", timeout=10)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            texts = visible_texts()
            if "You're All Set!" in texts or "Discover What's Inside" in texts or "TOTAL BALANCE" in texts:
                break
            time.sleep(0.25)
        else:
            raise AssertionError(f"Empty-inbox onboarding did not advance; visible={visible_texts()[:60]}")
        if "You're All Set!" in visible_texts() and "Enter Dashboard" in visible_texts():
            click_text("Enter Dashboard", timeout=10)
        dismiss_feature_tour()
        wait_text("TOTAL BALANCE", timeout=10)
        results["steps"].append("skipped discovery because empty inbox had no accounts to import")
        return
    for text in visible_texts():
        if text.startswith("Import ") and text.endswith(" Accounts"):
            click_text(text)
            break
    else:
        raise AssertionError("No enabled Import N Accounts action found")
    wait_text("You're All Set!", timeout=10)
    capture("import_complete")
    texts = visible_texts()
    if "Enter Dashboard" in texts:
        click_text("Enter Dashboard")
    dismiss_feature_tour()
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline and "TOTAL BALANCE" not in visible_texts():
        if "Skip" in visible_texts():
            click_text("Skip")
        else:
            time.sleep(0.4)
    wait_text("TOTAL BALANCE", timeout=10)
    if case.get("expected_balance"):
        dashboard = driver.page_source.replace(",", "")
        if case["expected_balance"] not in dashboard:
            raise AssertionError(f"Dashboard missing expected imported balance {case['expected_balance']}")
    if case.get("expected_cash_count") is not None:
        # The dashboard account carousel is horizontally scrollable. Scan one viewport at a time.
        width, height = driver.get_window_size().values()
        observed_pages = set()
        cash_card_seen = False
        for index in range(7):
            page_texts = visible_texts()
            observed_pages.add(tuple(page_texts))
            if any(text.strip().lower() == "cash" for text in page_texts):
                cash_card_seen = True
            capture(f"dashboard_account_scan_{index}")
            adb("shell", "input", "swipe", int(width * 0.88), int(height * 0.64), int(width * 0.17), int(height * 0.64), 350)
            time.sleep(0.25)
        if case["expected_cash_count"] != 1 or not cash_card_seen:
            raise AssertionError(f"Expected exactly one auto-created Cash account; visible card found={cash_card_seen}")
    expected_amounts = case.get("expected_record_amounts", [])
    expected_categories = case.get("expected_record_categories", [])
    if expected_amounts or expected_categories:
        records_controls = driver.find_elements(AppiumBy.ACCESSIBILITY_ID, "Records")
        if not records_controls:
            raise AssertionError("Records navigation control is missing after import")
        records_controls[-1].click()
        wait_text("All Records", timeout=10)
        record_source = driver.page_source.replace(",", "")
        missing_amounts = [amount for amount in expected_amounts if amount not in record_source]
        missing_categories = [category for category in expected_categories if category not in record_source]
        if missing_amounts or missing_categories:
            capture("imported_records_oracle_mismatch")
            raise AssertionError(f"Imported records mismatch: amounts={missing_amounts}; categories={missing_categories}")
        capture("imported_record_values")
    capture("dashboard_import_oracle")
    results["steps"].append("verified imported dashboard balance and any case-specific account invariant")


def cleanup_created_user():
    if not created_identity:
        results["cleanup"] = {"status": "NOT_REQUIRED", "reason": "signup did not complete"}
        return
    cleanup = {"identity": "PENDING", "fixture_sms": "PENDING", "retained_sms_ids": []}
    try:
        for _ in range(4):
            if "TOTAL BALANCE" in visible_texts():
                break
            driver.back()
            time.sleep(0.25)
        if "TOTAL BALANCE" not in visible_texts():
            raise AssertionError("Could not reach dashboard for safe deletion of the newly created test identity")
        profile_buttons = driver.find_elements(AppiumBy.ACCESSIBILITY_ID, "Profile")
        if not profile_buttons:
            raise AssertionError("Profile control not found; refusing to guess account-deletion coordinates")
        profile_buttons[-1].click()
        width, height = driver.get_window_size().values()
        for _ in range(12):
            if any(text.strip() == "Delete Account" for text in visible_texts()):
                break
            adb("shell", "input", "swipe", int(width * 0.5), int(height * 0.88), int(width * 0.5), int(height * 0.29), 350)
            time.sleep(0.25)
        if not any(text.strip() == "Delete Account" for text in visible_texts()):
            raise AssertionError("Delete Account option not visible in the profile menu")
        click_text("Delete Account", timeout=10)
        wait_text("This will permanently delete your account and all data. This cannot be undone.", timeout=10)
        click_text("Delete", timeout=10)
        if "Confirm account deletion" in visible_texts():
            password_fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
            if not password_fields:
                raise AssertionError("Reauthentication dialog has no password field")
            password_fields[-1].send_keys(password)
            click_text("Verify and delete", timeout=10)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            login_texts = visible_texts()
            if "Authentication" in login_texts or "Sign In" in login_texts:
                cleanup["identity"] = "DELETED_AND_RETURNED_TO_LOGIN"
                break
            time.sleep(0.3)
        if cleanup["identity"] == "PENDING":
            raise AssertionError("Account deletion did not return to an unauthenticated screen")
    except Exception as error:
        cleanup["identity"] = "FAILED"
        cleanup["identity_error"] = f"{type(error).__name__}: {error}"

    try:
        for row_id in fixture_sms_ids:
            subprocess.run(
                ["adb", "-s", args.device, "shell", "content", "delete", "--uri", f"content://sms/{row_id}"],
                check=True, capture_output=True, text=True, timeout=10,
                encoding="utf-8", errors="replace",
            )
        remaining_ids = inbox_row_ids()
        cleanup["retained_sms_ids"] = sorted(set(remaining_ids).intersection(fixture_sms_ids))
        cleanup["fixture_sms"] = "DELETED" if not cleanup["retained_sms_ids"] else "RETAINED_PROVIDER_DENIED"
    except Exception as error:
        try:
            remaining_ids = inbox_row_ids()
            cleanup["retained_sms_ids"] = sorted(set(remaining_ids).intersection(fixture_sms_ids))
        except Exception:
            cleanup["retained_sms_ids"] = fixture_sms_ids
        cleanup["fixture_sms"] = "RETAINED_PROVIDER_DENIED"
        cleanup["sms_cleanup_error"] = f"{type(error).__name__}: {error}"
    results["cleanup"] = cleanup
    if cleanup["identity"] != "DELETED_AND_RETURNED_TO_LOGIN" or cleanup["fixture_sms"] != "DELETED":
        results["status"] = "PASS_CLEANUP_INCOMPLETE" if results["status"] == "PASS" else "FAIL"


try:
    preflight_and_seed()
    start_app_and_signup()
    verify_discovery()
    import_and_verify()
    results["status"] = "PASS"
except Exception as error:
    results["status"] = "FAIL"
    results["error"] = f"{type(error).__name__}: {error}"
    if driver is not None:
        try:
            capture("failure")
        except Exception as capture_error:
            results["capture_error"] = f"{type(capture_error).__name__}: {capture_error}"
finally:
    if driver is not None:
        cleanup_created_user()
    results["finished_at"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    save_results()
    if driver is not None:
        driver.quit()

print(json.dumps({"case": args.case, "status": results["status"], "evidence": str(run_dir)}, indent=2))
if results["status"] != "PASS":
    raise SystemExit(1)
