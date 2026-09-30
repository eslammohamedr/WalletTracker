import argparse
import json
import re
import subprocess
import time
import uuid
from decimal import Decimal
from pathlib import Path
from xml.etree import ElementTree

parser = argparse.ArgumentParser(description="Run the live credit-payment emulator scenario.")
parser.add_argument("--execute", action="store_true", help="Allow SMS injection and app UI interaction.")
parser.add_argument("--device", default="emulator-5554", help="ADB serial / Appium device ID")
parser.add_argument("--order", choices=("credit-first", "debit-first"), default="credit-first")
parser.add_argument("--verify-statistics", action="store_true", help="Assert the paired payment is excluded from ordinary spending statistics.")
parser.add_argument("--verify-notification-navigation", action="store_true", help="Tap the observed payment notification and assert that it opens Records.")
parser.add_argument("--replay-identical-sms", action="store_true", help="Replay the exact same payment-side SMS body/sender and assert no duplicate record or balance effect.")
parser.add_argument("--source-sender", default="5550001", help="Emulated SMS sender for the debit-account bank")
parser.add_argument("--card-sender", default="5550001", help="Emulated SMS sender for the credit-card bank")
parser.add_argument("--source-account", default="SecondBank", help="Linked debit account used for payment")
parser.add_argument("--source-suffix", default="2222", help="Last digits printed by the source-account SMS")
parser.add_argument("--source-baseline", default="5000.00")
parser.add_argument("--card-baseline", default="3000.00")
parser.add_argument("--home-baseline", default="16000.00")
parser.add_argument("--expected-spent-today", default="0.00",
                    help="Expected ordinary expenses during the case; credit payment must not add to this value.")
parser.add_argument("--expected-ordinary-expense", default="0.00",
                    help="Existing Spending Statistics expense total that payment must not change.")
parser.add_argument("--amount", default="613.37")
parser.add_argument("--credit-amount", help="Credit-side amount; defaults to --amount")
parser.add_argument("--debit-amount", help="Debit-side amount; defaults to --amount")
args = parser.parse_args()
if not args.execute:
    parser.error("device interaction is disabled by default; pass --execute to run the live scenario")

DEVICE = args.device
PACKAGE = "com.example.wallettrackers"
SOURCE_NAME = args.source_account
SOURCE_SUFFIX = args.source_suffix
CARD_NAME = "TestCard"
CARD_SUFFIX = "3333"
SOURCE_BASELINE = Decimal(args.source_baseline)
CARD_BASELINE = Decimal(args.card_baseline)
HOME_BASELINE = Decimal(args.home_baseline)
EXPECTED_SPENT_TODAY = Decimal(args.expected_spent_today)
EXPECTED_ORDINARY_EXPENSE = Decimal(args.expected_ordinary_expense)
AMOUNT = Decimal(args.debit_amount or args.amount)
CREDIT_AMOUNT = Decimal(args.credit_amount or args.amount)
RECORD_AMOUNT = CREDIT_AMOUNT if args.order == "credit-first" else AMOUNT
SOURCE_AFTER = SOURCE_BASELINE - AMOUNT
CARD_AFTER = CARD_BASELINE + CREDIT_AMOUNT
HOME_AFTER = HOME_BASELINE - AMOUNT
MARKER = "AUTOPAY" + time.strftime("%H%M%S") + uuid.uuid4().hex[:10].upper()
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / "credit_payment"
OUTPUT.mkdir(parents=True, exist_ok=True)
RESULT = {
    "case": f"credit_card_payment_{args.order.replace('-', '_')}_and_rollback",
    "order": args.order,
    "marker": MARKER,
    "amount": f"{AMOUNT:.2f} EGP",
    "credit_amount": f"{CREDIT_AMOUNT:.2f} EGP",
    "source_sender": args.source_sender,
    "card_sender": args.card_sender,
    "status": "IN_PROGRESS",
    "steps": [],
    "cleanup": "not_needed",
}
RECORD_VISIBLE = False
DELETE_ATTEMPTED = False


def adb(*command, timeout=10):
    return subprocess.check_output(["adb", "-s", DEVICE, *command], timeout=timeout).decode("utf-8", errors="replace")


def save_result():
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")


def tree():
    remote_path = "/sdcard/wallet-credit-payment.xml"
    deadline = time.monotonic() + 10
    last_error = None
    while time.monotonic() < deadline:
        try:
            remaining = deadline - time.monotonic()
            adb("shell", "rm", "-f", remote_path, timeout=min(2, remaining))
            response = adb("shell", "uiautomator", "dump", "--compressed", remote_path,
                           timeout=min(5, max(1, deadline - time.monotonic())))
            if "dumped to:" not in response:
                raise RuntimeError(response.strip() or "Android returned no hierarchy path")
            xml_text = adb("shell", "cat", remote_path,
                           timeout=min(2, max(1, deadline - time.monotonic())))
            return ElementTree.fromstring(xml_text)
        except (ElementTree.ParseError, subprocess.SubprocessError, RuntimeError) as error:
            last_error = str(error)
            time.sleep(min(0.3, max(0, deadline - time.monotonic())))
    raise RuntimeError(f"Could not capture fresh UI hierarchy within 10 seconds: {last_error}")


def nodes():
    return list(tree().iter("node"))


def find_node(text, attribute="text"):
    return next((node for node in nodes() if node.get("package") == PACKAGE and text in node.get(attribute, "")), None)


def wait_node(text, attribute="text", timeout=10, present=True):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            node = find_node(text, attribute)
            if (node is not None) == present:
                return node
        except (ElementTree.ParseError, subprocess.SubprocessError, RuntimeError) as error:
            last_error = str(error)
        time.sleep(0.5)
    raise AssertionError(f"Timed out waiting for {attribute} {text!r}; {last_error or ''}")


def bounds(node):
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return (left + right) // 2, (top + bottom) // 2


def tap(text, attribute="content-desc", occurrence=-1):
    matches = [node for node in nodes() if text in node.get(attribute, "")]
    if not matches:
        raise AssertionError(f"Could not find {attribute} {text!r}")
    x, y = bounds(matches[occurrence])
    adb("shell", "input", "tap", str(x), str(y))


def capture(name, hierarchy=None):
    hierarchy = hierarchy if hierarchy is not None else tree()
    (OUTPUT / (name + ".xml")).write_bytes(ElementTree.tostring(hierarchy, encoding="utf-8"))
    image = subprocess.check_output(["adb", "-s", DEVICE, "exec-out", "screencap", "-p"], timeout=10)
    (OUTPUT / (name + ".png")).write_bytes(image)


def open_home():
    cancel = find_node("Cancel")
    if cancel is not None:
        x, y = bounds(cancel)
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(0.5)
    if find_node("TOTAL BALANCE") is None:
        home = find_node("Home", "content-desc")
        if home is not None:
            x, y = bounds(home)
            adb("shell", "input", "tap", str(x), str(y))
        else:
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
    wait_node("TOTAL BALANCE")


def verify_payment_statistics():
    open_home()
    tap("Stats")
    wait_node("Statistics", "text")
    tap("Spending", "text")
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        root = tree()
        parents = {child: parent for parent in root.iter() for child in parent}
        spending_tab = next((node for node in root.iter("node") if node.get("text", "").strip() == "Spending"), None)
        selected = False
        current = spending_tab
        while current is not None:
            if current.get("selected") == "true":
                selected = True
                break
            current = parents.get(current)
        visible_text = [node.get("text", "").strip() for node in root.iter("node") if node.get("text", "").strip()]
        expected_expense_text = f"{EXPECTED_ORDINARY_EXPENSE:,.2f} EGP"
        expected_expense_value = f"{EXPECTED_ORDINARY_EXPENSE:.2f}"
        visible_expense_values = {
            text.replace(",", "").replace(" EGP", "").strip()
            for text in visible_text
        }
        if selected and "Expense" in visible_text and expected_expense_value in visible_expense_values:
            capture("04_payment_statistics_exclusion", root)
            RESULT["statistics"] = {
                "ordinary_expense": expected_expense_text,
                "spending_tab_selected": True,
            }
            RESULT["steps"].append(
                f"verified paired credit payment leaves ordinary Spending Statistics Expense at {expected_expense_text}"
            )
            save_result()
            open_home()
            return
        time.sleep(0.4)
    raise AssertionError(f"Credit payment was counted as spending or Spending Statistics did not load: {visible_text}")


def verify_payment_notification_navigation():
    adb("shell", "cmd", "statusbar", "expand-notifications")
    deadline = time.monotonic() + 10
    notification_node = None
    while time.monotonic() < deadline:
        root = tree()
        notification_node = next((
            node for node in root.iter("node")
            if "Credit Card Payment Complete" in node.get("text", "")
        ), None)
        if notification_node is not None:
            break
        time.sleep(0.3)
    if notification_node is None:
        raise AssertionError("Payment completion notification was not visible in the notification shade")
    capture("05_notification_shade", root)
    parent_map = {child: parent for parent in root.iter("node") for child in parent}
    group_node = notification_node
    while group_node in parent_map and group_node.get("resource-id") != "com.android.systemui:id/expandableNotificationRow":
        group_node = parent_map[group_node]
    group_parent = parent_map.get(group_node)
    expand_button = next((
        node for node in group_parent.iter("node")
        if node.get("resource-id") == "android:id/expand_button"
    ), None) if group_parent is not None else None
    if expand_button is not None:
        x, y = bounds(expand_button)
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(0.5)
        root = tree()
        notification_node = next((
            node for node in root.iter("node")
            if "Credit Card Payment Complete" in node.get("text", "")
        ), None)
        if notification_node is None:
            raise AssertionError("Payment completion alert disappeared when its notification group expanded")
        capture("05_notification_group_expanded", root)
    parent_map = {child: parent for parent in root.iter("node") for child in parent}
    click_node = notification_node
    while click_node in parent_map and click_node.get("clickable") != "true":
        click_node = parent_map[click_node]
    RESULT["notification_navigation"] = {
        "title": notification_node.get("text", ""),
        "tap_target_clickable": click_node.get("clickable"),
        "tap_target_bounds": click_node.get("bounds"),
    }
    save_result()
    x, y = bounds(click_node)
    adb("shell", "input", "tap", str(x), str(y))
    try:
        wait_node("All Records", timeout=10)
    except AssertionError:
        capture("05_notification_tap_timeout")
        raise
    capture("05_payment_notification_navigation")
    RESULT["steps"].append("tapped Credit Card Payment Complete notification and verified it opened All Records")
    save_result()


def account_balance_visible(root, account_name, expected):
    expected = Decimal(str(expected))
    account_nodes = [node for node in root.iter("node") if node.get("text", "").strip() == account_name]
    for account_node in account_nodes:
        account_bounds = list(map(int, re.findall(r"\d+", account_node.get("bounds", ""))))
        if len(account_bounds) != 4:
            continue
        _, _, account_right, account_bottom = account_bounds
        for value_node in root.iter("node"):
            value_bounds = list(map(int, re.findall(r"\d+", value_node.get("bounds", ""))))
            if len(value_bounds) != 4:
                continue
            left, top, right, _ = value_bounds
            if not account_bottom - 8 <= top <= account_bottom + 140 or right < account_bounds[0] or left > account_right:
                continue
            value = value_node.get("text", "").replace(",", "").replace("EGP", "").strip()
            try:
                if Decimal(value) == expected:
                    return True
            except Exception:
                continue
    return False


def scan_balance(expected, account_name):
    width, height = 1440, 3120
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    if size:
        width, height = map(int, size.groups())
    swipe_y = int(height * 0.70)
    trace_pages = []

    def finish(found):
        RESULT.setdefault("account_balance_scans", []).append({
            "account": account_name,
            "expected": str(expected),
            "carousel_y": swipe_y,
            "pages": trace_pages,
            "matched": found,
        })
        save_result()
        return found

    root = tree()
    if account_balance_visible(root, account_name, expected):
        trace_pages.append([account_name])
        return finish(True)

    account_count = None
    for node in root.iter("node"):
        match = re.search(r"(\d+) active", node.get("text", ""), re.IGNORECASE)
        if match:
            account_count = int(match.group(1))
            break
    max_swipes = min(8, max(5, account_count or 8))
    account_order = ["CashWallet", "MainBank", "USDBank", "SecondBank", "EURBank", "SecondCard", "TestCard", "GoldWallet"]
    target_index = account_order.index(account_name) if account_name in account_order else len(account_order)
    visible_indices = [account_order.index(node.get("text", "").strip())
                       for node in root.iter("node") if node.get("text", "").strip() in account_order]
    swipe_directions = ((0.15, 0.9), (0.9, 0.15)) if visible_indices and target_index < min(visible_indices) else ((0.9, 0.15), (0.15, 0.9))
    for start_fraction, end_fraction in swipe_directions:
        previous_page = None
        unchanged_pages = 0
        for _ in range(max_swipes):
            root = tree()
            if account_balance_visible(root, account_name, expected):
                trace_pages.append([account_name])
                return finish(True)
            page_items = []
            for node in root.iter("node"):
                bounds_value = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
                if (len(bounds_value) == 4 and swipe_y - 250 <= bounds_value[1] <= swipe_y + 400
                        and node.get("text", "").strip()):
                    page_items.append(node.get("text", "").strip())
            page = tuple(page_items)
            trace_pages.append(list(page))
            unchanged_pages = unchanged_pages + 1 if page == previous_page else 0
            if unchanged_pages >= 2:
                break
            previous_page = page
            adb("shell", "input", "swipe", str(int(width * start_fraction)), str(swipe_y),
                str(int(width * end_fraction)), str(swipe_y), "600")
            time.sleep(0.5)
    return finish(False)


def assert_home_total(expected):
    open_home()
    width, height = 1440, 3120
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    if size:
        width, height = map(int, size.groups())
    for _ in range(5):
        if find_node(f"{expected:,.2f}") is not None or find_node(f"{expected:.2f}") is not None:
            return
        adb("shell", "input", "swipe", str(int(width * 0.5)), str(int(height * 0.28)),
            str(int(width * 0.5)), str(int(height * 0.82)), "250")
        time.sleep(0.2)
    raise AssertionError(f"Home total did not show EGP {expected}")


def assert_home_amounts(source, card, total):
    open_home()
    if not scan_balance(source, SOURCE_NAME):
        raise AssertionError(f"Expected {SOURCE_NAME} to show EGP {source}")
    if not scan_balance(card, CARD_NAME):
        raise AssertionError(f"Expected {CARD_NAME} to show EGP {card}")
    assert_home_total(total)


def assert_spent_today(expected):
    open_home()
    root = tree()
    labels = [node for node in root.iter("node") if node.get("text", "").strip().casefold() == "spent today"]
    if len(labels) != 1:
        raise AssertionError(f"Expected one visible Spent Today label; found {len(labels)}")

    label = labels[0]
    label_bounds = list(map(int, re.findall(r"\d+", label.get("bounds", ""))))
    if len(label_bounds) != 4:
        raise AssertionError("Spent Today label did not expose readable screen bounds")
    label_left, label_top, label_right, label_bottom = label_bounds
    candidates = []
    for node in root.iter("node"):
        text = node.get("text", "").strip()
        if not text or node is label:
            continue
        node_bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
        if len(node_bounds) != 4:
            continue
        left, top, right, bottom = node_bounds
        if top < label_bottom - 8 or top > label_bottom + 160:
            continue
        if right < label_left or left > label_right:
            continue
        numeric_text = text.replace(",", "").replace("EGP", "").strip()
        try:
            numeric_value = Decimal(numeric_text)
        except Exception:
            continue
        candidates.append((top - label_bottom, abs(left - label_left), text, numeric_value))
    if not candidates:
        raise AssertionError("Could not locate the numeric value below Spent Today")

    _, _, observed_text, observed_value = min(candidates, key=lambda item: (item[0], item[1]))
    RESULT["spent_today_after_payment"] = {
        "observed": observed_text,
        "expected": str(expected),
        "evidence_xml": "03_home_after_payment.xml",
        "evidence_screenshot": "03_home_after_payment.png",
    }
    save_result()
    try:
        capture("03_home_after_payment", root)
    except Exception as error:
        RESULT["spent_today_after_payment"]["evidence_capture_error"] = str(error)
        save_result()
    if observed_value != expected:
        raise AssertionError(f"Spent Today was {observed_text!r}; expected {expected}")


def send_sms(body, sender, settle_seconds=3):
    adb("emu", "sms", "send", sender, body, timeout=10)
    if settle_seconds:
        time.sleep(settle_seconds)


def search_amount():
    open_unfiltered_records()
    match = scan_payment_rows()
    if match is None:
        raise AssertionError(
            f"Could not find a single row matching Credit Payment, {SOURCE_NAME}, {CARD_NAME}, and -{RECORD_AMOUNT:.2f}"
        )
    save_payment_record_evidence()
    return match


def save_payment_record_evidence():
    RESULT["payment_record_evidence"] = {
        "signature": {
            "category": "Credit Payment",
            "source": SOURCE_NAME,
            "destination": CARD_NAME,
            "amount": f"-{RECORD_AMOUNT:.2f}",
        },
        "xml": "02_payment_record.xml",
        "screenshot": "02_payment_record.png",
    }
    save_result()
    try:
        capture("02_payment_record")
    except Exception as error:
        RESULT["payment_record_evidence"]["capture_error"] = str(error)
        save_result()


def find_payment_row(root=None):
    if root is None:
        root = tree()
    all_nodes = list(root.iter("node"))
    parents = {child: parent for parent in root.iter() for child in parent}
    matching_amount_nodes = []
    for amount_node in all_nodes:
        text = amount_node.get("text", "").replace(",", "").replace("EGP", "").strip()
        try:
            if Decimal(text) != -RECORD_AMOUNT:
                continue
        except Exception:
            continue
        current = amount_node
        while current is not None:
            row_bounds = list(map(int, re.findall(r"\d+", current.get("bounds", ""))))
            if len(row_bounds) != 4 or row_bounds[3] - row_bounds[1] >= 500:
                current = parents.get(current)
                continue
            row_text = " ".join(node.get("text", "") for node in current.iter("node"))
            if ("Credit Payment" in row_text and SOURCE_NAME in row_text
                    and CARD_NAME in row_text):
                matching_amount_nodes.append(amount_node)
                break
            current = parents.get(current)
    if len(matching_amount_nodes) > 1:
        raise AssertionError(f"Found {len(matching_amount_nodes)} rows matching the exact payment signature")
    return matching_amount_nodes[0] if matching_amount_nodes else None


def open_unfiltered_records():
    open_home()
    tap("Records")
    wait_node("All Records")


def scan_payment_rows():
    deadline = time.monotonic() + 10
    width, height = 1440, 3120
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    if size:
        width, height = map(int, size.groups())
    previous_page = None
    while time.monotonic() < deadline:
        root = tree()
        match = find_payment_row(root)
        if match is not None:
            return match
        page = tuple(node.get("text", "") for node in root.iter("node"))
        if page == previous_page:
            break
        previous_page = page
        adb("shell", "input", "swipe", str(int(width * 0.5)), str(int(height * 0.78)),
            str(int(width * 0.5)), str(int(height * 0.30)), "400")
        time.sleep(0.25)
    return None


def assert_payment_row_absent_before_injection():
    open_unfiltered_records()
    if scan_payment_rows() is not None:
        raise AssertionError(
            "An existing Credit Payment row matches the source, card name, and exact amount; "
            "refusing to inject an ambiguous duplicate."
        )
    open_home()


def delete_payment_record():
    global DELETE_ATTEMPTED
    open_unfiltered_records()
    amount_node = scan_payment_rows()
    if amount_node is None:
        raise AssertionError("Refusing to delete: exact payment row signature was not found")
    if "payment_record_evidence" not in RESULT:
        save_payment_record_evidence()
    x, y = bounds(amount_node)
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "1000")
    tap("Delete", "text")
    wait_node("Delete Record")
    buttons = [node for node in nodes() if node.get("text") == "Delete"]
    if not buttons:
        raise AssertionError("Delete confirmation was missing")
    x, y = bounds(buttons[-1])
    DELETE_ATTEMPTED = True
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(4)
    RESULT["cleanup"] = "payment delete confirmed; checking both account reversals after restart"
    capture("03_payment_deleted")
    save_result()


save_result()
try:
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    open_home()
    if not scan_balance(SOURCE_BASELINE, SOURCE_NAME) or not scan_balance(CARD_BASELINE, CARD_NAME):
        raise AssertionError("The payment case did not start at the expected source/card fixture balances")
    assert_home_total(HOME_BASELINE)
    assert_payment_row_absent_before_injection()
    capture("01_before")

    credit_sms = f"EGP {CREDIT_AMOUNT:.2f} payment received for credit card ****{CARD_SUFFIX} has been credited. Available credit EGP {CARD_AFTER:.2f}. {MARKER}"
    debit_sms = f"Your bank account ****{SOURCE_SUFFIX} was debited EGP {AMOUNT:.2f} for credit card payment to card ****{CARD_SUFFIX}. Available balance EGP {SOURCE_AFTER:.2f}. {MARKER}"
    first_kind, first_sms = (("credit-side", credit_sms) if args.order == "credit-first"
                             else ("debit-side", debit_sms))
    second_kind, second_sms = (("debit-side", debit_sms) if args.order == "credit-first"
                               else ("credit-side", credit_sms))
    RECORD_VISIBLE = True
    RESULT["injection_state"] = f"{first_kind.replace('-', '_')}_sms_send_attempted"
    RESULT["steps"].append(f"attempting {first_kind} SMS injection first")
    save_result()
    first_sender = args.card_sender if first_kind == "credit-side" else args.source_sender
    send_sms(first_sms, first_sender)
    RESULT["injection_state"] = f"{first_kind.replace('-', '_')}_sms_sent"
    save_result()
    first_card_after = CARD_BASELINE + (CREDIT_AMOUNT if args.order == "credit-first" else AMOUNT)
    if args.order == "credit-first":
        assert_home_amounts(SOURCE_BASELINE, first_card_after, HOME_BASELINE)
        RESULT["steps"].append("credit-side SMS arrived first; card availability rose once and source/debit/Home stayed unchanged")
    else:
        assert_home_amounts(SOURCE_AFTER, first_card_after, HOME_AFTER)
        RESULT["steps"].append("debit-side SMS arrived first; source debit and card credit were applied exactly once")
    save_result()

    RESULT["injection_state"] = f"{second_kind.replace('-', '_')}_sms_send_attempted"
    RESULT["steps"].append(f"attempting matching {second_kind} SMS injection")
    save_result()
    second_sender = args.card_sender if second_kind == "credit-side" else args.source_sender
    send_sms(second_sms, second_sender)
    RESULT["injection_state"] = "both_payment_sms_sent"
    save_result()
    assert_home_amounts(SOURCE_AFTER, CARD_AFTER, HOME_AFTER)
    RESULT["source_balance_after_payment"] = f"{SOURCE_AFTER:.2f} EGP"
    RESULT["card_available_after_payment"] = f"{CARD_AFTER:.2f} EGP"
    RESULT["home_total_after_payment"] = f"{HOME_AFTER:.2f} EGP"
    RESULT["steps"].append(f"matching {second_kind} SMS caused no duplicate effect; source, card, and Home totals reconciled")
    save_result()
    if args.replay_identical_sms:
        RESULT["steps"].append(f"replaying the identical {second_kind} sender/body to test duplicate-delivery handling")
        save_result()
        send_sms(second_sms, second_sender, settle_seconds=0)
        assert_home_amounts(SOURCE_AFTER, CARD_AFTER, HOME_AFTER)
        search_amount()
        RESULT["identical_sms_replay"] = {
            "sender": second_sender,
            "body": second_sms,
            "record_count": 1,
            "source_balance": f"{SOURCE_AFTER:.2f} EGP",
            "card_available": f"{CARD_AFTER:.2f} EGP",
            "home_total": f"{HOME_AFTER:.2f} EGP",
        }
        RESULT["steps"].append("identical SMS replay left one payment row and all balances unchanged")
        save_result()
    assert_spent_today(EXPECTED_SPENT_TODAY)
    if args.verify_statistics:
        verify_payment_statistics()
    notification_dump = subprocess.check_output(
        ["adb", "-s", DEVICE, "shell", "dumpsys", "notification", "--noredact"], timeout=10
    ).decode("utf-8", errors="replace")
    (OUTPUT / "notifications.txt").write_text(notification_dump, encoding="utf-8")
    completion_notification = (
        "Credit Card Payment Complete" in notification_dump
        or (
            "Credit Card Payment Confirmed" in notification_dump
            and f"Payment of {CREDIT_AMOUNT:.2f} confirmed for card ****{CARD_SUFFIX}" in notification_dump
        )
    )
    if not completion_notification:
        raise AssertionError("Credit-payment completion notification was not observed")
    RESULT["steps"].append("observed Credit Card Payment Complete notification in Android notification service")
    save_result()
    if args.verify_notification_navigation:
        verify_payment_notification_navigation()

    search_amount()
    RESULT["steps"].append("one Credit Payment record linked the source bank and destination card")
    delete_payment_record()
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(4)
    assert_home_amounts(SOURCE_BASELINE, CARD_BASELINE, HOME_BASELINE)
    RESULT["status"] = "PASS"
    RESULT["cleanup"] = "payment record deleted; source/card/Home baselines restored after process restart"
except Exception as error:
    RESULT["status"] = "FAIL"
    RESULT["error"] = str(error)
    if RECORD_VISIBLE and not DELETE_ATTEMPTED:
        try:
            delete_payment_record()
            RESULT["cleanup"] = "exact payment row deleted after case failure; verifying rollback after restart"
            save_result()
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
            time.sleep(4)
            assert_home_amounts(SOURCE_BASELINE, CARD_BASELINE, HOME_BASELINE)
            RESULT["cleanup"] = "exact payment row deleted after case failure; source/card/Home baselines restored after restart"
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"REQUIRES MANUAL CLEANUP: {cleanup_error}"
    save_result()
    raise

save_result()
print(json.dumps(RESULT, indent=2))
print(f"Evidence: {OUTPUT.resolve()}")
