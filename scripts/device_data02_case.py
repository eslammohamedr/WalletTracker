import json
import base64
import re
import secrets
import subprocess
import sys
import time
from datetime import datetime, timezone

from qa_campaign_support import Device, FirebaseUser, ROOT


def main():
    import argparse

    parser = argparse.ArgumentParser(description="DATA-02 offline-write/restart/sync flow on the real Wallet app")
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--csv-newline", action="store_true", help="also execute DATA-06 with a comma/quote/newline note and verify live CSV round-trip")
    parser.add_argument("--data01-server-sync", action="store_true", help="run DATA-01 and independently inspect Firestore after an offline save syncs, then clean up")
    parser.add_argument("--data04-account-edit", action="store_true", help="run DATA-04 by moving one live manual record between two disposable debit accounts")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        parser.error("Pass --execute to create/delete a disposable Firebase QA identity and add/remove a test record")

    if args.data04_account_edit and args.data01_server_sync:
        parser.error("Choose only one DATA-01 or DATA-04 scenario")
    case_name = "data-04-account-edit" if args.data04_account_edit else "data-01-server-sync" if args.data01_server_sync else "data-02-offline-restart"
    output = ROOT / "app/build/device-smoke" / (case_name + "-" + time.strftime("%Y%m%d-%H%M%S"))
    output.mkdir(parents=True, exist_ok=True)
    credentials = {
        "email": f"wallet.qa.data02.{time.time_ns()}@example.com",
        "password": secrets.token_hex(16),
    }
    (output / "credentials.json").write_text(json.dumps(credentials, indent=2), encoding="utf-8")
    result = {
        "case": "DATA-04" if args.data04_account_edit else "DATA-01" if args.data01_server_sync else "DATA-02",
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
        assert user.documents("accounts") == [] and user.documents("records") == [], "New user is not empty"
        user.put("accounts", "data02-mainbank", {
            "name": "MainBank", "accountType": "Debit", "last4Digits": "1111",
            "amount": "1000.00" if args.data04_account_edit else "10000.00", "currency": "EGP", "color": 0,
            "isArchived": False, "sortOrder": 0,
        })
        if args.data04_account_edit:
            user.put("accounts", "data04-secondbank", {
                "name": "SecondBank", "accountType": "Debit", "last4Digits": "2222",
                "amount": "500.00", "currency": "EGP", "color": 0,
                "isArchived": False, "sortOrder": 1,
            })
        device.finish_intro()
        device.wait_text("MainBank")
        if args.data04_account_edit:
            result["initial_state"] = {"accounts": {"MainBank": "1000.00 EGP", "SecondBank": "500.00 EGP"}, "home_total": "1500.00 EGP", "records": 0}
            result["steps"].append("Signed up a fresh user and seeded two EGP debit accounts at MainBank 1,000.00 and SecondBank 500.00; no records existed")
        else:
            result["initial_state"] = {"account": "MainBank", "balance": "10000.00 EGP", "home_total": "10000.00 EGP", "records": 0}
            result["steps"].append("Signed up a fresh user and seeded exactly one EGP 10,000.00 debit account; no records existed")
        save()
        device.driver.quit()
        device = None

        marker = f"DATA01_{time.time_ns()}" if args.data01_server_sync else f"DATA02_{time.time_ns()}"
        previous_results = set((ROOT / "app/build/device-smoke").glob("*/manual_income_record/results.json"))
        command = [
            sys.executable, str(ROOT / "scripts/device_manual_record_case.py"),
            "--device", args.device, "--account", "MainBank", "--currency", "EGP",
            "--type", "Income" if args.data04_account_edit else "Expense",
            "--category", "Salary" if args.data04_account_edit else "Groceries",
            "--amount", "0.23" if args.data04_account_edit else "0.09" if args.data01_server_sync else "0.17",
            "--account-baseline", "1000" if args.data04_account_edit else "10000",
            "--home-baseline", "1500" if args.data04_account_edit else "10000",
            "--home-delta", "0.23" if args.data04_account_edit else "-0.09" if args.data01_server_sync else "-0.17",
            "--note", marker,
        ]
        if args.data04_account_edit:
            command.extend([
                "--edit-to-account", "SecondBank", "--edit-account-baseline", "500",
                "--edit-to-type", "Expense", "--edit-to-category", "Groceries",
            ])
        elif args.data01_server_sync:
            command.extend(["--offline-save", "--force-stop-after-save", "--retain-record-after-sync"])
        else:
            command.extend(["--offline-save", "--force-stop-after-save"])
        completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=180)
        (output / "manual_record_runner.stdout.txt").write_text(completed.stdout, encoding="utf-8")
        (output / "manual_record_runner.stderr.txt").write_text(completed.stderr, encoding="utf-8")
        if completed.returncode != 0:
            raise AssertionError(f"Manual-record flow exited {completed.returncode}; see runner evidence")
        runner_results = set((ROOT / "app/build/device-smoke").glob("*/manual_income_record/results.json")) - previous_results
        if len(runner_results) != 1:
            raise AssertionError("Manual-record runner did not produce results.json")
        runner_result_path = next(iter(runner_results))
        runner_result = json.loads(runner_result_path.read_text(encoding="utf-8"))
        if runner_result.get("status") != "PASS":
            raise AssertionError(f"Manual-record flow did not pass: {runner_result.get('error', runner_result.get('status'))}")
        expected = ({
            "record_signature": "Expense / Groceries / SecondBank / -0.23 EGP",
            "account_after_add": "1000.23 EGP",
            "home_after_add": "1500.23 EGP",
            "edited_account_name": "SecondBank",
            "account_after_edit": "499.77 EGP",
            "initial_account_after_edit": "1000.00 EGP",
            "destination_account_after_edit": "499.77 EGP",
            "type_after_edit": "Expense",
        } if args.data04_account_edit else ({
            "record_signature": "Expense / Groceries / MainBank / -0.09 EGP",
            "account_after_add": "9999.91 EGP",
            "home_after_add": "9999.91 EGP",
        } if args.data01_server_sync else {
            "record_signature": "Expense / Groceries / MainBank / -0.17 EGP",
            "account_after_add": "9999.83 EGP",
            "home_after_add": "9999.83 EGP",
        }))
        for key, value in expected.items():
            if runner_result.get(key) != value:
                raise AssertionError(f"Unexpected {key}: {runner_result.get(key)!r}; expected {value!r}")
        evidence_match = re.search(r"^Evidence: (.+)$", completed.stdout, re.MULTILINE)
        result["manual_record_evidence"] = runner_result.get("evidence") or (
            evidence_match.group(1) if evidence_match else runner_result_path.relative_to(ROOT).as_posix()
        )
        result["record_assertions"] = expected
        if args.data01_server_sync:
            remote_record = None
            remote_records = []
            for _ in range(8):
                remote_records = user.documents("records")
                matches = [record for record in remote_records if record.get("comment") == marker]
                if matches:
                    if len(matches) != 1:
                        raise AssertionError(f"Expected one matching Firestore transaction, found {len(matches)}")
                    remote_record = matches[0]
                    break
                time.sleep(1)
            if remote_record is None:
                raise AssertionError("Offline-created record did not reach Firestore within 8 seconds after reconnection")
            remote_fields = {
                "type": remote_record.get("type"),
                "category": remote_record.get("category"),
                "accountId": remote_record.get("accountId"),
                "accountName": remote_record.get("accountName"),
                "amount": remote_record.get("amount"),
                "currency": remote_record.get("currency"),
                "comment": remote_record.get("comment"),
            }
            remote_expected = {
                "type": "Expense", "category": "Groceries", "accountId": "data02-mainbank",
                "accountName": "MainBank", "amount": "0.09", "currency": "EGP", "comment": marker,
            }
            if remote_fields != remote_expected:
                raise AssertionError(f"Firestore transaction fields differ: {remote_fields!r}; expected {remote_expected!r}")
            remote_accounts = user.documents("accounts")
            remote_main = next((account for account in remote_accounts if account.get("id") == "data02-mainbank"), None)
            if remote_main is None or remote_main.get("amount") != "9999.91":
                raise AssertionError(f"Firestore account did not sync to EGP 9,999.91: {remote_main}")
            result["remote_sync_assertions"] = {
                "record_document_id": remote_record["id"],
                "record_fields": remote_fields,
                "account_balance": remote_main.get("amount"),
                "account_count": len(remote_accounts),
                "record_count": len(remote_records),
            }
            result["steps"].append("independently read the exact synced transaction and EGP 9,999.91 MainBank balance from Firestore; no second device was used")
            save()
            previous_cleanup = set((ROOT / "app/build/device-smoke").glob("*/manual_income_record/results.json"))
            cleanup_command = [
                sys.executable, str(ROOT / "scripts/device_manual_record_case.py"),
                "--device", args.device, "--account", "MainBank", "--currency", "EGP",
                "--type", "Expense", "--category", "Groceries", "--amount", "0.09",
                "--account-baseline", "10000", "--home-baseline", "10000", "--home-delta", "-0.09",
                "--note", marker, "--cleanup-marker", marker,
            ]
            cleanup_run = subprocess.run(cleanup_command, cwd=ROOT, capture_output=True, text=True, timeout=180)
            (output / "remote_record_cleanup.stdout.txt").write_text(cleanup_run.stdout, encoding="utf-8")
            (output / "remote_record_cleanup.stderr.txt").write_text(cleanup_run.stderr, encoding="utf-8")
            if cleanup_run.returncode != 0:
                raise AssertionError(f"DATA-01 exact-row cleanup exited {cleanup_run.returncode}; see cleanup evidence")
            cleanup_results = set((ROOT / "app/build/device-smoke").glob("*/manual_income_record/results.json")) - previous_cleanup
            if len(cleanup_results) != 1:
                raise AssertionError("DATA-01 cleanup did not produce exactly one runner result")
            cleanup_result_path = next(iter(cleanup_results))
            cleanup_result = json.loads(cleanup_result_path.read_text(encoding="utf-8"))
            if cleanup_result.get("status") != "PASS":
                raise AssertionError(f"DATA-01 exact-row cleanup did not pass: {cleanup_result.get('error')}")
            result["cleanup_evidence"] = cleanup_result.get("evidence")
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                if user.documents("records") == []:
                    remote_accounts = user.documents("accounts")
                    remote_main = next((account for account in remote_accounts if account.get("id") == "data02-mainbank"), None)
                    if remote_main is not None and remote_main.get("amount") == "10000.00":
                        result["remote_cleanup_verification"] = {"record_count": 0, "mainbank_balance": remote_main["amount"]}
                        break
                time.sleep(1)
            else:
                raise AssertionError("Firestore did not converge to no records and EGP 10,000.00 after cleanup")
            result["steps"].append("deleted the exact transaction through the app and independently verified Firestore has no records and MainBank is back at EGP 10,000.00")
            save()
        if args.data04_account_edit:
            result["steps"].append("Created one EGP 0.23 income on MainBank, edited its type/category to Expense/Groceries and account to SecondBank, verified the source returned to EGP 1,000.00, destination became EGP 499.77 and Home changed by the correct net EGP -0.23, then deleted the exact row and restored both balances after restart")
            reverse_marker = f"DATA04_REVERSE_{time.time_ns()}"
            previous_results = set((ROOT / "app/build/device-smoke").glob("*/manual_income_record/results.json"))
            reverse_command = [
                sys.executable, str(ROOT / "scripts/device_manual_record_case.py"),
                "--device", args.device, "--account", "MainBank", "--currency", "EGP",
                "--type", "Expense", "--category", "Groceries", "--amount", "0.31",
                "--account-baseline", "1000", "--home-baseline", "1500", "--home-delta", "-0.31",
                "--note", reverse_marker,
                "--edit-to-account", "SecondBank", "--edit-account-baseline", "500",
                "--edit-to-type", "Income", "--edit-to-category", "Salary",
            ]
            reverse_run = subprocess.run(reverse_command, cwd=ROOT, capture_output=True, text=True, timeout=180)
            (output / "manual_record_reverse_runner.stdout.txt").write_text(reverse_run.stdout, encoding="utf-8")
            (output / "manual_record_reverse_runner.stderr.txt").write_text(reverse_run.stderr, encoding="utf-8")
            if reverse_run.returncode != 0:
                raise AssertionError(f"Reverse DATA-04 manual-record flow exited {reverse_run.returncode}; see runner evidence")
            reverse_results = set((ROOT / "app/build/device-smoke").glob("*/manual_income_record/results.json")) - previous_results
            if len(reverse_results) != 1:
                raise AssertionError("Reverse DATA-04 runner did not produce exactly one results.json")
            reverse_result_path = next(iter(reverse_results))
            reverse_result = json.loads(reverse_result_path.read_text(encoding="utf-8"))
            reverse_expected = {
                "record_signature": "Income / Salary / SecondBank / +0.31 EGP",
                "account_after_add": "999.69 EGP",
                "home_after_add": "1499.69 EGP",
                "edited_account_name": "SecondBank",
                "account_after_edit": "500.31 EGP",
                "initial_account_after_edit": "1000.00 EGP",
                "destination_account_after_edit": "500.31 EGP",
                "type_after_edit": "Income",
            }
            if reverse_result.get("status") != "PASS":
                raise AssertionError(f"Reverse DATA-04 flow did not pass: {reverse_result.get('error', reverse_result.get('status'))}")
            for key, value in reverse_expected.items():
                if reverse_result.get(key) != value:
                    raise AssertionError(f"Unexpected reverse {key}: {reverse_result.get(key)!r}; expected {value!r}")
            result["reverse_record_evidence"] = reverse_result.get("evidence", reverse_result_path.relative_to(ROOT).as_posix())
            result["reverse_record_assertions"] = reverse_expected
            result["steps"].append("Created an EGP 0.31 expense on MainBank, edited its type/category to Income/Salary and account to SecondBank, verified MainBank returned to EGP 1,000.00, SecondBank became EGP 500.31 and Home reflected the net EGP +0.31, then deleted it and restored both balances after restart")
        elif args.data01_server_sync:
            result["steps"].append("saved one EGP 0.09 expense offline, verified local persistence and process restart, confirmed the server-side record/account over Firestore REST, deleted it, and verified remote cleanup and baseline restoration")
        else:
            result["steps"].append("Saved one EGP 0.17 expense offline, verified local visibility, restored connectivity, restarted Wallet, verified exactly one expense and exact account/Home deltas, deleted it, and confirmed baselines after another restart")
        result["status"] = "PASS"

        if args.csv_newline and not args.data04_account_edit:
            csv_note = f"DATA06,{time.time_ns()}\nMemo: \"newline round-trip\""
            csv_marker = base64.b64encode(csv_note.encode("utf-8")).decode("ascii")
            previous_results = set((ROOT / "app/build/device-smoke").glob("*/manual_income_record/results.json"))
            csv_command = [
                sys.executable, str(ROOT / "scripts/device_manual_record_case.py"),
                "--device", args.device, "--account", "MainBank", "--currency", "EGP",
                "--type", "Expense", "--category", "Groceries", "--amount", "0.19",
                "--account-baseline", "10000", "--home-baseline", "10000", "--home-delta", "-0.19",
                "--note-base64", csv_marker, "--csv-export",
            ]
            csv_run = subprocess.run(csv_command, cwd=ROOT, capture_output=True, text=True, timeout=180)
            (output / "data06_manual_record_runner.stdout.txt").write_text(csv_run.stdout, encoding="utf-8")
            (output / "data06_manual_record_runner.stderr.txt").write_text(csv_run.stderr, encoding="utf-8")
            if csv_run.returncode != 0:
                raise AssertionError(f"DATA-06 manual-record/CSV flow exited {csv_run.returncode}; see runner evidence")
            csv_results = set((ROOT / "app/build/device-smoke").glob("*/manual_income_record/results.json")) - previous_results
            if len(csv_results) != 1:
                raise AssertionError("DATA-06 runner did not produce exactly one result file")
            csv_result_path = next(iter(csv_results))
            csv_result = json.loads(csv_result_path.read_text(encoding="utf-8"))
            if csv_result.get("status") != "PASS":
                raise AssertionError(f"DATA-06 live CSV flow did not pass: {csv_result.get('error', csv_result.get('status'))}")
            exported_comment = csv_result.get("csv_export", {}).get("comment")
            if exported_comment != csv_note:
                raise AssertionError(f"DATA-06 CSV comment did not preserve the exact multiline value: {exported_comment!r}")
            result["data06_csv_newline"] = {
                "status": "PASS",
                "note": csv_note,
                "csv_export_evidence": csv_result_path.relative_to(ROOT).as_posix(),
                "columns": csv_result["csv_export"]["record_columns"],
                "cleanup": csv_result.get("cleanup"),
            }
            result["steps"].append("DATA-06 live UI CSV export preserved a note containing commas, quotes, and an embedded newline across all eight columns; exact record deletion/restart restored both EGP 10,000 baselines")
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
                    try:
                        cleanup_device.signout()
                        cleanup_device.wait_text("Authentication")
                        result["cleanup"]["signed_out"] = True
                    except Exception as error:
                        cleanup_errors.append(f"UI sign-out: {type(error).__name__}: {error}")
                finally:
                    cleanup_device.driver.quit()
            except Exception as error:
                cleanup_errors.append(f"UI cleanup setup: {type(error).__name__}: {error}")
            try:
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
                if cleanup_errors:
                    result["cleanup"]["ui_cleanup_warnings"] = cleanup_errors
            except Exception as error:
                result["cleanup"]["identity"] = f"FAILED: {type(error).__name__}: {error}"
                result["status"] = "FAIL"
        result["finished_at"] = datetime.now(timezone.utc).isoformat()
        result["evidence"] = str(output)
        save()
    print(json.dumps({"case": result["case"], "status": result["status"], "cleanup": result["cleanup"], "data06_csv_newline": result.get("data06_csv_newline"), "evidence": str(output)}, indent=2))
    raise SystemExit(0 if result["status"] == "PASS" and result["cleanup"].get("identity", "").startswith("AUTH_IDENTITY") else 1)


if __name__ == "__main__":
    main()
