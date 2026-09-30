"""Exercise expired credit-payment pairing on the installed Wallet APK."""

import argparse
import json
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from decimal import Decimal
from pathlib import Path


parser = argparse.ArgumentParser(description="Exercise expired credit-payment pairing on a QA emulator")
parser.add_argument("--device", default="emulator-5556", help="ADB serial / Appium device ID")
args = parser.parse_args()

DEVICE = args.device
PACKAGE = "com.example.wallettrackers"
AMOUNT = Decimal("11.00") + Decimal(int(uuid.uuid4().hex[:4], 16) % 9000) / 100
SOURCE_BASELINE = Decimal("5000.00")
CARD_BASELINE = Decimal("3000.00")
HOME_BASELINE = Decimal("16000.00")
SOURCE_AFTER = SOURCE_BASELINE - AMOUNT
CARD_AFTER_FIRST = CARD_BASELINE + AMOUNT
CARD_AFTER_SECOND = CARD_AFTER_FIRST + AMOUNT
MARKER = "AUTOEXP" + uuid.uuid4().hex[:12].upper()
PREF_KEY = None
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / "live_sms_payment_expiry"
OUTPUT.mkdir(parents=True, exist_ok=False)
RESULT = {
    "case": "SMS-17_expired_credit_payment_pending_does_not_pair",
    "status": "IN_PROGRESS",
    "device": DEVICE,
    "amount": f"{AMOUNT:.2f} EGP",
    "marker": MARKER,
    "initial": {"SecondBank": f"{SOURCE_BASELINE:.2f}", "TestCard": f"{CARD_BASELINE:.2f}", "Home": f"{HOME_BASELINE:.2f}"},
    "steps": [],
    "cleanup": "not_started",
}


def adb(*command, timeout=15):
    completed = subprocess.run(
        ["adb", "-s", DEVICE, *map(str, command)],
        check=True,
        capture_output=True,
        text=True,
        timeout=timeout,
        encoding="utf-8",
        errors="replace",
    )
    return completed.stdout.strip()


def save():
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")


def dump_tree(name=None):
    remote = "/sdcard/wallet-expiry-case.xml"
    last_error = None
    for attempt in range(4):
        try:
            adb("shell", "uiautomator", "dump", "--compressed", remote, timeout=12)
            raw = adb("shell", "cat", remote, timeout=8)
            root = ET.fromstring(raw)
            break
        except (ET.ParseError, subprocess.SubprocessError) as error:
            last_error = error
            if attempt == 3:
                raise RuntimeError(f"UI hierarchy dump failed after four attempts: {last_error}") from error
            time.sleep(0.5 + attempt * 0.5)
    if name:
        (OUTPUT / f"{name}.xml").write_text(raw, encoding="utf-8")
        image_remote = f"/sdcard/{name}.png"
        adb("shell", "screencap", "-p", image_remote, timeout=10)
        adb("pull", image_remote, OUTPUT / f"{name}.png", timeout=10)
    return root


def visible_text(root):
    return [node.get("text", "").strip() for node in root.iter("node") if node.get("text", "").strip()]


def bounds(node):
    values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(values) != 4:
        raise AssertionError(f"Missing usable bounds: {node.attrib}")
    return (values[0] + values[2]) // 2, (values[1] + values[3]) // 2


def tap(x, y):
    adb("shell", "input", "tap", x, y)
    time.sleep(0.4)


def swipe(x1, y1, x2, y2, duration=400):
    adb("shell", "input", "swipe", x1, y1, x2, y2, duration)
    time.sleep(0.4)


def record_rows(root):
    parents = {child: parent for parent in root.iter() for child in parent}
    rows = []
    seen = set()
    for node in root.iter("node"):
        if not node.get("text", "").strip():
            continue
        current = node
        while current is not None:
            values = list(map(int, re.findall(r"\d+", current.get("bounds", ""))))
            if len(values) == 4 and 0 < values[3] - values[1] < 480:
                content = " ".join(child.get("text", "").strip() for child in current.iter("node") if child.get("text", "").strip())
                if "Credit Payment" in content and f"{AMOUNT:.2f}" in content and content not in seen:
                    seen.add(content)
                    amount_node = next(
                        (child for child in current.iter("node") if f"{AMOUNT:.2f}" in child.get("text", "")),
                        node,
                    )
                    rows.append({"content": content, "node": amount_node})
                    break
            current = parents.get(current)
    return rows


def get_pref_xml():
    command = ["adb", "-s", DEVICE, "shell", "run-as", PACKAGE, "cat",
               f"/data/user/0/{PACKAGE}/shared_prefs/pending_cc.xml"]
    completed = subprocess.run(command, capture_output=True, text=True, timeout=8,
                               encoding="utf-8", errors="replace")
    if completed.returncode and "No such file or directory" in completed.stderr:
        return "<map />"
    if completed.returncode:
        raise RuntimeError(f"Could not read pending credit-payment state: {completed.stderr.strip()}")
    return completed.stdout


def read_pref_value():
    global PREF_KEY
    raw = get_pref_xml()
    (OUTPUT / "pending_cc.xml").write_text(raw, encoding="utf-8")
    root = ET.fromstring(raw)
    matching = []
    for node in root.findall("string"):
        fields = (node.text or "").split("|")
        if len(fields) >= 3 and fields[0] == f"{AMOUNT:.2f}" and fields[1] == "3333":
            matching.append(node)
    if len(matching) > 1:
        raise AssertionError(f"Multiple pending payment entries match this case amount: {[node.get('name') for node in matching]}")
    entry = matching[0] if matching else None
    PREF_KEY = entry.get("name") if entry is not None else None
    return None if entry is None else entry.text


def age_pending_entry():
    raw = get_pref_xml()
    root = ET.fromstring(raw)
    entry = next((node for node in root.findall("string") if node.get("name") == PREF_KEY), None)
    if entry is None or not entry.text:
        raise AssertionError(f"Expected pending key {PREF_KEY!r} after credit-side SMS; found {raw}")
    fields = entry.text.split("|")
    if len(fields) != 3 or fields[1] != "3333":
        raise AssertionError(f"Unexpected pending payment data: {entry.text!r}")
    now_ms = int(time.time() * 1000)
    old_ms = now_ms - 49 * 60 * 60 * 1000
    fields[2] = str(old_ms)
    entry.text = "|".join(fields)
    aged_xml = ET.tostring(root, encoding="unicode", xml_declaration=True)
    aged_xml_bytes = aged_xml.encode("utf-8")
    written = subprocess.run(
        ["adb", "-s", DEVICE, "shell", "run-as", PACKAGE, "tee", "shared_prefs/pending_cc.xml"],
        input=aged_xml_bytes,
        capture_output=True,
        timeout=8,
        check=True,
    )
    if written.returncode != 0:
        raise AssertionError("Could not write the isolated expired-pending preference fixture")
    return {"credit_sms_id": fields[0], "card_suffix": fields[1], "age_hours": 49, "stored_epoch_ms": old_ms}


def wait_pending(expected_present, timeout=20):
    deadline = time.monotonic() + timeout
    last_value = None
    while time.monotonic() < deadline:
        try:
            last_value = read_pref_value()
            present = last_value is not None
            if present == expected_present:
                return last_value
        except Exception:
            pass
        time.sleep(0.5)
    raise AssertionError(f"Pending key {PREF_KEY!r} presence={expected_present} not reached; last value={last_value!r}")


def send_sms(body):
    adb("emu", "sms", "send", "5550001", body, timeout=12)
    time.sleep(5)


def home_page():
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", timeout=20)
    time.sleep(2)
    root = dump_tree()
    if "TOTAL BALANCE" not in visible_text(root):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.5)
        root = dump_tree()
    if "TOTAL BALANCE" not in visible_text(root):
        home = next((node for node in root.iter("node")
                     if node.get("content-desc") == "Home" or node.get("text", "").strip() == "Home"), None)
        if home is not None:
            tap(*bounds(home))
            time.sleep(0.5)
            root = dump_tree()
    if "TOTAL BALANCE" not in visible_text(root):
        raise AssertionError("Home dashboard did not load")
    return root


def scan_account(name, expected):
    expected = Decimal(str(expected))
    root = home_page()
    width, height = 1440, 3120
    size_match = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    if size_match:
        width, height = map(int, size_match.groups())
    directions = ((0.15, 0.86), (0.86, 0.15)) if name == "SecondBank" else ((0.86, 0.15), (0.15, 0.86))
    for start_fraction, end_fraction in directions:
        for _ in range(6):
            texts = visible_text(root)
            for index, value in enumerate(texts):
                if value == name and index + 1 < len(texts):
                    cleaned = texts[index + 1].replace(",", "").strip()
                    try:
                        if Decimal(cleaned) == expected:
                            return True
                    except Exception:
                        pass
            swipe(int(width * start_fraction), int(height * 0.70),
                  int(width * end_fraction), int(height * 0.70), 450)
            root = dump_tree()
    return False


def assert_home_total(expected):
    texts = visible_text(home_page())
    if "TOTAL BALANCE" not in texts:
        raise AssertionError("Home total label missing")
    index = texts.index("TOTAL BALANCE")
    if Decimal(texts[index + 1].replace(",", "")) != Decimal(str(expected)):
        raise AssertionError(f"Home total mismatch; expected={expected}; visible={texts[:12]}")


def open_records():
    home_page()
    tap(465, 2990)
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        root = dump_tree()
        if "All Records" in visible_text(root):
            return root
        time.sleep(0.3)
    raise AssertionError("Records screen did not load")


def target_row(root, kind):
    rows = record_rows(root)
    for row in rows:
        content = row["content"]
        if kind == "partial" and "SecondBank -> TestCard" not in content and "TestCard" in content:
            return row
        if kind == "debit" and "SecondBank -> TestCard" in content:
            return row
    return None


def delete_row(kind):
    root = open_records()
    row = target_row(root, kind)
    if row is None:
        raise AssertionError(f"Exact {kind} {AMOUNT:.2f} Credit Payment record was not visible")
    x, y = bounds(row["node"])
    swipe(x, y, x, y, 1000)
    root = dump_tree()
    delete_option = next((node for node in root.iter("node") if node.get("text", "").strip() == "Delete"), None)
    if delete_option is None:
        raise AssertionError("Record context menu did not expose Delete")
    x, y = bounds(delete_option)
    tap(x, y)
    root = dump_tree()
    confirmation = next((node for node in root.iter("node") if node.get("text", "").strip() == "Delete"), None)
    if "Delete Record" not in visible_text(root) or confirmation is None:
        raise AssertionError("Delete confirmation did not identify a record deletion")
    x, y = bounds(confirmation)
    tap(x, y)
    time.sleep(2)


def cleanup_exact_rows():
    errors = []
    for kind in ("debit", "partial"):
        try:
            root = open_records()
            if target_row(root, kind) is not None:
                delete_row(kind)
        except Exception as error:
            errors.append(f"{kind}: {type(error).__name__}: {error}")
    if errors:
        raise AssertionError("; ".join(errors))


def main():
    pending_before = read_pref_value()
    if pending_before is not None:
        raise AssertionError(f"Refusing to overwrite existing pending payment {PREF_KEY}: {pending_before}")
    assert_home_total(HOME_BASELINE)
    if not scan_account("SecondBank", SOURCE_BASELINE) or not scan_account("TestCard", CARD_BASELINE):
        raise AssertionError("Initial account balances differ from the QA fixture")
    records = open_records()
    if "No records found" not in visible_text(records):
        raise AssertionError("Expected an empty Records view before injecting the unique SMS case")
    save()

    credit_body = f"EGP {AMOUNT:.2f} payment received for credit card ****3333 has been credited. Available credit EGP {CARD_AFTER_FIRST:.2f}. {MARKER}"
    send_sms(credit_body)
    pending = wait_pending(True)
    RESULT["steps"].append("credit-side SMS created one partial card payment and a pending match")
    RESULT["pending_before_expiry"] = pending
    RESULT["credit_only_balance"] = {"TestCard": f"{CARD_AFTER_FIRST:.2f}", "SecondBank": f"{SOURCE_BASELINE:.2f}", "Home": f"{HOME_BASELINE:.2f}"}
    save()

    adb("shell", "am", "force-stop", PACKAGE)
    aged = age_pending_entry()
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", timeout=20)
    time.sleep(2)
    RESULT["controlled_fixture"] = aged
    RESULT["steps"].append("aged only the test-owned pending entry to 49 hours in the debug-app preference fixture and restarted the process")
    save()

    debit_body = f"Your bank account ****2222 was debited EGP {AMOUNT:.2f} for credit card payment to card ****3333. Available balance EGP {SOURCE_AFTER:.2f}. {MARKER}"
    send_sms(debit_body)
    wait_pending(False)
    RESULT["steps"].append("matching debit-side SMS removed the expired key instead of consuming it")
    if not scan_account("SecondBank", SOURCE_AFTER):
        raise AssertionError(f"SecondBank did not reach expected {SOURCE_AFTER:.2f} after the new debit")
    if not scan_account("TestCard", CARD_AFTER_SECOND):
        raise AssertionError(f"TestCard did not reach expected {CARD_AFTER_SECOND:.2f}; expired pair must not suppress the new credit")
    assert_home_total(HOME_BASELINE - AMOUNT)

    records = open_records()
    (OUTPUT / "records_after_expiry.xml").write_text(ET.tostring(records, encoding="unicode"), encoding="utf-8")
    rows = record_rows(records)
    partial = target_row(records, "partial")
    debit = target_row(records, "debit")
    if partial is None or debit is None or len(rows) < 2:
        raise AssertionError(f"Expected separate old partial and new debit-linked records, got {[row['content'] for row in rows]}")
    RESULT["rows_after_expiry"] = [row["content"] for row in rows]
    RESULT["balances_after_expiry"] = {"SecondBank": f"{SOURCE_AFTER:.2f}", "TestCard": f"{CARD_AFTER_SECOND:.2f}", "Home": f"{HOME_BASELINE - AMOUNT:.2f}"}
    RESULT["steps"].append("verified old partial record remained separate while new payment had its own SecondBank-to-TestCard record and exact balances")
    save()

    cleanup_exact_rows()
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", timeout=20)
    time.sleep(3)
    assert_home_total(HOME_BASELINE)
    if not scan_account("SecondBank", SOURCE_BASELINE) or not scan_account("TestCard", CARD_BASELINE):
        raise AssertionError("Exact-record deletion did not restore the source and card balances")
    records = open_records()
    if "No records found" not in visible_text(records):
        raise AssertionError("Test records remained after cleanup/restart")
    if read_pref_value() is not None:
        raise AssertionError("Expired pending payment key remained after cleanup")
    RESULT["status"] = "PASS"
    RESULT["cleanup"] = "two exact test-owned records deleted; app restarted; balances, empty ledger, and expired pending key verified"
    RESULT["steps"].append("deleted both exact test-owned records and verified baseline/no-record state after process restart")


try:
    main()
except Exception as error:
    RESULT["status"] = "FAIL"
    RESULT["error"] = f"{type(error).__name__}: {error}"
    try:
        cleanup_exact_rows()
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity", timeout=20)
        time.sleep(3)
        RESULT["cleanup"] = f"exact visible {AMOUNT:.2f} Credit Payment rows removed; inspect balances and evidence"
        RESULT["post_cleanup_home"] = visible_text(home_page())[:20]
    except Exception as cleanup_error:
        RESULT["cleanup"] = f"MANUAL REVIEW REQUIRED: {type(cleanup_error).__name__}: {cleanup_error}"
    raise
finally:
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")
    try:
        final_tree = dump_tree("final_state")
        RESULT["final_visible_text"] = visible_text(final_tree)
    except Exception as capture_error:
        RESULT["final_capture_error"] = str(capture_error)
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")

print(json.dumps(RESULT, indent=2))
print(f"Evidence: {OUTPUT.resolve()}")
