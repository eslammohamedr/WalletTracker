import argparse
import copy
import json
import re
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from decimal import Decimal

from appium.webdriver.common.appiumby import AppiumBy

from device_user_isolation_case import new_credentials
from qa_ai_fixture import AiFixture
from qa_campaign_support import Device, FirebaseUser, PACKAGE, ROOT


CATALOG = json.loads((ROOT / "scripts/onboarding_campaign_cases.json").read_text(encoding="utf-8"))


def card(ui, suffix):
    for _ in range(6):
        root = ET.fromstring(ui.driver.page_source)
        parents = {child: parent for parent in root.iter() for child in parent}
        node = next((node for node in root.iter() if node.get("class") == "android.widget.EditText" and suffix in node.get("text", "")), None)
        while node is not None:
            if any(child.get("class") == "android.widget.CheckBox" for child in node.iter()):
                return node
            node = parents.get(node)
        size = ui.driver.get_window_size()
        ui.driver.swipe(size["width"] // 2, int(size["height"] * .68), size["width"] // 2, int(size["height"] * .32), 300)
    raise AssertionError(f"Discovered card {suffix} is missing")


def deselect(ui, suffix):
    node = next(node for node in card(ui, suffix).iter() if node.get("class") == "android.widget.CheckBox")
    assert node.get("checked") == "true", f"Card {suffix} was already deselected"
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    ui.adb("shell", "input", "tap", (left + right) // 2, (top + bottom) // 2)


def edit_card(ui, values):
    node = card(ui, values["suffix"])
    name_node = next(node for node in node.iter() if node.get("class") == "android.widget.EditText" and values["suffix"] in node.get("text", ""))
    element = ui.driver.find_element(AppiumBy.XPATH, f'//android.widget.EditText[@text="{name_node.get("text")}"]')
    element.clear()
    ui.driver.find_element(AppiumBy.XPATH, '//android.widget.EditText[.//*[@text="Display Name"] and @text=""]').send_keys(values["name"])
    limit = ui.driver.find_element(AppiumBy.XPATH, '//android.widget.EditText[.//*[@text="Credit Limit"]]')
    limit.clear()
    ui.driver.find_element(AppiumBy.XPATH, '//android.widget.EditText[.//*[@text="Credit Limit"]]').send_keys(values["limit"])
    deselect(ui, values["deselect"])


def scan(ui):
    ui.tap("Scan My SMS History")
    ui.wait(lambda: any(label in ui.texts() for label in ("Accounts Discovered", "No Accounts Found", "Error")), "SMS discovery")


def import_accounts(ui):
    label = next((text for text in ui.texts() if re.fullmatch(r"Import \d+ Accounts", text)), None)
    assert label, "No selected accounts available for import"
    ui.tap(label)


def wait_import(ui):
    previous = None
    changed_at = time.monotonic()
    started = changed_at
    while time.monotonic() - started < 90:
        texts = ui.texts()
        if "You're All Set!" in texts:
            return
        if "Error" in texts:
            raise AssertionError(f"Import error: {texts}")
        progress = tuple(texts)
        if progress != previous:
            changed_at = time.monotonic()
            previous = progress
        if time.monotonic() - changed_at > 10:
            raise AssertionError(f"Import made no visible progress for 10 seconds: {texts}")
        time.sleep(.2)
    raise AssertionError("Import exceeded its 90-second overall case limit")


def verify_data(ui, user, fixture, result):
    accounts = user.documents("accounts")
    records = user.documents("records")
    statements = user.documents("creditStatements")
    result["actual"] = {"accounts": accounts, "records": records, "statements": statements}
    (ui.output / "data_snapshot.json").write_text(json.dumps(result["actual"], indent=2), encoding="utf-8")
    groups = fixture["groups"]
    expected_count = len(groups) + (0 if fixture.get("empty") else 1)
    assert len(accounts) == expected_count, f"Account count: expected {expected_count}, got {len(accounts)}; accounts={accounts}"
    for expected in groups:
        candidates = [account for account in accounts if account.get("last4Digits") == expected["suffix"] and (
            account.get("name") == expected["name"] if expected.get("name") else expected["bank"] in account.get("name", ""))]
        assert len(candidates) == 1, f"Expected one {expected['bank']} account {expected['suffix']}; got {candidates}"
        account = candidates[0]
        assert account["accountType"] == expected["type"], f"Wrong account type: {account}"
        assert Decimal(account["amount"]) == Decimal(expected["balance"]), f"Wrong account balance: {account}"
        if "limit" in expected:
            assert Decimal(str(account["creditLimit"])) == Decimal(expected["limit"]), f"Credit limit did not persist: {account}"
    if not fixture.get("empty"):
        cash = [account for account in accounts if account["accountType"] == "Cash"]
        assert len(cash) == 1 and Decimal(cash[0]["amount"]) == 0, f"Expected exactly one zero-balance Cash account: {cash}"
    assert len(records) == len(fixture["records"]), f"Record count: expected {len(fixture['records'])}, got {len(records)}"
    unmatched = records.copy()
    for expected in fixture["records"]:
        matches = [record for record in unmatched if Decimal(record["amount"]) == Decimal(expected["amount"]) and record["type"] == expected["type"] and record["category"] == expected["category"]]
        assert matches, f"Missing exact amount/type/category: {expected}; records={unmatched}"
        record = matches[0]
        unmatched.remove(record)
        assert bool(record.get("accountId")) != expected.get("unlinked", False), f"Wrong account link: {record}"
        if not expected.get("unlinked", False):
            linked = next((account for account in accounts if account["id"] == record["accountId"]), None)
            assert linked is not None, f"Record linked to a missing account: {record}"
            if "bank" in expected:
                assert expected["bank"] in linked["name"], f"Cross-bank record routing: {record}; account={linked}"
        if "balanceAfter" in expected:
            assert Decimal(record["balanceAfter"]) == Decimal(expected["balanceAfter"]), f"Wrong running balance: {record}"
        if "date" in expected:
            timestamp = datetime.fromisoformat(record["timestamp"].replace("Z", "+00:00")).timestamp() * 1000
            assert int(timestamp) == expected["date"], f"Wrong historical date: {record}"
    if "statement_amount" in fixture:
        assert len(statements) == 1, f"Expected one separate statement, got {statements}"
        assert Decimal(str(statements[0]["totalAmount"])) == Decimal(fixture["statement_amount"]), f"Wrong statement amount: {statements}"
        assert statements[0]["dueDate"].startswith("2026-10-09T21:00:00") or statements[0]["dueDate"].startswith("2026-10-10T00:00:00"), f"Wrong statement due date: {statements}"
    expected_total = sum((Decimal(group["balance"]) for group in groups if group["type"] == "Debit"), Decimal(0))
    ui.wait_text("TOTAL BALANCE")
    texts = ui.texts()
    total = Decimal(texts[texts.index("TOTAL BALANCE") + 1].replace(",", ""))
    assert total == expected_total, f"Dashboard total expected {expected_total}, got {total}"
    ui.capture("dashboard_verified")
    if records:
        ui.tap("Records", description=True)
        ui.wait_text("All Records")
        ui.capture("records_verified")
        ui.home()
    result["steps"].append("Verified exact account ownership/type/balance, record count/amount/type/category, historical dates and dashboard total")


def assign_unlinked(ui, user, result):
    ui.reveal("2 records need account assignment")
    ui.tap("2 records need account assignment")
    ui.tap("Groceries")
    for record_label in ("Groceries", "Salary"):
        if record_label == "Salary":
            ui.tap("Salary")
        ui.wait_text("Assign to account:")
        ui.tap("HSBC ****1234")
        if record_label == "Groceries":
            ui.wait(lambda: "Unlinked Records" in ui.texts() and "Groceries" not in ui.texts(), "first unlinked record assigned")
    ui.home()
    ui.driver.terminate_app(PACKAGE)
    ui.driver.activate_app(PACKAGE)
    ui.wait_text("TOTAL BALANCE")
    assert all(record.get("accountId") for record in user.documents("records")), "Assignment did not persist"
    assert not any("need account assignment" in text for text in ui.texts()), "Unlinked banner remained after assignment"
    ui.capture("assigned_records_after_restart")
    result["steps"].append("Assigned both deselected-card records through Home and verified links after restart")


def run_case(ui, case_id, fixture, branch="default"):
    parent = ui.output
    ui.output = parent / (case_id + "-" + branch)
    ui.output.mkdir(parents=True, exist_ok=True)
    credentials = new_credentials(case_id.lower())
    (ui.output / "credentials.json").write_text(json.dumps(credentials), encoding="utf-8")
    result = {"case": case_id, "branch": branch, "status": "IN_PROGRESS", "steps": [], "expected": fixture, "cleanup": {}, "device": ui.serial,
              "environment": "Debug APK; dated provider fixtures; Android 37 READ_RESTRICTED_MESSAGES allowed on disposable AVD only"}
    user = None
    ai_fixture = None
    sms_ids = []
    phase = "setup"

    def save():
        (ui.output / "results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")

    print(f"START {case_id} {branch}", flush=True)
    save()
    try:
        sms_ids = ui.seed_sms(fixture["messages"])
        result["fixture_sms_ids"] = sms_ids
        if fixture.get("ai"):
            ai_fixture = AiFixture(ui, fixture["ai"]["category"])
            ai_fixture.start()
            result["environment"] += "; controlled localhost AI responses, not external provider certification"
        ui.signup(credentials)
        user = FirebaseUser(credentials)
        result["steps"].append("Registered fresh disposable identity through the app and seeded dated SMS in the emulator inbox")
        if branch == "existing_cash":
            user.put("accounts", "existing-cash", {"name": "Cash", "accountType": "Cash", "last4Digits": "", "amount": "0.00", "currency": "EGP", "color": 4283215696})
        phase = "discovery"
        if fixture.get("deny_permission"):
            ui.adb("shell", "pm", "revoke", PACKAGE, "android.permission.READ_SMS")
            permission_dump = ui.adb("shell", "dumpsys", "package", PACKAGE)
            permission_match = re.search(r"android\.permission\.READ_SMS: granted=(true|false)", permission_dump)
            result["permission_checks"] = [{"phase": "before_denied_scan", "granted": permission_match.group(1) if permission_match else "unknown"}]
            assert permission_match and permission_match.group(1) == "false", "Android did not revoke READ_SMS before the denial test"
            ui.driver.activate_app(PACKAGE)
            denial_label = next((label for label in ("Don't allow", "Don’t allow") if label in ui.texts()), None)
            if denial_label:
                ui.tap(denial_label)
            else:
                ui.wait_text("Welcome to Wallet Trackers")
            ui.adb("shell", "pm", "revoke", PACKAGE, "android.permission.READ_SMS")
            permission_dump = ui.adb("shell", "dumpsys", "package", PACKAGE)
            permission_match = re.search(r"android\.permission\.READ_SMS: granted=(true|false)", permission_dump)
            result["permission_checks"].append({"phase": "denied_after_app_foregrounded", "granted": permission_match.group(1) if permission_match else "unknown"})
            assert permission_match and permission_match.group(1) == "false", "Could not hold READ_SMS denied after foregrounding the app"
            scan(ui)
            ui.wait_text("Error")
            assert user.documents("accounts") == [] and user.documents("records") == [], "Permission denial imported data"
            ui.capture("permission_denied")
            ui.tap("OK")
            ui.adb("shell", "pm", "grant", PACKAGE, "android.permission.READ_SMS")
            result["steps"].append("Denied SMS permission, verified no data import, granted permission and retried")
        scan(ui)
        ui.capture("discovery")
        if fixture.get("empty"):
            ui.wait_text("No Accounts Found")
            ui.finish_intro()
        else:
            ui.wait_text("Accounts Discovered")
            if fixture.get("merge_branches"):
                card(ui, "2222")
                ui.wait_text("Merge")
                if branch == "accept_merge":
                    ui.tap("Merge")
                    fixture["groups"] = [fixture["groups"][1]]
                result["steps"].append("Accepted replacement-card merge" if branch == "accept_merge" else "Kept replacement-card groups separate without accepting Merge")
            if fixture.get("edit"):
                edit_card(ui, fixture["edit"])
            if fixture.get("deselect"):
                deselect(ui, fixture["deselect"])
            ui.capture("confirmed_discovery")
            phase = "import"
            import_accounts(ui)
            if fixture.get("interrupt"):
                ui.wait(lambda: bool(user.documents("records")), "first persisted import record")
                partial = len(user.documents("records"))
                assert 0 < partial < len(fixture["records"]), f"Missed interruption window: {partial} records"
                ui.adb("shell", "am", "force-stop", PACKAGE)
                result["steps"].append(f"Interrupted after {partial} of {len(fixture['records'])} records persisted")
                ui.driver.activate_app(PACKAGE)
                ui.wait_text("Welcome to Wallet Trackers")
                scan(ui)
                import_accounts(ui)
            wait_import(ui)
            ui.capture("import_complete")
            ui.finish_intro()
        phase = "verification"
        if ai_fixture:
            expected_providers = fixture["ai"].get("expected_providers", ["groq"])
            ai_fixture.verify(expected_providers, [fixture["ai"]["sms"]] if "sms" in fixture["ai"] else None)
            result["steps"].append("Only the unresolved merchant reached the controlled AI provider; recognized records bypassed AI")
        if fixture.get("restart"):
            ui.driver.terminate_app(PACKAGE)
            ui.driver.activate_app(PACKAGE)
            ui.wait_text("TOTAL BALANCE")
        verify_data(ui, user, fixture, result)
        if fixture.get("assign_unlinked"):
            assign_unlinked(ui, user, result)
        result["status"] = "PASS"
    except KeyboardInterrupt:
        result["status"] = "ERROR"
        result["failure_phase"] = phase
        result["error"] = "Campaign interrupted; assertions did not complete"
    except Exception as error:
        result["status"] = "ERROR" if phase == "setup" else "FAIL"
        result["failure_phase"] = phase
        result["error"] = f"{type(error).__name__}: {error}"
        ui.capture("failure")
    finally:
        if ai_fixture:
            result["provider_requests"] = ai_fixture.requests
            ai_fixture.close()
            result["cleanup"]["ai_override"] = "REMOVED_AND_SERVER_STOPPED"
        ui.adb("shell", "pm", "grant", PACKAGE, "android.permission.READ_SMS")
        if user is not None:
            try:
                ui.home()
                ui.delete_user(credentials)
                assert user.documents("accounts") == [] and user.documents("records") == [] and user.documents("creditStatements") == [], "Deleted user retained documents"
                try:
                    FirebaseUser(credentials)
                except RuntimeError as error:
                    assert str(error) in {"INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "USER_NOT_FOUND"}, str(error)
                else:
                    raise AssertionError("Deleted identity still authenticates")
                result["cleanup"]["identity"] = "DELETED_AND_DATA_VERIFIED_EMPTY"
            except Exception as error:
                result["cleanup"]["identity"] = f"FAILED: {type(error).__name__}: {error}"
                result["status"] = "ERROR"
                ui.capture("cleanup_failure")
        try:
            ui.delete_sms(getattr(ui, "fixture_sms_ids", sms_ids))
            result["cleanup"]["sms"] = "DELETED_AND_VERIFIED"
        except Exception as error:
            result["cleanup"]["sms"] = f"FAILED: {error}"
            result["status"] = "ERROR"
        result["finished_at"] = datetime.now(timezone.utc).isoformat()
        save()
        ui.output = parent
    print(f"END {case_id} {branch}: {result['status']} {result.get('error', '')}", flush=True)
    return result


def execute(ui, selected):
    results = []
    for case_id in selected:
        fixture = copy.deepcopy(CATALOG[case_id])
        if fixture.get("interrupt"):
            fixture["messages"] = [{"body": "HSBC account ****1717 credited EGP 100.00 as salary. Available balance EGP 100.00"}]
            fixture["records"] = [{"amount": "100.00", "type": "Income", "category": "Salary"}]
            for index in range(30):
                fixture["messages"].append({"body": f"HSBC account ****1717 debited EGP 1.00 at Carrefour. Available balance EGP {99-index}.00"})
                fixture["records"].append({"amount": "1.00", "type": "Expense", "category": "Groceries"})
        branches = ["keep_separate", "accept_merge"] if fixture.get("merge_branches") else ["new_cash", "existing_cash"] if fixture.get("cash_branches") else ["default"]
        for branch in branches:
            result = run_case(ui, case_id, copy.deepcopy(fixture), branch)
            results.append(result)
            if result["status"] == "ERROR":
                return results
    return results


def main():
    parser = argparse.ArgumentParser(description="Fresh-identity real-app onboarding campaign with dated Android inbox fixtures and exact persisted value assertions")
    parser.add_argument("--device", default="emulator-5558")
    parser.add_argument("--cases", nargs="+", choices=sorted(CATALOG), default=sorted(CATALOG))
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        parser.error("Pass --execute to create and delete disposable identities and their emulator SMS fixtures")
    ui = Device(args.device, ROOT / "app/build/device-smoke" / ("onboarding-campaign-" + time.strftime("%Y%m%d-%H%M%S")))
    try:
        results = execute(ui, args.cases)
        print(json.dumps({"results": [{"case": item["case"], "branch": item["branch"], "status": item["status"]} for item in results], "evidence": str(ui.output)}, indent=2))
    finally:
        ui.restore_fixture_access()
        ui.driver.quit()
    raise SystemExit(0 if all(item["status"] == "PASS" for item in results) else 1)


if __name__ == "__main__":
    main()
