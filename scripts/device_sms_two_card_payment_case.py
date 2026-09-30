"""Exercise same-amount credit-payment pairing against two live QA cards."""

import json
import re
import subprocess
import time
import uuid
import argparse
import xml.etree.ElementTree as ET
from decimal import Decimal
from pathlib import Path


parser = argparse.ArgumentParser(description="Verify same-amount credit payments pair with their own cards")
parser.add_argument("--device", default="emulator-5556", help="ADB serial and Appium device ID")
args = parser.parse_args()

DEVICE = args.device
PACKAGE = "com.example.wallettrackers"
AMOUNT = Decimal("11.00") + Decimal(int(uuid.uuid4().hex[:4], 16) % 9000) / 100
SOURCE_START = Decimal("5000.00")
CARD_START = Decimal("3000.00")
HOME_START = Decimal("16000.00")
SOURCE_END = SOURCE_START - (AMOUNT * 2)
CARD_END = CARD_START + AMOUNT
HOME_END = HOME_START - (AMOUNT * 2)
MARKER = "AUTOMULTICC" + uuid.uuid4().hex[:10].upper()
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / "live_sms_two_card_payment"
OUTPUT.mkdir(parents=True, exist_ok=False)
RESULT = {
    "case": "SMS-18_two_same_amount_payments_pair_to_distinct_cards",
    "status": "IN_PROGRESS",
    "device": DEVICE,
    "amount": f"{AMOUNT:.2f} EGP",
    "marker": MARKER,
    "initial": {"SecondBank": f"{SOURCE_START:.2f}", "TestCard": f"{CARD_START:.2f}",
                "SecondCard": f"{CARD_START:.2f}", "Home": f"{HOME_START:.2f}"},
    "steps": [],
    "cleanup": "not_started",
}
DRIVER = None
MUTATION_STARTED = False


def adb(*command, timeout=10):
    completed = subprocess.run(
        ["adb", "-s", DEVICE, *map(str, command)], check=True, capture_output=True,
        text=True, timeout=timeout, encoding="utf-8", errors="replace",
    )
    return completed.stdout.strip()


def save():
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")


def dump_tree(name=None):
    if DRIVER is None:
        raise RuntimeError("Appium driver is not initialized")
    raw = DRIVER.page_source
    root = ET.fromstring(raw)
    if name:
        (OUTPUT / f"{name}.xml").write_text(raw, encoding="utf-8")
        image_remote = f"/sdcard/{name}.png"
        adb("shell", "screencap", "-p", image_remote, timeout=10)
        adb("pull", image_remote, OUTPUT / f"{name}.png", timeout=10)
    return root


def texts(root):
    return [node.get("text", "").strip() for node in root.iter() if node.get("text", "").strip()]


def bounds(node):
    points = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(points) != 4:
        raise AssertionError(f"Missing UI bounds: {node.attrib}")
    return (points[0] + points[2]) // 2, (points[1] + points[3]) // 2


def tap(x, y):
    adb("shell", "input", "tap", x, y)
    time.sleep(0.2)


def long_press(x, y):
    adb("shell", "input", "swipe", x, y, x, y, 1000)
    time.sleep(0.2)


def home():
    deadline = time.monotonic() + 10
    root = dump_tree()
    if "TOTAL BALANCE" not in texts(root):
        tap(210, 2990)
    while time.monotonic() < deadline:
        root = dump_tree()
        if "TOTAL BALANCE" in texts(root):
            return root
        time.sleep(0.25)
    if "TOTAL BALANCE" not in texts(root):
        (OUTPUT / "home_timeout.xml").write_bytes(ET.tostring(root, encoding="utf-8"))
        try:
            (OUTPUT / "home_timeout.png").write_bytes(DRIVER.get_screenshot_as_png())
        except Exception as error:
            RESULT["home_timeout_capture_error"] = str(error)
        raise AssertionError(f"Home dashboard did not load; observed UI texts: {texts(root)}")
    return root


def check_balance(account, expected):
    observed = account_balance(account)
    return observed == Decimal(str(expected)) if observed is not None else False


def account_balance(account):
    width, height = map(int, re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size")).groups())
    deadline = time.monotonic() + 10
    pages = []
    directions = ((0.15, 0.86), (0.86, 0.15)) if account == "SecondBank" else ((0.86, 0.15), (0.15, 0.86))
    for start_fraction, end_fraction in directions:
        start_x, end_x = int(width * start_fraction), int(width * end_fraction)
        for _ in range(4):
            if time.monotonic() >= deadline:
                RESULT.setdefault("account_balance_scans", []).append({"account": account, "pages": pages})
                save()
                return None
            root = home()
            values = texts(root)
            pages.append(values)
            for index, value in enumerate(values[:-1]):
                if value == account:
                    normalized = values[index + 1].replace(",", "").replace("EGP", "").strip()
                    try:
                        RESULT.setdefault("account_balance_scans", []).append({"account": account, "pages": pages})
                        save()
                        return Decimal(normalized)
                    except Exception:
                        pass
            adb("shell", "input", "swipe", start_x, int(height * 0.70), end_x, int(height * 0.70), 500)
            time.sleep(0.2)
    RESULT.setdefault("account_balance_scans", []).append({"account": account, "pages": pages})
    save()
    return None


def assert_home(expected):
    actual = home_total()
    if actual != Decimal(str(expected)):
        raise AssertionError(f"Home expected EGP {expected}; observed EGP {actual}")


def home_total():
    values = texts(home())
    index = values.index("TOTAL BALANCE")
    return Decimal(values[index + 1].replace(",", ""))


def records():
    home()
    tap(465, 2990)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        root = dump_tree()
        if "All Records" in texts(root):
            return root
        time.sleep(0.25)
    raise AssertionError("All Records screen did not load")


def payment_rows(root):
    parents = {child: parent for parent in root.iter() for child in parent}
    rows = []
    seen_bounds = set()
    for node in root.iter():
        if not node.get("text", "").strip():
            continue
        current = node
        while current is not None:
            points = list(map(int, re.findall(r"\d+", current.get("bounds", ""))))
            if len(points) == 4 and 0 < points[3] - points[1] < 480:
                content = " ".join(dict.fromkeys(
                    child.get("text", "").strip() for child in current.iter()
                    if child.get("text", "").strip()
                ))
                if "Credit Payment" in content and f"{AMOUNT:.2f}" in content:
                    row_bounds = tuple(points)
                    if row_bounds not in seen_bounds:
                        seen_bounds.add(row_bounds)
                        amount_node = next((child for child in current.iter()
                                            if f"{AMOUNT:.2f}" in child.get("text", "")), node)
                        rows.append({"content": content, "node": amount_node, "bounds": row_bounds})
                    break
            current = parents.get(current)
    return rows


def find_payment(account_part, root=None):
    root = root if root is not None else records()
    return next((row for row in payment_rows(root) if account_part in row["content"]), None)


def delete_payment(account_part):
    root = records()
    row = find_payment(account_part, root)
    if row is None:
        return False
    x, y = bounds(row["node"])
    long_press(x, y)
    root = dump_tree()
    delete_node = next((node for node in root.iter() if node.get("text", "").strip() == "Delete"), None)
    if delete_node is None:
        raise AssertionError(f"No Delete action for test-owned row {row['content']}")
    x, y = bounds(delete_node)
    tap(x, y)
    root = dump_tree()
    confirm = next((node for node in root.iter() if node.get("text", "").strip() == "Delete"), None)
    if "Delete Record" not in texts(root) or confirm is None:
        raise AssertionError("Deletion confirmation did not identify a record")
    x, y = bounds(confirm)
    tap(x, y)
    time.sleep(1.5)
    return True


def cleanup_rows():
    errors = []
    for target in ("SecondBank -> TestCard", "SecondBank -> SecondCard", "TestCard", "SecondCard"):
        for _ in range(3):
            try:
                if find_payment(target) is None:
                    break
                delete_payment(target)
            except Exception as error:
                errors.append(f"{target}: {type(error).__name__}: {error}")
                break
    if errors:
        raise AssertionError("; ".join(errors))


def pending_entries():
    command = ["adb", "-s", DEVICE, "shell", "run-as", PACKAGE, "cat",
               f"/data/user/0/{PACKAGE}/shared_prefs/pending_cc.xml"]
    completed = subprocess.run(command, capture_output=True, text=True, timeout=10,
                               encoding="utf-8", errors="replace")
    if completed.returncode and "No such file or directory" in completed.stderr:
        raw = "<map />"
    elif completed.returncode:
        raise RuntimeError(f"Could not read pending credit-payment state: {completed.stderr.strip()}")
    else:
        raw = completed.stdout
    (OUTPUT / "pending_cc.xml").write_text(raw, encoding="utf-8")
    root = ET.fromstring(raw)
    entries = []
    for node in root.findall("string"):
        key = node.get("name", "")
        if not key.startswith("cc_pending_"):
            continue
        value = node.text or ""
        parts = value.split("|")
        suffix = key.removeprefix("cc_pending_")
        if len(parts) < 3:
            entries.append({"key": key, "raw": value, "format": "invalid"})
        elif suffix.replace(".", "", 1).isdigit():
            entries.append({"key": key, "amount": suffix, "sms_id": parts[0],
                            "card_digits": parts[1], "timestamp": parts[2], "format": "legacy"})
        else:
            entries.append({"key": key, "sms_id": suffix, "amount": parts[0],
                            "card_digits": parts[1], "timestamp": parts[2], "format": "sms_id"})
    return entries


def clear_test_pending_state():
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "run-as", PACKAGE, "rm", "-f",
        f"/data/user/0/{PACKAGE}/shared_prefs/pending_cc.xml")


def send(body):
    adb("emu", "sms", "send", "5550001", body, timeout=10)
    time.sleep(1)


def wait_pending(expected_digits, expected_count, timeout=10):
    deadline = time.monotonic() + timeout
    last = []
    while time.monotonic() < deadline:
        last = pending_entries()
        valid = [entry for entry in last if entry.get("format") != "invalid"]
        if len(last) == expected_count and {entry.get("card_digits") for entry in valid} == set(expected_digits):
            return last
        time.sleep(0.3)
    raise AssertionError(f"Pending-credit state mismatch: expected {expected_count} entries for {expected_digits}; observed {last}")


def wait_pending_empty(timeout=10):
    deadline = time.monotonic() + timeout
    last = []
    while time.monotonic() < deadline:
        last = pending_entries()
        if not last:
            return
        time.sleep(0.3)
    raise AssertionError(f"Pending-credit entries were not consumed: {last}")


def initialize_appium():
    global DRIVER
    from appium import webdriver
    from appium.options.android import UiAutomator2Options
    from appium.webdriver.client_config import AppiumClientConfig

    options = UiAutomator2Options().load_capabilities({
        "platformName": "Android",
        "appium:automationName": "UiAutomator2",
        "appium:deviceName": DEVICE,
        "appium:udid": DEVICE,
        "appium:appPackage": PACKAGE,
        "appium:appActivity": ".MainActivity",
        "appium:noReset": True,
        "appium:newCommandTimeout": 120,
        "appium:uiautomator2ServerLaunchTimeout": 10000,
        "appium:uiautomator2ServerReadTimeout": 10000,
    })
    client_config = AppiumClientConfig(remote_server_addr="http://127.0.0.1:4723", timeout=10)
    DRIVER = webdriver.Remote(options=options, client_config=client_config)
    DRIVER.implicitly_wait(0)
    DRIVER.update_settings({"waitForIdleTimeout": 500})


def main():
    global MUTATION_STARTED
    initialize_appium()
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", timeout=10)
    existing_pending = pending_entries()
    RESULT["pending_before"] = existing_pending
    if existing_pending:
        raise AssertionError(f"Refusing to mutate while pending credit payments exist: {existing_pending}")
    assert_home(HOME_START)
    for account, baseline in (("SecondBank", SOURCE_START), ("TestCard", CARD_START), ("SecondCard", CARD_START)):
        if not check_balance(account, baseline):
            raise AssertionError(f"Initial {account} balance differs from fixture {baseline}")
    root = records()
    if "No records found" not in texts(root):
        raise AssertionError("Requires an empty Records screen before the unique dual-card flow")
    RESULT["steps"].append("verified empty Records, pending-payment state, and exact four-account/Home baselines")
    save()

    credit_one = f"EGP {AMOUNT:.2f} payment received for credit card ****3333 has been credited. Available credit EGP {CARD_END:.2f}. {MARKER}A"
    credit_two = f"EGP {AMOUNT:.2f} payment received for credit card ****6666 has been credited. Available credit EGP {CARD_END:.2f}. {MARKER}B"
    debit_one = f"Your bank account ****2222 was debited EGP {AMOUNT:.2f} for credit card payment to card ****3333. Available balance EGP {SOURCE_START - AMOUNT:.2f}. {MARKER}C"
    debit_two = f"Your bank account ****2222 was debited EGP {AMOUNT:.2f} for credit card payment to card ****6666. Available balance EGP {SOURCE_END:.2f}. {MARKER}D"

    MUTATION_STARTED = True
    send(credit_one)
    pending_one = wait_pending({"3333"}, 1)
    RESULT["pending_after_first_credit"] = pending_one
    send(credit_two)
    pending_two = wait_pending({"3333", "6666"}, 2)
    if len({entry["key"] for entry in pending_two}) != 2 or any(
        Decimal(entry["amount"]) != AMOUNT for entry in pending_two
    ):
        raise AssertionError(f"Expected two distinct same-amount card pending entries, observed {pending_two}")
    RESULT["pending_after_two_credit_messages"] = pending_two
    RESULT["steps"].append("injected equal-amount credit-side messages for TestCard and SecondCard before either debit-side message")
    if not check_balance("TestCard", CARD_END) or not check_balance("SecondCard", CARD_END):
        raise AssertionError("Both cards did not receive exactly one credit-side balance effect")
    source_after_credit = account_balance("SecondBank")
    RESULT["source_balance_after_credit_only"] = (
        f"{source_after_credit:.2f}" if source_after_credit is not None else None
    )
    if source_after_credit != SOURCE_START:
        raise AssertionError(
            f"Credit-side messages should leave SecondBank at {SOURCE_START:.2f}; "
            f"observed {RESULT['source_balance_after_credit_only']}"
        )
    RESULT["credit_only_balances"] = {"TestCard": f"{CARD_END:.2f}", "SecondCard": f"{CARD_END:.2f}",
                                      "SecondBank": f"{SOURCE_START:.2f}", "Home": f"{HOME_START:.2f}"}
    save()

    send(debit_one)
    pending_after_first_debit = wait_pending({"6666"}, 1)
    RESULT["pending_after_first_debit"] = pending_after_first_debit
    send(debit_two)
    wait_pending_empty()
    RESULT["pending_after_second_debit"] = []
    RESULT["steps"].append("injected each matching source-debit SMS with its explicit destination-card suffix")
    if not check_balance("SecondBank", SOURCE_END):
        raise AssertionError(f"SecondBank expected {SOURCE_END:.2f} after two debits")
    if not check_balance("TestCard", CARD_END) or not check_balance("SecondCard", CARD_END):
        raise AssertionError("Paired debits changed either credit-card available balance a second time")
    assert_home(HOME_END)

    root = records()
    (OUTPUT / "records_after_pairing.xml").write_bytes(ET.tostring(root, encoding="utf-8"))
    rows = [row["content"] for row in payment_rows(root)]
    RESULT["rows_after_pairing"] = rows
    expected = ("SecondBank -> TestCard", "SecondBank -> SecondCard")
    if len(rows) != 2 or not all(any(dest in row for row in rows) for dest in expected):
        RESULT["status"] = "FAIL"
        raise AssertionError(f"Expected exactly one independently paired row per card {expected}; observed {rows}")
    if not all(f"{AMOUNT:.2f}" in row for row in rows):
        raise AssertionError(f"Paired row amount mismatch: {rows}")
    RESULT["after_pairing"] = {"SecondBank": f"{SOURCE_END:.2f}", "TestCard": f"{CARD_END:.2f}",
                               "SecondCard": f"{CARD_END:.2f}", "Home": f"{HOME_END:.2f}"}
    RESULT["steps"].append("verified exactly two Credit Payment rows, each linked to the intended distinct card and exact same amount")
    save()

    cleanup_rows()
    adb("shell", "am", "force-stop", PACKAGE)
    verify_fixture("success_cleanup")
    RESULT["status"] = "PASS"
    RESULT["cleanup"] = "exact test-owned payment rows removed; restart restored all account and Home baselines; ledger empty"
    RESULT["steps"].append("deleted all exact test-owned payment rows and verified empty Records and restored account/Home balances after restart")
    save()


def verify_fixture(stage):
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", timeout=10)
    deadline = time.monotonic() + 10
    last_error = None
    while time.monotonic() < deadline:
        try:
            assert_home(HOME_START)
            balances = {}
            for account, baseline in (("SecondBank", SOURCE_START), ("TestCard", CARD_START), ("SecondCard", CARD_START)):
                balances[account] = account_balance(account)
            empty_records = "No records found" in texts(records())
            actual_home = home_total()
            audit = {"Home": f"{actual_home:.2f}", **{
                account: f"{amount:.2f}" if amount is not None else None for account, amount in balances.items()
            }, "empty_records": empty_records}
            expected_balances = {"SecondBank": SOURCE_START, "TestCard": CARD_START, "SecondCard": CARD_START}
            if actual_home != HOME_START or any(balances[account] != expected for account, expected in expected_balances.items()) or not empty_records:
                raise AssertionError(f"Fixture cleanup audit failed: {audit}")
            RESULT[f"{stage}_audit"] = {**audit, "records_empty": True}
            return
        except Exception as error:
            last_error = error
            time.sleep(0.5)
    raise AssertionError(f"Fixture cleanup did not verify after restart: {last_error}")


try:
    main()
except Exception as error:
    RESULT["status"] = "FAIL"
    RESULT["error"] = f"{type(error).__name__}: {error}"
    if not MUTATION_STARTED:
        RESULT["cleanup"] = "no test SMS was injected; pre-existing state was left untouched"
    else:
        try:
            cleanup_rows()
        except Exception as cleanup_error:
            RESULT["cleanup_row_error"] = f"{type(cleanup_error).__name__}: {cleanup_error}"
        try:
            clear_test_pending_state()
        except Exception as cleanup_error:
            RESULT["pending_cleanup_error"] = f"{type(cleanup_error).__name__}: {cleanup_error}"
        try:
            verify_fixture("failure_cleanup")
            RESULT["cleanup"] = "test-owned payment rows removed; restart audit verified exact account/Home baselines and empty Records"
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"MANUAL REVIEW REQUIRED: {type(cleanup_error).__name__}: {cleanup_error}"
            try:
                RESULT["post_cleanup_home_text"] = texts(home())[:20]
                RESULT["post_cleanup_account_checks"] = {
                    account: (f"{amount:.2f}" if amount is not None else None)
                    for account, amount in ((account, account_balance(account))
                                            for account in ("SecondBank", "TestCard", "SecondCard"))
                }
                RESULT["post_cleanup_home"] = f"{home_total():.2f}"
                RESULT["post_cleanup_pending"] = pending_entries()
                RESULT["post_cleanup_records_empty"] = "No records found" in texts(records())
            except Exception as audit_error:
                RESULT["post_cleanup_audit_error"] = f"{type(audit_error).__name__}: {audit_error}"
    raise
finally:
    save()
    if DRIVER is not None:
        try:
            DRIVER.quit()
        except Exception:
            pass

print(json.dumps(RESULT, indent=2))
print(f"Evidence: {OUTPUT.resolve()}")
