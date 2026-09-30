import argparse
import json
import re
import subprocess
import time
from datetime import datetime, timezone
from decimal import Decimal

from device_user_isolation_case import new_credentials
from qa_ai_fixture import AiFixture
from qa_campaign_support import Device, FirebaseUser, PACKAGE, ROOT


def prepare_qa_permissions(serial):
    avd = subprocess.check_output(["adb", "-s", serial, "emu", "avd", "name"], timeout=10).decode().splitlines()[0]
    if avd not in {"Wallet_23Cases_Temp", "Wallet_Onboarding_QA"}:
        raise AssertionError(f"Refusing AI SMS campaign on non-QA AVD: {avd}")
    for permission in ("android.permission.RECEIVE_SMS", "android.permission.READ_SMS", "android.permission.POST_NOTIFICATIONS"):
        subprocess.run(["adb", "-s", serial, "shell", "pm", "grant", PACKAGE, permission], check=True, timeout=10)
    sdk = int(subprocess.check_output(["adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk"], timeout=10))
    if sdk >= 37:
        subprocess.run(["adb", "-s", serial, "shell", "appops", "set", PACKAGE, "READ_RESTRICTED_MESSAGES", "allow"], check=True, timeout=10)
    subprocess.run(["adb", "-s", serial, "shell", "appops", "set", "com.android.shell", "WRITE_SMS", "allow"], check=True, timeout=10)


def run_ai06_case(ui, user, server, result):
    accounts = [
        ("ai-fixture-account", "AI Fixture HSBC", "Debit", "1111", "1000.00", None),
        ("ai-fixture-source", "AI Fixture Source", "Debit", "2222", "500.00", None),
        ("ai-fixture-cash", "AI Fixture Cash", "Cash", "", "100.00", None),
        ("ai-fixture-card", "AI Fixture Card", "Credit Card", "3333", "100.00", "1000.00"),
    ]
    for account_id, name, account_type, suffix, amount, limit in accounts:
        data = {"name": name, "accountType": account_type, "last4Digits": suffix, "amount": amount,
                "currency": "EGP", "color": 4283215696}
        if limit:
            data.update({"creditLimit": float(limit), "billingDay": 15})
        user.put("accounts", account_id, data)
    result["seeded_accounts"] = user.documents("accounts")
    assert {account["id"] for account in result["seeded_accounts"]} == {account[0] for account in accounts}, "Firebase fixture accounts were not all persisted"
    ui.wait_text("AI Fixture HSBC")
    ui.wait(lambda: "1,600.00" in ui.texts(), "initialized dashboard total")
    messages = [
        ("HSBC", "Your bank account ****1111 cash withdrawal EGP 10.00 at ATM. Available balance EGP 990.00. QA_AI06_ATM"),
        ("HSBC", "Your credit card ****3333 statement is ready. Total amount due EGP 20.00. Minimum payment EGP 5.00. Due Date 10/10/2026. QA_AI06_STATEMENT"),
        ("HSBC", "EGP 20.00 payment received for credit card ****3333 has been credited. Available credit EGP 120.00. QA_AI06_CREDIT"),
        ("HSBC", "Your bank account ****2222 was debited EGP 20.00 for credit card payment to card ****3333. Available balance EGP 480.00. QA_AI06_DEBIT"),
    ]
    result["expected"] = {
        "provider_requests": 0,
        "records": 2,
        "atm": {"source_balance": "990.00", "cash_balance": "110.00"},
        "card_payment": {"source_balance": "480.00", "card_balance": "120.00", "linked": True},
        "statement": {"totalAmount": 20.0, "removedAfterPayment": True},
    }

    def account_amount(account_id):
        return Decimal(next(account["amount"] for account in user.documents("accounts") if account["id"] == account_id))

    def wait_until(description, predicate):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.25)
        raise AssertionError(f"Timed out waiting for {description}")

    for index, (sender, body) in enumerate(messages):
        ui.adb("emu", "sms", "send", sender, body)
        if index == 0:
            wait_until("ATM transfer and balance updates", lambda: len(user.documents("records")) == 1 and account_amount("ai-fixture-account") == Decimal("990.00") and account_amount("ai-fixture-cash") == Decimal("110.00"))
        elif index == 1:
            wait_until("credit statement creation", lambda: len(user.documents("creditStatements")) == 1)
            statement = user.documents("creditStatements")[0]
            assert statement["accountId"] == "ai-fixture-card" and float(statement["totalAmount"]) == 20.0, f"Incorrect statement: {statement}"
        elif index == 2:
            wait_until("credit-side payment record", lambda: len(user.documents("records")) == 2 and account_amount("ai-fixture-card") == Decimal("120.00"))
        else:
            try:
                wait_until("debit-side payment linkage and paid-statement cleanup", lambda: len(user.documents("records")) == 2 and account_amount("ai-fixture-source") == Decimal("480.00") and not user.documents("creditStatements"))
            except AssertionError as error:
                raise AssertionError(f"{error}; records={user.documents('records')}; accounts={user.documents('accounts')}; statements={user.documents('creditStatements')}") from error
        assert server.requests == [], f"Deterministic SMS handler unexpectedly called AI: {server.requests}"

    records = user.documents("records")
    atm_record = next((record for record in records if record.get("comment") == "ATM Withdrawal"), None)
    payment_record = next((record for record in records if record.get("category") == "Credit Payment"), None)
    assert atm_record and atm_record["category"] == "Transfer" and Decimal(atm_record["amount"]) == Decimal("10.00"), f"Incorrect ATM row: {records}"
    assert Decimal(atm_record["balanceBefore"]) == Decimal("1000.00") and Decimal(atm_record["balanceAfter"]) == Decimal("990.00"), f"ATM balance snapshots are wrong: {atm_record}"
    assert payment_record and "->" in payment_record["accountName"] and Decimal(payment_record["amount"]) == Decimal("20.00"), f"Incorrect payment row: {records}"
    assert account_amount("ai-fixture-account") == Decimal("990.00")
    assert account_amount("ai-fixture-cash") == Decimal("110.00")
    assert account_amount("ai-fixture-source") == Decimal("480.00")
    assert account_amount("ai-fixture-card") == Decimal("120.00")
    assert not user.documents("creditStatements"), f"Paid statement or reminder record remained: {user.documents('creditStatements')}"
    server.verify([], [])
    ui.wait_text("TOTAL BALANCE")
    ui.wait(lambda: "1,580.00" in ui.texts(), "dashboard total after card payment")
    ui.driver.terminate_app(PACKAGE)
    ui.driver.activate_app(PACKAGE)
    ui.wait_text("TOTAL BALANCE")
    assert len(user.documents("records")) == 2 and server.requests == [], "Deterministic records or no-AI assertion changed after restart"
    result["actual"] = {"records": records, "accounts": user.documents("accounts"), "creditStatements": user.documents("creditStatements")}
    result["steps"] = [
        "Injected a real ATM SMS and verified deterministic transfer plus Main/Cash balance changes",
        "Injected a real statement SMS and verified amount, due-card link, and subsequent paid state",
        "Injected both credit-payment SMS legs and verified one linked payment with exact source/card balance deltas",
        "Asserted zero controlled AI provider requests for all four special-type SMS and after restart",
    ]
    result["status"] = "PASS"


def run_case(ui, case_id):
    parent = ui.output
    ui.output = parent / case_id
    ui.output.mkdir(parents=True, exist_ok=True)
    credentials = new_credentials(case_id.lower())
    (ui.output / "credentials.json").write_text(json.dumps(credentials), encoding="utf-8")
    result = {"case": case_id, "status": "IN_PROGRESS", "steps": [], "cleanup": {}, "device": ui.serial,
              "environment": "Real debug app/Firebase/SMS receiver; controlled localhost AI dependency responses (not external provider certification)"}
    user = None
    server = None
    phase = "setup"
    sms_body = "HSBC account ****8181 debited EGP 0.10 at QA_UNCLASSIFIED_VENDOR. Available balance EGP 999.90"

    def save():
        (ui.output / "results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")

    print(f"START {case_id}", flush=True)
    save()
    try:
        ui.seed_sms([])
        ui.signup(credentials)
        user = FirebaseUser(credentials)
        ui.finish_intro()
        assert user.documents("accounts") == [] and user.documents("records") == [], "Identity is not empty"
        if case_id != "AI-06":
            user.put("accounts", "ai-fixture-account", {"name": "AI Fixture HSBC", "accountType": "Debit", "last4Digits": "8181", "amount": "1000.00", "currency": "EGP", "color": 4283215696})
        if case_id == "AI-01":
            user.put("categoryRules", "qa-merchant-rule", {"merchantKeyword": "QA_UNCLASSIFIED_VENDOR", "category": "Groceries"})
        if case_id != "AI-06":
            ui.wait_text("AI Fixture HSBC")
        expected_category = "Groceries" if case_id == "AI-01" else "Others" if case_id == "AI-04" else "Shopping"
        failures = () if case_id in {"AI-01", "AI-05", "AI-06"} else ("groq",) if case_id == "AI-02" else ("groq", "gemini") if case_id == "AI-03" else ("groq", "gemini", "cerebras")
        expected_providers = ["groq"] if case_id == "AI-01" else ["groq", "gemini"] if case_id == "AI-02" else ["groq", "gemini", "cerebras"] * (2 if case_id == "AI-04" else 1)
        if case_id == "AI-06":
            expected_category = "Transfer"
            expected_providers = []
        ai05_messages = [
            "HSBC account notification QA_AI05_REJECT without transaction details",
            "HSBC account debit confirmation; amount is one tenth of an Egyptian pound; QA_AI05_ACCEPT",
        ]
        ai05_responses = {
            "QA_AI05_REJECT": json.dumps({"amount": "999.99", "category": "Shopping", "type": "Expense", "isBankRelated": False}),
            "QA_AI05_ACCEPT": json.dumps({"amount": "0.10", "category": "Groceries", "type": "Expense", "isBankRelated": True, "last4Digits": "8181", "comment": "AI fixture extraction"}),
        }
        server = AiFixture(ui, "Restaurants" if case_id == "AI-01" else "Shopping", failures,
                           ai05_responses if case_id == "AI-05" else None)
        server.start()
        if case_id == "AI-06":
            phase = "live_sms"
            run_ai06_case(ui, user, server, result)
        else:
            if case_id == "AI-05":
                expected_category = "Groceries"
                expected_providers = ["groq", "groq"]
                result["expected"] = {"rejected_sms": "no record and unchanged balance", "accepted_sms": {"amount": "0.10", "category": expected_category, "balance": "999.90"}, "providers": expected_providers}
            else:
                result["expected"] = {"amount": "0.10", "category": expected_category, "balance": "999.90", "providers": expected_providers}
            phase = "live_sms"
            if case_id == "AI-05":
                ui.adb("emu", "sms", "send", "HSBC", ai05_messages[0])
                ui.wait(lambda: len(server.requests) == 1, "unknown-format rejection reached controlled AI provider")
                time.sleep(1)
                assert user.documents("records") == [], "AI extraction marked non-bank input as bank-related"
                assert Decimal(user.documents("accounts")[0]["amount"]) == Decimal("1000.00"), "Rejected extraction changed account balance"
                ui.adb("emu", "sms", "send", "HSBC", ai05_messages[1])
                sms_body = ai05_messages[1]
            else:
                ui.adb("emu", "sms", "send", "HSBC", sms_body)
            ui.wait(lambda: len(user.documents("records")) == 1, "one real SMS record")
            ui.wait(lambda: Decimal(user.documents("accounts")[0]["amount"]) == Decimal("999.90"), "SMS account delta")
            records = user.documents("records")
            record = records[0]
            result["actual"] = {"records": records, "accounts": user.documents("accounts")}
            assert Decimal(record["amount"]) == Decimal("0.10") and record["type"] == "Expense", f"Wrong amount/type: {record}"
            assert record["category"] == expected_category, f"Wrong category: {record}"
            assert record["accountId"] == "ai-fixture-account", f"Wrong account: {record}"
            if case_id == "AI-05":
                assert Decimal(record["balanceBefore"]) == Decimal("1000.00"), f"Missing/wrong pre-transaction balance: {record}"
            expected_sms = ai05_messages if case_id == "AI-05" else [sms_body] * len(expected_providers)
            server.verify(expected_providers, expected_sms)
            ui.wait(lambda: "999.90" in ui.texts(), "dashboard exact total")
            ui.capture("dashboard_after_sms")
            ui.tap("Records", description=True)
            ui.wait_text("All Records")
            ui.wait_text(expected_category)
            assert "0.10" in " ".join(ui.texts()), "Amount missing from Records UI"
            ui.capture("record_after_sms")
            ui.home()
            ui.driver.terminate_app(PACKAGE)
            ui.driver.activate_app(PACKAGE)
            ui.wait_text("TOTAL BALANCE")
            assert len(user.documents("records")) == 1, "Duplicate record after restart"
            assert Decimal(user.documents("accounts")[0]["amount"]) == Decimal("999.90"), "Balance changed after restart"
            ui.wait(lambda: "999.90" in ui.texts(), "persisted dashboard")
            ui.capture("dashboard_after_restart")
            result["steps"] += ["Registered a fresh UI identity; initialized EGP 1000.00 account and optional merchant rule",
                                "Rejected an AI extraction with isBankRelated=false without a record or balance change; accepted a bank-related unknown-format extraction with exact amount/category/account/balance" if case_id == "AI-05" else "Injected one real emulator SMS; verified amount 0.10, category, account link and balance 999.90 in backend and UI",
                                "Asserted exact controlled provider fallback sequence and only dummy API credentials",
                                "Restarted app; verified one record and unchanged balance"]
            result["status"] = "PASS"
    except (Exception, KeyboardInterrupt) as error:
        result["status"] = "ERROR" if phase == "setup" else "FAIL"
        result["error"] = f"{type(error).__name__}: {error}"
        if case_id == "AI-06":
            result["repository_errors"] = ui.adb("logcat", "-d", "-s", "FirebaseRepository:E", "HomeViewModel:E")[-12000:]
            result["sms_logs"] = ui.adb("logcat", "-d", "-s", "SmsReceiver:D", "SmsParser:D", "AiService:W")[-18000:]
        save()
        ui.capture("failure")
    finally:
        if server:
            result["provider_requests"] = server.requests
            server.close()
            result["cleanup"]["ai_override"] = "REMOVED_AND_SERVER_STOPPED"
        if user:
            try:
                ui.home()
                ui.delete_user(credentials)
                for collection in ("accounts", "records", "categoryRules", "creditStatements"):
                    assert not user.documents(collection), f"Retained {collection} after deletion"
                try:
                    FirebaseUser(credentials)
                except RuntimeError as error:
                    assert str(error) in {"INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "USER_NOT_FOUND"}, str(error)
                else:
                    raise AssertionError("Deleted identity still authenticates")
                result["cleanup"]["identity"] = "DELETED_AND_DATA_VERIFIED_EMPTY"
            except Exception as error:
                result["cleanup"]["identity_ui_warning"] = str(error).splitlines()[0]
                try:
                    user.delete_test_identity(("accounts", "records", "categoryRules", "creditStatements"))
                    try:
                        FirebaseUser(credentials)
                    except RuntimeError as auth_error:
                        if str(auth_error) not in {"INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "USER_NOT_FOUND"}:
                            raise
                    else:
                        raise AssertionError("Disposable Firebase identity still authenticates after fallback deletion")
                    result["cleanup"]["identity"] = "DELETED_AND_VERIFIED_BY_FIREBASE_REST_FALLBACK"
                except Exception as fallback_error:
                    result["cleanup"]["identity"] = f"FAILED: {fallback_error}"
                    result["cleanup"]["identity_fallback"] = f"FAILED: {fallback_error}"
                    result["status"] = "ERROR"
        try:
            ids = re.findall(r"_id=(\d+)", ui.adb("shell", "content", "query", "--uri", "content://sms", "--projection", "_id"))
            ui.delete_sms(ids)
            result["cleanup"]["sms"] = "DELETED_AND_VERIFIED"
        except Exception as error:
            result["cleanup"]["sms"] = f"FAILED: {error}"
            result["status"] = "ERROR"
        result["finished_at"] = datetime.now(timezone.utc).isoformat()
        save()
        ui.output = parent
    print(f"END {case_id}: {result['status']} {result.get('error', '')}", flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description="AI priority/fallback real-app acceptance cases with deterministic provider fault injection")
    parser.add_argument("--device", default="emulator-5558")
    parser.add_argument("--cases", nargs="+", choices=["AI-01", "AI-02", "AI-03", "AI-04", "AI-05", "AI-06"], default=["AI-01", "AI-02", "AI-03", "AI-04", "AI-05", "AI-06"])
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        parser.error("Pass --execute to create and delete disposable identities and inject QA SMS")
    prepare_qa_permissions(args.device)
    ui = Device(args.device, ROOT / "app/build/device-smoke" / ("ai-campaign-" + time.strftime("%Y%m%d-%H%M%S")))
    results = []
    try:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and "Authentication" not in ui.texts():
            texts = ui.texts()
            permission_prompt = next((prompt for prompt in (
                "Allow WalletTrackers to send you notifications?",
                "Allow WalletTrackers to send and view SMS messages?",
            ) if prompt in texts), None)
            if permission_prompt:
                ui.tap("Allow")
            else:
                time.sleep(0.2)
        ui.wait_text("Authentication")
        for case_id in args.cases:
            result = run_case(ui, case_id)
            results.append(result)
            if result["status"] == "ERROR":
                break
    finally:
        ui.restore_fixture_access()
        ui.driver.quit()
    raise SystemExit(0 if all(result["status"] == "PASS" for result in results) else 1)


if __name__ == "__main__":
    main()
