import json
import secrets
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from qa_campaign_support import Device, FirebaseUser, ROOT


def main():
    import argparse

    parser = argparse.ArgumentParser(description="Fresh-user live transfer edit, balance, and rollback scenario")
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        parser.error("Pass --execute to create/delete a disposable identity and run the real-app transfer test")

    output = ROOT / "app/build/device-smoke" / ("data05-transfer-edit-" + time.strftime("%Y%m%d-%H%M%S"))
    output.mkdir(parents=True, exist_ok=True)
    credentials = {
        "email": f"wallet.qa.data05.{time.time_ns()}@example.com",
        "password": secrets.token_hex(16),
    }
    (output / "credentials.json").write_text(json.dumps(credentials, indent=2), encoding="utf-8")
    result = {
        "case": "DATA-05-transfer-edit-live",
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
        assert user.documents("accounts") == [] and user.documents("records") == [], "New identity is not empty"
        fixtures = [
            ("data05-main", "MainBank", "Debit", "1111", "10000.00", "EGP", 0),
            ("data05-second", "SecondBank", "Debit", "2222", "5000.00", "EGP", 1),
            ("data05-cash", "CashWallet", "Cash", "", "1000.00", "EGP", 2),
        ]
        for account_id, name, account_type, suffix, amount, currency, order in fixtures:
            user.put("accounts", account_id, {
                "name": name, "accountType": account_type, "last4Digits": suffix,
                "amount": amount, "currency": currency, "color": 0,
                "isArchived": False, "sortOrder": order,
            })
        device.finish_intro()
        device.wait_text("MainBank")
        result["baseline"] = {"home_egp": "16000.00", "MainBank": "10000.00", "SecondBank": "5000.00", "CashWallet": "1000.00", "records": 0}
        result["steps"].append("registered a disposable user and independently seeded an empty transfer fixture with exact EGP 16,000 Home baseline")
        save()
        device.driver.quit()
        device = None

        command = [
            sys.executable, str(ROOT / "scripts/device_transfer_case.py"),
            "--device", args.device, "--execute", "--amount", "0.07", "--edit-amount", "0.11",
            "--source-baseline", "10000.00", "--destination-baseline", "5000.00",
            "--home-baseline", "16000.00", "--verify-statistics",
        ]
        completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=300)
        (output / "transfer_case.stdout.txt").write_text(completed.stdout, encoding="utf-8")
        (output / "transfer_case.stderr.txt").write_text(completed.stderr, encoding="utf-8")
        if completed.returncode != 0:
            raise AssertionError(f"Live transfer edit scenario exited {completed.returncode}; see child evidence")
        result["transfer_runner"] = json.JSONDecoder().raw_decode(completed.stdout.lstrip())[0]
        result["steps"].append("real-app transfer create/edit/delete scenario passed its balance and cleanup assertions")
        account_balances = {account["id"]: account.get("amount") for account in user.documents("accounts")}
        records = user.documents("records")
        if account_balances != {"data05-main": "10000.00", "data05-second": "5000.00", "data05-cash": "1000.00"} or records:
            raise AssertionError(f"Server-side transfer cleanup did not restore fixture: accounts={account_balances}, records={records}")
        result["remote_cleanup"] = {"accounts": account_balances, "record_count": len(records)}
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
            try:
                cleanup_device = Device(args.device, output / "cleanup")
                try:
                    cleanup_device.signout()
                    cleanup_device.wait_text("Authentication")
                    result["cleanup"]["signed_out"] = True
                finally:
                    cleanup_device.driver.quit()
            except Exception as error:
                result["cleanup"]["signout_warning"] = f"{type(error).__name__}: {error}"
            try:
                result["cleanup"]["deleted_documents"] = user.delete_test_identity(["accounts", "records"])
                result["cleanup"]["identity"] = "AUTH_IDENTITY_AND_FIXTURE_COLLECTIONS_DELETED_VERIFIED"
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
