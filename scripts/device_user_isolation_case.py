import argparse
import json
import secrets
import time
from datetime import datetime, timezone
from decimal import Decimal

from qa_campaign_support import Device, FirebaseUser, ROOT


def new_credentials(label):
    return {"email": f"wallet.qa.isolation.{time.time_ns()}.{label}@example.com", "password": secrets.token_hex(16)}


def execute(device, credentials, already_registered_a=False):
    result = {"case": "APP-12", "status": "IN_PROGRESS", "device": device.serial, "steps": [], "cleanup": {}}
    users = {}
    current = None
    fixtures = {
        "user_a": {"name": "Isolation Alpha", "balance": "87.66", "amount": "12.34", "type": "Expense", "category": "Groceries"},
        "user_b": {"name": "Isolation Beta", "balance": "225.00", "amount": "25.00", "type": "Income", "category": "Salary"},
    }

    def save():
        (device.output / "results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")

    def fixture(label):
        user = users[label]
        values = fixtures[label]
        assert user.documents("accounts") == [] and user.documents("records") == [], "Fresh identity contains existing data"
        user.put("accounts", label + "-account", {
            "name": values["name"], "accountType": "Debit", "last4Digits": "1212" if label == "user_a" else "3434",
            "amount": values["balance"], "currency": "EGP", "color": 4283215696, "isArchived": False, "sortOrder": 0,
        })
        user.put("records", label + "-record", {
            "accountId": label + "-account", "accountName": values["name"], "amount": values["amount"],
            "category": values["category"], "currency": "EGP", "type": values["type"],
            "timestamp": {"timestampValue": datetime.now(timezone.utc).isoformat()}, "comment": "APP-12 " + label,
            "balanceAfter": values["balance"], "balanceBefore": "100.00" if label == "user_a" else "200.00",
        })
        result["steps"].append(f"Seeded authenticated {label} with one unique account and one record")

    def verify(label, stage):
        values = fixtures[label]
        other = fixtures["user_b" if label == "user_a" else "user_a"]
        device.wait_text(values["name"])
        texts = device.texts()
        total = Decimal(texts[texts.index("TOTAL BALANCE") + 1].replace(",", ""))
        assert total == Decimal(values["balance"]), f"{label}: dashboard {total}, expected {values['balance']}"
        assert other["name"] not in texts, f"Leaked {other['name']} on {label} dashboard"
        device.capture(stage + "_dashboard")
        device.tap("Records", description=True)
        device.wait_text("All Records")
        device.wait_text(values["name"])
        texts = device.texts()
        assert values["category"] in texts, f"Missing {label} category"
        assert values["amount"] in " ".join(texts).replace(",", ""), f"Missing {label} amount"
        assert other["name"] not in texts, f"Leaked {other['name']} into {label} records"
        device.capture(stage + "_records")
        device.home()
        result["steps"].append(f"{stage}: verified only {label}'s account, record, category and EGP {total} total")
        save()

    try:
        for label in ("user_a", "user_b"):
            if label != "user_a" or not already_registered_a:
                device.signup(credentials[label])
            current = label
            users[label] = FirebaseUser(credentials[label])
            device.finish_intro()
            fixture(label)
            verify(label, "initial_" + label)
            device.signout()
            current = None
        for label in ("user_a", "user_b", "user_a"):
            device.login(credentials[label])
            current = label
            verify(label, "switch_" + label + "_" + str(len(result["steps"])))
            device.signout()
            current = None
        result["status"] = "PASS"
    except Exception as error:
        result["status"] = "FAIL"
        result["error"] = f"{type(error).__name__}: {error}"
        device.capture("failure")
    finally:
        for label, user in users.items():
            try:
                if current and current != label:
                    device.signout()
                    current = None
                if current != label:
                    device.login(credentials[label])
                    current = label
                device.delete_user(credentials[label])
                current = None
                assert user.documents("accounts") == [] and user.documents("records") == [], "Deleted identity retained fixture documents"
                try:
                    FirebaseUser(credentials[label])
                except RuntimeError as error:
                    if str(error) not in {"INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "USER_NOT_FOUND"}:
                        raise
                else:
                    raise AssertionError("Deleted identity still accepts its password")
                result["cleanup"][label] = "DELETED_AUTH_IDENTITY_AND_VERIFIED_EMPTY_FIRESTORE"
            except Exception as error:
                result["cleanup"][label] = f"FAILED: {type(error).__name__}: {error}"
                result["status"] = "FAIL"
                device.capture("cleanup_failure_" + label)
        result["finished_at"] = datetime.now(timezone.utc).isoformat()
        save()
    return result


def main():
    parser = argparse.ArgumentParser(description="APP-12: two real Firebase logins on one QA emulator with isolated account/record values")
    parser.add_argument("--device", default="emulator-5558")
    parser.add_argument("--execute", action="store_true")
    arguments = parser.parse_args()
    if not arguments.execute:
        parser.error("Pass --execute to create and delete the two case-owned QA identities")
    output = ROOT / "app/build/device-smoke" / ("user-isolation-" + time.strftime("%Y%m%d-%H%M%S"))
    output.mkdir(parents=True, exist_ok=True)
    credentials = {label: new_credentials(label) for label in ("user_a", "user_b")}
    (output / "credentials.json").write_text(json.dumps(credentials), encoding="utf-8")
    device = Device(arguments.device, output)
    try:
        result = execute(device, credentials)
        print(json.dumps({"case": "APP-12", "status": result["status"], "evidence": str(output)}, indent=2))
    finally:
        device.driver.quit()
    raise SystemExit(0 if result["status"] == "PASS" else 1)


if __name__ == "__main__":
    main()
