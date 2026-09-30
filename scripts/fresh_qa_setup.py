import argparse
import json
import os
import re
import secrets
import string
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from decimal import Decimal
from pathlib import Path

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy
from selenium.common.exceptions import NoSuchElementException, TimeoutException
from selenium.webdriver.support.ui import WebDriverWait


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.example.wallettrackers"
parser = argparse.ArgumentParser(
    description="Reset, seed, or register a disposable QA user and initialize financial test accounts."
)
parser.add_argument("--device", required=True, help="ADB serial, e.g. emulator-5554")
parser.add_argument("--server", default="http://127.0.0.1:4723")
parser.add_argument("--apk", default="app/build/outputs/apk/debug/app-debug.apk")
parser.add_argument("--fixture", default="scripts/financial_qa_accounts.json")
parser.add_argument("--output", default="app/build/device-smoke")
parser.add_argument(
    "--delete-signed-in-qa-user", action="store_true",
    help="Required explicit confirmation: permanently delete all data for the currently signed-in account.",
)
parser.add_argument(
    "--seed-current-user", action="store_true",
    help="Seed fixture accounts into the currently signed-in empty QA profile without deleting or registering a user.",
)
parser.add_argument(
    "--register-new-user", action="store_true",
    help="Register a new disposable QA user and seed fixtures without deleting any existing user.",
)
parser.add_argument("--dry-run", action="store_true", help="Validate fixture/APK/device only; do not launch or mutate the app.")
args = parser.parse_args()

if sum((args.delete_signed_in_qa_user, args.seed_current_user, args.register_new_user)) > 1:
    parser.error("Choose only one of --delete-signed-in-qa-user, --seed-current-user, or --register-new-user")
if not args.dry_run and not args.delete_signed_in_qa_user and not args.seed_current_user and not args.register_new_user:
    parser.error("Choose an explicit QA setup mode; account deletion is never implicit")

fixture_path = (ROOT / args.fixture).resolve()
apk_path = (ROOT / args.apk).resolve()
fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
accounts = fixture["accounts"]
if not accounts or len({account["name"] for account in accounts}) != len(accounts):
    parser.error("Fixture must contain uniquely named accounts")
required_types = {"Debit", "Credit Card", "Cash", "Gold"}
if not required_types.issubset({account["type"] for account in accounts}):
    parser.error(f"Fixture must cover account types {sorted(required_types)}")
expected_home_total = sum(
    float(account["amount"])
    for account in accounts
    if account["type"] in {"Debit", "Cash"} and account["currency"].upper() == "EGP"
)
if round(expected_home_total, 2) != round(float(fixture["expected_home_total_balance_egp"]), 2):
    parser.error(f"Fixture EGP dashboard oracle is inconsistent: computed {expected_home_total:.2f}")
if not apk_path.is_file():
    parser.error(f"APK does not exist: {apk_path}")

email = os.environ.get("WALLET_QA_EMAIL", "").strip()
password = os.environ.get("WALLET_QA_PASSWORD", "")
if not email and not args.seed_current_user:
    email = f"wallet.qa.{datetime.now().strftime('%Y%m%d%H%M%S')}.{secrets.token_hex(2)}@example.com"
if not password and not args.seed_current_user:
    alphabet = string.ascii_letters + string.digits + "!@#_-"
    password = "".join(secrets.choice(alphabet) for _ in range(28))

run_dir = (ROOT / args.output / ("fresh-qa-setup-" + time.strftime("%Y%m%d-%H%M%S"))).resolve()
results_path = run_dir / "results.json"
credentials_path = run_dir / "qa-credentials.json"
driver = None
results = {
    "case": "fresh_qa_user_and_financial_fixture_setup",
    "device": args.device,
    "apk": str(apk_path.relative_to(ROOT)),
    "fixture_id": fixture["fixture_id"],
    "new_identity": email or "currently signed-in QA user",
    "status": "IN_PROGRESS",
    "steps": [],
    "baseline": {},
    "safety": (
        "Registered a new disposable identity; this mode does not delete any existing user."
        if args.register_new_user else
        "Seeded the existing signed-in QA profile; this mode does not delete any user."
        if args.seed_current_user else
        "Only the currently signed-in account is deleted, after the explicit CLI confirmation flag."
    ),
}
results["credentials_file"] = str(credentials_path.relative_to(ROOT)) if email else None


def adb(*command, timeout=10):
    return subprocess.run(
        ["adb", "-s", args.device, *map(str, command)], check=True,
        capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace",
    ).stdout.strip()


def save_results():
    run_dir.mkdir(parents=True, exist_ok=True)
    results_path.write_text(json.dumps(results, indent=2), encoding="utf-8")


def capture(name):
    if driver is None:
        return
    run_dir.mkdir(parents=True, exist_ok=True)
    driver.save_screenshot(str(run_dir / f"{name}.png"))
    (run_dir / f"{name}.xml").write_text(driver.page_source, encoding="utf-8")


def visible_texts():
    root = ET.fromstring(driver.page_source)
    return [node.attrib.get("text", "") for node in root.iter() if node.attrib.get("text")]


def wait_text(text, timeout=10):
    selector = json.dumps(text)
    try:
        return WebDriverWait(driver, timeout, poll_frequency=0.4).until(
            lambda current: current.find_element(
                AppiumBy.ANDROID_UIAUTOMATOR, f"new UiSelector().text({selector})"
            )
        )
    except (TimeoutException, NoSuchElementException) as error:
        raise AssertionError(f"Expected visible text {text!r}; visible={visible_texts()[:80]}") from error


def wait_text_contains(text, timeout=10):
    selector = json.dumps(text)
    try:
        return WebDriverWait(driver, timeout, poll_frequency=0.4).until(
            lambda current: current.find_element(
                AppiumBy.ANDROID_UIAUTOMATOR, f"new UiSelector().textContains({selector})"
            )
        )
    except (TimeoutException, NoSuchElementException) as error:
        raise AssertionError(f"Expected visible text containing {text!r}; visible={visible_texts()[:80]}") from error


def tap_text(text, occurrence=-1, timeout=10):
    try:
        wait_text(text, timeout)
    except AssertionError:
        escaped = json.dumps(text)
        driver.find_element(
            AppiumBy.ANDROID_UIAUTOMATOR,
            f'new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text({escaped}))',
        )
    elements = driver.find_elements(
        AppiumBy.ANDROID_UIAUTOMATOR, f"new UiSelector().text({json.dumps(text)})"
    )
    elements[occurrence].click()


def hide_keyboard():
    try:
        if driver.is_keyboard_shown():
            driver.press_keycode(4)
    except Exception:
        pass


def allow_runtime_permissions():
    for _ in range(10):
        if driver.current_package not in {"com.android.permissioncontroller", "com.google.android.permissioncontroller"}:
            break
        buttons = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Allow")')
        if not buttons:
            break
        buttons[-1].click()
        time.sleep(0.4)
    avd_name = adb("emu", "avd", "name").splitlines()[0].strip()
    if avd_name not in {"Wallet_23Cases_Temp", "Wallet_Onboarding_QA"}:
        raise AssertionError(f"Refusing to change runtime permissions on non-QA AVD {avd_name!r}")
    for permission in ("android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.POST_NOTIFICATIONS"):
        adb("shell", "pm", "grant", PACKAGE, permission)


def delete_signed_in_user():
    current_texts = visible_texts()
    if "Authentication" in current_texts or ("Sign In" in current_texts and "Create Account" in current_texts):
        results["steps"].append("existing user was already signed out; left that Firebase identity untouched and will create a separate fresh QA account")
        results["note"] = "Previous account deletion cleared its wallet data but Firebase retained the identity; its password was unavailable for reauthentication."
        return False
    if "Update Balances from SMS" in visible_texts():
        tap_text("Cancel", timeout=10)
        time.sleep(0.5)
    for _ in range(5):
        if "TOTAL BALANCE" in visible_texts():
            break
        driver.back()
        time.sleep(0.5)
    if "TOTAL BALANCE" not in visible_texts():
        raise AssertionError("Expected a signed-in dashboard; refusing to guess which user to delete")

    capture("before_delete_signed_in_user")
    # Email sign-up currently does not display the active email in the profile sheet.
    # The explicit command-line confirmation is therefore the user-identity safeguard.
    match = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    width, height = map(int, match.groups()) if match else (1440, 3120)
    adb("shell", "input", "tap", int(width * 0.915), int(height * 0.131))
    time.sleep(0.5)
    for _ in range(8):
        if "Delete Account" in visible_texts():
            break
        adb("shell", "input", "swipe", width // 2, int(height * 0.83), width // 2, int(height * 0.48), 450)
        time.sleep(0.3)
    tap_text("Delete Account", timeout=10)
    wait_text_contains("permanently delete your account", timeout=10)
    deletes = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Delete")')
    if not deletes:
        raise AssertionError("Delete confirmation dialog did not expose a Delete button")
    deletes[-1].click()
    if "Confirm account deletion" in visible_texts():
        current_password = os.environ.get("WALLET_QA_CURRENT_PASSWORD", "")
        if not current_password:
            raise AssertionError("Account deletion requires WALLET_QA_CURRENT_PASSWORD; wallet data was not deleted")
        password_field = driver.find_element(AppiumBy.XPATH, '//android.widget.EditText')
        password_field.send_keys(current_password)
        tap_text("Verify and delete", timeout=10)
    try:
        wait_text("Authentication", timeout=10)
    except AssertionError as error:
        capture("delete_user_did_not_return_to_login")
        raise AssertionError(
            "Account deletion did not return to login. Stop: the app may have hit Firebase's recent-login rule. "
            "Do not continue or reuse this account; inspect the screenshot and sign in freshly before retrying."
        ) from error
    results["steps"].append("deleted the signed-in QA identity and returned to the unauthenticated screen")
    save_results()
    return True


def signup_fresh_user():
    links = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Create Account")')
    for _ in range(5):
        if links:
            break
        match = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
        width, height = map(int, match.groups()) if match else (1440, 3120)
        adb("shell", "input", "swipe", width // 2, int(height * 0.85), width // 2, int(height * 0.3), 450)
        time.sleep(0.3)
        links = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Create Account")')
    if not links:
        raise AssertionError("Login screen has no Create Account action")
    links[-1].click()
    wait_text("Your Details")
    fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
    if len(fields) < 3:
        raise AssertionError(f"Sign-up form exposed {len(fields)} of 3 expected input fields")
    for field, value in zip(fields[:3], [email, password, password]):
        field.click()
        field.send_keys(value)
        hide_keyboard()
    buttons = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Create Account")')
    if not buttons:
        raise AssertionError("Sign-up screen has no Create Account submit button")
    buttons[-1].click()
    dismiss_post_auth_intro()
    allow_runtime_permissions()
    results["steps"].append("registered a new email/password QA user and skipped SMS-history onboarding")
    capture("fresh_user_dashboard_before_fixture")
    save_results()


def dismiss_post_auth_intro():
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        current_texts = visible_texts()
        if "TOTAL BALANCE" in current_texts:
            return
        if "Welcome to Wallet Trackers" in current_texts:
            wait_text_contains("Skip", timeout=min(10, max(1, deadline - time.monotonic()))).click()
            time.sleep(0.3)
            continue
        if "Discover What's Inside" in current_texts or driver.find_elements(AppiumBy.ACCESSIBILITY_ID, "Close sheet"):
            match = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
            width, height = map(int, match.groups()) if match else (1440, 3120)
            adb("shell", "input", "tap", width // 2, int(height * 0.08))
            time.sleep(0.3)
            continue
        time.sleep(0.3)
    raise AssertionError(f"Post-auth intro did not reach Home; visible={visible_texts()[:60]}")


def sign_in_qa_user():
    if not email or not password:
        raise AssertionError("Set WALLET_QA_EMAIL and WALLET_QA_PASSWORD to reauthenticate the disposable QA user")
    fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
    if len(fields) < 2:
        raise AssertionError(f"Sign-in screen exposed {len(fields)} of 2 expected input fields")
    fields[0].send_keys(email)
    fields[1].send_keys(password)
    hide_keyboard()
    tap_text("Sign In", timeout=10)
    allow_runtime_permissions()
    dismiss_post_auth_intro()
    results["steps"].append("reauthenticated the disposable QA user from its protected credentials file")
    save_results()


def horizontal_scroll_accounts_to_end():
    width, height = driver.get_window_size().values()
    for _ in range(5):
        add_controls = driver.find_elements(AppiumBy.ACCESSIBILITY_ID, "Add Account")
        if add_controls:
            return add_controls[-1]
        add_labels = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Add")')
        if add_labels:
            return add_labels[-1]
        adb("shell", "input", "swipe", int(width * 0.9), int(height * 0.70), int(width * 0.15), int(height * 0.70), 450)
        time.sleep(0.3)
    raise AssertionError("Could not scroll Accounts row to the Add Account card")


def scroll_accounts_to_start(width, height):
    for _ in range(8):
        adb("shell", "input", "swipe", int(width * 0.15), int(height * 0.70), int(width * 0.9), int(height * 0.70), 450)
        time.sleep(0.15)


def field(label):
    selector = f'//android.widget.EditText[.//*[@text="{label}"]]'
    try:
        return WebDriverWait(driver, 10, poll_frequency=0.4).until(
            lambda current: current.find_element(AppiumBy.XPATH, selector)
        )
    except TimeoutException as error:
        raise AssertionError(f"Account form field {label!r} was not exposed") from error


def set_field(label, value):
    element = field(label)
    element.click()
    element.send_keys(str(value))
    expected = str(value).replace(",", "")
    try:
        WebDriverWait(driver, 3, poll_frequency=0.3).until(
            lambda current: expected in (field(label).get_attribute("text") or "").replace(",", "")
        )
    except TimeoutException:
        actual = field(label).get_attribute("text") or ""
        raise AssertionError(f"Field {label!r} did not retain {expected!r}; actual={actual!r}")
    hide_keyboard()


def choose_dropdown(label, value):
    field(label).click()
    tap_text(value, timeout=10)


def add_account(account):
    add_control = horizontal_scroll_accounts_to_end()
    add_control.click()
    field("Account Name")
    set_field("Account Name", account["name"])
    choose_dropdown("Account Type", account["type"])

    if account.get("last4"):
        set_field("Last 4 Digits", account["last4"])
    if account["type"] == "Credit Card":
        set_field("Credit Limit", account["credit_limit"])
        set_field("Available Credit", account["amount"])
        set_field("Statement Day (1–31)", account["billing_day"])
    else:
        amount_label = "Weight in Grams" if account["type"] == "Gold" else "Current Balance"
        set_field(amount_label, account["amount"])
    if account["type"] != "Gold" and account["currency"] != "EGP":
        choose_dropdown("Currency", account["currency"])
    wait_text("Add", timeout=10)
    save_buttons = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Add")')
    if not save_buttons:
        raise AssertionError(f"Add button missing for account {account['name']}")
    required_values = [("Account Name", account["name"])]
    if account.get("last4"):
        required_values.append(("Last 4 Digits", account["last4"]))
    amount_label = "Weight in Grams" if account["type"] == "Gold" else (
        "Available Credit" if account["type"] == "Credit Card" else "Current Balance"
    )
    required_values.append((amount_label, account["amount"]))
    for label, expected in required_values:
        actual = field(label).get_attribute("text") or ""
        if str(expected) not in actual.replace(",", ""):
            capture(f"invalid_account_form_{account['name']}")
            raise AssertionError(f"Account form lost {label!r}: expected {expected!r}, actual={actual!r}")
    save_buttons[-1].click()
    wait_text("TOTAL BALANCE", timeout=10)
    results["steps"].append(f"created {account['type']} account {account['name']} ({account['currency']})")
    save_results()


def create_fixture_accounts():
    width, height = driver.get_window_size().values()
    scroll_accounts_to_start(width, height)
    seen_text = set()
    for _ in range(8):
        seen_text.update(visible_texts())
        adb("shell", "input", "swipe", int(width * 0.9), int(height * 0.70), int(width * 0.15), int(height * 0.70), 450)
        time.sleep(0.2)
    existing = [account for account in accounts if account["name"] in seen_text]
    total_texts = visible_texts()
    total_index = total_texts.index("TOTAL BALANCE") if "TOTAL BALANCE" in total_texts else -1
    if total_index < 0 or total_index + 1 >= len(total_texts):
        raise AssertionError(f"Home dashboard total is not visible: {total_texts[:60]}")
    actual_total = Decimal(total_texts[total_index + 1].replace(",", ""))
    expected_existing_total = sum(
        (Decimal(item["amount"]) for item in existing
         if item["type"] in {"Debit", "Cash"} and item["currency"] == "EGP"),
        Decimal("0.00"),
    )
    if actual_total != expected_existing_total:
        raise AssertionError(
            f"Dashboard baseline drift before fixture setup: expected {expected_existing_total}, actual {actual_total}, "
            f"existing fixture accounts={[item['name'] for item in existing]}"
        )
    for account in accounts:
        if account["name"] not in {item["name"] for item in existing}:
            add_account(account)
    scroll_accounts_to_start(width, height)
    seen_text = set()
    for _ in range(8):
        seen_text.update(visible_texts())
        adb("shell", "input", "swipe", int(width * 0.9), int(height * 0.66), int(width * 0.15), int(height * 0.66), 450)
        time.sleep(0.2)
    missing = [account["name"] for account in accounts if account["name"] not in seen_text]
    if missing:
        raise AssertionError(f"Fixture account cards missing from UI after horizontal scan: {missing}")
    visible_amounts = {
        Decimal(token.replace(",", ""))
        for text in seen_text
        for token in re.findall(r"(?<!\d)\d[\d,]*(?:\.\d+)?", text)
    }
    missing_amounts = [
        account["name"] for account in accounts
        if Decimal(account["amount"]) not in visible_amounts
    ]
    if missing_amounts:
        raise AssertionError(f"Expected fixture balances/available credit were not visible: {missing_amounts}")
    credit_used_percent = round(
        (1 - float(next(item for item in accounts if item["type"] == "Credit Card")["amount"]) /
         float(next(item for item in accounts if item["type"] == "Credit Card")["credit_limit"])) * 100
    )
    if f"{credit_used_percent}% used" not in seen_text:
        raise AssertionError("Credit Card utilization does not match its seeded limit and available credit")
    home_total = fixture["expected_home_total_balance_egp"]
    wait_text("TOTAL BALANCE")
    normalized_source = driver.page_source.replace(",", "")
    if home_total not in normalized_source:
        raise AssertionError(f"Expected Home EGP TOTAL BALANCE {home_total}; visible texts={visible_texts()[:100]}")
    results["baseline"] = {
        "account_count": len(accounts),
        "accounts": [{key: value for key, value in account.items() if key != "last4"} for account in accounts],
        "dashboard_total_egp": home_total,
        "expected_dashboard_rule": "active EGP Debit + Cash only; excludes credit cards, Gold, and non-EGP nominal amounts",
    }
    capture("fixture_accounts_and_baseline_verified")
    results["steps"].append(f"verified all {len(accounts)} account cards and the EGP dashboard baseline")
    results["status"] = "PASS"
    save_results()


def main():
    booted = adb("shell", "getprop", "sys.boot_completed")
    if booted != "1":
        raise AssertionError("Emulator is not fully booted")
    if args.dry_run:
        print(json.dumps({"status": "DRY_RUN_PASS", "fixture": fixture["fixture_id"], "accounts": len(accounts)}))
        return

    run_dir.mkdir(parents=True, exist_ok=False)
    if email:
        credentials_path.write_text(json.dumps({"email": email, "password": password}, indent=2), encoding="utf-8")
        try:
            credentials_path.chmod(0o600)
        except OSError:
            pass
    options = UiAutomator2Options().load_capabilities({
        "platformName": "Android", "appium:automationName": "UiAutomator2",
        "appium:deviceName": args.device, "appium:udid": args.device,
        "appium:appPackage": PACKAGE, "appium:appActivity": ".MainActivity",
        "appium:noReset": True, "appium:newCommandTimeout": 10,
        "appium:uiautomator2ServerLaunchTimeout": 10000,
    })
    global driver
    driver = webdriver.Remote(args.server.rstrip("/"), options=options)
    driver.implicitly_wait(0)
    allow_runtime_permissions()
    if args.seed_current_user:
        if "Authentication" in visible_texts():
            sign_in_qa_user()
        else:
            dismiss_post_auth_intro()
        if "Account Name" in visible_texts() and "Cancel" in visible_texts():
            tap_text("Cancel", timeout=10)
        if "TOTAL BALANCE" not in visible_texts():
            match = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
            width, height = map(int, match.groups()) if match else (1440, 3120)
            adb("shell", "input", "swipe", width // 2, int(height * 0.32), width // 2, int(height * 0.82), 450)
        wait_text("TOTAL BALANCE", timeout=10)
        create_fixture_accounts()
        return
    if args.register_new_user:
        signup_fresh_user()
        create_fixture_accounts()
        results["credentials_file"] = str(credentials_path.relative_to(ROOT))
        results["note"] = "Credentials are sensitive, stored only in ignored app/build evidence; do not commit or share them."
        save_results()
        return
    delete_signed_in_user()
    signup_fresh_user()
    create_fixture_accounts()
    results["credentials_file"] = str(credentials_path.relative_to(ROOT))
    results["note"] = "Credentials are sensitive, stored only in ignored app/build evidence; do not commit or share them."
    save_results()


try:
    main()
except Exception as error:
    results["status"] = "FAIL"
    results["error"] = f"{type(error).__name__}: {error}"
    if driver is not None:
        try:
            capture("setup_failure")
        except Exception:
            pass
    save_results()
    raise
finally:
    if driver is not None:
        driver.quit()
if not args.dry_run:
    if email:
        print(f"QA identity: {email}")
        print(f"Credentials file: {credentials_path.resolve()}")
    else:
        print("Seeded the currently signed-in QA identity")
    print(f"Results and screenshots: {run_dir.resolve()}")
