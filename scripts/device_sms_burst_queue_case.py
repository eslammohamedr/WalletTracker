import argparse
import json
import os
import secrets
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from device_sms_export_replay import COLLECTIONS, PACKAGE, clear_inbox, prepare_qa_permissions
from qa_campaign_support import Device, FirebaseUser, ROOT


parser = argparse.ArgumentParser(description="Verify rapid incoming SMS are queued without receiver ANRs or loss")
parser.add_argument("--device", default="emulator-5558")
parser.add_argument("--server", default="http://127.0.0.1:4724")
parser.add_argument("--count", type=int, default=40)
args = parser.parse_args()
if not 1 <= args.count <= 100:
    parser.error("--count must be between 1 and 100")

output = ROOT / "app/build/device-smoke" / ("sms-burst-queue-" + time.strftime("%Y%m%d-%H%M%S"))
output.mkdir(parents=True, exist_ok=False)
credentials = {"email": f"wallet.qa.smsburst.{time.time_ns()}@example.com", "password": secrets.token_urlsafe(20)}
result = {"case": "rapid_sms_work_queue_delivery", "device": args.device, "requested": args.count,
          "status": "IN_PROGRESS", "started_at": datetime.now(timezone.utc).isoformat(), "cleanup": {}}
device = None
user = None
(output / "credentials.json").write_text(json.dumps(credentials), encoding="utf-8")


def save():
    (output / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


def account_amount():
    account = next(row for row in user.documents("accounts") if row.get("name") == "HSBC Main")
    return str(account.get("amount"))


try:
    os.environ["APPIUM_SERVER_URL"] = args.server
    prepare_qa_permissions(args.device)
    if clear_inbox(args.device):
        raise AssertionError("Disposable QA Inbox was not empty before burst test")
    device = Device(args.device, output / "device")
    device.signup(credentials)
    user = FirebaseUser(credentials)
    user.put("accounts", "burst-hsbc-main", {"name": "HSBC Main", "accountType": "Debit",
                                               "last4Digits": "3001", "amount": "10000.00",
                                               "currency": "EGP", "color": 0, "isArchived": False,
                                               "sortOrder": 0})
    device.finish_intro()
    device.wait_text("HSBC Main")
    device.adb("logcat", "-c")
    sms_bodies = []
    for index in range(args.count):
        body = (f"Your HSBC Account ********3001 was debited with IPN outward transfer for EGP 1.00 "
                f"on 30-09-2026 12:{index:02d} to QA recipient {index:03d} with reference BURST{index:04d}.")
        sms_bodies.append(body)
        subprocess.run(["adb", "-s", args.device, "emu", "sms", "send", "HSBC", body],
                       check=True, capture_output=True, text=True, timeout=15)
    result["injected"] = len(sms_bodies)
    save()

    deadline = time.monotonic() + 180
    records = []
    while time.monotonic() < deadline:
        records = user.documents("records")
        if len(records) >= args.count:
            break
        time.sleep(2)
    account_final = account_amount()
    logcat = device.adb("logcat", "-d", "-v", "threadtime")
    (output / "logcat.txt").write_text(logcat, encoding="utf-8")
    anr_lines = [line for line in logcat.splitlines()
                 if ("ANR in com.example.wallettrackers" in line or
                     ("Reason: Broadcast of Intent" in line and "SmsReceiver" in line))]
    duplicate_ids = len(records) - len({row.get("id") for row in records})
    invalid_rows = [row for row in records if row.get("category") != "Instapay outcome" or
                    row.get("type") != "Expense" or row.get("accountName") != "HSBC Main" or
                    str(row.get("amount")) != "1.00" or str(row.get("currency")) != "EGP"]
    result.update({"record_count": len(records), "inbox_count": clear_inbox(args.device),
                   "account_balance": account_final, "duplicate_record_ids": duplicate_ids,
                   "invalid_record_count": len(invalid_rows), "anr_count": len(anr_lines),
                   "anr_evidence": anr_lines[:5]})
    expected_balance = f"{10000 - args.count:.2f}"
    result["expected"] = {"record_count": args.count, "account_balance": expected_balance,
                           "duplicate_record_ids": 0, "invalid_record_count": 0, "anr_count": 0}
    result["status"] = "PASS" if (len(records) == args.count and account_final == expected_balance and
                                   duplicate_ids == 0 and not invalid_rows and not anr_lines) else "FAIL"
except Exception as error:
    result["status"] = "FAIL"
    result["error"] = f"{type(error).__name__}: {error}"
    if device is not None:
        try:
            device.capture("failure")
            result["diagnostics"] = "failure.png and failure.xml captured"
        except Exception as capture_error:
            result["diagnostics"] = f"capture failed: {capture_error}"
        try:
            (output / "failure-logcat.txt").write_text(device.adb("logcat", "-d", "-v", "threadtime"), encoding="utf-8")
        except Exception:
            pass
finally:
    if device is not None:
        try:
            device.adb("shell", "pm", "clear", PACKAGE)
            result["cleanup"]["app_data_cleared"] = True
        except Exception as error:
            result["cleanup"]["app_data"] = str(error)
    if user is None:
        try:
            user = FirebaseUser(credentials)
            result["cleanup"]["identity_recovered_for_cleanup"] = True
        except Exception as error:
            result["cleanup"]["identity_recovery"] = str(error)
    if user is not None:
        try:
            result["cleanup"]["deleted_documents"] = user.delete_test_identity(COLLECTIONS)
            result["cleanup"]["identity_deleted"] = True
        except Exception as error:
            result["cleanup"]["identity"] = str(error)
    try:
        result["cleanup"]["sms_rows_deleted"] = clear_inbox(args.device)
    except Exception as error:
        result["cleanup"]["inbox"] = str(error)
    if device is not None:
        try:
            device.driver.quit()
        except Exception:
            pass
    result["finished_at"] = datetime.now(timezone.utc).isoformat()
    save()
    try:
        (output / "credentials.json").unlink()
    except OSError:
        pass
print(json.dumps(result, ensure_ascii=False))
