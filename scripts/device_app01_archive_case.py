import json
import secrets
import subprocess
import sys
import time
from datetime import datetime, timezone

from qa_campaign_support import Device, FirebaseUser, ROOT


def main():
    import argparse

    parser = argparse.ArgumentParser(description="APP-01 real-app account create/edit/archive/restore/delete regression")
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        parser.error("Pass --execute to register/delete a disposable Firebase identity and manage a test-owned account")

    output = ROOT / "app/build/device-smoke" / ("account-archive-" + time.strftime("%Y%m%d-%H%M%S"))
    output.mkdir(parents=True, exist_ok=True)
    credentials = {
        "email": f"wallet.qa.archive.{time.time_ns()}@example.com",
        "password": secrets.token_hex(16),
    }
    (output / "credentials.json").write_text(json.dumps(credentials, indent=2), encoding="utf-8")
    result = {
        "case": "APP-01",
        "status": "IN_PROGRESS",
        "device": args.device,
        "started_at": datetime.now(timezone.utc).isoformat(),
        "steps": [],
        "cleanup": {},
    }

    def save():
        (output / "results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")

    save()
    device = Device(args.device, output)
    user = None
    try:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            texts = device.texts()
            if "Authentication" in texts:
                break
            if "Allow" in texts and any("Allow WalletTrackers" in text for text in texts):
                device.tap("Allow")
            else:
                time.sleep(0.2)
        else:
            raise AssertionError(f"Authentication screen did not appear: {device.texts()}")

        device.signup(credentials)
        user = FirebaseUser(credentials)
        assert user.documents("accounts") == [] and user.documents("records") == [], "Fresh APP-01 identity was not empty"
        device.finish_intro()
        result["steps"].append("Created a fresh disposable Firebase identity through the production signup flow; initial account and record collections were empty")
        save()
        device.driver.quit()
        device = None

        command = [
            sys.executable, str(ROOT / "scripts/device_app_acceptance.py"),
            "--case", "APP-01", "--device", args.device, "--execute",
        ]
        completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=240)
        (output / "app01_runner.stdout.txt").write_text(completed.stdout, encoding="utf-8")
        (output / "app01_runner.stderr.txt").write_text(completed.stderr, encoding="utf-8")
        if completed.returncode != 0:
            raise AssertionError(f"APP-01 UI runner exited {completed.returncode}; see app01_runner logs")
        json_result = completed.stdout.split("Evidence:", 1)[0].strip()
        app_result = json.loads(json_result)
        expected_fragments = ("archived the test-owned account", "restored the archived account", "deleted only the unique case-owned account")
        for fragment in expected_fragments:
            if not any(fragment in step for step in app_result.get("steps", [])):
                raise AssertionError(f"APP-01 result omitted expected archive lifecycle step {fragment!r}")
        result["app01_ui_result"] = app_result
        result["steps"].append("APP-01 created and edited a debit account, archived it, restored it from Archived Accounts, verified it active again, and deleted it")
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
                deleted_documents = user.delete_test_identity(["accounts", "records"])
                try:
                    FirebaseUser(credentials)
                except RuntimeError as error:
                    if str(error) not in {"INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "USER_NOT_FOUND"}:
                        raise
                else:
                    raise AssertionError("Deleted test identity still accepts its credentials")
                result["cleanup"]["deleted_documents"] = deleted_documents
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
