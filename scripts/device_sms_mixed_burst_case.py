import argparse
import json
import re
import secrets
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree

from qa_campaign_support import Device, ROOT


PARSER = argparse.ArgumentParser(description="Verify a mixed SMS income/expense burst on a live Wallet emulator.")
PARSER.add_argument("--execute", action="store_true")
PARSER.add_argument("--device", default="emulator-5554")
ARGS = PARSER.parse_args()
if not ARGS.execute:
    PARSER.error("Pass --execute to inject test SMS and mutate the disposable QA Wallet.")

MARKER = "BURST" + secrets.token_hex(5).upper()
INCOME_AMOUNT = "0.15"
EXPENSE_AMOUNT = "0.10"
BASE_ACCOUNT = "10000.00"
BASE_HOME = "16000.00"
AFTER_ACCOUNT = "10000.05"
AFTER_HOME = "16000.05"
SENDER = "1922"
INCOME_BODY = f"Your HSBC account ****1111 was credited EGP {INCOME_AMOUNT} from salary. Available balance EGP 10000.15. {MARKER}IN"
EXPENSE_BODY = f"Your HSBC account ****1111 was debited EGP {EXPENSE_AMOUNT} at Carrefour. Available balance EGP {AFTER_ACCOUNT}. {MARKER}EX"
OUTPUT = ROOT / "app/build/device-smoke" / ("sms-mixed-burst-" + time.strftime("%Y%m%d-%H%M%S"))
OUTPUT.mkdir(parents=True, exist_ok=True)
RESULT = {
    "case": "SMS-24-mixed-income-expense-burst",
    "device": ARGS.device,
    "marker": MARKER,
    "sms": [{"sender": SENDER, "body": INCOME_BODY}, {"sender": SENDER, "body": EXPENSE_BODY}],
    "baseline": {"MainBank": BASE_ACCOUNT, "Home": BASE_HOME},
    "expected_after_burst": {"MainBank": AFTER_ACCOUNT, "Home": AFTER_HOME},
    "status": "IN_PROGRESS",
    "steps": [],
    "cleanup": "not_started",
}
DEVICE = None
INJECTED = False


def save():
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2, ensure_ascii=False), encoding="utf-8")


def page():
    return ElementTree.fromstring(DEVICE.driver.page_source)


def texts():
    return [node.get("text", "").strip() for node in page().iter() if node.get("text", "").strip()]


def amount_row(amount, category, merchant):
    root = page()
    nodes = list(root.iter())
    matches = []
    for amount_node in nodes:
        if amount_node.get("text", "").strip() != amount:
            continue
        bounds = list(map(int, re.findall(r"\d+", amount_node.get("bounds", ""))))
        if len(bounds) != 4:
            continue
        middle_y = (bounds[1] + bounds[3]) // 2
        nearby = []
        for node in nodes:
            label = node.get("text", "").strip()
            node_bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if label and len(node_bounds) == 4 and abs((node_bounds[1] + node_bounds[3]) // 2 - middle_y) <= 130:
                nearby.append(label)
        if category in nearby and "MainBank" in nearby and merchant in nearby:
            matches.append((amount_node, nearby))
    if len(matches) > 1:
        raise AssertionError(f"Expected one {category}/{merchant}/{amount} row, found {len(matches)}")
    return matches[0] if matches else None


def delete_row(amount, category, merchant):
    found = amount_row(amount, category, merchant)
    if found is None:
        return False
    node = found[0]
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
    x = (left + right) // 2
    y = (top + bottom) // 2
    DEVICE.adb("shell", "input", "swipe", x, y, x + 5, y + 5, 900)
    DEVICE.tap("Delete")
    DEVICE.wait_text("Delete Record")
    DEVICE.tap("Delete")
    time.sleep(1)
    return True


try:
    DEVICE = Device(ARGS.device, OUTPUT)
    DEVICE.home()
    before = texts()
    if "16,000.00" not in before or "10000.00" not in before:
        raise AssertionError(f"Unexpected QA fixture baseline: {before}")
    DEVICE.tap("Records")
    DEVICE.wait_text("All Records")
    if "No records found" not in texts():
        raise AssertionError("Refusing to run mixed burst unless the Records screen is empty")
    DEVICE.capture("01_before")
    RESULT["steps"].append("verified pristine EGP 16,000.00 Home / EGP 10,000.00 MainBank fixture and empty Records")
    save()

    INJECTED = True
    DEVICE.adb("emu", "sms", "send", SENDER, INCOME_BODY)
    DEVICE.adb("emu", "sms", "send", SENDER, EXPENSE_BODY)
    RESULT["steps"].append("sent one unique EGP 0.15 salary credit and one unique EGP 0.10 Carrefour debit in one SMS burst")
    save()

    DEVICE.tap("Home")
    DEVICE.wait(lambda: "10000.05" in texts() and "16,000.05" in texts(), "income/expense balance effects")
    DEVICE.capture("02_home_after_burst")
    DEVICE.tap("Records")
    DEVICE.wait_text("All Records")
    deadline = time.monotonic() + 15
    income = expense = None
    while time.monotonic() < deadline:
        income = amount_row("+0.15 EGP", "Salary", "MainBank")
        expense = amount_row("-0.10 EGP", "Groceries", "MainBank")
        if income and expense:
            break
        time.sleep(0.5)
    if income is None or expense is None:
        raise AssertionError(f"Mixed burst rows missing: income={income}, expense={expense}; visible={texts()}")
    record_texts = texts()
    if record_texts.count("+0.15 EGP") != 1 or record_texts.count("-0.10 EGP") != 1:
        raise AssertionError(f"Expected exactly one record per SMS event, visible amounts={record_texts}")
    DEVICE.capture("03_records_after_burst")
    RESULT["steps"].append("verified one Salary/+0.15 and one Groceries/Carrefour/-0.10 row; Home and MainBank net +0.05")
    RESULT["observed"] = {"MainBank": "10000.05 EGP", "Home": "16000.05 EGP", "records": record_texts}
    save()

    if not delete_row("-0.10 EGP", "Groceries", "Carrefour"):
        raise AssertionError("Exact expense test row disappeared before cleanup")
    if not delete_row("+0.15 EGP", "Salary", "MainBank"):
        raise AssertionError("Exact income test row disappeared before cleanup")
    DEVICE.driver.terminate_app("com.example.wallettrackers")
    DEVICE.driver.activate_app("com.example.wallettrackers")
    DEVICE.wait_text("TOTAL BALANCE")
    after = texts()
    if "16,000.00" not in after or "10000.00" not in after:
        raise AssertionError(f"Cleanup did not restore fixture after restart: {after}")
    DEVICE.tap("Records")
    DEVICE.wait_text("All Records")
    DEVICE.wait_text("No records found")
    RESULT["steps"].append("deleted both exact event records; restart restored both fixture balances and empty Records")
    RESULT["cleanup"] = "both test records deleted; Home/MainBank baselines verified after restart"
    RESULT["status"] = "PASS"
except Exception as error:
    RESULT["error"] = f"{type(error).__name__}: {error}"
    RESULT["status"] = "FAIL" if INJECTED else "INCONCLUSIVE"
    if DEVICE is not None and INJECTED:
        try:
            DEVICE.home()
            DEVICE.tap("Records")
            DEVICE.wait_text("All Records")
            deleted = []
            for amount, category, merchant in (("-0.10 EGP", "Groceries", "Carrefour"), ("+0.15 EGP", "Salary", "MainBank")):
                deleted.append(delete_row(amount, category, merchant))
            RESULT["cleanup"] = f"failure cleanup deleted exact rows {deleted}; verify fixture before reuse"
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"REQUIRES MANUAL CLEANUP: {cleanup_error}"
finally:
    save()
    if DEVICE is not None:
        DEVICE.driver.quit()
    print(json.dumps(RESULT, indent=2, ensure_ascii=False))
    print(f"Evidence: {OUTPUT}")
if RESULT["status"] != "PASS":
    raise SystemExit(1)
