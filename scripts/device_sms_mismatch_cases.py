import argparse
import json
import os
import re
import secrets
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from device_sms_export_replay import COLLECTIONS, PACKAGE, clear_inbox, prepare_qa_permissions
from qa_ai_fixture import AiFixture
from qa_campaign_support import Device, FirebaseUser, ROOT
from sms_category_suite import read_messages


parser = argparse.ArgumentParser(description="Run focused live regressions for known SMS output mismatches")
parser.add_argument("--device", default="emulator-5558")
parser.add_argument("--server", default="http://127.0.0.1:4724")
args = parser.parse_args()

output = ROOT / "app/build/device-smoke" / ("sms-mismatch-cases-" + time.strftime("%Y%m%d-%H%M%S"))
output.mkdir(parents=True, exist_ok=False)
credentials = {"email": f"wallet.qa.smsmismatch.{time.time_ns()}@example.com", "password": secrets.token_urlsafe(20)}
result = {"case": "focused_sms_output_mismatches", "status": "IN_PROGRESS", "device": args.device,
          "started_at": datetime.now(timezone.utc).isoformat(), "tests": [], "cleanup": {}}
(output / "credentials.json").write_text(json.dumps(credentials), encoding="utf-8")

device = None
user = None
fixture = None


def save():
    (output / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


def send(sender, body):
    subprocess.run(["adb", "-s", args.device, "emu", "sms", "send", sender, body],
                   check=True, capture_output=True, text=True, timeout=15)
    time.sleep(4)


def wait_for(predicate, description):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.5)
    raise AssertionError(f"Timed out waiting for {description}")


def test_foreign_currency_charges():
    source = read_messages()
    for index, number in enumerate((1055, 960, 959, 958, 957, 956, 950, 948, 947, 946, 669, 589, 10), start=1):
        body = source[number]["body"]
        suffix = re.search(r"\*{2,}\s*(\d{3,4})", body).group(1)
        balance_match = re.search(r"(?i)available limit is\s*EGP\s*([\d,]+(?:\.\d+)?)", body)
        currency_match = re.search(r"(?i)\b(USD|EUR|SAR)\s+([\d,]+(?:\.\d+)?)", body)
        if balance_match is None or currency_match is None:
            raise AssertionError(f"SMS #{number} is missing foreign-charge or printed-limit data")
        ending_balance = float(balance_match.group(1).replace(",", ""))
        expected_amount = "123.45"
        starting_balance = ending_balance + float(expected_amount)
        account_name = f"HSBC Card {suffix}"
        before = len(user.documents("records"))
        baseline_sms = (f"Your Credit Card ending with *** {suffix} has been used for EGP 1.00 on 30/09/2026 "
                        f"at QA baseline {number}. Your available limit is EGP {starting_balance:.2f}")
        send("HSBC", baseline_sms)
        wait_for(lambda: str(next(row for row in user.documents("accounts") if row["name"] == account_name).get("amount"))
                 == f"{starting_balance:.2f}", f"{account_name} baseline for SMS #{number}")
        before_target = {row["id"] for row in user.documents("records")}
        send("HSBC", body)
        wait_for(lambda: any(row["id"] not in before_target for row in user.documents("records")),
                 f"foreign-currency card output for SMS #{number}")
        rows = [row for row in user.documents("records") if row["id"] not in before_target]
        record = rows[-1]
        accounts = {row["name"]: row for row in user.documents("accounts")}
        observed = {"amount": str(record.get("amount")), "currency": record.get("currency"),
                    "account": record.get("accountName"), "balance": str(accounts[account_name].get("amount"))}
        expected = {"amount": expected_amount, "currency": "EGP", "account": account_name,
                    "balance": f"{ending_balance:.2f}"}
        result["tests"].append({"id": f"FX-{index:02}", "source_sms_number": number,
                                "source_currency": currency_match.group(1).upper(),
                                "sender": "HSBC", "body": body, "expected": expected,
                                "actual": observed, "status": "PASS" if observed == expected else "FAIL"})
        save()


def test_banque_misr_payment_reconciliation():
    source_balance = 100000.0
    card_balance = 1000.0
    cases = ((6630.00, 6700.00), (10010.00, 10000.00), (1001.00, 1000.00),
             (1134.14, 1133.00), (11811.80, 11800.00))
    for index, (debit_amount, credit_amount) in enumerate(cases, start=1):
        source_balance -= debit_amount
        card_balance += credit_amount
        before_ids = {row["id"] for row in user.documents("records")}
        debit_sms = (f"From HSBC: Your account ****3001 transfer EGP {debit_amount:,.2f} to your Credit Card ending with 7000 "
                     f"as credit card payment. Available balance is EGP {source_balance:.2f} TESTBM{index}")
        credit_sms = (f"Deposit of EGP {credit_amount:.2f} was made to BM credit card ending ****7000 at BM-Online on 30/09/2026. "
                      f"Your current balance is EGP {card_balance:.2f}. To view transactions, visit bnkmsr.com/online. TESTBM{index}")
        send("HSBC", debit_sms)
        wait_for(lambda: any(row["id"] not in before_ids for row in user.documents("records")),
                 f"Banque Misr pair #{index} debit record")
        after_debit_ids = {row["id"] for row in user.documents("records")}
        send("Banque Misr", credit_sms)
        wait_for(lambda: any(row["category"] == "Credit Payment" and
                             row.get("accountName") == "HSBC Main -> Banque Misr Card 7000" and
                             row.get("transferDestinationAmount") == f"{credit_amount:.2f}"
                             for row in user.documents("records") if row["id"] not in before_ids),
                 f"Banque Misr pair #{index} reconciliation")
        records = user.documents("records")
        payment_rows = [row for row in records if row["id"] not in before_ids and
                        row.get("category") == "Credit Payment" and
                        row.get("accountName") == "HSBC Main -> Banque Misr Card 7000"]
        accounts = {row["name"]: row for row in user.documents("accounts")}
        actual = {"payment_record_count": len(payment_rows),
                  "source_debit_amount": str(payment_rows[-1].get("amount")) if payment_rows else None,
                  "card_credit_amount": str(payment_rows[-1].get("transferDestinationAmount")) if payment_rows else None,
                  "source_account_balance": str(accounts["HSBC Main"].get("amount")),
                  "card_balance": str(accounts["Banque Misr Card 7000"].get("amount")),
                  "records_after_credit_sms": len({row["id"] for row in records} - after_debit_ids)}
        expected = {"payment_record_count": 1, "source_debit_amount": f"{debit_amount:.2f}",
                    "card_credit_amount": f"{credit_amount:.2f}",
                    "source_account_balance": f"{source_balance:.2f}",
                    "card_balance": f"{card_balance:.2f}", "records_after_credit_sms": 0}
        result["tests"].append({"id": f"CC-BM-{index:02}", "send_order": "debit first, then Banque Misr credit",
                                "messages": [debit_sms, credit_sms], "expected": expected,
                                "actual": actual, "status": "PASS" if actual == expected else "FAIL"})
        save()


try:
    os.environ["APPIUM_SERVER_URL"] = args.server
    prepare_qa_permissions(args.device)
    if clear_inbox(args.device):
        raise AssertionError("Disposable QA inbox was not empty before focused tests")
    device = Device(args.device, output / "device")
    device.signup(credentials)
    user = FirebaseUser(credentials)
    for account_id, name, kind, suffix, amount, order in [
        ("fx-main", "HSBC Main", "Debit", "3001", "100000.00", 0),
        ("fx-card", "HSBC Card 2929", "Credit Card", "2929", "10000.00", 1),
        ("fx-card-2601", "HSBC Card 2601", "Credit Card", "2601", "10000.00", 2),
        ("bm-card", "Banque Misr Card 7000", "Credit Card", "7000", "1000.00", 3),
    ]:
        user.put("accounts", account_id, {"name": name, "accountType": kind, "last4Digits": suffix,
                                           "amount": amount, "currency": "EGP", "color": 0,
                                           "creditLimit": 20000.0 if kind == "Credit Card" else None,
                                           "billingDay": 15 if kind == "Credit Card" else None,
                                           "isArchived": False, "sortOrder": order})
    device.finish_intro()
    device.wait_text("HSBC Main")
    fixture = AiFixture(device, category="Subscriptions").start()
    test_foreign_currency_charges()
    test_banque_misr_payment_reconciliation()
    result["status"] = "PASS" if all(test["status"] == "PASS" for test in result["tests"]) else "FAIL"
except Exception as error:
    result["status"] = "FAIL"
    result["error"] = f"{type(error).__name__}: {error}"
finally:
    if fixture is not None:
        try:
            fixture.close()
        except Exception as error:
            result["cleanup"]["ai_fixture"] = str(error)
    if device is not None:
        try:
            device.adb("shell", "pm", "clear", PACKAGE)
            result["cleanup"]["app_data_cleared"] = True
        except Exception as error:
            result["cleanup"]["app_data"] = str(error)
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
    credential_file = output / "credentials.json"
    if credential_file.exists():
        credential_file.unlink()
    result["finished_at"] = datetime.now(timezone.utc).isoformat()
    save()
print(json.dumps({"status": result["status"], "tests": result["tests"], "cleanup": result["cleanup"],
                  "output": str(output)}, ensure_ascii=False))
