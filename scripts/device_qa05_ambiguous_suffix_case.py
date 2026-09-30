import json
import secrets
import subprocess
import sys
import time
from decimal import Decimal
from datetime import datetime, timezone
from pathlib import Path

from qa_campaign_support import Device, FirebaseUser, ROOT


def main():
    import argparse

    parser = argparse.ArgumentParser(description="QA-05 live same-suffix ambiguity test with a fresh disposable identity")
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        parser.error("Pass --execute to create/delete a disposable identity and inject one synthetic SMS")

    output = ROOT / "app/build/device-smoke" / ("qa05-ambiguous-suffix-" + time.strftime("%Y%m%d-%H%M%S"))
    output.mkdir(parents=True, exist_ok=True)
    credentials = {
        "email": f"wallet.qa.qa05.{time.time_ns()}@example.com",
        "password": secrets.token_hex(16),
    }
    (output / "credentials.json").write_text(json.dumps(credentials, indent=2), encoding="utf-8")
    result = {
        "case": "QA-05-ambiguous-suffix",
        "status": "IN_PROGRESS",
        "device": args.device,
        "started_at": datetime.now(timezone.utc).isoformat(),
        "steps": [],
        "cleanup": {},
    }

    def save():
        (output / "results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")

    save()
    device = None
    user = None
    try:
        device = Device(args.device, output)
        device.wait_text("Authentication")
        device.signup(credentials)
        user = FirebaseUser(credentials)
        assert user.documents("accounts") == [] and user.documents("records") == [], "New QA-05 identity is not empty"
        fixtures = [
            ("qa05-mainbank", "MainBank", "1111", "10000.00", 0),
            ("qa05-hsbc", "HSBC Wallet", "4444", "500.00", 1),
            ("qa05-cib", "CIB Wallet", "4444", "700.00", 2),
        ]
        for account_id, name, suffix, amount, order in fixtures:
            user.put("accounts", account_id, {
                "name": name, "accountType": "Debit", "last4Digits": suffix,
                "amount": amount, "currency": "EGP", "color": 0,
                "isArchived": False, "sortOrder": order,
            })
        device.finish_intro()
        device.wait_text("MainBank")
        result["initial_state"] = {
            "accounts": {name: amount for _, name, _, amount, _ in fixtures},
            "shared_suffix": "4444",
            "home_total": "11200.00 EGP",
            "records": 0,
        }
        result["steps"].append("signed up a fresh user and seeded MainBank plus HSBC/CIB debit accounts that share suffix 4444; Firestore started with no records")
        save()
        device.driver.quit()
        device = None

        previous = set((ROOT / "app/build/device-smoke").glob("*/live_sms_unknown_account_unlinked_record/results.json"))
        command = [
            sys.executable, str(ROOT / "scripts/device_sms_unknown_account_case.py"),
            "--device", args.device, "--baseline-home", "11200", "--baseline-mainbank", "10000",
            "--amount", "0.02", "--unknown-suffix", "4444",
            "--ambiguous-account-name", "HSBC Wallet", "--ambiguous-account-name", "CIB Wallet",
            "--retain-unlinked-record",
        ]
        completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=180)
        (output / "sms_case.stdout.txt").write_text(completed.stdout, encoding="utf-8")
        (output / "sms_case.stderr.txt").write_text(completed.stderr, encoding="utf-8")
        if completed.returncode != 0:
            raise AssertionError(f"QA-05 SMS scenario exited {completed.returncode}; see runner evidence")
        runs = set((ROOT / "app/build/device-smoke").glob("*/live_sms_unknown_account_unlinked_record/results.json")) - previous
        if len(runs) != 1:
            raise AssertionError("QA-05 did not produce exactly one SMS result")
        run_path = next(iter(runs))
        sms_result = json.loads(run_path.read_text(encoding="utf-8"))
        if sms_result.get("status") != "PASS":
            raise AssertionError(f"QA-05 product checks failed: {sms_result.get('error', sms_result.get('status'))}")
        if not sms_result.get("unlinked_banner") or sms_result.get("notification") != "Action Required: Match Account / Groceries: -0.02 EGP":
            raise AssertionError(f"Ambiguous suffix did not surface as unlinked/action-required: {sms_result}")
        result["sms_evidence"] = run_path.relative_to(ROOT).as_posix()
        result["sms_marker"] = sms_result["marker"]
        result["steps"].append("injected the identical sender/body suffix 4444, observed an unlinked row and Action Required alert, and verified Home/MainBank stayed unchanged")

        remote_record = None
        remote_records = []
        for _ in range(12):
            remote_records = user.documents("records")
            matches = [record for record in remote_records if sms_result["marker"] in record.get("comment", "")]
            if matches:
                if len(matches) != 1:
                    raise AssertionError(f"Expected one remote ambiguous transaction, found {len(matches)}")
                remote_record = matches[0]
                break
            time.sleep(1)
        if remote_record is None:
            raise AssertionError("Ambiguous unlinked transaction was not present in Firestore")
        suffix_account_ids = {"qa05-hsbc", "qa05-cib"}
        suffix_account_names = {"HSBC Wallet", "CIB Wallet"}
        if remote_record.get("accountId", "") in suffix_account_ids or remote_record.get("accountName", "") in suffix_account_names:
            raise AssertionError(f"Ambiguous transaction silently linked to one duplicate-suffix account: {remote_record}")
        if (remote_record.get("type") != "Expense" or remote_record.get("category") != "Groceries"
                or Decimal(str(remote_record.get("amount", "0"))) != Decimal("0.02")
                or remote_record.get("currency") != "EGP"):
            raise AssertionError(f"Ambiguous transaction fields were not correct: {remote_record}")
        result["remote_record"] = {
            "document_id": remote_record["id"],
            "account_id": remote_record.get("accountId", ""),
            "account_name": remote_record.get("accountName", ""),
            "type": remote_record.get("type"),
            "category": remote_record.get("category"),
            "amount": remote_record.get("amount"),
            "currency": remote_record.get("currency"),
        }
        result["steps"].append("independently verified the synced transaction in Firestore without either ambiguous account ID/name")

        cleanup_command = [
            sys.executable, str(ROOT / "scripts/device_sms_unknown_account_case.py"),
            "--device", args.device, "--baseline-home", "11200", "--baseline-mainbank", "10000",
            "--amount", "0.02", "--unknown-suffix", "4444", "--cleanup-marker", sms_result["marker"],
        ]
        cleanup_run = subprocess.run(cleanup_command, cwd=ROOT, capture_output=True, text=True, timeout=180)
        (output / "cleanup_case.stdout.txt").write_text(cleanup_run.stdout, encoding="utf-8")
        (output / "cleanup_case.stderr.txt").write_text(cleanup_run.stderr, encoding="utf-8")
        if cleanup_run.returncode != 0:
            raise AssertionError(f"QA-05 exact-row cleanup exited {cleanup_run.returncode}; see cleanup runner evidence")

        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            records_left = [record for record in user.documents("records") if sms_result["marker"] in record.get("comment", "")]
            accounts_now = {account["id"]: account.get("amount") for account in user.documents("accounts")}
            if not records_left and accounts_now == {"qa05-mainbank": "10000.00", "qa05-hsbc": "500.00", "qa05-cib": "700.00"}:
                result["remote_cleanup"] = {"record_count_for_marker": 0, "account_balances": accounts_now}
                break
            time.sleep(1)
        else:
            raise AssertionError(f"Ambiguous case did not clean up remotely: records={records_left}; accounts={accounts_now}")
        result["steps"].append("verified exact-row deletion and restart left all three remote balances at baseline and removed the test record")
        result["status"] = "PASS"
    except Exception as error:
        result["status"] = "FAIL"
        result["error"] = f"{type(error).__name__}: {error}"
    finally:
        if device is not None:
            try:
                device.driver.quit()
            except Exception:
                pass
        if user is not None:
            cleanup_errors = []
            try:
                cleanup_device = Device(args.device, output / "cleanup")
                try:
                    cleanup_device.signout()
                    cleanup_device.wait_text("Authentication")
                    result["cleanup"]["signed_out"] = True
                finally:
                    cleanup_device.driver.quit()
            except Exception as error:
                cleanup_errors.append(f"UI sign-out: {type(error).__name__}: {error}")
            try:
                deleted_documents = user.delete_test_identity(["accounts", "records"])
                try:
                    FirebaseUser(credentials)
                except RuntimeError as error:
                    if str(error) not in {"INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "USER_NOT_FOUND"}:
                        raise
                else:
                    raise AssertionError("Deleted QA-05 identity still accepts its credentials")
                result["cleanup"]["deleted_documents"] = deleted_documents
                result["cleanup"]["identity"] = "AUTH_IDENTITY_AND_FIXTURE_COLLECTIONS_DELETED_VERIFIED"
                if cleanup_errors:
                    result["cleanup"]["warnings"] = cleanup_errors
            except Exception as error:
                result["cleanup"]["identity"] = f"FAILED: {type(error).__name__}: {error}"
                result["status"] = "FAIL"
        result["finished_at"] = datetime.now(timezone.utc).isoformat()
        result["evidence"] = str(output)
        save()
    print(json.dumps({"case": result["case"], "status": result["status"], "cleanup": result["cleanup"], "evidence": str(output)}, indent=2))
    raise SystemExit(0 if result["status"] == "PASS" and result["cleanup"].get("identity", "").startswith("AUTH_IDENTITY") else 1)


if __name__ == "__main__":
    main()
