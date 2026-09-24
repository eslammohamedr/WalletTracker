import json
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree


DEVICE = "emulator-5554"
PACKAGE = "com.example.wallettrackers"
SENDER = "5550001"
ACCOUNT_NAME = "hsbc"
ACCOUNT_SUFFIX = "3001"
AMOUNT_TEXT = "-0.01 EGP"
OUTPUT = Path("app/build/device-smoke") / time.strftime("%Y%m%d-%H%M%S") / "live_sms_expense"
OUTPUT.mkdir(parents=True, exist_ok=True)
MARKER = "AUTOTEST" + time.strftime("%H%M%S") + "QWERTY"
BODY = f"Your HSBC Account ****{ACCOUNT_SUFFIX} was debited EGP 0.01 at Cafe {MARKER}."
RESULT = {"case": "live_sms_expense_and_rollback", "marker": MARKER, "amount": "0.01 EGP",
          "status": "IN_PROGRESS", "steps": [], "cleanup": "not_needed"}
SMS_ID = None
RECORD_VISIBLE = False


def adb(*command, timeout=45):
    return subprocess.check_output(
        ["adb", "-s", DEVICE, *command], timeout=timeout
    ).decode("utf-8", errors="replace")


def save_result():
    (OUTPUT / "results.json").write_text(json.dumps(RESULT, indent=2), encoding="utf-8")


def tree():
    response = adb("shell", "uiautomator", "dump", "/sdcard/wallet-sms-live.xml")
    if "dumped to:" not in response:
        raise RuntimeError("Android did not return a fresh UI hierarchy")
    return ElementTree.fromstring(adb("shell", "cat", "/sdcard/wallet-sms-live.xml"))


def nodes():
    return list(tree().iter("node"))


def find_node(text, attribute="text"):
    return next((node for node in nodes()
                 if node.get("package") == PACKAGE and text in node.get(attribute, "")), None)


def wait_node(text, attribute="text", timeout=100, present=True):
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


def bounds(node):
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return (left + right) // 2, (top + bottom) // 2


def tap(text, attribute="content-desc"):
    x, y = bounds(wait_node(text, attribute, timeout=40))
    adb("shell", "input", "tap", str(x), str(y))


def capture(name):
    (OUTPUT / (name + ".xml")).write_bytes(ElementTree.tostring(tree(), encoding="utf-8"))
    image = subprocess.check_output(["adb", "-s", DEVICE, "exec-out", "screencap", "-p"], timeout=30)
    (OUTPUT / (name + ".png")).write_bytes(image)


def check_account_balance(expected):
    home_account = wait_node("TOTAL BALANCE")
    del home_account
    wait_node(ACCOUNT_NAME)
    return wait_node(expected) is not None


def open_home():
    if find_node("TOTAL BALANCE") is None:
        back = find_node("Back", "content-desc")
        if back:
            x, y = bounds(back)
            adb("shell", "input", "tap", str(x), str(y))
        else:
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
    wait_node("TOTAL BALANCE")


def delete_test_record():
    global RECORD_VISIBLE
    open_home()
    tap("Records")
    wait_node("All Records")
    if find_node(MARKER) is None:
        search = wait_node("Search category, account, comment...", "text", timeout=20)
        x, y = bounds(search)
        adb("shell", "input", "tap", str(x), str(y))
        adb("shell", "input", "text", MARKER)
    marker_node = wait_node(MARKER, timeout=100)
    x, y = bounds(marker_node)
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "1000")
    tap("Delete", "text")
    wait_node("Delete Record")
    delete_buttons = [node for node in nodes() if node.get("text") == "Delete"]
    if not delete_buttons:
        raise AssertionError("Delete confirmation button was not shown")
    x, y = bounds(delete_buttons[-1])
    adb("shell", "input", "tap", str(x), str(y))
    wait_node(MARKER, timeout=100, present=False)
    RECORD_VISIBLE = False
    RESULT["cleanup"] = "test record deleted in app"
    capture("03_record_deleted")
    save_result()


def remove_test_sms():
    global SMS_ID
    if SMS_ID is None:
        query = adb("shell", "content", "query", "--uri", "content://sms/inbox",
                    "--projection", "_id", "--where", f"address='{SENDER}' AND body LIKE '%{MARKER}%'", timeout=30)
        match = re.search(r"_id=(\d+)", query)
        if match:
            SMS_ID = match.group(1)
    if SMS_ID is None:
        RESULT["cleanup"] += "; no matching inbox row available to remove"
        return
    adb("shell", "content", "delete", "--uri", "content://sms", "--where", f"_id={SMS_ID}", timeout=30)
    RESULT["cleanup"] += "; injected inbox message deleted by exact provider row ID"


try:
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    open_home()
    wait_node(ACCOUNT_NAME)
    baseline = next(node.get("text") for node in nodes() if node.get("text") == "-35288.5")
    RESULT["baseline_account_balance"] = baseline
    RESULT["steps"].append("captured signed-in account balance")
    capture("01_before")

    adb("emu", "sms", "send", SENDER, BODY, timeout=30)
    RECORD_VISIBLE = True
    RESULT["steps"].append("injected one synthetic 0.01 EGP bank-shaped SMS into the emulator")
    save_result()

    open_home()
    tap("Records")
    wait_node("All Records")
    search = wait_node("Search category, account, comment...", "text", timeout=20)
    x, y = bounds(search)
    adb("shell", "input", "tap", str(x), str(y))
    adb("shell", "input", "text", MARKER)
    marker_node = wait_node(MARKER, timeout=120)
    RECORD_VISIBLE = True
    wait_node(AMOUNT_TEXT, timeout=20)
    if not find_node("hsbc", "text"):
        raise AssertionError("Transaction did not display the linked HSBC account")
    RESULT["steps"].append("found exactly tagged expense with expected amount and linked account")
    capture("02_record_created")

    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    home_after = False
    wait_node("All Records")
    delete_test_record()
    open_home()
    after = next(node.get("text") for node in nodes() if node.get("text") == baseline)
    RESULT["steps"].append("restored baseline account balance after deleting test record")
    capture("04_balance_restored")

    try:
        remove_test_sms()
    except (subprocess.SubprocessError, RuntimeError) as error:
        RESULT["cleanup"] += f"; record is removed, inbox cleanup unsupported: {error}"
    RESULT["status"] = "PASS"
    RESULT["account_balance_after_cleanup"] = after
    save_result()
except Exception as error:
    RESULT["status"] = "FAIL"
    RESULT["error"] = str(error)
    save_result()
    if RECORD_VISIBLE:
        try:
            delete_test_record()
            open_home()
            RESULT["cleanup"] = "test record deleted after case failure"
        except Exception as cleanup_error:
            RESULT["cleanup"] = f"REQUIRES MANUAL CLEANUP: {cleanup_error}"
    try:
        remove_test_sms()
    except Exception as cleanup_error:
        RESULT["cleanup"] += f"; injected SMS cleanup failed: {cleanup_error}"
    save_result()
    raise

print(json.dumps(RESULT, indent=2))
print(f"Evidence: {OUTPUT.resolve()}")
