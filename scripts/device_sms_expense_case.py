import json
import argparse
import re
import subprocess
import time
import uuid
from pathlib import Path
from decimal import Decimal
from xml.etree import ElementTree

parser = argparse.ArgumentParser(
    epilog=(
        "Example: python scripts/device_sms_expense_case.py --account-name USDBank "
        "--account-suffix 4444 --currency USD --baseline-balance 1000.00 "
        "--baseline-dashboard-total 16000.00 --dashboard-delta 0 --amount 0.25"
    )
)
parser.add_argument("--cleanup-marker", help="Delete only this tagged test record without injecting SMS")
parser.add_argument("--cleanup-category-rule", action="store_true", help="Delete only the category rule named by --merchant")
parser.add_argument("--device", default="emulator-5554", help="ADB serial / Appium device ID")
parser.add_argument("--ui-backend", choices=("adb", "appium"), default="appium")
parser.add_argument("--account-name", default="MainBank")
parser.add_argument("--account-suffix", default="1111")
parser.add_argument("--baseline-balance", default="10000.00")
parser.add_argument("--baseline-dashboard-total", default="16000.00", help="Expected Home EGP total when it differs from this account balance")
parser.add_argument("--amount", default="0.01")
parser.add_argument("--expected-record-amount", help="Override the displayed signed amount/currency assertion, e.g. '-50.00 EGP' for converted charges")
parser.add_argument("--printed-balance", help="Expected post-transaction balance printed in the SMS")
parser.add_argument("--expected-account-balance-after", help="Override the expected account balance after the SMS")
parser.add_argument("--account-balance-currency", help="Currency used by the account balance display")
parser.add_argument("--expect-empty-balance-after", action="store_true", help="Assert the record does not display a post-transaction balance")
parser.add_argument("--currency", choices=("EGP", "USD", "EUR"), default="EGP")
parser.add_argument("--category", default="Groceries")
parser.add_argument("--rule-category", help="Save a merchant rule, replay, remove the rule, and verify fallback")
parser.add_argument("--rule-parent", help="Parent category for --rule-category")
parser.add_argument("--merchant")
parser.add_argument("--sms-sender", default="5550001", help="SMS sender address used for the synthetic event")
parser.add_argument(
    "--sms-body-template",
    help="Optional bank-shaped body template with {suffix}, {amount}, {currency}, {balance}, {merchant}, and {marker}",
)
parser.add_argument("--transaction-type", choices=("expense", "income"), default="expense")
parser.add_argument("--account-type", choices=("debit", "credit"), default="debit")
parser.add_argument("--dashboard-delta", help="Override dashboard delta, e.g. 0 for a credit-card purchase")
parser.add_argument("--expect-notification-title", help="Assert a system notification title before cleanup")
parser.add_argument("--expect-notification-body", help="Assert a system notification body before cleanup")
parser.add_argument("--forbid-notification-text", help="Assert a system notification does not contain this case-specific text")
args = parser.parse_args()
if args.rule_category and (not args.rule_parent or not args.merchant):
    parser.error("--rule-category requires --rule-parent and --merchant")
if args.cleanup_category_rule and not args.merchant:
    parser.error("--cleanup-category-rule requires --merchant")

DEVICE = args.device
PACKAGE = "com.example.wallettrackers"
SENDER = "5550001"
CUSTOM_BODY = args.sms_body_template is not None
MERCHANT = args.merchant or "Carrefour"
ACCOUNT_NAME = args.account_name
ACCOUNT_SUFFIX = args.account_suffix
CURRENCY = args.currency
AMOUNT = Decimal(args.amount).quantize(Decimal("0.01"))
EXPECTED_CATEGORY = args.category
BASELINE_BALANCE = Decimal(args.baseline_balance).quantize(Decimal("0.01"))
BALANCE_DELTA = AMOUNT if args.transaction_type == "income" else -AMOUNT
AFTER_TRANSACTION_BALANCE = (
    Decimal(args.printed_balance).quantize(Decimal("0.01"))
    if args.printed_balance is not None else
    Decimal(args.expected_account_balance_after).quantize(Decimal("0.01"))
    if args.expected_account_balance_after is not None else BASELINE_BALANCE + BALANCE_DELTA
)
BALANCE_DELTA = AFTER_TRANSACTION_BALANCE - BASELINE_BALANCE
ACCOUNT_BALANCE_CURRENCY = args.account_balance_currency or CURRENCY
BASELINE_DASHBOARD_TOTAL = Decimal(args.baseline_dashboard_total or args.baseline_balance).quantize(Decimal("0.01"))
DEFAULT_DASHBOARD_DELTA = (
    Decimal("0.00") if args.account_type == "credit" or CURRENCY != "EGP" else BALANCE_DELTA
)
DASHBOARD_DELTA = Decimal(args.dashboard_delta) if args.dashboard_delta is not None else DEFAULT_DASHBOARD_DELTA
AFTER_TRANSACTION_DASHBOARD_TOTAL = BASELINE_DASHBOARD_TOTAL + DASHBOARD_DELTA
TRANSACTION_VERB = "credited" if args.transaction_type == "income" else "debited"
TRANSACTION_SIGN = "+" if args.transaction_type == "income" else "-"
AMOUNT_TEXT = args.expected_record_amount or f"{TRANSACTION_SIGN}{AMOUNT:.2f} {CURRENCY}"
FORMATTED_BASELINE = f"{BASELINE_DASHBOARD_TOTAL:,.2f}"
FORMATTED_AFTER_TRANSACTION = f"{AFTER_TRANSACTION_DASHBOARD_TOTAL:,.2f}"
CASE_NAME = "live_sms_credit_card_purchase_and_rollback" if args.account_type == "credit" else f"live_sms_{args.transaction_type}_{CURRENCY.lower()}_and_rollback"
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / CASE_NAME
OUTPUT.mkdir(parents=True, exist_ok=True)
MARKER = args.cleanup_marker or ("AUTOTEST" + time.strftime("%H%M%S") + uuid.uuid4().hex[:10].upper())
BODY = (
    f"Your account ending {ACCOUNT_SUFFIX} has been debited {CURRENCY} {AMOUNT:.2f} for credit card purchase "
    f"at {MERCHANT}. Available balance {CURRENCY} {BASELINE_BALANCE - AMOUNT:.2f}. {MARKER}"
    if args.account_type == "credit"
    else f"Your bank account ****{ACCOUNT_SUFFIX} was {TRANSACTION_VERB} {CURRENCY} {AMOUNT:.2f} at {MERCHANT}. "
         f"{f'Available balance {CURRENCY} {AFTER_TRANSACTION_BALANCE:.2f}. ' if args.printed_balance is not None else ''}{MARKER}."
)
SENDER = args.sms_sender
if args.sms_body_template:
    BODY = args.sms_body_template.format(
        suffix=ACCOUNT_SUFFIX,
        amount=f"{AMOUNT:.2f}",
        currency=CURRENCY,
        balance=f"{AFTER_TRANSACTION_BALANCE:.2f}",
        merchant=MERCHANT,
        marker=MARKER,
    )
RESULT = {"case": CASE_NAME, "marker": MARKER, "amount": f"{AMOUNT:.2f} {CURRENCY}",
          "account": ACCOUNT_NAME, "account_suffix": ACCOUNT_SUFFIX,
          "account_balance_currency": ACCOUNT_BALANCE_CURRENCY,
          "sms_sender": SENDER, "sms_body": BODY,
          "account_type": args.account_type,
          "currency": CURRENCY,
          "expected_category": EXPECTED_CATEGORY, "merchant": args.merchant or None,
          "transaction_type": args.transaction_type,
          "printed_balance": (
              f"{AFTER_TRANSACTION_BALANCE:.2f} {CURRENCY}"
              if args.printed_balance is not None or "{balance}" in (args.sms_body_template or "")
              else None
          ),
          "status": "IN_PROGRESS", "steps": [], "cleanup": "not_needed"}
SMS_ID = None
RECORD_VISIBLE = False
DELETE_ATTEMPTED = False
DRIVER = None
RULE_CREATED = False
INITIAL_CATEGORY = EXPECTED_CATEGORY


def adb(*command, timeout=10):
    return subprocess.check_output(
        ["adb", "-s", DEVICE, *command], timeout=timeout
    ).decode("utf-8", errors="replace")


def save_result():
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")


def tree():
    if DRIVER is not None:
        return ElementTree.fromstring(DRIVER.page_source)
    remote_path = "/sdcard/wallet-sms-live.xml"
    deadline = time.monotonic() + 10
    last_error = None
    for attempt in range(3):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        try:
            adb("shell", "rm", "-f", remote_path, timeout=min(2, remaining))
            response = adb("shell", "uiautomator", "dump", "--compressed", remote_path,
                           timeout=min(5, max(1, deadline - time.monotonic())))
            if "dumped to:" not in response:
                raise RuntimeError("Android did not confirm a fresh UI hierarchy")
            content = adb("shell", "cat", remote_path,
                          timeout=min(2, max(1, deadline - time.monotonic())))
            return ElementTree.fromstring(content)
        except (ElementTree.ParseError, subprocess.SubprocessError, RuntimeError) as error:
            last_error = str(error)
            if attempt < 2 and time.monotonic() < deadline:
                time.sleep(min(0.4, max(0, deadline - time.monotonic())))
    raise RuntimeError(f"Could not capture fresh UI hierarchy within 10 seconds: {last_error}")


def nodes():
    return [node for node in tree().iter() if node.attrib]


def find_node(text, attribute="text"):
    return next((node for node in nodes()
                 if node.get("package") == PACKAGE and text in node.get(attribute, "")), None)


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
        time.sleep(1)
    raise AssertionError(f"Timed out waiting for {attribute} {text!r} present={present}; {last_error or ''}")


def dismiss_budget_alert():
    for _ in range(3):
        title = next((candidate for candidate in ("Budget Warning", "Budget Exceeded!")
                      if find_node(candidate, "text") is not None), None)
        if title is None:
            return
        dismiss = find_node("Got it", "text")
        if dismiss is None:
            raise AssertionError(f"{title} modal has no Got it action")
        horizontal, vertical = bounds(dismiss)
        adb("shell", "input", "tap", str(horizontal), str(vertical))
        wait_node(title, timeout=5, present=False)
        RESULT.setdefault("budget_alerts_dismissed", []).append(title)
        save_result()
    if any(find_node(title, "text") is not None for title in ("Budget Warning", "Budget Exceeded!")):
        raise AssertionError("Budget alert modal reappeared repeatedly and blocked navigation")


def bounds(node):
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return (left + right) // 2, (top + bottom) // 2


def tap(text, attribute="content-desc"):
    if text == "Records":
        dismiss_budget_alert()
        for attempt in range(2):
            try:
                node = wait_node(text, attribute, timeout=5)
            except AssertionError:
                if DRIVER is None:
                    raise
                adb("shell", "input", "keyevent", "KEYCODE_BACK")
                time.sleep(0.5)
                node = wait_node(text, attribute, timeout=5)
            x, y = bounds(node)
            adb("shell", "input", "tap", str(x), str(y))
            try:
                wait_node("All Records", timeout=5)
                return
            except AssertionError:
                dismiss_budget_alert()
                if attempt == 1:
                    raise
    else:
        x, y = bounds(wait_node(text, attribute, timeout=10))
        adb("shell", "input", "tap", str(x), str(y))


def capture(name, root=None):
    image = subprocess.check_output(["adb", "-s", DEVICE, "exec-out", "screencap", "-p"], timeout=10)
    (OUTPUT / (name + ".png")).write_bytes(image)
    try:
        current_tree = root if root is not None else tree()
        (OUTPUT / (name + ".xml")).write_bytes(ElementTree.tostring(current_tree, encoding="utf-8"))
    except (ElementTree.ParseError, subprocess.SubprocessError, RuntimeError) as error:
        (OUTPUT / (name + ".xml.error")).write_text(str(error), encoding="utf-8")


def verify_notification():
    if not args.expect_notification_title and not args.expect_notification_body and not args.forbid_notification_text:
        return
    adb("shell", "cmd", "statusbar", "expand-notifications")
    deadline = time.monotonic() + (3 if args.forbid_notification_text else 10)
    observed = []
    root = None
    while time.monotonic() < deadline:
        root = tree()
        observed = [node.get("text", "").strip() for node in root.iter() if node.get("text", "").strip()]
        if args.forbid_notification_text and any(args.forbid_notification_text in item for item in observed):
            capture("04_budget_notification_unexpected", root)
            adb("shell", "cmd", "statusbar", "collapse")
            raise AssertionError(
                f"Unexpected budget notification text {args.forbid_notification_text!r} was visible: {observed}"
            )
        title_matches = not args.expect_notification_title or any(args.expect_notification_title in item for item in observed)
        body_matches = not args.expect_notification_body or any(args.expect_notification_body in item for item in observed)
        if title_matches and body_matches and not args.forbid_notification_text:
            capture("04_budget_notification", root)
            RESULT["budget_notification"] = {
                "title": args.expect_notification_title,
                "body": args.expect_notification_body,
                "observed_text": observed,
            }
            RESULT["steps"].append("verified exact system budget-notification title and body before app force-stop")
            adb("shell", "cmd", "statusbar", "collapse")
            return
        time.sleep(0.3)
    if args.forbid_notification_text:
        capture("04_budget_notification_absence", root)
        RESULT["budget_notification_absence"] = {
            "forbidden_text": args.forbid_notification_text,
            "observed_text": observed,
        }
        RESULT["steps"].append("verified the SMS did not post a case-specific Android budget notification below 75%")
        adb("shell", "cmd", "statusbar", "collapse")
        return
    capture("04_budget_notification_missing", root)
    adb("shell", "cmd", "statusbar", "collapse")
    raise AssertionError(
        f"Expected budget notification title/body were not visible before cleanup: "
        f"title={args.expect_notification_title!r} body={args.expect_notification_body!r} visible={observed}"
    )


def check_account_balance(expected):
    return scan_account_carousel(expected)


def scan_account_carousel(expected):
    wait_node("TOTAL BALANCE")
    expected_value = Decimal(str(expected).replace(",", ""))
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    width, height = map(int, size.groups()) if size else (1440, 3120)
    known_accounts = {"MainBank", "SecondBank", "CashWallet", "USDBank", "EURBank", "TestCard", "GoldWallet", ACCOUNT_NAME}
    pages = []
    observed_values = []

    account_order = ["CashWallet", "MainBank", "USDBank", "SecondBank", "EURBank", "SecondCard", "TestCard", "GoldWallet"]
    target_index = account_order.index(ACCOUNT_NAME) if ACCOUNT_NAME in account_order else len(account_order)
    initial_names = {node.get("text", "").strip() for node in nodes()}
    visible_indices = [account_order.index(name) for name in initial_names if name in account_order]
    swipe_directions = ((0.15, 0.9), (0.9, 0.15)) if visible_indices and target_index < min(visible_indices) else ((0.9, 0.15), (0.15, 0.9))
    for swipe_start, swipe_end in swipe_directions:
        previous_signature = None
        repeated_page_count = 0
        for _ in range(8):
            current_nodes = nodes()
            visible_accounts = []
            for node in current_nodes:
                label = node.get("text", "").strip()
                box = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
                if label in known_accounts and len(box) == 4:
                    visible_accounts.append((box[0], label))
            visible_accounts.sort()
            signature = tuple(visible_accounts)
            account_nodes = [node for node in current_nodes if node.get("text", "").strip() == ACCOUNT_NAME]
            page = {"accounts": [label for _, label in visible_accounts], "matched_account_visible": bool(account_nodes)}
            candidate_values = []

            for account_node in account_nodes:
                account_box = list(map(int, re.findall(r"\d+", account_node.get("bounds", ""))))
                if len(account_box) != 4:
                    continue
                account_center_x = (account_box[0] + account_box[2]) // 2
                for value_node in current_nodes:
                    value_box = list(map(int, re.findall(r"\d+", value_node.get("bounds", ""))))
                    if len(value_box) != 4:
                        continue
                    value_center_x = (value_box[0] + value_box[2]) // 2
                    if abs(value_center_x - account_center_x) > 320 or not 0 <= value_box[1] - account_box[3] <= 400:
                        continue
                    text = value_node.get("text", "")
                    for currency_label in ("EGP", "USD", "EUR", "$", "\u20ac"):
                        text = text.replace(currency_label, "")
                    text = text.replace(",", "").strip()
                    try:
                        value = Decimal(text)
                    except Exception:
                        continue
                    candidate_values.append(text)
                    observed_values.append(text)
                    if value == expected_value:
                        page["candidate_balances"] = list(dict.fromkeys(candidate_values))
                        page["matched_balance"] = text
                        pages.append(page)
                        RESULT["account_balance_scan"] = {
                            "account": ACCOUNT_NAME,
                            "expected": f"{expected_value:.2f} {CURRENCY}",
                            "pages": pages,
                        }
                        save_result()
                        return True

            page["candidate_balances"] = list(dict.fromkeys(candidate_values))
            pages.append(page)
            repeated_page_count = repeated_page_count + 1 if signature == previous_signature else 0
            if repeated_page_count >= 2:
                break
            previous_signature = signature
            adb("shell", "input", "swipe", str(int(width * swipe_start)), str(int(height * 0.70)),
                str(int(width * swipe_end)), str(int(height * 0.70)), "350")
            time.sleep(0.35)

    RESULT["account_balance_observations"] = {
        "account": ACCOUNT_NAME,
        "expected": f"{expected_value:.2f} {CURRENCY}",
        "observed_values": list(dict.fromkeys(observed_values)),
        "pages": pages,
        "precision_mismatch": any(
            value.quantize(Decimal("0.01")) == expected_value and value != expected_value
            for value in (Decimal(text) for text in observed_values)
        ),
    }
    save_result()
    return False


def _legacy_check_account_balance(expected):
    wait_node("TOTAL BALANCE")
    scroll_accounts_to_start()
    expected_value = Decimal(str(expected).replace(",", ""))
    observed_values = []
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        current_nodes = nodes()
        account_nodes = [node for node in current_nodes if node.get("text", "").strip() == ACCOUNT_NAME]
        for account_node in account_nodes:
            account_bounds = list(map(int, re.findall(r"\d+", account_node.get("bounds", ""))))
            if len(account_bounds) != 4:
                continue
            account_center_x = (account_bounds[0] + account_bounds[2]) // 2
            account_bottom = account_bounds[3]
            for balance_node in current_nodes:
                balance_bounds = list(map(int, re.findall(r"\d+", balance_node.get("bounds", ""))))
                if len(balance_bounds) != 4:
                    continue
                balance_center_x = (balance_bounds[0] + balance_bounds[2]) // 2
                if abs(balance_center_x - account_center_x) > 320 or not 0 <= balance_bounds[1] - account_bottom <= 400:
                    continue
                text = balance_node.get("text", "")
                for currency_label in ("EGP", "USD", "EUR", "$", "€"):
                    text = text.replace(currency_label, "")
                text = text.replace(",", "").strip()
                try:
                    value = Decimal(text)
                    observed_values.append(text)
                    if value == expected_value:
                        return True
                except Exception:
                    continue
        size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
        width, height = map(int, size.groups()) if size else (1440, 3120)
        adb("shell", "input", "swipe", str(int(width * 0.9)), str(int(height * 0.70)),
            str(int(width * 0.15)), str(int(height * 0.70)), "350")
        time.sleep(1)
    RESULT["account_balance_observations"] = {
        "account": ACCOUNT_NAME,
        "expected": f"{expected_value:.2f} {CURRENCY}",
        "observed_values": list(dict.fromkeys(observed_values)),
        "precision_mismatch": any(
            value.quantize(Decimal("0.01")) == expected_value and value != expected_value
            for value in (Decimal(text) for text in observed_values)
        ),
    }
    save_result()
    return False


def visible_named_account_balance(root):
    account_nodes = [
        node for node in root.iter()
        if node.get("text", "").strip() == ACCOUNT_NAME
    ]
    if not account_nodes:
        return {"visible": False, "account": ACCOUNT_NAME, "balance_values": []}

    values = []
    for account_node in account_nodes:
        account_bounds = list(map(int, re.findall(r"\d+", account_node.get("bounds", ""))))
        if len(account_bounds) != 4:
            continue
        account_left, _, account_right, account_bottom = account_bounds
        account_center_x = (account_left + account_right) // 2
        for value_node in root.iter():
            value_text = value_node.get("text", "").strip()
            value_bounds = list(map(int, re.findall(r"\d+", value_node.get("bounds", ""))))
            if not value_text or len(value_bounds) != 4:
                continue
            left, top, right, _ = value_bounds
            center_x = (left + right) // 2
            if abs(center_x - account_center_x) > 320 or not 0 <= top - account_bottom <= 400:
                continue
            values.append(value_text)
    return {
        "visible": True,
        "account": ACCOUNT_NAME,
        "account_bounds": account_nodes[0].get("bounds", ""),
        "balance_values": list(dict.fromkeys(values)),
    }


def capture_balance_mismatch_diagnostics(expected):
    open_home()
    first_tree = tree()
    first_observation = visible_named_account_balance(first_tree)
    diagnostics = {
        "expected_account_balance": f"{Decimal(str(expected)):.2f} {CURRENCY}",
        "account": ACCOUNT_NAME,
        "before_cleanup": first_observation,
        "evidence_before_cleanup": {
            "xml": "balance_mismatch_before_cleanup.xml",
            "screenshot": "balance_mismatch_before_cleanup.png",
        },
    }
    RESULT["balance_mismatch_diagnostics"] = diagnostics
    save_result()
    try:
        capture("balance_mismatch_before_cleanup", first_tree)
    except Exception as error:
        diagnostics["capture_before_cleanup_error"] = str(error)
        save_result()

    time.sleep(1.5)
    retry_tree = tree()
    retry_observation = visible_named_account_balance(retry_tree)
    diagnostics["settled_retry_same_ui"] = retry_observation
    diagnostics["evidence_settled_retry"] = {
            "xml": "balance_mismatch_settled_retry.xml",
            "screenshot": "balance_mismatch_settled_retry.png",
    }
    save_result()
    try:
        capture("balance_mismatch_settled_retry", retry_tree)
    except Exception as error:
        diagnostics["capture_settled_retry_error"] = str(error)
        save_result()


def scroll_accounts_to_start():
    match = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    width, height = map(int, match.groups()) if match else (1440, 3120)
    for _ in range(8):
        adb("shell", "input", "swipe", str(int(width * 0.15)), str(int(height * 0.70)),
            str(int(width * 0.9)), str(int(height * 0.70)), "450")
        time.sleep(0.15)


def check_dashboard_total(expected):
    wait_node("TOTAL BALANCE")
    return wait_node(expected) is not None


def find_record_amount_node(require_expected_category=True):
    current_nodes = nodes()
    candidates = [node for node in current_nodes if AMOUNT_TEXT == node.get("text", "").strip()]
    nearby_texts = [ACCOUNT_NAME]
    if require_expected_category:
        nearby_texts.append(EXPECTED_CATEGORY)
    if require_expected_category and (not CUSTOM_BODY or args.merchant):
        nearby_texts.append(MERCHANT)
    for candidate in candidates:
        candidate_bounds = list(map(int, re.findall(r"\d+", candidate.get("bounds", ""))))
        if len(candidate_bounds) != 4:
            continue
        candidate_y = (candidate_bounds[1] + candidate_bounds[3]) // 2
        has_context = True
        for expected_text in nearby_texts:
            if not any(
                expected_text in node.get("text", "")
                and len(bounds_value := list(map(int, re.findall(r"\d+", node.get("bounds", ""))))) == 4
                and abs(((bounds_value[1] + bounds_value[3]) // 2) - candidate_y) <= 240
                for node in current_nodes
            ):
                has_context = False
                break
        if has_context:
            return candidate
    return None


def wait_test_record(timeout=10, present=True, require_expected_category=True):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        record_node = find_record_amount_node(require_expected_category)
        if (record_node is not None) == present:
            return record_node
        time.sleep(0.5)
    raise AssertionError(f"Could not find unique record {EXPECTED_CATEGORY}/{MERCHANT}/{ACCOUNT_NAME}/{AMOUNT_TEXT}")


def open_home():
    dismiss_budget_alert()
    try:
        is_home = find_node("TOTAL BALANCE") is not None
    except (subprocess.CalledProcessError, ElementTree.ParseError):
        is_home = False
    if not is_home:
        if find_node("My Wallet") is not None and find_node("Split Receipt") is not None:
            size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
            width, height = map(int, size.groups()) if size else (1440, 3120)
            try:
                adb("shell", "input", "tap", str(int(width * 0.95)), str(int(height * 0.29)))
                wait_node("TOTAL BALANCE", timeout=5)
                return
            except (AssertionError, subprocess.CalledProcessError, ElementTree.ParseError):
                pass
    if not is_home:
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.7)
        try:
            is_home = find_node("TOTAL BALANCE") is not None
            home = None if is_home else find_node("Home", "content-desc")
        except (subprocess.CalledProcessError, ElementTree.ParseError):
            home = None
        if home is not None:
            x, y = bounds(home)
            adb("shell", "input", "tap", str(x), str(y))
        elif not is_home:
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
    wait_node("TOTAL BALANCE")


def delete_test_record():
    global RECORD_VISIBLE, DELETE_ATTEMPTED
    open_home()
    tap("Records")
    wait_node("All Records")
    record_node = find_node(MARKER) if args.cleanup_marker else None
    if record_node is None:
        record_node = wait_test_record(timeout=10, require_expected_category=False)
    x, y = bounds(record_node)
    adb("shell", "input", "swipe", str(x), str(y), str(x + 4), str(y + 4), "1000")
    tap("Delete", "text")
    wait_node("Delete Record")
    delete_buttons = [node for node in nodes() if node.get("text") == "Delete"]
    if not delete_buttons:
        raise AssertionError("Delete confirmation button was not shown")
    x, y = bounds(delete_buttons[-1])
    DELETE_ATTEMPTED = True
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(4)
    RESULT["cleanup"] = "one confirmed delete attempted; checking persistence after relaunch"
    capture("03_record_deleted")
    save_result()


def remove_test_sms():
    global SMS_ID
    query = adb("shell", "content", "query", "--uri", "content://sms/inbox",
                "--projection", "_id,address,body", timeout=10)
    if "Error while accessing provider:sms" in query:
        raise RuntimeError(f"Could not query SMS inbox to verify marker cleanup: {query[:300]}")
    matching_rows = []
    for row in re.split(r"(?=Row:\s*\d+\s)", query):
        if MARKER in row:
            match = re.search(r"_id=(\d+)", row)
            if match:
                matching_rows.append(match.group(1))
    if not matching_rows and SMS_ID is None:
        RESULT["cleanup"] += f"; no inbox row contains exact marker {MARKER}"
        return
    SMS_ID = SMS_ID or matching_rows[0]
    delete_error = None
    try:
        adb("shell", "content", "delete", "--uri", "content://sms",
            "--where", f"_id={SMS_ID}", timeout=10)
    except subprocess.SubprocessError as error:
        delete_error = str(error)

    verification = adb("shell", "content", "query", "--uri", "content://sms/inbox",
                       "--projection", "_id,address,body", timeout=10)
    if "Error while accessing provider:sms" in verification:
        raise RuntimeError(f"Could not verify SMS inbox cleanup: {verification[:300]}")
    retained = False
    for row in re.split(r"(?=Row:\s*\d+\s)", verification):
        match = re.search(r"_id=(\d+)", row)
        if MARKER in row and match and match.group(1) == SMS_ID:
            retained = True
            break
    if retained:
        reason = f"; provider deletion denied or ineffective for row _id={SMS_ID}; exact SMS marker {MARKER} remains in inbox"
        if delete_error:
            reason += f" ({delete_error})"
        RESULT["cleanup"] += reason
    else:
        RESULT["cleanup"] += f"; exact injected inbox row _id={SMS_ID} removed and verified absent"


def refuse_duplicate_injected_sms():
    query = adb("shell", "content", "query", "--uri", "content://sms/inbox",
                "--projection", "_id,address,body", timeout=10)
    if "Error while accessing provider:sms" in query:
        raise RuntimeError(f"Could not query SMS inbox before injection: {query[:300]}")
    if MARKER in query:
        raise AssertionError(
            f"Synthetic SMS marker {MARKER} already exists in the inbox; refusing to inject a duplicate."
        )


def assert_bank_sender_recognized():
    deadline = time.monotonic() + 10
    log_text = ""
    expected_entry = f"sender='{SENDER}'"
    while time.monotonic() < deadline:
        log_text = adb("logcat", "-d", "-v", "brief", "-s", "SmsParser:D", timeout=10)
        lines = log_text.splitlines()
        starts = [index for index, line in enumerate(lines)
                  if "isBankSms START:" in line and expected_entry in line]
        detections = next((
            [line for line in lines[start + 1:start + 20] if "isBankSms:" in line and "senderBank=" in line]
            for start in starts
            if any("isBankSms:" in line and "senderBank=" in line for line in lines[start + 1:start + 20])
        ), [])
        if starts and detections:
            RESULT["bank_sender_detection"] = {
                "sender": SENDER,
                "observed": "senderBank=true" in detections[-1],
                "evidence": {"input": lines[starts[-1]], "decision": detections[-1]},
            }
            save_result()
            if not RESULT["bank_sender_detection"]["observed"]:
                raise AssertionError(f"Source bank sender {SENDER!r} was not recognized: {detections[-1]}")
            return
        time.sleep(0.5)
    raise AssertionError(f"Parser did not log bank-sender detection for {SENDER!r} within 10 seconds")


def scroll_to_text(label, direction="up"):
    if DRIVER is not None:
        from appium.webdriver.common.appiumby import AppiumBy

        selector = f"new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(new UiSelector().text({json.dumps(label)}))"
        try:
            element = DRIVER.find_element(AppiumBy.ANDROID_UIAUTOMATOR, selector)
            return element
        except Exception:
            pass
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    width, height = map(int, size.groups())
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        matches = [node for node in nodes() if node.get("text", "").strip() == label]
        if matches:
            if DRIVER is not None:
                try:
                    from appium.webdriver.common.appiumby import AppiumBy
                    return DRIVER.find_element(AppiumBy.ANDROID_UIAUTOMATOR,
                                               f'new UiSelector().text({json.dumps(label)})')
                except Exception:
                    pass
            return matches[-1]
        start, end = (0.78, 0.4) if direction == "up" else (0.4, 0.78)
        adb("shell", "input", "swipe", str(int(width * 0.5)), str(int(height * start)),
            str(int(width * 0.5)), str(int(height * end)), "300")
        time.sleep(0.35)
    raise AssertionError(f"Could not reveal {label!r} within 10 seconds")


def restart_and_verify_clean():
    for attempt in range(2):
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        try:
            open_home()
            break
        except AssertionError:
            if attempt == 1:
                raise
    tap("Records")
    wait_node("All Records")
    if find_record_amount_node(require_expected_category=False) is not None:
        raise AssertionError("Rule-test record persisted after deletion")
    open_home()
    if not check_account_balance(f"{BASELINE_BALANCE:.2f}") or not check_dashboard_total(FORMATTED_BASELINE):
        raise AssertionError("Rule-test cleanup did not restore baseline")


def native_ui_tree():
    remote_path = "/sdcard/wallet-category-rule-cleanup.xml"
    last_error = None
    for attempt in range(3):
        try:
            adb("shell", "rm", "-f", remote_path)
            response = adb("shell", "uiautomator", "dump", "--compressed", remote_path)
            if "dumped to:" not in response:
                raise RuntimeError("Android did not confirm a fresh UI hierarchy")
            return ElementTree.fromstring(adb("shell", "cat", remote_path))
        except (RuntimeError, subprocess.CalledProcessError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(0.5 * (attempt + 1))
    raise RuntimeError(f"Could not read Android UI hierarchy after three attempts: {last_error}")


def replay_with_category(category, phase):
    global MARKER, BODY, EXPECTED_CATEGORY, RECORD_VISIBLE, DELETE_ATTEMPTED
    old_marker = MARKER
    MARKER = "AUTORULE" + uuid.uuid4().hex[:14].upper()
    BODY = BODY.replace(old_marker, MARKER)
    EXPECTED_CATEGORY = category
    RECORD_VISIBLE = False
    DELETE_ATTEMPTED = False
    RESULT.setdefault("rule_replays", []).append({"phase": phase, "body": BODY, "expected_category": category})
    save_result()
    refuse_duplicate_injected_sms()
    adb("emu", "sms", "send", SENDER, BODY)
    RECORD_VISIBLE = True
    open_home()
    tap("Records")
    wait_node("All Records")
    wait_test_record()
    capture(phase + "_record")
    open_home()
    if not check_account_balance(f"{AFTER_TRANSACTION_BALANCE:.2f}") or not check_dashboard_total(FORMATTED_AFTER_TRANSACTION):
        raise AssertionError(f"{phase} changed the wrong account or amount")
    RESULT["steps"].append(f"{phase}: exact {category} record and account/Home arithmetic verified")
    save_result()


def save_rule_and_replay():
    global RULE_CREATED, EXPECTED_CATEGORY
    open_home()
    tap("Records")
    wait_node("All Records")
    target = wait_test_record()
    horizontal, vertical = bounds(target)
    adb("shell", "input", "swipe", str(horizontal), str(vertical), str(horizontal), str(vertical), "1000")
    tap("Save as Category Rule", "text")
    wait_node("Categories")
    capture("rule_category_parents")
    target = scroll_to_text(args.rule_parent)
    if DRIVER is not None:
        target.click()
    else:
        horizontal, vertical = bounds(target)
        adb("shell", "input", "tap", str(horizontal), str(vertical))
    wait_node(args.rule_parent)
    capture("rule_category_subcategories")
    target = scroll_to_text(args.rule_category)
    RULE_CREATED = True
    if DRIVER is not None:
        target.click()
    else:
        horizontal, vertical = bounds(target)
        adb("shell", "input", "tap", str(horizontal), str(vertical))
    EXPECTED_CATEGORY = args.rule_category
    open_home()
    tap("Records")
    wait_node("All Records")
    wait_test_record()
    capture("rule_reclassified_existing_record")
    open_home()
    if not check_account_balance(f"{AFTER_TRANSACTION_BALANCE:.2f}") or not check_dashboard_total(FORMATTED_AFTER_TRANSACTION):
        raise AssertionError("Saving a category rule changed the financial amount")
    RESULT["steps"].append(f"saved merchant rule to {args.rule_category}; existing record reclassified without another balance effect")
    delete_test_record()
    remove_test_sms()
    restart_and_verify_clean()
    replay_with_category(args.rule_category, "saved_rule_after_restart")


def remove_created_rule():
    global RULE_CREATED
    open_home()
    tap("Profile")
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    width, height = map(int, size.groups())
    label = f"Delete category rule for {MERCHANT}"
    deadline = time.monotonic() + 10
    root = tree()
    while (not any(node.get("content-desc", "") == label for node in root.iter())
           and time.monotonic() < deadline):
        adb("shell", "input", "swipe", str(width // 2), str(int(height * 0.88)),
            str(width // 2), str(int(height * 0.29)), "400")
        time.sleep(0.25)
        root = tree()
    delete_node = next((node for node in root.iter() if node.get("content-desc", "") == label), None)
    if delete_node is None:
        raise AssertionError(f"Could not reveal test-owned category rule deletion control {label!r}")
    RESULT["category_rule_before_delete"] = label
    horizontal, vertical = bounds(delete_node)
    adb("shell", "input", "tap", str(horizontal), str(vertical))
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        root = tree()
        if not any(node.get("content-desc", "") == label for node in root.iter()):
            break
        time.sleep(0.25)
    if any(node.get("content-desc", "") == label for node in root.iter()):
        raise AssertionError("Saved merchant rule remained visible after deletion")
    capture("rule_deleted", root)
    RULE_CREATED = False
    RESULT["category_rule_removed"] = True
    RESULT["steps"].append("deleted exactly the test-owned merchant rule")


try:
    if args.ui_backend == "appium":
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
        })
        client_config = AppiumClientConfig(remote_server_addr="http://127.0.0.1:4723", timeout=10)
        DRIVER = webdriver.Remote(options=options, client_config=client_config)
        DRIVER.implicitly_wait(0)
        DRIVER.update_settings({"waitForIdleTimeout": 500})
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
    package_paths = adb("shell", "pm", "path", PACKAGE).splitlines()
    base_apk = next((line.removeprefix("package:") for line in package_paths if line.endswith("/base.apk")), None)
    RESULT["device"] = DEVICE
    RESULT["ui_backend"] = args.ui_backend
    if base_apk:
        RESULT["installed_apk_sha256"] = adb("shell", "sha256sum", base_apk).split()[0]
    RESULT["android_version"] = adb("shell", "getprop", "ro.build.version.release").strip()
    save_result()
    if args.cleanup_marker:
        open_home()
        tap("Records")
        wait_node("All Records")
        if find_node(MARKER) is None:
            search = wait_node("Search category, account, comment...", "text", timeout=10)
            x, y = bounds(search)
            adb("shell", "input", "tap", str(x), str(y))
            adb("shell", "input", "text", MARKER)
        try:
            wait_node(MARKER, timeout=2)
        except AssertionError:
            search = wait_node("Search category, account, comment...", "text", timeout=10)
            x, y = bounds(search)
            adb("shell", "input", "tap", str(x), str(y))
            adb("shell", "input", "keycombination", "113", "29")
            adb("shell", "input", "keyevent", "KEYCODE_DEL")
            wait_node("All Records")
            wait_test_record(timeout=10, require_expected_category=True)
        RECORD_VISIBLE = True
        delete_test_record()
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
        time.sleep(4)
        open_home()
        tap("Records")
        wait_node("All Records")
        if find_node(MARKER) is not None:
            raise AssertionError("Tagged test record reappeared after delete and app restart")
        open_home()
        if not check_account_balance(f"{BASELINE_BALANCE:.2f}") or not check_dashboard_total(FORMATTED_BASELINE):
            raise AssertionError("Tagged test record was deleted, but account/dashboard baseline was not restored")
        RESULT["status"] = "PASS"
        RESULT["steps"].append("removed exactly the requested test marker and verified baseline balances")
        RESULT["cleanup"] = "tagged test record deleted; checking exact injected SMS row"
        remove_test_sms()
        save_result()
        print(json.dumps(RESULT, indent=2))
        print(f"Evidence: {OUTPUT.resolve()}")
        raise SystemExit(0)
    if args.cleanup_category_rule:
        open_home()
        remove_created_rule()
        RESULT["status"] = "PASS"
        RESULT["steps"].append(f"removed only the category rule for merchant {MERCHANT!r}")
        RESULT["cleanup"] = "exact merchant category rule deleted and verified absent"
        save_result()
        print(json.dumps(RESULT, indent=2))
        print(f"Evidence: {OUTPUT.resolve()}")
        raise SystemExit(0)
    open_home()
    if not check_account_balance(f"{BASELINE_BALANCE:.2f}"):
        raise AssertionError(
            f"{ACCOUNT_NAME} did not show its expected initial balance {BASELINE_BALANCE:.2f} {CURRENCY}"
        )
    RESULT["baseline_account_balance"] = f"{BASELINE_BALANCE:.2f} {CURRENCY}"
    if not check_dashboard_total(FORMATTED_BASELINE):
        raise AssertionError(f"Dashboard total did not match the expected baseline EGP {FORMATTED_BASELINE}")
    tap("Records")
    wait_node("All Records")
    if find_record_amount_node(require_expected_category=False) is not None:
        raise AssertionError("A matching transaction signature already exists; refusing an ambiguous live test")
    refuse_duplicate_injected_sms()
    open_home()
    RESULT["baseline_dashboard_total"] = f"{FORMATTED_BASELINE} EGP"
    RESULT["steps"].append("captured signed-in account balance")
    capture("01_before")

    adb("emu", "sms", "send", SENDER, BODY, timeout=10)
    RECORD_VISIBLE = True
    RESULT["steps"].append(
        f"injected one synthetic {CURRENCY} {AMOUNT:.2f} bank-shaped {args.transaction_type} SMS into the emulator"
    )
    save_result()
    if not SENDER.isdigit():
        assert_bank_sender_recognized()

    open_home()
    tap("Records")
    wait_node("All Records")
    marker_node = wait_test_record(timeout=10)
    RECORD_VISIBLE = True
    wait_node(AMOUNT_TEXT, timeout=10)
    if find_node(ACCOUNT_NAME, "text") is None:
        raise AssertionError(f"Transaction did not display the linked {ACCOUNT_NAME} account")
    if find_node(EXPECTED_CATEGORY, "text") is None:
        raise AssertionError(f"Transaction did not display expected category {EXPECTED_CATEGORY}")
    RESULT["steps"].append(f"found tagged {args.transaction_type} with expected amount, {EXPECTED_CATEGORY} category, and linked account")
    capture("02_record_created")
    if args.expect_empty_balance_after:
        displayed_balance = f"{BASELINE_BALANCE:,.2f} {ACCOUNT_BALANCE_CURRENCY}"
        if find_node(displayed_balance) is not None:
            raise AssertionError(f"Expected empty balanceAfter, but record displays {displayed_balance}")
        RESULT["balance_after_assertion"] = {"expected": "empty", "observed": "empty"}
        RESULT["steps"].append("verified the foreign credit-card record has empty balanceAfter")
        save_result()

    open_home()
    if not check_account_balance(f"{AFTER_TRANSACTION_BALANCE:.2f}"):
        raise AssertionError(
            f"{ACCOUNT_NAME} did not change from {CURRENCY} {BASELINE_BALANCE:.2f} "
            f"to {CURRENCY} {AFTER_TRANSACTION_BALANCE:.2f}"
        )
    if not check_dashboard_total(FORMATTED_AFTER_TRANSACTION):
        raise AssertionError(f"Dashboard total did not change to EGP {FORMATTED_AFTER_TRANSACTION}")
    RESULT["account_balance_after_transaction"] = f"{AFTER_TRANSACTION_BALANCE:.2f} {CURRENCY}"
    RESULT["dashboard_total_after_transaction"] = f"{FORMATTED_AFTER_TRANSACTION} EGP"
    RESULT["steps"].append(
        f"verified {ACCOUNT_NAME} changed by {CURRENCY} {BALANCE_DELTA:+.2f}; Home changed by EGP {DASHBOARD_DELTA:+.2f}"
    )
    capture(f"03_balances_after_{args.transaction_type}")

    verify_notification()

    if args.rule_category:
        save_rule_and_replay()
    delete_test_record()
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(4)
    open_home()
    after = f"{BASELINE_BALANCE:.2f}"
    tap("Records")
    wait_node("All Records")
    if find_node(MARKER) is not None or find_record_amount_node(require_expected_category=False) is not None:
        raise AssertionError("Tagged test record reappeared after delete and app restart")
    open_home()
    if not check_account_balance(after):
        raise AssertionError("Account balance did not return to its exact baseline after deletion")
    if not check_dashboard_total(FORMATTED_BASELINE):
        raise AssertionError("Dashboard total did not return to baseline after deleting the test record")
    RESULT["record_absent_after_restart"] = True
    RESULT["dashboard_total_after_cleanup"] = f"{BASELINE_DASHBOARD_TOTAL:.2f} EGP"
    RESULT["steps"].append("restored baseline account balance after deleting test record")
    capture("04_balance_restored")

    try:
        remove_test_sms()
    except (subprocess.SubprocessError, RuntimeError) as error:
        RESULT["cleanup"] += f"; record is removed, inbox cleanup unsupported: {error}"
    if RULE_CREATED:
        remove_created_rule()
        restart_and_verify_clean()
        replay_with_category(INITIAL_CATEGORY, "fallback_after_rule_deletion")
        delete_test_record()
        remove_test_sms()
        restart_and_verify_clean()
        RESULT["rule_test"] = {"category": args.rule_category, "merchant": MERCHANT,
                               "persisted_after_restart": True, "fallback_verified": True, "removed": True}
    RESULT["status"] = "PASS"
    RESULT["account_balance_after_cleanup"] = f"{after} {CURRENCY}"
    save_result()
except Exception as error:
    RESULT["status"] = "FAIL"
    RESULT["error"] = str(error)
    try:
        capture("failure_at_assertion")
    except Exception as capture_error:
        RESULT["failure_capture_error"] = str(capture_error)
    save_result()
    if RECORD_VISIBLE and not DELETE_ATTEMPTED:
        try:
            capture_balance_mismatch_diagnostics(AFTER_TRANSACTION_BALANCE)
        except Exception as diagnostic_error:
            RESULT["balance_mismatch_diagnostics"] = {
                "capture_error": str(diagnostic_error),
                "expected_account_balance": f"{AFTER_TRANSACTION_BALANCE:.2f} {CURRENCY}",
            }
            save_result()
        try:
            delete_test_record()
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
            time.sleep(4)
            open_home()
            tap("Records")
            wait_node("All Records")
            if find_record_amount_node(require_expected_category=False) is not None:
                raise AssertionError("Failed-case record remained after deletion and restart")
            open_home()
            if not check_account_balance(f"{BASELINE_BALANCE:.2f}") or not check_dashboard_total(FORMATTED_BASELINE):
                raise AssertionError("Failed-case cleanup did not restore exact baseline balances")
            RESULT["cleanup"] = "test record deleted after case failure; restart and baseline restoration verified"
            RESULT["record_absent_after_restart"] = True
            RESULT["account_balance_after_cleanup"] = f"{BASELINE_BALANCE:.2f} {CURRENCY}"
            RESULT["dashboard_total_after_cleanup"] = f"{BASELINE_DASHBOARD_TOTAL:.2f} EGP"
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"REQUIRES MANUAL CLEANUP: {cleanup_error}"
    try:
        remove_test_sms()
    except Exception as cleanup_error:
        RESULT["cleanup"] += f"; injected SMS cleanup failed: {cleanup_error}"
    if RULE_CREATED:
        try:
            remove_created_rule()
        except Exception as rule_error:
            RESULT["cleanup"] += f"; RULE REQUIRES CLEANUP: {rule_error}"
    save_result()
    raise
finally:
    if DRIVER is not None:
        try:
            DRIVER.quit()
        except Exception:
            pass

print(json.dumps(RESULT, indent=2))
print(f"Evidence: {OUTPUT.resolve()}")
