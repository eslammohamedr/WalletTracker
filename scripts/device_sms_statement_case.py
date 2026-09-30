"""Guarded real-app statement SMS, notification, payment, and rollback scenario."""

import argparse
import contextlib
import datetime
import json
import re
import secrets
import sqlite3
import string
import subprocess
import tempfile
import time
from decimal import Decimal
from pathlib import Path
from xml.etree import ElementTree

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy


PACKAGE = "com.example.wallettrackers"
SENDER = "1861"
CARD = "TestCard"
CARD_SUFFIX = "3333"
parser = argparse.ArgumentParser(
    description="Exercise statement SMS ingestion, reminders, credit payment, and exact rollback."
)
parser.add_argument("--execute", action="store_true", help="Allow SMS injection and app mutations.")
parser.add_argument(
    "--recover-existing",
    action="store_true",
    help="Pay and roll back only an already-visible exact 3333 / 0.07 / 10 Oct 2026 statement; sends no SMS.",
)
parser.add_argument(
    "--rollback-existing-payment",
    action="store_true",
    help="Delete only the exact existing Credit / SecondBank -> TestCard / -0.07 EGP row; sends no SMS and does not pay.",
)
parser.add_argument("--device", default="emulator-5554", help="ADB serial / Appium device ID.")
parser.add_argument("--server", default="http://127.0.0.1:4723", help="Appium server URL.")
parser.add_argument("--amount", default="0.07", help="Statement total in EGP.")
parser.add_argument("--minimum", default="0.03", help="Minimum payment in EGP.")
parser.add_argument("--due-date", default="10/10/2026", help="Statement due date in DD/MM/YYYY format.")
parser.add_argument("--source-account", default="SecondBank", help="EGP debit account used to pay the statement.")
parser.add_argument("--home-baseline", default="15999.83")
parser.add_argument("--source-baseline", default="5000.00")
parser.add_argument("--card-baseline", default="3000.00")
args = parser.parse_args()
selected_modes = sum((args.execute, args.recover_existing, args.rollback_existing_payment))
if selected_modes > 1:
    parser.error("Choose only one of --execute, --recover-existing, or --rollback-existing-payment.")
if not selected_modes:
    parser.error("No device action is allowed by default; pass an explicit execution or recovery mode.")

SOURCE = args.source_account
AMOUNT = Decimal(args.amount)
MINIMUM = Decimal(args.minimum)
parsed_due_date = datetime.datetime.strptime(args.due_date, "%d/%m/%Y")
DUE_TEXT = f"{parsed_due_date.day} {parsed_due_date.strftime('%b %Y')}"
HOME_BASELINE = Decimal(args.home_baseline)
SOURCE_BASELINE = Decimal(args.source_baseline)
CARD_BASELINE = Decimal(args.card_baseline)

MARKER = "AUTOSTMT" + "".join(secrets.choice(string.ascii_uppercase + string.digits) for _ in range(12))
BODY = (
    f"Your credit card ****{CARD_SUFFIX} statement is ready. Total amount due EGP {AMOUNT:.2f}. "
    f"Minimum payment EGP {MINIMUM:.2f}. Due Date {args.due_date}. {MARKER}"
)
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / "live_credit_statement_small_payment"
OUTPUT.mkdir(parents=True, exist_ok=True)
RESULT = {
    "case": "live_credit_statement_small_payment_and_rollback",
    "status": "IN_PROGRESS",
    "device": args.device,
    "marker": MARKER,
    "sender": SENDER,
    "amount": f"{AMOUNT:.2f} EGP",
    "minimum_due": f"{MINIMUM:.2f} EGP",
    "due_date": args.due_date,
    "baseline": {
        "home_egp": f"{HOME_BASELINE:.2f}",
        "source_account": SOURCE,
        "source_egp": f"{SOURCE_BASELINE:.2f}",
        "credit_card": CARD,
        "card_available_egp": f"{CARD_BASELINE:.2f}",
    },
    "sms_injection_attempted": False,
    "steps": [],
    "cleanup": "not_needed",
}
driver = None
statement_seen = False
payment_started = False
delete_attempted = False


def save():
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")


def adb(*command, timeout=10):
    return subprocess.check_output(["adb", "-s", args.device, *command], timeout=timeout).decode(
        "utf-8", errors="replace"
    )


def adb_bytes(*command, timeout=10):
    return subprocess.check_output(["adb", "-s", args.device, *command], timeout=timeout)


def tree():
    deadline = time.monotonic() + 10
    last = None
    while time.monotonic() < deadline:
        try:
            return ElementTree.fromstring(driver.page_source)
        except Exception as error:
            last = str(error)
            time.sleep(0.25)
    raise RuntimeError(f"Appium hierarchy unavailable within 10 seconds: {last}")


def nodes(root=None):
    root = root if root is not None else tree()
    return [node for node in root.iter() if node is not root and node.attrib]


def texts(root=None):
    return [node.get("text", "").strip() for node in nodes(root) if node.get("text", "").strip()]


def has_text(value, root=None):
    return any(value.casefold() in text.casefold() for text in texts(root))


def wait_text(value, timeout=10, present=True):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if has_text(value) == present:
            return
        time.sleep(0.35)
    raise AssertionError(f"Timed out waiting for text {value!r}, present={present}")


def tap_text(value, exact=False):
    selector = f"new UiSelector().text({json.dumps(value)})" if exact else (
        f"new UiSelector().textContains({json.dumps(value)})"
    )
    found = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, selector)
    if not found:
        wait_text(value)
        found = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, selector)
    if not found:
        raise AssertionError(f"Could not tap visible text {value!r}")
    found[-1].click()


def tap_accessibility(value):
    found = driver.find_elements(AppiumBy.ACCESSIBILITY_ID, value)
    if not found:
        raise AssertionError(f"Could not tap accessibility label {value!r}")
    found[-1].click()


def capture(name):
    driver.save_screenshot(str(OUTPUT / f"{name}.png"))
    (OUTPUT / f"{name}.xml").write_text(driver.page_source, encoding="utf-8")


def record_row(root=None):
    root = root if root is not None else tree()
    all_nodes = list(root.iter())
    parents = {child: parent for parent in root.iter() for child in parent}
    matches = []
    for amount_node in all_nodes:
        value = amount_node.get("text", "").replace(",", "").replace("EGP", "").strip()
        try:
            if Decimal(value) != -AMOUNT:
                continue
        except Exception:
            continue
        current = amount_node
        while current is not None:
            bounds = list(map(int, re.findall(r"\d+", current.get("bounds", ""))))
            row_text = " ".join(child.get("text", "") for child in current.iter())
            if len(bounds) == 4 and bounds[3] - bounds[1] < 500:
                if (SOURCE in row_text and CARD in row_text and "Credit" in row_text):
                    matches.append(amount_node)
                    break
            current = parents.get(current)
    if len(matches) > 1:
        raise AssertionError(f"Ambiguous payment record signature: found {len(matches)} matching rows")
    return matches[0] if matches else None


def scan_record_signature():
    width, height = 1440, 3120
    try:
        match = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
        if match:
            width, height = map(int, match.groups())
    except Exception:
        pass
    previous_page = None
    for _ in range(20):
        root = tree()
        found = record_row(root)
        if found is not None:
            return found
        page = tuple(texts(root))
        if page == previous_page:
            break
        previous_page = page
        adb("shell", "input", "swipe", str(int(width * 0.5)), str(int(height * 0.76)),
            str(int(width * 0.5)), str(int(height * 0.30)), "400")
        time.sleep(0.3)
    return None


def go_home():
    if not has_text("TOTAL BALANCE"):
        try:
            tap_text("Home")
        except Exception:
            driver.back()
    wait_text("TOTAL BALANCE")


def carousel_has_balance(account, expected):
    expected_text = f"{expected:,.2f}"
    root = tree()
    account_nodes = [n for n in nodes(root) if n.get("text", "").strip() == account]
    for account_node in account_nodes:
        rect = list(map(int, re.findall(r"\d+", account_node.get("bounds", ""))))
        if len(rect) != 4:
            continue
        for candidate in nodes(root):
            value = candidate.get("text", "").replace(",", "").replace("EGP", "").strip()
            try:
                if Decimal(value) != expected:
                    continue
            except Exception:
                continue
            value_rect = list(map(int, re.findall(r"\d+", candidate.get("bounds", ""))))
            if len(value_rect) == 4 and rect[1] <= value_rect[1] <= rect[3] + 180:
                return True
    return expected_text in texts(root) or f"{expected:.2f}" in texts(root)


def scan_account(account, expected):
    width, height = 1440, 3120
    try:
        match = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
        if match:
            width, height = map(int, match.groups())
    except Exception:
        pass
    for swipe_start, swipe_end in ((0.78, 0.25), (0.25, 0.78)):
        for _ in range(16):
            if carousel_has_balance(account, expected):
                return True
            adb("shell", "input", "swipe", str(int(width * swipe_start)), str(int(height * 0.70)),
                str(int(width * swipe_end)), str(int(height * 0.70)), "450")
            time.sleep(0.35)
    return False


def assert_baseline():
    go_home()
    current = texts()
    if "TOTAL BALANCE" not in current or f"{HOME_BASELINE:,.2f}" not in current:
        raise AssertionError(f"Home total baseline mismatch; expected {HOME_BASELINE}, visible={current}")
    if not scan_account(SOURCE, SOURCE_BASELINE):
        raise AssertionError(f"Could not confirm {SOURCE} balance {SOURCE_BASELINE}")
    if not scan_account(CARD, CARD_BASELINE):
        raise AssertionError(f"Could not confirm {CARD} available credit {CARD_BASELINE}")


def assert_balances(home, source, card):
    go_home()
    visible = texts()
    if f"{home:,.2f}" not in visible:
        raise AssertionError(f"Home total expected {home:.2f}; visible={visible}")
    if not scan_account(SOURCE, source):
        raise AssertionError(f"{SOURCE} expected {source:.2f} EGP")
    if not scan_account(CARD, card):
        raise AssertionError(f"{CARD} expected {card:.2f} EGP available credit")


def open_credit_tab():
    go_home()
    tap_accessibility("Stats")
    wait_text("Statistics")
    tap_text("Credit", exact=True)
    wait_text("Credit Card Statements")


def find_statement_signature():
    values = [value.replace(",", "") for value in texts()]
    return (
        any(f"Card Ending ****{CARD_SUFFIX}" in value for value in values)
        and any(f"{AMOUNT:.2f} EGP" in value for value in values)
        and any(DUE_TEXT in value for value in values)
    )


def statement_notification():
    dump = adb("shell", "dumpsys", "notification", "--noredact", timeout=10)
    (OUTPUT / "notifications.txt").write_text(dump, encoding="utf-8")
    for block in re.split(r"android.title=String \(Credit Card Bill Issued\)", dump)[1:]:
        notification_text = re.search(r"android.text=String \(([^)]*)\)", block)
        if notification_text is None or CARD_SUFFIX not in notification_text.group(1):
            continue
        printed_amount = re.search(r"([\d,]+(?:\.\d+)?)\s+EGP", notification_text.group(1))
        if printed_amount and Decimal(printed_amount.group(1).replace(",", "")) == AMOUNT:
            return True
    return False


def reminder_work_rows(sms_id):
    if not re.fullmatch(r"[A-Za-z0-9:_-]+", sms_id):
        raise AssertionError(f"Unexpected statement SMS ID for WorkManager query: {sms_id!r}")
    names = [f"reminder_{sms_id}_{days}" for days in (5, 1, 0)]
    rows = {}
    with tempfile.TemporaryDirectory(prefix="wallet-workdb-") as temporary_directory:
        database_path = Path(temporary_directory) / "androidx.work.workdb"
        for suffix in ("", "-wal", "-shm"):
            remote_path = f"no_backup/androidx.work.workdb{suffix}"
            try:
                contents = adb_bytes("exec-out", "run-as", PACKAGE, "cat", remote_path, timeout=10)
            except subprocess.CalledProcessError:
                if suffix == "":
                    raise
                continue
            (Path(str(database_path) + suffix)).write_bytes(contents)
        with contextlib.closing(sqlite3.connect(database_path)) as connection:
            result = connection.execute(
                "SELECT WorkName.name, WorkSpec.state FROM WorkName "
                "JOIN WorkSpec ON WorkName.work_spec_id=WorkSpec.id "
                "WHERE WorkName.name IN (?, ?, ?)", names
            )
            rows = {name: int(state) for name, state in result.fetchall()}
    return names, rows


def latest_statement_sms_id(previous_logs):
    current = adb("logcat", "-d", "-s", "ReminderMgr:D", timeout=10)
    previous_lines = set(previous_logs.splitlines())
    matches = []
    for line in current.splitlines():
        if line in previous_lines:
            continue
        match = re.search(r"scheduleStatementReminders START: smsId=([^ ]+) card=(\d{4}) amount=([0-9.]+)\b", line)
        if match and match.group(2) == CARD_SUFFIX and Decimal(match.group(3)) == AMOUNT:
            matches.append(match.group(1))
    if not matches:
        raise AssertionError("Could not determine the new statement SMS ID from ReminderManager diagnostics")
    return matches[-1]


def assert_statement_reminders_scheduled(sms_id):
    names, rows = reminder_work_rows(sms_id)
    expected_names = [name for name, days in zip(names, (5, 1, 0)) if parsed_due_date.date() - datetime.timedelta(days=days) >= datetime.date.today()]
    if any(name not in rows or rows[name] != 0 for name in expected_names):
        raise AssertionError(f"Expected future statement reminders ENQUEUED before payment: {rows}; expected={expected_names}")
    RESULT["reminder_work_before_payment"] = {
        name: ("not scheduled; reminder date already passed" if name not in rows else "ENQUEUED" if rows[name] == 0 else rows[name])
        for name in names
    }
    save()


def assert_statement_reminders_cancelled(sms_id):
    deadline = time.monotonic() + 10
    last_rows = {}
    names, _ = reminder_work_rows(sms_id)
    while time.monotonic() < deadline:
        _, last_rows = reminder_work_rows(sms_id)
        if all(name not in last_rows or last_rows[name] == 5 for name in names):
            RESULT["reminder_work_after_payment"] = {
                name: ("absent" if name not in last_rows else "CANCELLED") for name in names
            }
            save()
            return
        time.sleep(0.5)
    raise AssertionError(f"Statement reminder WorkNames were not cancelled after payment: {last_rows}")


def verify_payment_excluded_from_spending():
    go_home()
    tap_accessibility("Stats")
    wait_text("Statistics")
    tap_text("Spending", exact=True)
    wait_text("Spending Over Time")
    deadline = time.monotonic() + 10
    visible = []
    while time.monotonic() < deadline:
        root = tree()
        visible = texts(root)
        if "Spending Over Time" in visible and "Expense" in visible and "0.00" in visible:
            capture("08_payment_statistics_exclusion")
            RESULT["statistics"] = {"ordinary_expense": "0.00 EGP", "spending_report_visible": True}
            RESULT["steps"].append("verified the statement payment did not inflate ordinary Spending Statistics")
            save()
            go_home()
            return
        time.sleep(0.4)
    raise AssertionError(f"Statement payment appeared as ordinary spending or Statistics failed to load: {visible}")


def assert_payment_row_present():
    tap_text("Records")
    wait_text("All Records")
    found = scan_record_signature()
    if found is not None:
        capture("04_payment_record")
        RESULT["payment_row_signature"] = f"Credit / {SOURCE} -> {CARD} / -{AMOUNT:.2f} EGP"
        save()
        return found
    raise AssertionError(f"No unique Credit row for {SOURCE} -> {CARD} / -{AMOUNT:.2f} EGP")


def delete_exact_payment():
    global delete_attempted
    tap_text("Records")
    wait_text("All Records")
    target = assert_payment_row_present()
    rect = list(map(int, re.findall(r"\d+", target.get("bounds", ""))))
    if len(rect) != 4:
        raise AssertionError("Exact payment amount row has no screen bounds; refusing delete")
    x, y = (rect[0] + rect[2]) // 2, (rect[1] + rect[3]) // 2
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")
    wait_text("Delete")
    capture("05_delete_action_sheet")
    action_delete = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Delete")')
    if not action_delete:
        raise AssertionError("Record options did not expose Delete; refusing cleanup")
    action_delete[-1].click()
    wait_text("Delete Record")
    capture("06_delete_confirmation")
    buttons = driver.find_elements(AppiumBy.ANDROID_UIAUTOMATOR, 'new UiSelector().text("Delete")')
    if not buttons:
        raise AssertionError("Delete Record confirmation dialog had no Delete button; refusing cleanup")
    delete_attempted = True
    RESULT["cleanup"] = "exact row signature verified; deleting payment record"
    save()
    buttons[-1].click()
    wait_text("Delete Record", present=False)
    time.sleep(1)
    if scan_record_signature() is not None:
        raise AssertionError("Payment record remained after delete confirmation")
    capture("07_payment_deleted")


def payment_state_absent():
    open_credit_tab()
    if find_statement_signature():
        return False
    tap_text("Records")
    wait_text("All Records")
    return scan_record_signature() is None


save()
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
        "appium:uiautomator2ServerLaunchTimeout": 10000,
    })
    driver = webdriver.Remote(args.server.rstrip("/"), options=options)
    driver.implicitly_wait(0)
    try:
        driver.command_executor.set_timeout(10)
    except AttributeError:
        pass
    driver.activate_app(PACKAGE)
    if not args.rollback_existing_payment:
        assert_baseline()
    if args.recover_existing:
        open_credit_tab()
        if not find_statement_signature():
            raise AssertionError(f"Recovery refused: exact {CARD} ****{CARD_SUFFIX} / EGP {AMOUNT:.2f} / due {DUE_TEXT} statement not visible")
        capture("01_recovery_statement_preflight")
        tap_text("Records")
        wait_text("All Records")
        if scan_record_signature() is not None:
            raise AssertionError("Recovery refused: matching payment row already exists; inspect before proceeding")
        open_credit_tab()
        if not find_statement_signature():
            raise AssertionError("Recovery refused: statement disappeared during preflight")
        statement_seen = True
        RESULT["steps"].append("recovery mode confirmed exact existing statement; no SMS will be sent")
        tap_text("Pay Now")
        wait_text("Select Payment Account")
        if not has_text(SOURCE):
            raise AssertionError(f"{SOURCE} is not offered as an eligible EGP payment account")
        payment_started = True
        tap_text(SOURCE, exact=True)
        wait_text("Select Payment Account", present=False)
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline and find_statement_signature():
            time.sleep(0.5)
        if find_statement_signature():
            raise AssertionError("Statement remained after recovery payment submission")
        assert_payment_row_present()
        go_home()
        if not scan_account(SOURCE, SOURCE_BASELINE - AMOUNT):
            raise AssertionError(f"{SOURCE} did not decrease by exactly EGP {AMOUNT:.2f} after recovery payment")
        if not scan_account(CARD, CARD_BASELINE + AMOUNT):
            raise AssertionError(f"{CARD} available credit did not increase by exactly EGP {AMOUNT:.2f}")
        if f"{HOME_BASELINE - AMOUNT:,.2f}" not in texts():
            raise AssertionError(f"Home dashboard did not change by exactly -EGP {AMOUNT:.2f} after recovery payment")
        delete_exact_payment()
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        time.sleep(3)
        assert_baseline()
        if not payment_state_absent():
            raise AssertionError("Recovery artifacts remain after payment rollback")
        RESULT["cleanup"] = "exact recovery payment row deleted; source/card/Home baseline restored after restart"
        RESULT["steps"].append("recovery completed without SMS injection; baseline restored after relaunch")
        RESULT["status"] = "PASS"
        save()
        print(json.dumps(RESULT, indent=2))
        print(f"Evidence: {OUTPUT.resolve()}")
        raise SystemExit(0)
    if args.rollback_existing_payment:
        assert_balances(HOME_BASELINE - AMOUNT, SOURCE_BASELINE - AMOUNT, CARD_BASELINE + AMOUNT)
        capture("01_rollback_existing_payment_preflight")
        open_credit_tab()
        if find_statement_signature():
            raise AssertionError("Rollback refused: a matching unpaid statement is still visible")
        tap_text("Records")
        wait_text("All Records")
        if scan_record_signature() is None:
            raise AssertionError(f"Rollback refused: exact Credit / {SOURCE} -> {CARD} / -{AMOUNT:.2f} row not found")
        RESULT["steps"].append("verified paid-state balances and exact existing Credit payment row; no SMS/payment action")
        delete_exact_payment()
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        time.sleep(3)
        assert_baseline()
        if not payment_state_absent():
            raise AssertionError("Rollback did not remove matching statement/payment artifacts")
        RESULT["cleanup"] = "exact existing payment row deleted; source/card/Home baseline restored after restart"
        RESULT["steps"].append("restarted and verified baseline balances and absence of matching statement/payment row")
        RESULT["status"] = "PASS"
        save()
        print(json.dumps(RESULT, indent=2))
        print(f"Evidence: {OUTPUT.resolve()}")
        raise SystemExit(0)
    capture("01_home_preflight")
    RESULT["steps"].append(f"verified controlled Home, {SOURCE}, and {CARD} baseline")

    open_credit_tab()
    if has_text("No pending credit card statements") is False:
        # A statement row is an unsafe precondition, regardless of its amount.
        if any("Card Ending ****" in value for value in texts()):
            raise AssertionError("Pending statement exists; refusing SMS injection and payment")
    capture("02_credit_preflight")
    RESULT["steps"].append("confirmed there is no pending credit-card statement before injection")

    tap_text("Records")
    wait_text("All Records")
    if scan_record_signature() is not None:
        raise AssertionError("Matching payment record exists; refusing ambiguous duplicate")
    go_home()
    reminder_logs_before = adb("logcat", "-d", "-s", "ReminderMgr:D", timeout=10)
    save()

    RESULT["sms_injection_attempted"] = True
    RESULT["steps"].append("injecting one uniquely marked statement SMS from numeric sender 1861")
    save()
    adb("emu", "sms", "send", SENDER, BODY, timeout=10)
    time.sleep(5)
    open_credit_tab()
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline and not find_statement_signature():
        time.sleep(0.5)
    if not find_statement_signature():
        raise AssertionError("Statement card/amount/due-date signature did not appear")
    statement_seen = True
    visible = texts()
    if not has_text("Reminders active"):
        raise AssertionError("Statement UI does not indicate active reminders")
    capture("03_statement_created")
    RESULT["steps"].append(f"verified {CARD}, EGP {AMOUNT:.2f} total, due {DUE_TEXT}, and active reminders")
    RESULT["minimum_due_sms"] = f"{MINIMUM:.2f} EGP in injected SMS; app statement model/UI exposes total due only"
    if not statement_notification():
        raise AssertionError("Credit Card Bill Issued notification did not include card, amount, and due statement")
    RESULT["steps"].append("verified statement-issued notification in Android notification service")
    statement_sms_id = latest_statement_sms_id(reminder_logs_before)
    RESULT["statement_sms_id"] = statement_sms_id
    assert_statement_reminders_scheduled(statement_sms_id)
    RESULT["steps"].append("verified the five-day, one-day, and due-day reminder WorkNames were enqueued")
    assert_baseline()
    RESULT["steps"].append("verified statement ingestion did not alter account or Home balances")
    save()

    open_credit_tab()
    if not find_statement_signature():
        raise AssertionError("Statement signature disappeared before payment; refusing payment action")
    tap_text("Pay Now")
    wait_text("Select Payment Account")
    if not has_text(SOURCE):
        raise AssertionError(f"{SOURCE} is not offered as an eligible EGP payment account")
    payment_started = True
    tap_text(SOURCE, exact=True)
    wait_text("Select Payment Account", present=False)
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline and find_statement_signature():
        time.sleep(0.5)
    if find_statement_signature():
        raise AssertionError("Statement remained after payment submission")
    assert_statement_reminders_cancelled(statement_sms_id)
    RESULT["steps"].append("verified all three statement reminder WorkNames are cancelled or absent after payment")
    RESULT["steps"].append(f"paid the EGP {AMOUNT:.2f} statement from {SOURCE} through the app picker")
    save()
    assert_payment_row_present()
    go_home()
    if not scan_account(SOURCE, SOURCE_BASELINE - AMOUNT):
        raise AssertionError(f"{SOURCE} did not decrease by exactly EGP {AMOUNT:.2f} after payment")
    if not scan_account(CARD, CARD_BASELINE + AMOUNT):
        raise AssertionError(f"{CARD} available credit did not increase by exactly EGP {AMOUNT:.2f}")
    if f"{HOME_BASELINE - AMOUNT:,.2f}" not in texts():
        raise AssertionError(f"Home dashboard did not change by exactly -EGP {AMOUNT:.2f} after payment")
    RESULT["after_payment"] = {
        "source_account": SOURCE,
        "source_balance": f"{SOURCE_BASELINE - AMOUNT:.2f} EGP",
        "card_available_credit": f"{CARD_BASELINE + AMOUNT:.2f} EGP",
        "home_total": f"{HOME_BASELINE - AMOUNT:.2f} EGP",
    }
    save()
    verify_payment_excluded_from_spending()

    delete_exact_payment()
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
    time.sleep(3)
    assert_baseline()
    if not payment_state_absent():
        raise AssertionError("Statement/payment fixture artifacts remain after rollback")
    RESULT["cleanup"] = f"exact payment row deleted; {SOURCE}, {CARD}, Home and no-statement baseline restored after restart"
    RESULT["steps"].append("force-stopped/relaunched and verified exact financial and statement baseline restoration")
    RESULT["status"] = "PASS"
except Exception as error:
    mutation_may_have_occurred = payment_started or delete_attempted
    RESULT["status"] = "FAIL" if RESULT["sms_injection_attempted"] or mutation_may_have_occurred else "INCONCLUSIVE"
    RESULT["error"] = f"{type(error).__name__}: {error}"
    if not RESULT["sms_injection_attempted"]:
        RESULT["steps"].append("no statement SMS was sent; startup or safety preflight did not pass")
    elif payment_started and not delete_attempted and driver is not None:
        try:
            delete_exact_payment()
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
            time.sleep(3)
            assert_baseline()
            RESULT["cleanup"] = "exact payment row deleted after failure; financial baseline restored after restart"
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"cleanup blocked; do not rerun until manually inspected: {cleanup_error}"
    elif statement_seen:
        RESULT["cleanup"] = "statement exists but payment did not complete; left untouched for safe manual inspection"
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
