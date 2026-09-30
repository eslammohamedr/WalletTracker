import argparse
import hashlib
import json
import re
import secrets
import sqlite3
import subprocess
import sys
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from qa_ai_fixture import AiFixture
from qa_campaign_support import Device, FirebaseUser, PACKAGE, ROOT
from sms_category_suite import read_messages


COLLECTIONS = [
    "accounts", "records", "creditStatements", "budgets", "savingsGoals",
    "debts", "bills", "categoryRules", "customSubCategories", "notifications",
]
SPECIAL_TYPES = {"Statement", "AtmWithdrawal", "CardPayment", "CreditCardReceived"}


def adb(serial, *args, timeout=30):
    return subprocess.check_output(["adb", "-s", serial, *map(str, args)], timeout=timeout)


def dump_inbox(serial):
    raw = adb(serial, "shell", "content", "query", "--uri", "content://sms/inbox",
              "--projection", "_id:address:body:date", timeout=60).decode("utf-8", errors="replace")
    rows = []
    for line in raw.splitlines():
        match = __import__("re").search(r"_id=(\d+), address=(.*?), body=(.*), date=(\d+)\s*$", line)
        if match:
            rows.append({"id": match.group(1), "sender": match.group(2), "body": match.group(3), "date": int(match.group(4))})
    return rows


def inbox_count(serial):
    raw = adb(serial, "shell", "content", "query", "--uri", "content://sms/inbox",
              "--projection", "_id", timeout=60).decode("utf-8", errors="replace")
    return len(__import__("re").findall(r"\b_id=\d+", raw))


def clear_inbox(serial):
    maximum_rows = inbox_count(serial)
    deadline = time.monotonic() + 45
    empty_since = None
    while time.monotonic() < deadline:
        current_rows = inbox_count(serial)
        maximum_rows = max(maximum_rows, current_rows)
        if current_rows == 0:
            empty_since = empty_since or time.monotonic()
            if time.monotonic() - empty_since >= 1.5:
                return maximum_rows
        else:
            empty_since = None
            adb(serial, "shell", "content", "delete", "--uri", "content://sms", "--where", "type=1", timeout=60)
        time.sleep(0.5)
    raise AssertionError("Could not keep QA Inbox empty after repeated provider-wide deletes")


def inbox_batch_missing(batch, rows):
    received = defaultdict(int)
    for row in rows:
        received[(row["sender"], row["body"])] += 1
    missing = []
    for entry in batch:
        key = (entry["injected_sender"], entry["body"])
        if received[key] > 0:
            received[key] -= 1
        else:
            missing.append(entry["number"])
    return missing


def wait_for_inbox_event(serial, entry, occurrence, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        rows = dump_inbox(serial)
        received = sum(1 for row in rows if (row["sender"], row["body"]) ==
                       (entry["injected_sender"], entry["body"]))
        if received >= occurrence:
            return True
        time.sleep(0.5)
    return False


def wait_for_inbox_batch(serial, batch, timeout=45):
    deadline = time.monotonic() + timeout
    rows = []
    missing = [entry["number"] for entry in batch]
    while time.monotonic() < deadline:
        rows = dump_inbox(serial)
        missing = inbox_batch_missing(batch, rows)
        if not missing:
            return rows, missing
        time.sleep(1)
    return rows, missing


def prepare_qa_permissions(serial):
    avd = adb(serial, "emu", "avd", "name").decode("utf-8", errors="replace").splitlines()[0]
    if avd not in {"Wallet_23Cases_Temp", "Wallet_Onboarding_QA"}:
        raise AssertionError(f"Refusing SMS replay on non-QA AVD: {avd}")
    for permission in ("android.permission.RECEIVE_SMS", "android.permission.READ_SMS", "android.permission.POST_NOTIFICATIONS"):
        adb(serial, "shell", "pm", "grant", PACKAGE, permission)
    sdk = int(adb(serial, "shell", "getprop", "ro.build.version.sdk").decode().strip())
    if sdk >= 37:
        adb(serial, "shell", "appops", "set", PACKAGE, "READ_RESTRICTED_MESSAGES", "allow")
    adb(serial, "shell", "appops", "set", "com.android.shell", "WRITE_SMS", "allow")


def broadcast_id(timestamp, sender, body):
    digest = hashlib.sha256(f"{sender}\0{body}".encode("utf-8")).digest()[:12].hex()
    return f"{timestamp}_{digest}"


def sms_content_hash(sms_id):
    return str(sms_id or "").rsplit("_", 1)[-1]


def source_dates():
    source = (ROOT / "app/src/test/resources/sms_export.txt").read_text(encoding="utf-8-sig")
    blocks = re.split(r"(?m)^SMS #(\d+)\s*$", source)
    dates = {}
    for offset in range(1, len(blocks), 2):
        number, block = blocks[offset:offset + 2]
        match = re.search(r"(?m)^Date\s*:\s*(.+)$", block)
        if not match:
            raise ValueError(f"SMS #{number} has no export timestamp")
        dates[int(number)] = datetime.strptime(match.group(1).strip(), "%d/%m/%Y %H:%M")
    return dates


def signed_account_effect(actual):
    try:
        amount = float(actual["amount"])
    except (TypeError, ValueError):
        return 0.0
    if actual["type"] in {"Income", "CreditCardReceived", "CardPayment"}:
        return amount
    if actual["type"] in {"Expense", "AtmWithdrawal"}:
        return -amount
    return 0.0


def account_suffix_for_message(entry):
    actual = entry["parser_actual"]
    return str(actual.get("digits") or "")


def prepare_expected_balances(messages, accounts):
    suffix_to_name = {suffix: name for _, name, _, suffix, _, _ in accounts if suffix}
    current_by_name = {}
    opening_by_name = {}
    account_currency = {name: "EGP" for _, name, _, _, _, _ in accounts}
    for entry in messages:
        entry["expected_amount"] = str(entry["parser_actual"]["amount"])
        entry["expected_currency"] = entry["parser_actual"].get("currency") or "EGP"
        suffix = account_suffix_for_message(entry)
        matched = [candidate for candidate in suffix_to_name if candidate.endswith(suffix) or suffix.endswith(candidate)] if suffix else []
        if len(matched) != 1:
            continue
        entry["expected_account_name"] = suffix_to_name[matched[0]]
        entry["expected_currency"] = account_currency[entry["expected_account_name"]]

    for account_name in set(suffix_to_name.values()):
        account_entries = [entry for entry in messages if entry.get("expected_account_name") == account_name]
        anchors = [entry for entry in account_entries if entry["parser_actual"].get("balance") is not None]
        if not anchors:
            continue
        anchor = anchors[0]
        anchor_index = messages.index(anchor)
        prefix = [entry for entry in messages[:anchor_index + 1] if entry.get("expected_account_name") == account_name]
        if any((entry["parser_actual"].get("currency") or "EGP") != "EGP" for entry in prefix):
            continue
        initial = float(anchor["parser_actual"]["balance"]) - signed_account_effect(anchor["parser_actual"])
        initial -= sum(signed_account_effect(entry["parser_actual"]) for entry in prefix[:-1])
        current_by_name[account_name] = initial
        opening_by_name[account_name] = initial

    for entry in messages:
        account_name = entry.get("expected_account_name")
        if not account_name or account_name not in current_by_name:
            continue
        actual = entry["parser_actual"]
        current = current_by_name[account_name]
        if actual.get("balance") is not None:
            after = float(actual["balance"])
            entry["expected_currency"] = account_currency[account_name]
            if (actual.get("currency") or "EGP") != account_currency[account_name]:
                entry["expected_amount"] = f"{abs(current - after):.2f}"
            current_by_name[account_name] = after
        elif (actual.get("currency") or "EGP") == account_currency[account_name]:
            current_by_name[account_name] = current + signed_account_effect(actual)
    return {name: f"{amount:.2f}" for name, amount in opening_by_name.items()}


def sqlite_snapshot(serial, output):
    adb(serial, "shell", "am", "force-stop", PACKAGE)
    for suffix in ("", "-wal", "-shm"):
        remote = f"/data/user/0/{PACKAGE}/databases/wallet_db{suffix}"
        try:
            data = adb(serial, "exec-out", "run-as", PACKAGE, "cat", remote)
        except subprocess.CalledProcessError:
            continue
        if data:
            (output / f"wallet_db{suffix}").write_bytes(data)
    database = output / "wallet_db"
    if not database.is_file():
        raise RuntimeError("Could not export the disposable user's Room database")
    connection = sqlite3.connect(database)
    connection.row_factory = sqlite3.Row
    try:
        records = [dict(row) for row in connection.execute(
            "SELECT id, accountId, accountName, category, amount, currency, type, timestamp, balanceAfter, balanceBefore, smsId, comment, transferDestinationAmount FROM records ORDER BY timestamp, id"
        )]
        accounts = [dict(row) for row in connection.execute(
            "SELECT id, name, accountType, last4Digits, amount, currency FROM accounts ORDER BY sortOrder, id"
        )]
    finally:
        connection.close()
    return records, accounts


def evaluate_rows(messages, inbox_rows, records, statements, inbox_seen_numbers):
    inbox_by_event = defaultdict(list)
    for row in inbox_rows:
        inbox_by_event[(row["sender"], row["body"])].append(row)
    for rows in inbox_by_event.values():
        rows.sort(key=lambda row: row["date"])
    records_by_sms_id = defaultdict(list)
    for record in records:
        if record.get("smsId"):
            records_by_sms_id[sms_content_hash(record["smsId"])].append(record)
    statements_by_sms_id = defaultdict(list)
    for statement in statements:
        if statement.get("smsId"):
            statements_by_sms_id[sms_content_hash(statement["smsId"])].append(statement)

    outcomes = []
    for entry in messages:
        sender = entry["injected_sender"]
        candidates = inbox_by_event[(sender, entry["body"]) ]
        inbox = candidates.pop(0) if candidates else None
        event_id = broadcast_id(inbox["date"], sender, entry["body"]) if inbox else "content_" + sms_content_hash(broadcast_id(0, sender, entry["body"]))
        digest = sms_content_hash(event_id)
        matching = records_by_sms_id.get(digest, [])
        matching_statements = statements_by_sms_id.get(digest, [])
        actual = entry["parser_actual"]
        expected_type = actual["type"]
        statement_followup = None
        result_linked = None
        expected_category = {
            "Statement": "Credit Card",
            "AtmWithdrawal": "Transfer",
            "CardPayment": "Credit Payment",
            "CreditCardReceived": "Credit Payment",
        }.get(expected_type, actual["category"])
        if expected_type == "Statement":
            if matching_statements:
                statement = matching_statements[0]
                checks = {
                    "account_suffix": str(statement.get("cardLast4Digits", "")) == str(actual.get("digits") or ""),
                    "amount": abs(float(statement.get("totalAmount", 0)) - float(actual["amount"])) < 0.005,
                }
                status = "PASS" if all(checks.values()) else "OUTPUT_MISMATCH"
            else:
                checks = None
                amount = float(actual["amount"])
                digits = str(actual.get("digits") or "")
                next_statement_date = min((candidate["date"] for candidate in messages
                    if candidate is not entry
                    and candidate["parser_actual"]["type"] == "Statement"
                    and str(candidate["parser_actual"].get("digits") or "") == digits
                    and candidate["date"] > entry["date"]), default=None)
                later_payments = [candidate for candidate in messages
                    if candidate["date"] > entry["date"]
                    and candidate["parser_actual"]["type"] in {"CardPayment", "CreditCardReceived"}
                    and str(candidate["parser_actual"].get("digits") or "") == digits
                    and (next_statement_date is None or candidate["date"] < next_statement_date)]
                debit_payments = [candidate for candidate in later_payments
                    if candidate["parser_actual"]["type"] == "CardPayment"]
                payment_total = sum(float(candidate["parser_actual"]["amount"]) for candidate in
                                    (debit_payments or later_payments))
                if later_payments and payment_total + 0.005 < amount:
                    status = "STATEMENT_MISSING_AFTER_PARTIAL_PAYMENT"
                    statement_followup = {"payment_source_numbers": [candidate["number"] for candidate in (debit_payments or later_payments)],
                                          "payment_total": f"{payment_total:.2f}", "statement_due": f"{amount:.2f}"}
                elif later_payments:
                    status = "PARTIAL_STATEMENT_PAYMENT_COVERAGE_INFERRED"
                    statement_followup = {"payment_source_numbers": [candidate["number"] for candidate in (debit_payments or later_payments)],
                                          "payment_total": f"{payment_total:.2f}", "statement_due": f"{amount:.2f}"}
                else:
                    status = "EXPECTED_STATEMENT_MISSING"
        elif expected_type in {"CardPayment", "CreditCardReceived"} and not matching:
            status = "PAYMENT_PAIR_OR_PENDING_LINK_REQUIRES_REVIEW"
        elif actual["declined"] or not actual["bank_sms"] or not actual["amount"]:
            status = "UNEXPECTED_RECORD" if matching else "PASS_NO_RECORD_EXPECTED" if entry["number"] in inbox_seen_numbers else "NO_RECORD_NO_INBOX_EVIDENCE"
        elif matching:
            row = matching[0]
            paired_credit = expected_type == "CreditCardReceived" and "->" in str(row.get("accountName", ""))
            observed_amount = (row.get("transferDestinationAmount") if paired_credit else row.get("amount"))
            checks = {
                "type": row["type"] == ("Income" if expected_type == "Income" else "Expense"),
                "category": row["category"] == expected_category,
            "amount": bool(observed_amount) and abs(float(observed_amount) - float(entry.get("expected_amount", actual["amount"]))) < 0.005,
            "currency": str(row.get("currency") or "EGP") == str(entry.get("expected_currency") or actual.get("currency") or "EGP"),
            }
            if paired_credit:
                checks["credit_side_amount_recorded"] = bool(observed_amount)
            status = "PASS" if all(checks.values()) else "OUTPUT_MISMATCH"
        else:
            status = "EXPECTED_RECORD_MISSING"
            checks = None
            if expected_type == "Expense" and entry.get("expected_account_name"):
                linked = next((record for record in records
                    if record.get("category") == "Credit Payment"
                    and record.get("accountName", "").startswith(entry["expected_account_name"] + " ->")
                    and abs(float(record.get("amount", 0)) - float(entry.get("expected_amount", actual["amount"]))) < 0.005), None)
                if linked:
                    status = "PAYMENT_PAIR_DESTINATION_AMOUNT_MISSING" if not linked.get("transferDestinationAmount") else "PAYMENT_PAIR_LINKED_TO_COUNTERPART_SMS"
                    result_linked = {key: linked.get(key) for key in ("accountName", "amount", "transferDestinationAmount", "currency", "smsId")}
                else:
                    result_linked = None
            else:
                result_linked = None
        result = {"number": entry["number"], "sender": sender, "sms_id": event_id,
                  "parser_type": expected_type, "receiver_default_category": expected_category,
                  "parser_amount": actual["amount"], "expected_amount": entry.get("expected_amount", actual["amount"]),
                  "expected_currency": entry.get("expected_currency", actual.get("currency") or "EGP"),
                  "record_count_for_sms_id": len(matching),
                  "inbox_row_verified": entry["number"] in inbox_seen_numbers, "status": status}
        if matching_statements:
            result["statement"] = {key: matching_statements[0].get(key) for key in
                                   ("accountId", "cardLast4Digits", "totalAmount", "dueDate", "paid")}
            result["checks"] = checks or {}
        if statement_followup:
            result["statement_followup"] = statement_followup
        if matching:
            result["record"] = {key: matching[0].get(key) for key in
                                 ("accountName", "category", "amount", "transferDestinationAmount", "currency", "type", "comment")}
            result["checks"] = checks or {}
        if not matching and result_linked:
            result["linked_record"] = result_linked
        outcomes.append(result)
    return outcomes


def main():
    parser = argparse.ArgumentParser(description="Replay selected SMS export messages through the installed app receiver on a disposable QA AVD")
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--limit", type=int, default=1088, help="Maximum number of messages to replay after sender filtering")
    parser.add_argument("--all-messages", action="store_true", help="Replay every source SMS instead of one representative per sender")
    parser.add_argument("--settle-seconds", type=float, default=3.0)
    parser.add_argument("--inbox-batch-size", type=int, default=400)
    args = parser.parse_args()
    if not args.execute:
        parser.error("Pass --execute to create/delete a disposable identity and inject SMS into the QA AVD")
    if not 1 <= args.limit <= 1088:
        parser.error("--limit must be between 1 and 1088")
    if not 50 <= args.inbox_batch_size <= 600:
        parser.error("--inbox-batch-size must be between 50 and 600")

    source_messages = read_messages()
    dates = source_dates()
    evaluation = json.loads((ROOT / "app/build/reports/sms-export-evaluation.json").read_text(encoding="utf-8"))
    parser_by_number = {row["number"]: row for row in evaluation["messages"]}
    selected_numbers = sorted(source_messages)
    if not args.all_messages:
        first_by_sender = {}
        for number in selected_numbers:
            sender_key = re.sub(r"\s+", "", source_messages[number]["sender"]).casefold()
            first_by_sender.setdefault(sender_key, number)
        selected_numbers = sorted(first_by_sender.values())
    selected_numbers = selected_numbers[:args.limit]
    messages = []
    for number in selected_numbers:
        source = source_messages[number]
        parser_row = parser_by_number[number]
        injected_sender = source["sender"].replace(" ", "")
        messages.append({
            "number": number,
            "sender": source["sender"],
            "injected_sender": injected_sender,
            "body": source["body"],
            "parser_actual": parser_row["actual"],
            "date": dates[number],
        })
    messages.sort(key=lambda item: (item["date"], item["number"]))

    output = ROOT / "app/build/device-smoke" / ("sms-export-live-replay-" + time.strftime("%Y%m%d-%H%M%S"))
    output.mkdir(parents=True, exist_ok=True)
    credentials = {"email": f"wallet.qa.smsreplay.{time.time_ns()}@example.com", "password": secrets.token_hex(16)}
    (output / "credentials.json").write_text(json.dumps(credentials, indent=2), encoding="utf-8")
    result = {"case": "sms_export_live_receiver_replay", "status": "IN_PROGRESS", "source_count": len(source_messages),
              "requested_count": len(messages), "device": args.device, "started_at": datetime.now(timezone.utc).isoformat(),
              "sender_filter": "all_messages" if args.all_messages else "one_per_sender",
              "selected_source_numbers": [item["number"] for item in messages],
              "steps": [], "cleanup": {}, "inbox_batches": [], "inbox_missing_source_sms": []}

    def save():
        (output / "results.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")

    save()
    device = None
    user = None
    fixture = None
    sms_sent = 0
    try:
        prepare_qa_permissions(args.device)
        initial_inbox_count = inbox_count(args.device)
        if initial_inbox_count:
            raise AssertionError(f"QA inbox must be empty before bulk replay; found {initial_inbox_count} rows")
        device = Device(args.device, output / "device")
        device.signup(credentials)
        user = FirebaseUser(credentials)
        if user.documents("accounts") or user.documents("records"):
            raise AssertionError("New replay identity is not empty")

        accounts = [
            ("smsreplay-hsbc-3001", "HSBC Main", "Debit", "3001", "1000000.00", 0),
            ("smsreplay-hsbc-2929", "HSBC Card 2929", "Credit Card", "2929", "1000000.00", 1),
            ("smsreplay-hsbc-2505", "HSBC Card 2505", "Credit Card", "2505", "1000000.00", 2),
            ("smsreplay-hsbc-2601", "HSBC Card 2601", "Credit Card", "2601", "1000000.00", 3),
            ("smsreplay-bm-7000", "Banque Misr Card 7000", "Credit Card", "7000", "1000000.00", 4),
            ("smsreplay-cash", "CashWallet", "Cash", "", "1000000.00", 5),
        ]
        opening_amounts = prepare_expected_balances(messages, accounts)
        for account_id, name, account_type, suffix, amount, order in accounts:
            amount = opening_amounts.get(name, amount)
            user.put("accounts", account_id, {
                "name": name, "accountType": account_type, "last4Digits": suffix,
                "amount": amount, "currency": "EGP", "color": 0,
                "creditLimit": 1000000.0 if account_type == "Credit Card" else None,
                "billingDay": 15 if account_type == "Credit Card" else None,
                "isArchived": False, "sortOrder": order,
            })
        device.finish_intro()
        device.wait_text("HSBC Main")
        fixture_responses = {}
        for row in evaluation["messages"]:
            body = row["body"]
            category = row["actual"]["category"]
            fixture_responses.setdefault(body, category)
        fixture = AiFixture(device, category="Others", responses_by_marker=dict(
            sorted(fixture_responses.items(), key=lambda item: len(item[0]), reverse=True)
        )).start()
        result["steps"].append("registered a disposable identity, seeded six isolated accounts, and routed category-AI calls to the local QA stub")
        result["initial_accounts"] = len(accounts)
        save()

        result["steps"].append("kept Firebase online for real persistence; category-AI traffic is routed only to the local QA stub")
        save()

        inbox_seen_numbers = set()
        occurrences = defaultdict(int)
        for index, entry in enumerate(messages, start=1):
            completed = subprocess.run(
                ["adb", "-s", args.device, "emu", "sms", "send", entry["injected_sender"], entry["body"]],
                cwd=ROOT, capture_output=True, text=True, timeout=15,
            )
            if completed.returncode != 0 or "KO" in completed.stdout:
                raise AssertionError(f"SMS #{entry['number']} injection failed: {completed.stdout[-300:]} {completed.stderr[-300:]}")
            sms_sent += 1
            event_key = (entry["injected_sender"], entry["body"])
            occurrences[event_key] += 1
            if not wait_for_inbox_event(args.device, entry, occurrences[event_key]):
                result.setdefault("inbox_delivery_timeouts", []).append(entry["number"])
            time.sleep(max(args.settle_seconds, 0.5))
            if index % 25 == 0 or index == len(messages):
                result["injected"] = index
                result["local_ai_requests"] = len(fixture.requests)
                save()
                print(f"Injected {index}/{len(messages)} SMS", flush=True)
            if index % args.inbox_batch_size == 0 or index == len(messages):
                prefix = messages[:index]
                inbox_rows, batch_missing = wait_for_inbox_batch(args.device, prefix)
                inbox_seen_numbers.update(item["number"] for item in prefix if item["number"] not in batch_missing)
                result["inbox_missing_source_sms"].extend(batch_missing)
                result["inbox_batches"].append({"through_source_number": index,
                                                "inbox_rows": len(inbox_rows),
                                                "matched_source_messages": index - len(batch_missing),
                                                "missing_source_numbers": batch_missing})
                save()

        result["steps"].append(f"injected all {sms_sent} source SMS messages through Android SMS_RECEIVED; local AI requests={len(fixture.requests)}")
        inbox_rows, final_batch_missing = wait_for_inbox_batch(args.device, messages, timeout=120)
        result["steps"].append("waited up to 120 seconds for every source SMS to appear in the retained QA Inbox")
        time.sleep(60)
        result["steps"].append("allowed 60 seconds for queued SmsReceiver work to persist before snapshot")
        receiver_logs = adb(args.device, "logcat", "-d", "-v", "threadtime", timeout=60).decode("utf-8", errors="replace")
        (output / "receiver-logcat.txt").write_text("\n".join(
            line for line in receiver_logs.splitlines()
            if any(tag in line for tag in ("SmsReceiver", "Repo", "ReminderMgr"))
        ), encoding="utf-8")
        records, local_accounts = sqlite_snapshot(args.device, output)
        user.reauthenticate()
        statements = user.documents("creditStatements")
        result["final_statements"] = statements
        inbox_seen_numbers.update(item["number"] for item in messages if item["number"] not in final_batch_missing)
        result["inbox_missing_source_sms"] = list(final_batch_missing)
        outcomes = evaluate_rows(messages, inbox_rows, records, statements, inbox_seen_numbers)
        result["inbox_count"] = len(inbox_rows)
        result["matched_source_sms_count"] = len(inbox_seen_numbers)
        result["inbox_missing_source_sms"] = sorted(set(result["inbox_missing_source_sms"]))
        result["inbox_missing_count"] = len(result["inbox_missing_source_sms"])
        result["multipart_extra_inbox_rows"] = max(0, len(inbox_rows) - len(messages))
        result["local_account_count"] = len(local_accounts)
        result["record_count"] = len(records)
        result["statement_count"] = len(statements)
        result["outcome_counts"] = dict(__import__("collections").Counter(item["status"] for item in outcomes))
        result["message_results"] = outcomes
        result["local_accounts"] = local_accounts
        failures = [item for item in outcomes if item["status"] in {"UNEXPECTED_RECORD", "EXPECTED_RECORD_MISSING", "EXPECTED_STATEMENT_MISSING", "OUTPUT_MISMATCH", "STATEMENT_MISSING_AFTER_PARTIAL_PAYMENT", "PAYMENT_PAIR_DESTINATION_AMOUNT_MISSING"}]
        partials = [item for item in outcomes if item["status"] in {"PAYMENT_PAIR_OR_PENDING_LINK_REQUIRES_REVIEW", "NO_RECORD_NO_INBOX_EVIDENCE", "PARTIAL_STATEMENT_FOLLOWED_BY_MATCHING_PAYMENT", "PARTIAL_STATEMENT_PAYMENT_COVERAGE_INFERRED", "PAYMENT_PAIR_LINKED_TO_COUNTERPART_SMS"}]
        result["status"] = "FAIL" if failures else "PARTIAL" if partials else "PASS"
        result["steps"].append("correlated received Inbox events to persisted Room records by SmsBroadcastId and compared type/category/amount for every direct record")
    except Exception as error:
        result["status"] = "FAIL"
        result["error"] = f"{type(error).__name__}: {error}"
        result["injected"] = sms_sent
    finally:
        if fixture is not None:
            try:
                fixture.close()
            except Exception as error:
                result["cleanup"]["ai_stub_warning"] = f"{type(error).__name__}: {error}"
        if device is not None:
            try:
                device.adb("shell", "pm", "clear", PACKAGE)
                result["cleanup"]["app_data_cleared"] = True
            except Exception as error:
                result["cleanup"]["app_data"] = f"FAILED: {type(error).__name__}: {error}"
        if user is not None:
            try:
                result["cleanup"]["deleted_documents"] = user.delete_test_identity(COLLECTIONS)
                result["cleanup"]["identity"] = "AUTH_IDENTITY_AND_FIRESTORE_FIXTURES_DELETED_VERIFIED"
            except Exception as error:
                result["cleanup"]["identity"] = f"FAILED: {type(error).__name__}: {error}"
                result["status"] = "FAIL"
        if device is not None:
            try:
                result["cleanup"]["sms_rows_deleted"] = clear_inbox(args.device)
                result["cleanup"]["sms_delete_result"] = "Deleted QA Inbox rows using Android provider and verified raw row count"
                if inbox_count(args.device):
                    result["cleanup"]["inbox"] = "REQUIRES_MANUAL_CLEANUP"
                    result["status"] = "FAIL"
                else:
                    result["cleanup"]["inbox"] = "QA_AVD_INBOX_EMPTY_VERIFIED"
            except Exception as error:
                result["cleanup"]["inbox"] = f"FAILED: {type(error).__name__}: {error}"
                result["status"] = "FAIL"
            for permission in ("android.permission.RECEIVE_SMS", "android.permission.READ_SMS", "android.permission.POST_NOTIFICATIONS"):
                try:
                    device.adb("shell", "pm", "revoke", PACKAGE, permission)
                except Exception:
                    pass
            try:
                device.adb("shell", "appops", "set", "com.android.shell", "WRITE_SMS", "ignore")
            except Exception:
                pass
            try:
                device.adb("shell", "appops", "set", PACKAGE, "READ_RESTRICTED_MESSAGES", "ignore")
            except Exception:
                pass
            try:
                device.driver.quit()
            except Exception:
                pass
        result["finished_at"] = datetime.now(timezone.utc).isoformat()
        result["evidence"] = str(output)
        save()
    print(json.dumps({"case": result["case"], "status": result["status"], "injected": result.get("injected", 0),
                      "record_count": result.get("record_count"), "outcome_counts": result.get("outcome_counts"),
                      "cleanup": result["cleanup"], "evidence": str(output)}, indent=2, ensure_ascii=False))
    raise SystemExit(0 if result["status"] == "PASS" and result["cleanup"].get("identity", "").startswith("AUTH_IDENTITY") else 1)


if __name__ == "__main__":
    main()
