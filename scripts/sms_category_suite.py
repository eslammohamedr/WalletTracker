import argparse
from collections import Counter
from datetime import datetime
from decimal import Decimal
import hashlib
import html
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/test/resources/sms_export.txt"
PLANS = ROOT / "scripts/sms_category_cases.json"


def read_messages():
    content = SOURCE.read_text(encoding="utf-8-sig")
    blocks = re.split(r"(?m)^SMS #(\d+)\s*$", content)
    messages = {}
    for offset in range(1, len(blocks), 2):
        number, block = blocks[offset:offset + 2]
        fields = {}
        for label in ("Sender", "Type", "Category"):
            match = re.search(rf"(?m)^{label}\s*:\s*([^\r\n]*)", block)
            if not match:
                raise ValueError(f"SMS #{number} has no {label}")
            fields[label.lower()] = match.group(1).strip()
        body = re.search(r"Body\s*:\s*\n(.*?)(?=\n--- App extracted ---)", block, re.S)
        if not body:
            raise ValueError(f"SMS #{number} has no body")
        messages[int(number)] = {**fields, "body": body.group(1).strip()}
    declared = re.search(r"Total messages:\s*(\d+)", content)
    if declared and len(messages) != int(declared.group(1)):
        raise ValueError("Source message count mismatch")
    return messages


def body_template(message):
    body = message["body"]
    if "USD" in body or "EUR" in body:
        raise ValueError("Foreign-currency samples require a separate reviewed FX case")
    body, suffix_count = re.subn(r"\*+\s*[-]?\d{3,4}", "****{suffix}", body)
    if suffix_count != 1:
        raise ValueError("Expected exactly one account/card identifier")
    body, amount_count = re.subn(r"\bEGP\s+[\d,]+(?:\.\d+)?", "{currency} {amount}", body, count=1)
    if amount_count != 1:
        raise ValueError("Expected one leading EGP transaction amount")
    body, balance_count = re.subn(r"\bEGP\s+[\d,]+(?:\.\d+)?", "{currency} {balance}", body)
    if balance_count > 1:
        raise ValueError("Multiple secondary amounts need manual review")
    return body + " {marker}"


def save_reports(destination, inventory, executions):
    report = {**inventory, "executions": executions}
    result_name = "SMS_CATEGORY_RESULTS.json" if destination == ROOT else "category-results.json"
    html_name = "SMS_CATEGORY_REPORT.html" if destination == ROOT else "index.html"
    (destination / result_name).write_text(json.dumps(report, indent=2), encoding="utf-8")
    rows = []
    for category in inventory["categories"]:
        related = [execution for execution in executions if category["category"] in
                   (execution["expected_category"], execution.get("rule_category"))]
        live_checks = "; ".join(execution["id"] + ": " + execution["status"] for execution in related)
        if not related:
            live_checks = "Dedicated statement/ATM cases in the main test report"
        rows.append("<tr>" + "".join(f"<td>{html.escape(str(value))}</td>" for value in (
            category["category"], category["messages"], category["types"], live_checks, category["next_check"]
        )) + "</tr>")
    execution_rows = []
    for execution in executions:
        evidence = execution.get("evidence")
        evidence_link = ""
        if evidence:
            import os
            relative = Path(os.path.relpath(ROOT / evidence, destination)).as_posix()
            evidence_link = f'<a href="{html.escape(relative)}">Evidence</a>'
        execution_rows.append("<tr>" + "".join(f"<td>{html.escape(str(value))}</td>" for value in (
            execution["id"], execution["sms_number"], execution["historical_category"],
            (execution["expected_category"] + " -> " + execution["rule_category"] + " -> " + execution["expected_category"]
             if execution.get("rule_category") else execution["expected_category"]), execution["status"], execution.get("error", "")
        )) + f"<td>{evidence_link}</td></tr>")
    document = """<!doctype html><html lang="en"><meta charset="utf-8">
<title>SMS corpus category tests</title><style>body{font:16px system-ui;margin:32px;color:#182333}
table{border-collapse:collapse;width:100%;margin:20px 0}td,th{border:1px solid #ccd4df;padding:9px;text-align:left}
th{background:#eaf0f7}p{max-width:1000px}</style><h1>SMS corpus category tests</h1>"""
    document += f"<p>{inventory['message_count']} messages; {len(inventory['categories'])} historical category labels. Source SHA-256: {inventory['sha256']}.</p>"
    totals = dict(Counter(execution["status"] for execution in executions))
    document += "<p><strong>Representative case status:</strong> " + html.escape("; ".join(f"{status}: {count}" for status, count in totals.items())) + ". These counts describe this category campaign, not the main 112-case inventory.</p>"
    document += "<p>Historical labels are inventory, not expected results. Each executable case has a reviewed expectation in scripts/sms_category_cases.json. Replay replaces account suffix, transaction amount and printed balance; it retains the source bank wording and merchant. Where supported by the export, replay uses the original HSBC or Banque Misr sender name. Run evidence records the sender actually injected.</p>"
    document += "<p>Steps: verify fixture account and Home balances; inject one SMS; assert linked account, signed amount and category; verify exact account/Home deltas; delete the record; restart; verify record absence and restored balances. A failed cleanup stops the suite. Retained Inbox SMS are recorded in evidence.</p>"
    document += "<h2>Execution</h2><table><tr><th>Case</th><th>Source SMS</th><th>Historical label</th><th>Expected category</th><th>Status</th><th>Error</th><th>Evidence</th></tr>" + "".join(execution_rows) + "</table>"
    document += "<h2>Full label inventory and remaining checks</h2><table><tr><th>Label</th><th>Messages</th><th>Types</th><th>Live checks</th><th>Required coverage</th></tr>" + "".join(rows) + "</table></html>"
    (destination / html_name).write_text(document, encoding="utf-8")


def publish_latest(inventory, executions):
    combined = {execution["id"]: execution for execution in executions}
    runs = sorted((ROOT / "app/build/device-scenarios").glob("categories-*/category-results.json"), key=lambda path: path.stat().st_mtime)
    for run in runs:
        data = json.loads(run.read_text(encoding="utf-8-sig"))
        if data["sha256"] != inventory["sha256"]:
            continue
        for execution in data["executions"]:
            if execution["status"] != "NOT_RUN":
                combined[execution["id"]] = execution
                if execution["status"] == "FAIL" and execution.get("marker"):
                    for result_path in (ROOT / "app/build/device-smoke").glob("*/live_sms_*/results.json"):
                        result = json.loads(result_path.read_text(encoding="utf-8-sig"))
                        if result.get("marker") == execution["marker"] and result.get("manual_cleanup_verification"):
                            execution["cleanup"] = result.get("cleanup", execution.get("cleanup", ""))
                            execution["manual_cleanup_verification"] = result["manual_cleanup_verification"]
                            execution["evidence"] = result_path.relative_to(ROOT).as_posix()
    save_reports(ROOT, inventory, list(combined.values()))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--device", default="emulator-5556")
    parser.add_argument("--case", action="append", dest="cases")
    parser.add_argument("--rules-only", action="store_true", help="Run saved-rule cases after installing the accessible QA APK")
    args = parser.parse_args()
    messages = read_messages()
    plans = json.loads(PLANS.read_text(encoding="utf-8"))
    unknown = set(args.cases or []) - {plan["id"] for plan in plans}
    if unknown:
        parser.error(f"Unknown cases: {sorted(unknown)}")
    destination = ROOT / "app/build/device-scenarios" / ("categories-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    destination.mkdir(parents=True)
    personal = {"Lending", "Rent", "Fast food", "Travel to another city", "Credit", "Cafe", "Clothes"}
    categories = []
    for category in sorted({message["category"] for message in messages.values()}):
        members = [message for message in messages.values() if message["category"] == category]
        next_check = "Replay representative merchants across banks, accounts and currencies."
        if category in personal:
            next_check = "Review ambiguous merchant/person purpose; create a user category rule, replay, delete rule, and verify fallback."
        if category == "Credit Card":
            next_check = "Statement runner: due date, minimum/total, payment matching, reminders and dashboard."
        if category == "Transfer":
            next_check = "ATM runner: debit source, credit Cash exactly once, unchanged total; test historical-import exception."
        if category == "Others":
            next_check = "Separate unresolved merchants, newly recognized merchants, card-payment debits and card-payment receipts."
        categories.append({"category": category, "messages": len(members), "types": dict(Counter(message["type"] for message in members)), "next_check": next_check})
    inventory = {"source": SOURCE.relative_to(ROOT).as_posix(), "sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(), "message_count": len(messages), "categories": categories}
    executions = []
    for plan in plans:
        source = messages[plan["sms_number"]]
        executions.append({**plan, "historical_category": source["category"], "source_sender": source["sender"], "status": "NOT_RUN"})
    save_reports(destination, inventory, executions)
    publish_latest(inventory, executions)
    print(f"Report: {destination / 'index.html'}", flush=True)
    if not args.execute:
        return
    for index, execution in enumerate(executions):
        if args.cases and execution["id"] not in args.cases:
            continue
        if not args.cases and bool(execution.get("rule_category")) != args.rules_only:
            continue
        credit = execution["account_type"] == "credit"
        template = body_template(messages[execution["sms_number"]])
        merchant = execution.get("merchant")
        if execution.get("rule_category"):
            merchant = "QARULE" + uuid.uuid4().hex[:12].upper().translate(str.maketrans("0123456789", "GHIJKLMNOP"))
            if credit:
                merchant = "Fawry " + merchant
                template, replacements = re.subn(r"(?<= at ).*?(?=\s+on\s+\d)", merchant, template)
            else:
                template, replacements = re.subn(r"\b(from|to)\s+.*?(?=\s+with reference)", lambda match: match.group(1) + " " + merchant, template)
            if replacements != 1:
                raise ValueError("Could not isolate the rule merchant in the source template")
            execution["test_merchant"] = merchant
            execution["substitutions"] = "Fixture suffix, amount, balance and unique test-owned counterparty; original source case retained by SMS number."
        sms_sender = "BanqueMisr" if execution["source_sender"] == "Banque Misr" else execution["source_sender"]
        command = [sys.executable, str(ROOT / "scripts/device_sms_expense_case.py"),
                   "--device", args.device, "--ui-backend", "appium", "--account-name", "TestCard" if credit else "MainBank",
                   "--account-suffix", "3333" if credit else "1111", "--baseline-balance", "3000" if credit else "10000",
                   "--account-type", execution["account_type"], "--amount", str(Decimal("0.21") + Decimal(index) / 100),
                   "--category", execution["expected_category"], "--transaction-type", execution.get("transaction_type", "expense"),
                   "--sms-sender", sms_sender, "--sms-body-template", template]
        if merchant:
            command.extend(["--merchant", merchant])
        if execution.get("rule_category"):
            command.extend(["--rule-category", execution["rule_category"], "--rule-parent", execution["rule_parent"]])
        execution["status"] = "RUNNING"
        save_reports(destination, inventory, executions)
        print(f"Running {execution['id']} (source SMS #{execution['sms_number']})", flush=True)
        before = set((ROOT / "app/build/device-smoke").glob("*/live_sms_*/results.json"))
        with (destination / (execution["id"] + ".log")).open("w", encoding="utf-8") as log:
            process = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        added = set((ROOT / "app/build/device-smoke").glob("*/live_sms_*/results.json")) - before
        if len(added) != 1:
            execution.update(status="ERROR", error="Runner did not produce exactly one result file")
            save_reports(destination, inventory, executions)
            break
        evidence = added.pop()
        result = json.loads(evidence.read_text(encoding="utf-8"))
        execution.update(status=result["status"], marker=result.get("marker"), evidence=evidence.relative_to(ROOT).as_posix(), error=result.get("error", ""), cleanup=result.get("cleanup", ""))
        save_reports(destination, inventory, executions)
        publish_latest(inventory, executions)
        print(f"{execution['id']}: {execution['status']}", flush=True)
        if process.returncode or result["status"] != "PASS":
            print("Suite stopped for failure investigation and cleanup verification.", flush=True)
            break


if __name__ == "__main__":
    main()
