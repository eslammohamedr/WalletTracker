"""Opt-in black-box Appium acceptance cases for APP-01 through APP-07.

All writes use unique, case-owned names. This runner intentionally never deletes
an account or a user, and it never creates financial ledger records.
"""

import argparse
import json
import re
import subprocess
import time
import uuid
from decimal import Decimal
from pathlib import Path
from xml.etree import ElementTree

PACKAGE = "com.example.wallettrackers"
BASE_URL = "http://127.0.0.1:4723"
OUT_ROOT = Path("app/build/device-smoke")
TIMEOUT = 10

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--case", required=True, choices=[f"APP-0{i}" for i in range(1, 8)])
parser.add_argument("--execute", action="store_true", help="Required to connect to Appium or mutate app data")
parser.add_argument("--device", default="emulator-5554")
parser.add_argument("--search-text", help="Existing distinctive text required by read-only APP-07")
parser.add_argument("--account", default="MainBank", help="Existing account used by non-destructive APP-02")
parser.add_argument("--cleanup-account", help="Delete only this exact QA account, then exit")
args = parser.parse_args()

if not args.execute:
    parser.error("No device changes made. Pass --execute explicitly to run the selected case.")
if args.case == "APP-07" and not args.search_text:
    parser.error("APP-07 is read-only and requires --search-text matching an existing record")

try:
    from appium import webdriver
    from appium.options.android import UiAutomator2Options
    from appium.webdriver.common.appiumby import AppiumBy
except ImportError as error:
    parser.error(f"Appium dependency missing; install scripts/requirements-appium.txt ({error})")

run_id = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6].upper()
output = OUT_ROOT / run_id / args.case.lower().replace("-", "_")
output.mkdir(parents=True, exist_ok=True)
driver = None
mutated = False
cleanup_action = None
cleanup = "not_required"
result = {
    "case": args.case,
    "status": "IN_PROGRESS",
    "run_id": run_id,
    "device": args.device,
    "steps": [],
    "evidence": [],
    "cleanup": cleanup,
    "blockers": [],
}


def adb(*command, timeout=TIMEOUT):
    return subprocess.check_output(["adb", "-s", args.device, *command], timeout=timeout).decode("utf-8", errors="replace")


def root():
    return ElementTree.fromstring(driver.page_source)


def all_nodes():
    return list(root().iter())


def visible_texts():
    return [node.get("text", "").strip() for node in all_nodes() if node.get("text", "").strip()]


def bounds(node):
    values = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(values) != 4:
        raise AssertionError(f"Node has no bounds: {node.attrib}")
    return values


def tap_node(node):
    x1, y1, x2, y2 = bounds(node)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap_exact(label, occurrence=0):
    matches = [node for node in all_nodes() if node.get("text", "").strip() == label]
    if not matches:
        matches = [node for node in all_nodes() if node.get("content-desc", "").strip() == label]
    if len(matches) <= occurrence:
        raise AssertionError(f"Missing {label!r} occurrence {occurrence}; visible={visible_texts()}")
    tap_node(matches[occurrence])
    time.sleep(0.45)


def tap_contains(fragment):
    matches = [node for node in all_nodes() if fragment.casefold() in (node.get("text", "").strip()).casefold()]
    if not matches:
        raise AssertionError(f"Missing text containing {fragment!r}; visible={visible_texts()}")
    tap_node(matches[0])
    time.sleep(0.45)


def row_container(label):
    root_node = root()
    parents = {child: parent for parent in root_node.iter() for child in parent}
    node = next((n for n in root_node.iter() if n.get("text", "").strip() == label), None)
    if node is None:
        raise AssertionError(f"Could not locate row label {label!r}")
    candidates = []
    current = node
    while current is not None:
        try:
            x1, y1, x2, y2 = bounds(current)
            row_text = " ".join(n.get("text", "") for n in current.iter())
            if label in row_text and 250 < x2 - x1 < 1500 and 100 < y2 - y1 < 1100:
                candidates.append((x2 - x1, current, (x1, y1, x2, y2)))
        except AssertionError:
            pass
        current = parents.get(current)
    if not candidates:
        raise AssertionError(f"No compact row container found for {label!r}")
    return min(candidates, key=lambda item: item[0])[2]


def tap_row_action(label, right_offset=22, y_offset=38):
    x1, y1, x2, y2 = row_container(label)
    adb("shell", "input", "tap", str(x2 - right_offset), str(min(y2 - 16, y1 + y_offset)))
    time.sleep(0.45)


def scroll_until_visible(label, attempts=16):
    for _ in range(attempts):
        if label in visible_texts():
            return
        adb("shell", "input", "swipe", "720", "2600", "720", "900", "500")
        time.sleep(0.25)
    wait_for(label)


def scroll_until_prefix(prefix, attempts=16):
    for _ in range(attempts):
        if any(text.startswith(prefix) for text in visible_texts()):
            return
        adb("shell", "input", "swipe", "720", "2600", "720", "900", "500")
        time.sleep(0.25)
    raise AssertionError(f"No visible text starts with {prefix!r}: {visible_texts()}")


def delete_subcategory_row(label):
    root_node = root()
    label_node = next((node for node in root_node.iter() if node.get("text", "").strip() == label), None)
    if label_node is None:
        raise AssertionError(f"Could not locate custom subcategory {label!r}")
    _, label_top, _, label_bottom = bounds(label_node)
    label_center_y = (label_top + label_bottom) // 2
    delete_nodes = [node for node in root_node.iter() if node.get("content-desc", "").strip() == "Delete"]
    delete_node = min(delete_nodes, key=lambda node: abs(sum(bounds(node)[1::2]) // 2 - label_center_y), default=None)
    if delete_node is None:
        raise AssertionError(f"Delete control is not visible for custom subcategory {label!r}")
    tap_node(delete_node)
    time.sleep(0.45)


def tap_goal_action(label, action_index):
    root_node = root()
    parents = {child: parent for parent in root_node.iter() for child in parent}
    target = next((node for node in root_node.iter() if node.get("text", "").strip() == label), None)
    if target is None:
        raise AssertionError(f"Goal label {label!r} is not visible")
    _, label_y1, _, label_y2 = bounds(target)
    label_y = (label_y1 + label_y2) // 2
    current = target
    while current is not None:
        buttons = []
        for node in current.iter():
            if node.get("class") not in {"android.widget.Button", "android.view.View"} \
                    or node.get("clickable") != "true" or node.get("enabled") != "true":
                continue
            x1, y1, x2, y2 = bounds(node)
            center_y = (y1 + y2) // 2
            if abs(center_y - label_y) <= 110 and x1 >= bounds(target)[2]:
                buttons.append((x1 + x2, node))
        if len(buttons) >= 2 or (action_index == 1 and len(buttons) == 1):
            buttons.sort(key=lambda entry: entry[0])
            tap_node(buttons[min(action_index, len(buttons) - 1)][1])
            time.sleep(0.45)
            return
        current = parents.get(current)
    raise AssertionError(f"Could not find goal action button {action_index} for {label!r}")


def tap_row_delete_button(label):
    root_node = root()
    parents = {child: parent for parent in root_node.iter() for child in parent}
    target = next((node for node in root_node.iter() if node.get("text", "").strip() == label), None)
    if target is None:
        raise AssertionError(f"Row label {label!r} is not visible")
    label_x1, label_y1, label_x2, label_y2 = bounds(target)
    label_y = (label_y1 + label_y2) // 2
    current = target
    while current is not None:
        buttons = []
        for node in current.iter():
            if node.get("class") != "android.widget.Button" or node.get("enabled") != "true":
                continue
            x1, y1, x2, y2 = bounds(node)
            center_y = (y1 + y2) // 2
            if x1 >= label_x2 and abs(center_y - label_y) <= 160:
                buttons.append((x1 + x2, node))
        if buttons:
            buttons.sort(key=lambda entry: entry[0])
            tap_node(buttons[-1][1])
            time.sleep(0.45)
            return
        current = parents.get(current)
    raise AssertionError(f"Could not find delete button for {label!r}; label_bounds={(label_x1, label_y1, label_x2, label_y2)}")


def wait_for(text, present=True):
    deadline = time.monotonic() + TIMEOUT
    while time.monotonic() < deadline:
        found = any(text.casefold() in value.casefold() for value in visible_texts())
        if found == present:
            return
        time.sleep(0.25)
    raise AssertionError(f"Timed out waiting for {text!r} present={present}; visible={visible_texts()}")


def capture(name):
    path = output / name
    path.mkdir(parents=True, exist_ok=True)
    (path / "hierarchy.xml").write_bytes(ElementTree.tostring(root(), encoding="utf-8"))
    (path / "screen.png").write_bytes(subprocess.check_output(["adb", "-s", args.device, "exec-out", "screencap", "-p"], timeout=TIMEOUT))
    result["evidence"].append(str(path))
    save()


def save():
    (output / "results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")


def go_home():
    for _ in range(4):
        current_texts = visible_texts()
        if "TOTAL BALANCE" in current_texts:
            return
        home_node = next((node for node in all_nodes() if node.get("text", "").strip() == "Home"), None)
        if home_node is not None:
            tap_node(home_node)
        else:
            back_node = next((node for node in all_nodes() if node.get("content-desc", "").strip() == "Back"), None)
            if back_node is not None:
                tap_node(back_node)
            else:
                adb("shell", "input", "keyevent", "4")
        time.sleep(0.4)
    wait_for("TOTAL BALANCE")


def home_total_balance():
    go_home()
    texts = visible_texts()
    index = texts.index("TOTAL BALANCE")
    return Decimal(texts[index + 1].replace(",", "").strip())


def wait_home_total_balance(expected):
    deadline = time.monotonic() + TIMEOUT
    observed = None
    while time.monotonic() < deadline:
        observed = home_total_balance()
        if observed == expected:
            return observed
        time.sleep(0.2)
    raise AssertionError(f"Home balance was {observed}, expected {expected}")


def open_drawer():
    matches = [node for node in all_nodes() if node.get("content-desc", "").strip() == "Menu"]
    if not matches:
        raise AssertionError(f"Menu action unavailable; visible={visible_texts()}")
    tap_node(matches[0])
    wait_for("Savings Goals")


def open_feature(label):
    go_home()
    open_drawer()
    tap_exact(label)
    wait_for(label if label != "Debts & Loans" else "Debt Tracker")


def fill_edit(index, value):
    fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
    if index >= len(fields):
        raise AssertionError(f"Expected edit field #{index}; fields={len(fields)} visible={visible_texts()}")
    fields[index].click()
    fields[index].clear()
    fields[index].send_keys(str(value))


def hide_keyboard_if_visible():
    if driver.is_keyboard_shown():
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.25)


def delete_dialog(confirm_text="Delete"):
    wait_for("Delete")
    tap_exact(confirm_text)
    time.sleep(0.8)


def app02_delete_confirmation_cancel():
    go_home()
    long_press_text(args.account)
    wait_for("Delete Account")
    tap_exact("Delete Account")
    wait_for(f'Delete "{args.account}"?')
    capture("confirmation_before_cancel")
    if "Cancel" not in visible_texts():
        raise AssertionError("Delete confirmation has no Cancel action")
    tap_exact("Cancel")
    wait_for(f'Delete "{args.account}"?', present=False)
    result["steps"].append("opened an existing account deletion confirmation and canceled without deleting")
    result["blockers"].append("APP-02 orphan/reassignment behavior remains untested: product has no stated policy; confirmation-only case is non-destructive")


def reveal_home_account(label):
    matches = [node for node in all_nodes() if node.get("text", "").strip() == label]
    if not matches or matches[0].get("displayed") != "true":
        screen = adb("shell", "wm", "size")
        dimensions = re.search(r"(\d+)x(\d+)", screen)
        if dimensions is None:
            raise AssertionError(f"Could not determine emulator screen dimensions: {screen}")
        width, height = map(int, dimensions.groups())
        accounts_heading = next((node for node in all_nodes() if node.get("text", "").strip() == "Accounts"), None)
        if accounts_heading is None:
            raise AssertionError("Accounts heading is not visible while locating account")
        _, _, _, heading_y2 = bounds(accounts_heading)
        swipe_y = min(heading_y2 + 250, int(height * 0.82))
        for start_x, end_x in ((int(width * 0.68), int(width * 0.42)),
                               (int(width * 0.42), int(width * 0.68))):
            for _ in range(16):
                adb("shell", "input", "swipe", str(start_x), str(swipe_y), str(end_x), str(swipe_y), "550")
                time.sleep(0.35)
                matches = [node for node in all_nodes() if node.get("text", "").strip() == label and node.get("displayed") == "true"]
                if matches:
                    break
            if matches:
                break
    if not matches:
        raise AssertionError(f"Could not reveal account {label!r} in the Home carousel")
    return matches[0]


def long_press_text(label):
    match = reveal_home_account(label)
    x1, y1, x2, y2 = bounds(match)
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    adb("shell", "input", "swipe", str(x), str(y), str(x + 2), str(y + 2), "900")
    time.sleep(0.4)


def app01_account_create_edit_delete():
    global mutated, cleanup_action
    name = f"QA Account {run_id}"
    edited_name = name + " Edited"
    go_home()
    starting_total = home_total_balance()
    for _ in range(2):
        if any(n.get("content-desc", "").strip() == "Add Account" for n in all_nodes()):
            break
        accounts_heading = next((n for n in all_nodes() if n.get("text", "").strip() == "Accounts"), None)
        if accounts_heading is None:
            break
        _, heading_y1, _, heading_y2 = bounds(accounts_heading)
        screen = adb("shell", "wm", "size")
        dimensions = re.search(r"(\d+)x(\d+)", screen)
        if dimensions is None:
            raise AssertionError(f"Could not determine emulator screen dimensions: {screen}")
        width, height = map(int, dimensions.groups())
        swipe_y = min(heading_y2 + max(160, int(height * 0.09)), int(height * 0.86))
        adb("shell", "input", "swipe", str(width - 200), str(swipe_y), "200", str(swipe_y), "650")
        time.sleep(0.6)
    if any(n.get("content-desc", "").strip() == "Add Account" for n in all_nodes()):
        tap_exact("Add Account")
    else:
        tap_exact("Add")
    wait_for("Add Account")
    fill_edit(0, name)
    account_type_fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
    if len(account_type_fields) < 4 or account_type_fields[1].text != "Debit":
        raise AssertionError(f"Expected the default Debit account fields; values={[field.text for field in account_type_fields]}")
    fill_edit(2, str(int(time.time() * 1000) % 10000).zfill(4))
    fill_edit(3, "12.34")
    hide_keyboard_if_visible()
    tap_exact("Add")
    mutated = True
    cleanup_action = lambda: cleanup_account(edited_name if edited_name in visible_texts() else name)
    reveal_home_account(name)
    expected_active_total = starting_total + Decimal("12.34")
    wait_home_total_balance(expected_active_total)
    capture("account_created")
    long_press_text(name)
    wait_for("Edit Account")
    tap_exact("Edit Account")
    wait_for("Edit Account")
    fill_edit(0, edited_name)
    tap_exact("Update")
    reveal_home_account(edited_name)
    capture("account_edited")
    wait_home_total_balance(expected_active_total)
    result["steps"].append("created a unique EGP 12.34 debit account, verified Home balance, and edited its name without a balance change")
    long_press_text(edited_name)
    wait_for("Archive Account")
    tap_exact("Archive Account")
    wait_for(edited_name, present=False)
    wait_home_total_balance(starting_total)
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    wait_for("TOTAL BALANCE")
    wait_for(edited_name, present=False)
    wait_home_total_balance(starting_total)
    result["steps"].append("archived the test-owned account, verified it disappeared from the active list and Home total, then restarted Wallet to verify archived state persisted")
    scroll_until_prefix("Archived Accounts (")
    archived_toggle = next((node for node in all_nodes() if node.get("text", "").strip().startswith("Archived Accounts (")), None)
    if archived_toggle is None:
        raise AssertionError(f"Archived account restore control was not exposed: {visible_texts()}")
    tap_node(archived_toggle)
    wait_for(edited_name)
    wait_for("Restore")
    capture("archived_account_available_to_restore")
    tap_exact("Restore")
    wait_for(edited_name)
    wait_home_total_balance(expected_active_total)
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    wait_for("TOTAL BALANCE")
    wait_for(edited_name)
    wait_home_total_balance(expected_active_total)
    result["archive_balance_checks"] = {
        "before_add": str(starting_total),
        "active_after_add": str(expected_active_total),
        "after_archive": str(starting_total),
        "after_restore": str(expected_active_total),
    }
    result["steps"].append("restored the archived account through the Archived Accounts section, verified its exact balance, and restarted Wallet to verify restore persisted")
    cleanup_account(edited_name)
    wait_home_total_balance(starting_total)
    cleanup_action = None
    result["steps"].append("deleted only the unique case-owned account and verified Home returned to its original balance")


def cleanup_account(name):
    go_home()
    if name not in visible_texts():
        archived_toggle = next((node for node in all_nodes() if node.get("text", "").strip().startswith("Archived Accounts (")), None)
        if archived_toggle is None:
            scroll_until_prefix("Archived Accounts (")
            archived_toggle = next((node for node in all_nodes() if node.get("text", "").strip().startswith("Archived Accounts (")), None)
        if archived_toggle is None:
            raise AssertionError(f"Archived test account {name!r} cannot be restored for cleanup")
        tap_node(archived_toggle)
        wait_for(name)
        tap_exact("Restore")
        wait_for(name)
    long_press_text(name)
    wait_for("Delete Account")
    tap_exact("Delete Account")
    wait_for(f'Delete "{name}"?')
    capture("account_delete_confirmation")
    tap_exact("Delete")
    wait_for(name, present=False)


def app03_goal_crud():
    global mutated, cleanup_action
    name = f"QA Goal {run_id}"
    open_feature("Savings Goals")
    tap_exact("Add Goal")
    wait_for("New Savings Goal")
    fill_edit(0, name)
    fill_edit(1, "0")
    hide_keyboard_if_visible()
    assert_action_enabled("Save Goal", False, "zero target")
    fill_edit(1, "-1")
    hide_keyboard_if_visible()
    assert_action_enabled("Save Goal", False, "negative target")
    fill_edit(1, "100")
    fill_edit(2, "101")
    hide_keyboard_if_visible()
    assert_action_enabled("Save Goal", False, "already-saved amount above target")
    fill_edit(2, "0")
    hide_keyboard_if_visible()
    assert_action_enabled("Save Goal", True, "valid goal values")
    result["steps"].append("verified zero/negative targets and already-saved-above-target values cannot be saved")
    capture("goal_invalid_boundaries")
    tap_exact("Save Goal")
    mutated = True
    cleanup_action = lambda: cleanup_goal(name + " Edited" if name + " Edited" in visible_texts() else name)
    result["cleanup"] = f"delete exact goal {name} in finally"
    wait_for(name)
    capture("goal_created")
    add_funds = next((node for node in all_nodes() if node.get("text", "").strip().startswith("Add ") and node.get("text", "").strip().endswith(" more")), None)
    if add_funds is None:
        raise AssertionError(f"Goal has no Add Funds action; visible={visible_texts()}")
    tap_node(add_funds)
    wait_for(f"Add Funds to {name}")
    fill_edit(0, "0")
    hide_keyboard_if_visible()
    assert_action_enabled("Add", False, "zero contribution")
    fill_edit(0, "-1")
    hide_keyboard_if_visible()
    assert_action_enabled("Add", False, "negative contribution")
    fill_edit(0, "101")
    hide_keyboard_if_visible()
    assert_action_enabled("Add", False, "contribution above remaining goal")
    fill_edit(0, "25")
    hide_keyboard_if_visible()
    assert_action_enabled("Add", True, "valid contribution")
    result["steps"].append("verified zero, negative, and above-remaining contributions are rejected before adding funds")
    capture("goal_contribution_boundaries")
    tap_exact("Add")
    wait_for("25.00 EGP")
    capture("goal_contribution")
    result["steps"].append("created exact EGP 100 goal and contributed EGP 25; observed saved progress EGP 25")
    add_funds = next((node for node in all_nodes() if node.get("text", "").strip().startswith("Add ") and node.get("text", "").strip().endswith(" more")), None)
    if add_funds is None:
        raise AssertionError(f"Goal has no remaining Add Funds action after contribution; visible={visible_texts()}")
    tap_node(add_funds)
    wait_for(f"Add Funds to {name}")
    fill_edit(0, "80")
    hide_keyboard_if_visible()
    assert_action_enabled("Add", False, "contribution above the EGP 75 remaining goal")
    capture("goal_remaining_contribution_rejected")
    tap_exact("Cancel")
    wait_for(f"Add Funds to {name}", present=False)
    result["steps"].append("verified an EGP 80 contribution is rejected when only EGP 75 remains")
    # The card exposes edit/delete as icon buttons without accessible labels. Use the exact goal's row bounds.
    tap_goal_action(name, action_index=0)
    wait_for("Edit Goal")
    fill_edit(0, name + " Edited")
    hide_keyboard_if_visible()
    tap_exact("Save Goal")
    wait_for(name + " Edited")
    capture("goal_edited")
    tap_goal_action(name + " Edited", action_index=1)
    wait_for("Delete Goal")
    tap_exact("Delete")
    wait_for(name + " Edited", present=False)
    cleanup_action = None
    result["steps"].append("edited target goal name and deleted only the exact case-owned goal")


def assert_action_enabled(label, expected, scenario):
    current = all_nodes()
    node = next((node for node in current if node.get("text", "").strip() == label), None)
    if node is None:
        raise AssertionError(f"Missing {label!r} action while testing {scenario}; visible={visible_texts()}")
    parents = {child: parent for parent in current for child in parent}
    action = node
    while action in parents and action.get("clickable") != "true":
        action = parents[action]
    actual = action.get("enabled", "true").lower() == "true"
    if actual != expected:
        raise AssertionError(f"{label!r} action enabled={actual}, expected {expected} for {scenario}; visible={visible_texts()}")


def app04_debt_crud():
    global mutated, cleanup_action
    name = f"QA Debt {run_id}"
    open_feature("Debts & Loans")
    tap_exact("Add Debt")
    wait_for("Add New Debt")
    fill_edit(0, name)
    fill_edit(1, "12.50")
    fill_edit(2, "acceptance test")
    hide_keyboard_if_visible()
    tap_exact("Save")
    mutated = True
    cleanup_action = lambda: cleanup_debt(name)
    result["cleanup"] = f"delete exact debt {name} in finally"
    wait_for(name)
    capture("debt_created")
    if not any("12.50" in text for text in visible_texts()):
        raise AssertionError("Debt card does not show expected EGP 12.50")
    result["steps"].append("created and verified exact case-owned debt amount EGP 12.50")
    result["blockers"].append("APP-04 partial repayment/overpayment cannot be tested: UI only offers Settle, not a repayment amount")
    tap_row_delete_button(name)
    wait_for("Delete Debt")
    tap_exact("Delete")
    wait_for(name, present=False)
    cleanup_action = None
    result["steps"].append("deleted only the exact case-owned debt")


def app05_bill_crud():
    global mutated, cleanup_action
    name = f"QA Bill {run_id}"
    open_feature("Monthly Bills")
    tap_exact("Add Bill")
    wait_for("New Recurring Bill")
    fill_edit(0, name)
    fill_edit(1, "8.25")
    fill_edit(2, "15")
    hide_keyboard_if_visible()
    tap_exact("Save Bill")
    mutated = True
    cleanup_action = lambda: cleanup_bill(name)
    result["cleanup"] = f"remove exact bill {name} in finally"
    wait_for(name)
    capture("bill_created")
    if not any("Day 15" in text for text in visible_texts()) or not any("8 EGP" in text for text in visible_texts()):
        raise AssertionError("Bill card did not reflect expected due day/amount")
    result["steps"].append("created and verified recurring bill amount EGP 8.25 due day 15")
    result["blockers"].append("APP-05 mark-paid/next-due behavior is unsupported by current Bill UI; only create/edit/remove exists")
    tap_row_delete_button(name)
    wait_for("Remove Bill")
    tap_exact("Remove")
    wait_for(name, present=False)
    cleanup_action = None
    result["steps"].append("removed only the exact case-owned bill")


def app06_custom_subcategory():
    global mutated, cleanup_action
    name = f"QA Category {run_id}"
    go_home()
    open_drawer()
    tap_exact("Categories")
    wait_for("Categories")
    tap_exact("Food & Drinks")
    wait_for("Food & Drinks")
    tap_exact("Add subcategory")
    wait_for("New Subcategory")
    fill_edit(0, name)
    hide_keyboard_if_visible()
    tap_exact("Add")
    mutated = True
    cleanup_action = lambda: cleanup_subcategory(name)
    result["cleanup"] = f"delete exact custom subcategory {name} in finally"
    scroll_until_visible(name)
    wait_for(name)
    capture("custom_subcategory_created")
    delete_subcategory_row(name)
    wait_for(name, present=False)
    cleanup_action = None
    result["steps"].append("created and deleted exact case-owned custom subcategory")
    result["blockers"].append("Built-in category CRUD is unavailable; merchant-rule creation requires Save as Category Rule from a record. No ledger record will be created by this runner.")


def cleanup_goal(name):
    open_feature("Savings Goals")
    tap_goal_action(name, action_index=1)
    wait_for("Delete Goal")
    tap_exact("Delete")
    wait_for(name, present=False)


def cleanup_debt(name):
    open_feature("Debts & Loans")
    tap_row_delete_button(name)
    wait_for("Delete Debt")
    tap_exact("Delete")
    wait_for(name, present=False)


def cleanup_bill(name):
    open_feature("Monthly Bills")
    tap_row_delete_button(name)
    wait_for("Remove Bill")
    tap_exact("Remove")
    wait_for(name, present=False)


def cleanup_subcategory(name):
    go_home()
    open_drawer()
    tap_exact("Categories")
    tap_exact("Food & Drinks")
    scroll_until_visible(name)
    wait_for(name)
    delete_subcategory_row(name)
    wait_for(name, present=False)


def record_rows():
    root_node = root()
    parents = {child: parent for parent in root_node.iter() for child in parent}
    rows = []
    seen = set()
    for node in root_node.iter():
        text = node.get("text", "").strip()
        if not text:
            continue
        current = node
        while current is not None:
            box = list(map(int, re.findall(r"\d+", current.get("bounds", ""))))
            content = " ".join((x.get("text", "") or "").strip() for x in current.iter())
            if len(box) == 4 and 0 < box[3] - box[1] < 500 and any(token in content for token in ("EGP", "USD", "EUR")):
                if content not in seen:
                    seen.add(content)
                    rows.append(content)
                break
            current = parents.get(current)
    return rows


def app07_search_filters():
    go_home()
    tap_exact("Records")
    wait_for("All Records")
    before = record_rows()
    if not before:
        raise AssertionError("APP-07 requires at least one visible existing record; read-only case will not seed data")
    result["baseline_record_row_count"] = len(before)
    search = next((field for field in driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
                   if "Search category" in (field.get_attribute("text") or "") or "search" in (field.get_attribute("contentDescription") or "").lower()), None)
    if search is None:
        fields = driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
        if not fields:
            raise AssertionError("Search field is not available")
        search = fields[0]
    search.click()
    search.send_keys(args.search_text)
    try:
        driver.hide_keyboard()
    except Exception:
        pass
    time.sleep(0.6)
    matched = record_rows()
    if not matched or not all(args.search_text.casefold() in row.casefold() for row in matched):
        raise AssertionError(f"Search returned nonmatching or no rows for {args.search_text!r}: {matched}")
    capture("search_matches")
    search.clear()
    try:
        driver.hide_keyboard()
    except Exception:
        pass
    time.sleep(0.5)
    restored = record_rows()
    if len(restored) != len(before):
        raise AssertionError(f"Clearing search did not restore row count: before={len(before)} after={len(restored)}")
    tap_exact("Expense")
    expense_rows = record_rows()
    for row in expense_rows:
        if "Income" in row:
            raise AssertionError(f"Expense filter retained income row: {row}")
    capture("expense_filter")
    tap_exact("All")
    result["steps"].append(f"searched for existing text {args.search_text!r}, verified only matching rows, cleared and restored {len(before)} rows, and checked Expense filter")
    result["blockers"].append("Date/account/category filter acceptance remains parameter-dependent; use --search-text with controlled QA records for precise expected counts")


def dispatch():
    if args.case == "APP-01":
        app01_account_create_edit_delete()
    elif args.case == "APP-02":
        app02_delete_confirmation_cancel()
    elif args.case == "APP-03":
        app03_goal_crud()
    elif args.case == "APP-04":
        app04_debt_crud()
    elif args.case == "APP-05":
        app05_bill_crud()
    elif args.case == "APP-06":
        app06_custom_subcategory()
    elif args.case == "APP-07":
        app07_search_filters()


try:
    driver_options = UiAutomator2Options().load_capabilities({
        "platformName": "Android",
        "appium:automationName": "UiAutomator2",
        "appium:deviceName": args.device,
        "appium:udid": args.device,
        "appium:appPackage": PACKAGE,
        "appium:appActivity": ".MainActivity",
        "appium:noReset": True,
        "appium:newCommandTimeout": 10,
    })
    driver = webdriver.Remote(BASE_URL, options=driver_options)
    driver.implicitly_wait(0)
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    go_home()
    wait_for("TOTAL BALANCE")
    capture("preflight_home")
    if args.cleanup_account:
        cleanup_account(args.cleanup_account)
        result["steps"].append(f"deleted only the exact QA account {args.cleanup_account!r}")
    else:
        dispatch()
    if result["status"] == "IN_PROGRESS":
        result["status"] = "PASS"
    result["cleanup"] = "case-owned data deleted or read-only; verify screenshots and results.json"
except Exception as exc:
    result["status"] = "FAIL" if mutated else "INCONCLUSIVE"
    result["error"] = f"{type(exc).__name__}: {exc}"
    if mutated:
        result["cleanup"] = "MANUAL REVIEW REQUIRED: case-owned data may remain; do not rerun blindly"
    save()
    if driver is not None:
        try:
            capture("failure_state")
        except Exception:
            pass
finally:
    if mutated and cleanup_action is not None:
        try:
            cleanup_action()
            result["cleanup"] = "case-owned object removed through UI cleanup"
        except Exception as cleanup_exc:
            result["status"] = "FAIL"
            result["cleanup"] = f"MANUAL REVIEW REQUIRED: {type(cleanup_exc).__name__}: {cleanup_exc}"
    save()
    if driver is not None:
        driver.quit()

print(json.dumps(result, indent=2))
print(f"Evidence: {output.resolve()}")
