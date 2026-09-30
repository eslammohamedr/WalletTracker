"""Live DATA-07 and APP-08 campaign cases with disposable, explicitly seeded users.

Call run_case(existing_device, "DATA-07") or run_case(existing_device, "APP-08")
from the campaign while the app is signed out and the QA SMS inbox is empty.
Initialization uses authenticated Firestore REST writes; all acceptance assertions
use the Android UI, and budget threshold events arrive through emulator SMS.
"""

import calendar
import json
import re
import time
from datetime import datetime, timezone
from decimal import Decimal
from zoneinfo import ZoneInfo
from xml.etree import ElementTree

from device_user_isolation_case import new_credentials
from qa_ai_fixture import AiFixture
from qa_campaign_support import FirebaseUser


LOCAL_TIMEZONE = ZoneInfo("Africa/Cairo")
COLLECTIONS = (
    "accounts", "records", "budgets", "notifications", "categoryRules",
    "customSubCategories", "creditStatements", "savingsGoals", "debts", "bills",
)
MONTHS = {name: index for index, name in enumerate(calendar.month_name) if name}


def local_timestamp(value):
    local = datetime.fromisoformat(value)
    if local.tzinfo is None:
        local = local.replace(tzinfo=LOCAL_TIMEZONE)
    return {"timestampValue": local.astimezone(timezone.utc).isoformat()}


def screen_tree(ui):
    return ElementTree.fromstring(ui.driver.page_source)


def tap_node(ui, node):
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
    ui.adb("shell", "input", "tap", (left + right) // 2, (top + bottom) // 2)


def home(ui):
    for _ in range(8):
        root = screen_tree(ui)
        if any(node.get("text") == "Got it" for node in root.iter()):
            ui.capture("budget_alert_dialog")
            ui.tap("Got it")
            continue
        if any(node.get("text") == "TOTAL BALANCE" for node in root.iter()):
            return
        if any(node.get("content-desc") == "Menu" for node in root.iter()):
            size = ui.driver.get_window_size()
            ui.driver.swipe(size["width"] // 2, int(size["height"] * 0.35),
                            size["width"] // 2, int(size["height"] * 0.85), 200)
        elif any(node.get("content-desc") == "Home" for node in root.iter()):
            ui.tap("Home", description=True)
        else:
            ui.driver.back()
    ui.wait_text("TOTAL BALANCE")


def navigate_month(ui, year, month, budget=False):
    destination = f"{calendar.month_name[month]} {year}"
    for _ in range(60):
        texts = ui.texts()
        current = next((text for text in texts if re.fullmatch(
            r"(?:" + "|".join(MONTHS) + r") \d{4}", text,
        )), None)
        assert current is not None, "English month heading not visible"
        if current == destination:
            return
        month_name, year_text = current.split()
        forward = (int(year_text), MONTHS[month_name]) < (year, month)
        if budget:
            ui.tap("Next month" if forward else "Previous month", description=True)
        else:
            root = screen_tree(ui)
            heading = next(node for node in root.iter() if node.get("text") == current)
            _, heading_top, _, heading_bottom = map(int, re.findall(r"\d+", heading.get("bounds", "")))
            heading_center_y = (heading_top + heading_bottom) // 2
            controls = []
            for node in root.iter():
                if node.get("clickable") != "true" or node.get("class") != "android.widget.Button":
                    continue
                _, top, _, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
                if abs((top + bottom) // 2 - heading_center_y) <= 100:
                    controls.append(node)
            controls.sort(key=lambda node: int(re.findall(r"\d+", node.get("bounds", ""))[0]))
            if len(controls) != 2:
                raise AssertionError(f"Expected two month-arrow buttons beside {current}; found {len(controls)}")
            tap_node(ui, controls[1 if forward else 0])
        ui.wait(lambda: current not in ui.texts(), "calendar advances one month")
    raise AssertionError(f"Could not reach month {destination} within 60 navigation actions")


def open_budget(ui):
    home(ui)
    ui.tap("Budget", description=True)
    ui.wait_text("Budgets")
    ui.wait(lambda: "Spent" in ui.texts(), "budget cards loaded")


def budget_card(ui, currency):
    root = screen_tree(ui)
    limit_label = f"Limit: 100.00 {currency} / mo"
    limit = next((node for node in root.iter() if node.get("text") == limit_label), None)
    assert limit is not None, f"Missing budget in {currency}"
    parents = {child: parent for parent in root.iter() for child in parent}
    container = parents.get(limit)
    while container is not None:
        texts = [node.get("text") for node in container.iter() if node.get("text")]
        if "Spent" in texts and "Groceries" in texts:
            return texts
        container = parents.get(container)
    raise AssertionError(f"Cannot locate Groceries budget card in {currency}")


def assert_budget(ui, currency, expected):
    expected = Decimal(str(expected))
    texts = budget_card(ui, currency)
    spent = texts[texts.index("Spent") + 1]
    assert spent == f"{expected:,.2f} {currency}", f"Expected spent {expected} {currency}; observed {spent}"
    remaining_label = "Over by" if expected > 100 else "Remaining"
    assert texts[texts.index(remaining_label) + 1] == f"{abs(Decimal(100) - expected):,.2f} {currency}", texts


def notification_signature(ui, output_name):
    dump = ui.adb("shell", "dumpsys", "notification", "--noredact")
    (ui.output / output_name).write_text(dump, encoding="utf-8")
    active = re.split(r"Historical notifications|Notification history|Enqueued Notification List", dump)[0]
    signatures = []
    for block in active.split("NotificationRecord(")[1:]:
        header = block.splitlines()[0]
        if "pkg=com.example.wallettrackers" not in header or "channel=budget_alerts" not in header:
            continue
        title = re.search(r"android.title=String \(([^\n]*)\)", block)
        body = re.search(r"android.text=String \(([^\n]*)\)", block)
        if title and body and "Groceries" in title.group(1):
            signatures.append((title.group(1), body.group(1)))
    return sorted(set(signatures))


class PeriodCase:
    def __init__(self, ui, case_id):
        self.ui = ui
        self.case_id = case_id
        self.credentials = new_credentials(case_id.lower())
        self.user = None
        self.registered = False
        self.sms_ids = set()
        self.result = {"case": case_id, "status": "IN_PROGRESS", "steps": [], "checks": [], "cleanup": {}}
        self.now = None
        self.ai_fixture = None

    def save(self):
        (self.ui.output / "results.json").write_text(json.dumps(self.result, indent=2), encoding="utf-8")

    def check(self, name, action):
        item = {"name": name, "status": "IN_PROGRESS"}
        self.result["checks"].append(item)
        try:
            action()
            item["status"] = "PASS"
        except Exception as error:
            item.update(status="FAIL", error=f"{type(error).__name__}: {error}")
        try:
            self.ui.capture(name)
        except Exception as error:
            item["capture_error"] = str(error)
        self.save()

    def account(self, currency, suffix, amount="1000.00"):
        self.user.put("accounts", f"period-{currency}", {
            "name": f"Period {currency}", "accountType": "Debit", "last4Digits": suffix,
            "amount": amount, "currency": currency, "color": 4283215696,
            "isArchived": False, "sortOrder": {"EGP": 0, "USD": 1, "EUR": 2}[currency],
        })

    def record(self, marker, value, timestamp, currency="EGP", record_type="Expense", category="Groceries"):
        self.user.put("records", marker, {
            "accountId": f"period-{currency}", "accountName": f"Period {currency}",
            "amount": value, "currency": currency, "category": category, "type": record_type,
            "timestamp": local_timestamp(timestamp), "comment": marker,
        })

    def sync_record(self, marker):
        home(self.ui)
        self.ui.tap("Records", description=True)
        self.ui.wait_text("All Records")
        self.ui.wait(lambda: marker in self.ui.texts(), "Firestore initialization visible in real app")
        home(self.ui)

    def inject(self, amount, expected_total, stage, expect_alert):
        home(self.ui)
        before = notification_signature(self.ui, stage + "_notifications_before.txt")
        marker = "PERIOD" + str(time.time_ns())
        balance = Decimal("1000") - Decimal(str(expected_total))
        body = (
            f"Your bank account ****7171 was debited by EGP {amount} at CARREFOUR {marker} "
            f"on {self.now:%d/%m/%Y}. Your available balance is EGP {balance:.2f}."
        )
        self.ui.adb("emu", "sms", "send", "5550001", body)
        self.result["steps"].append({"incoming_sms": body, "expected_spent_egp": str(expected_total)})
        self.save()
        self.ui.wait(lambda: any(marker in record.get("comment", "") for record in self.user.documents("records")), "incoming SMS record persisted")
        records = [record for record in self.user.documents("records") if marker in record.get("comment", "")]
        assert len(records) == 1, f"SMS produced {len(records)} records"
        record = records[0]
        assert record.get("category") == "Groceries" and Decimal(record["amount"]) == Decimal(amount), record
        assert record.get("accountId") == "period-EGP" and record.get("currency") == "EGP", record
        rows = self.ui.adb("shell", "content", "query", "--uri", "content://sms", "--projection", "_id")
        self.sms_ids.update(re.findall(r"_id=(\d+)", rows))
        if expect_alert:
            self.ui.wait_text("Got it")
            self.ui.capture(stage + "_budget_dialog")
            self.ui.tap("Got it")
        self.ui.tap("Records", description=True)
        self.ui.wait_text("All Records")
        self.ui.wait(lambda: any(marker in text for text in self.ui.texts()), "incoming SMS visible in Records")
        self.ui.capture(stage + "_record")
        open_budget(self.ui)
        self.ui.wait(lambda: f"{Decimal(str(expected_total)):,.2f} EGP" in budget_card(self.ui, "EGP"), "updated budget spent")
        assert_budget(self.ui, "EGP", expected_total)
        if expect_alert:
            expected_text = f"Spent {Decimal(str(expected_total)):.2f} / 100.00 EGP"
            self.ui.wait(lambda: any(expected_text in body for _, body in notification_signature(
                self.ui, stage + "_notifications_after.txt",
            )), "budget threshold notification amount")
            after = notification_signature(self.ui, stage + "_notifications_after.txt")
            assert after != before, "Crossed budget threshold did not update notification"
        else:
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                after = notification_signature(self.ui, stage + "_notifications_after.txt")
                assert after == before, f"Unexpected budget alert without threshold crossing: {after}"
                time.sleep(0.2)

    def budgets(self):
        current = self.now.replace(day=1, hour=12, minute=0, second=0, microsecond=0).isoformat()
        self.account("EGP", "7171", "925.02")
        self.user.put("budgets", "groceries-egp", {"category": "Groceries", "monthlyLimit": 100.0, "currency": "EGP"})
        self.record("DATA07_CURRENT_INITIAL", "74.98", current)
        self.record("DATA07_CURRENT_INCOME_EXCLUDED", "300.00", current, record_type="Income")
        self.record("DATA07_CURRENT_OTHER_CATEGORY", "400.00", current, category="Clothes")
        for marker, timestamp, amount in (
            ("SEP_END", "2025-09-30T23:59:59", "13.37"),
            ("OCT_START", "2025-10-01T00:00:00", "22.22"),
            ("YEAR_END", "2025-12-31T23:59:59", "33.33"),
            ("YEAR_START", "2026-01-01T00:00:00", "44.44"),
        ):
            self.record("DATA07_" + marker, amount, timestamp)
        self.sync_record("DATA07_CURRENT_INITIAL")
        open_budget(self.ui)
        self.check("current_month_excludes_income_other_categories_and_history", lambda: assert_budget(self.ui, "EGP", "74.98"))
        for amount, spent, stage, alert in (
            ("0.01", "74.99", "below_75_no_alert", False),
            ("0.01", "75.00", "exact_75_alert", True),
            ("24.99", "99.99", "below_100_no_repeat", False),
            ("0.01", "100.00", "exact_100_alert", True),
            ("0.01", "100.01", "above_100_remaining_negative_no_repeat", False),
        ):
            self.check(stage, lambda amount=amount, spent=spent, stage=stage, alert=alert: self.inject(amount, spent, stage, alert))
        for year, month, expected in ((2025, 9, "13.37"), (2025, 10, "22.22"), (2025, 12, "33.33"), (2026, 1, "44.44")):
            def inspect_period(year=year, month=month, expected=expected):
                open_budget(self.ui)
                navigate_month(self.ui, year, month, budget=True)
                assert_budget(self.ui, "EGP", expected)
            self.check(f"month_{year}_{month:02d}_isolation", inspect_period)
        self.account("USD", "7272")
        self.account("EUR", "7373")
        for currency, amount in (("USD", "8.50"), ("EUR", "9.25")):
            self.user.put("budgets", "groceries-" + currency.lower(), {"category": "Groceries", "monthlyLimit": 100.0, "currency": currency})
            self.record("DATA07_CURRENT_" + currency, amount, current, currency)
        self.result["currency_oracle"] = "Each currency budget includes only expenses denominated in that currency; no undocumented exchange rate is assumed."
        for currency, expected in (("EGP", "100.01"), ("USD", "8.50"), ("EUR", "9.25")):
            def inspect_currency(currency=currency, expected=expected):
                open_budget(self.ui)
                self.ui.reveal(f"Limit: 100.00 {currency} / mo")
                assert_budget(self.ui, currency, expected)
            self.check("currency_scope_" + currency, inspect_currency)

    def calendar(self):
        self.account("EGP", "7171")
        fixtures = (
            ("SEP_LAST_SECOND", "2025-09-30T23:59:59", "11.11", "Expense"),
            ("OCT_MIDNIGHT", "2025-10-01T00:00:00", "22.22", "Expense"),
            ("DEC_LAST_SECOND", "2025-12-31T23:59:59", "33.33", "Expense"),
            ("JAN_MIDNIGHT", "2026-01-01T00:00:00", "44.44", "Income"),
            ("FEB28_LAST_SECOND", "2024-02-28T23:59:59", "55.55", "Expense"),
            ("LEAP_MIDNIGHT", "2024-02-29T00:00:00", "66.66", "Expense"),
            ("LEAP_INCOME", "2024-02-29T12:00:00", "5.00", "Income"),
            ("MAR_MIDNIGHT", "2024-03-01T00:00:00", "77.77", "Expense"),
        )
        self.result["fixtures"] = [{"marker": marker, "timestamp": timestamp, "amount": amount, "type": record_type} for marker, timestamp, amount, record_type in fixtures]
        for marker, timestamp, amount, record_type in fixtures:
            self.record(marker, amount, timestamp, record_type=record_type)
        self.sync_record("JAN_MIDNIGHT")
        self.ui.tap("Menu", description=True)
        self.ui.reveal("Calendar")
        self.ui.tap("Calendar")
        self.ui.wait_text("Calendar")
        dates = sorted({datetime.fromisoformat(timestamp).date() for _, timestamp, _, _ in fixtures}, reverse=True)
        for selected_date in dates:
            def inspect_day(selected_date=selected_date):
                navigate_month(self.ui, selected_date.year, selected_date.month)
                self.ui.tap(str(selected_date.day))
                self.ui.wait_text(f"Day {selected_date.day}")
                expected = [(marker, amount, record_type) for marker, timestamp, amount, record_type in fixtures if datetime.fromisoformat(timestamp).date() == selected_date]
                net = sum((Decimal(amount) if record_type == "Income" else -Decimal(amount) for _, amount, record_type in expected), Decimal(0))
                self.ui.wait_text(f"{'+' if net >= 0 else ''}{net:.2f} EGP")
                texts = self.ui.texts()
                for marker, amount, record_type in expected:
                    self.ui.reveal(marker)
                    self.ui.wait_text(marker)
                    self.ui.wait_text(f"{'+' if record_type == 'Income' else '-'}{amount} EGP")
                texts.extend(self.ui.texts())
                wrong_markers = [marker for marker, timestamp, _, _ in fixtures if datetime.fromisoformat(timestamp).date() != selected_date and marker in texts]
                assert not wrong_markers, f"Records leaked from another local date: {wrong_markers}"
            self.check("calendar_" + selected_date.isoformat(), inspect_day)
        def empty_day():
            navigate_month(self.ui, 2024, 3)
            self.ui.tap("2")
            self.ui.wait_text("Day 2")
            self.ui.wait_text("No records on this day")
        self.check("calendar_empty_day", empty_day)

    def execute(self):
        try:
            self.ui.wait_text("Authentication")
            rows = self.ui.adb("shell", "content", "query", "--uri", "content://sms", "--projection", "_id")
            assert "No result found" in rows, "Requires empty dedicated QA inbox before creating identity"
            device_date = self.ui.adb("shell", "date", "+%Y-%m-%dT%H:%M:%S%z")
            self.now = datetime.strptime(device_date, "%Y-%m-%dT%H:%M:%S%z")
            assert self.ui.adb("shell", "getprop", "persist.sys.timezone") == "Africa/Cairo", "Expected QA device timezone Africa/Cairo"
            self.result["device_local_time"] = self.now.isoformat()
            (self.ui.output / "credentials.json").write_text(json.dumps(self.credentials), encoding="utf-8")
            self.ui.signup(self.credentials)
            self.registered = True
            self.user = FirebaseUser(self.credentials)
            self.ui.finish_intro()
            for collection in COLLECTIONS:
                assert not self.user.documents(collection), f"Fresh identity contains {collection}"
            self.result["steps"].append("Fresh identity registered through UI; intro skipped; empty Firestore verified")
            if self.case_id == "DATA-07":
                self.ai_fixture = AiFixture(self.ui, "Groceries")
                self.ai_fixture.start()
                self.result["environment"] = "Real debug app/Firebase/incoming SMS; Groceries AI response controlled locally to isolate budget logic"
                self.budgets()
            else:
                self.calendar()
            self.result["status"] = "PASS" if self.result["checks"] and all(item["status"] == "PASS" for item in self.result["checks"]) else "FAIL"
        except Exception as error:
            self.result.update(status="FAIL", error=f"{type(error).__name__}: {error}")
            self.ui.capture("failure")
        finally:
            if self.ai_fixture:
                self.result["provider_requests"] = self.ai_fixture.requests
                self.ai_fixture.close()
                self.result["cleanup"]["ai_override"] = "REMOVED_AND_SERVER_STOPPED"
            if self.registered:
                try:
                    home(self.ui)
                    self.ui.delete_user(self.credentials)
                    if self.user is None:
                        raise AssertionError("No pre-deletion Firebase session available to verify fixture removal")
                    for collection in COLLECTIONS:
                        assert self.user.documents(collection) == [], f"Deleted identity retained {collection}"
                    try:
                        FirebaseUser(self.credentials)
                    except RuntimeError as error:
                        assert str(error) in {"INVALID_LOGIN_CREDENTIALS", "EMAIL_NOT_FOUND", "USER_NOT_FOUND"}, str(error)
                    else:
                        raise AssertionError("Deleted identity still accepts password")
                    self.result["cleanup"]["identity"] = "DELETED_AND_ALL_TOUCHED_COLLECTIONS_EMPTY"
                except Exception as error:
                    self.result["cleanup"]["identity"] = f"FAILED: {type(error).__name__}: {error}"
                    self.result["status"] = "FAIL"
            if self.registered and self.case_id == "DATA-07":
                try:
                    rows = self.ui.adb("shell", "content", "query", "--uri", "content://sms", "--projection", "_id")
                    self.sms_ids.update(re.findall(r"_id=(\d+)", rows))
                    self.ui.delete_sms(sorted(self.sms_ids, key=int))
                    self.result["cleanup"]["sms"] = "ALL_CASE_SMS_REMOVED_AND_INBOX_EMPTY"
                except Exception as error:
                    self.result["cleanup"]["sms"] = f"FAILED: {type(error).__name__}: {error}"
                    self.result["status"] = "FAIL"
            self.result["finished_at"] = datetime.now(timezone.utc).isoformat()
            self.save()
        return self.result


def run_case(ui, case_id):
    if case_id not in {"DATA-07", "APP-08"}:
        raise ValueError(f"Unsupported period case: {case_id}")
    parent_output = ui.output
    ui.output = parent_output / (case_id.lower().replace("-", "_") + "_" + str(time.time_ns()))
    ui.output.mkdir(parents=True, exist_ok=True)
    try:
        return PeriodCase(ui, case_id).execute()
    finally:
        ui.output = parent_output
