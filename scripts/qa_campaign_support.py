import json
import os
import re
import subprocess
import time
import urllib.error
import urllib.request
from urllib.parse import urlencode
import xml.etree.ElementTree as ET
from pathlib import Path

from appium import webdriver
from appium.options.android import UiAutomator2Options
from appium.webdriver.common.appiumby import AppiumBy


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.example.wallettrackers"


class FirebaseUser:
    def __init__(self, credentials):
        config = json.loads((ROOT / "app/google-services.json").read_text())
        client = next(item for item in config["client"] if item["client_info"]["android_client_info"]["package_name"] == PACKAGE)
        self.api_key = client["api_key"][0]["current_key"]
        self.project = config["project_info"]["project_id"]
        self.credentials = credentials
        response = self.auth("signInWithPassword", {**credentials, "returnSecureToken": True})
        self.uid = response["localId"]
        self.token = response["idToken"]
        self.base = f"https://firestore.googleapis.com/v1/projects/{self.project}/databases/(default)/documents/users/{self.uid}"

    def request(self, url, payload=None, method=None, authenticated=False):
        headers = {"Content-Type": "application/json"}
        if authenticated:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(url, data=None if payload is None else json.dumps(payload).encode(), headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            details = json.loads(error.read())
            raise RuntimeError(details.get("error", {}).get("message", f"HTTP {error.code}")) from None

    def auth(self, action, payload):
        return self.request(f"https://identitytoolkit.googleapis.com/v1/accounts:{action}?key={self.api_key}", payload)

    def reauthenticate(self):
        response = self.auth("signInWithPassword", {**self.credentials, "returnSecureToken": True})
        self.token = response["idToken"]

    @staticmethod
    def encode(value):
        if value is None:
            return {"nullValue": None}
        if isinstance(value, bool):
            return {"booleanValue": value}
        if isinstance(value, int):
            return {"integerValue": str(value)}
        if isinstance(value, float):
            return {"doubleValue": value}
        if isinstance(value, dict):
            return value
        return {"stringValue": str(value)}

    def put(self, collection, document_id, values):
        fields = {name: self.encode(value) for name, value in {**values, "userId": self.uid}.items()}
        return self.request(f"{self.base}/{collection}/{document_id}", {"fields": fields}, "PATCH", True)

    def documents(self, collection):
        documents = []
        page_token = None
        while True:
            query = {"pageSize": "1000"}
            if page_token:
                query["pageToken"] = page_token
            try:
                result = self.request(f"{self.base}/{collection}?{urlencode(query)}", authenticated=True)
            except RuntimeError as error:
                if not documents and 'lacks "/" at index' in str(error):
                    return []
                raise
            documents.extend({**{
                name: next(iter(value.values())) for name, value in document.get("fields", {}).items()
            }, "id": document["name"].rsplit("/", 1)[-1]} for document in result.get("documents", []))
            page_token = result.get("nextPageToken")
            if not page_token:
                return documents

    def delete_test_identity(self, collections):
        self.reauthenticate()
        deleted = {}
        for collection in collections:
            documents = self.documents(collection)
            for document in documents:
                self.request(f"{self.base}/{collection}/{document['id']}", method="DELETE", authenticated=True)
            if self.documents(collection):
                raise AssertionError(f"Temporary identity retained documents in {collection}")
            deleted[collection] = len(documents)
        self.reauthenticate()
        self.auth("delete", {"idToken": self.token})
        return deleted


class Device:
    def __init__(self, serial, output, driver=None):
        self.serial = serial
        self.output = Path(output)
        self.output.mkdir(parents=True, exist_ok=True)
        avd = self.adb("emu", "avd", "name").splitlines()[0]
        if avd not in {"Wallet_23Cases_Temp", "Wallet_Onboarding_QA"}:
            raise AssertionError(f"Expected dedicated QA AVD, found {avd}")
        self.driver = driver or webdriver.Remote(os.environ.get("APPIUM_SERVER_URL", "http://127.0.0.1:4723"), options=UiAutomator2Options().load_capabilities({
            "platformName": "Android", "appium:automationName": "UiAutomator2",
            "appium:deviceName": serial, "appium:udid": serial,
            "appium:appPackage": PACKAGE, "appium:appActivity": ".MainActivity",
            "appium:noReset": True, "appium:newCommandTimeout": 1200,
            "appium:adbExecTimeout": 60000, "appium:uiautomator2ServerInstallTimeout": 60000,
            "appium:uiautomator2ServerLaunchTimeout": 60000, "appium:appWaitForLaunch": False,
            "appium:uiautomator2ServerReadTimeout": 30000,
        }))
        self.driver.implicitly_wait(0)
        self.driver.update_settings({"waitForIdleTimeout": 300, "waitForSelectorTimeout": 0})

    def adb(self, *arguments):
        return subprocess.check_output(["adb", "-s", self.serial, *map(str, arguments)], timeout=10).decode("utf-8", errors="replace").strip()

    def texts(self):
        return [node.get("text") for node in ET.fromstring(self.driver.page_source).iter() if node.get("text")]

    def wait(self, predicate, description):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.2)
        raise AssertionError(f"Timed out: {description}; visible={self.texts()}")

    def wait_text(self, label):
        self.wait(lambda: label in self.texts(), label)

    def tap(self, label, description=False):
        locator = (AppiumBy.ACCESSIBILITY_ID, label) if description else (
            AppiumBy.ANDROID_UIAUTOMATOR, f"new UiSelector().text({json.dumps(label, ensure_ascii=False)})")
        self.wait(lambda: bool(self.driver.find_elements(*locator)), label)
        self.driver.find_elements(*locator)[-1].click()

    def fill(self, index, value):
        fields = self.driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")
        if index >= len(fields):
            raise AssertionError(f"Missing input {index}; found {len(fields)}")
        fields[index].send_keys(str(value))

    def capture(self, name):
        self.driver.save_screenshot(str(self.output / f"{name}.png"))
        (self.output / f"{name}.xml").write_text(self.driver.page_source, encoding="utf-8")

    def reveal(self, label):
        size = self.driver.get_window_size()
        for _ in range(6):
            if label in self.texts():
                return
            self.adb("shell", "input", "swipe", size["width"] // 2,
                     int(size["height"] * .82), size["width"] // 2,
                     int(size["height"] * .24), 500)
            time.sleep(0.25)
        self.wait_text(label)

    def signup(self, credentials):
        for _ in range(20):
            visible = self.texts()
            if "Allow" in visible and any("Allow WalletTrackers" in text for text in visible):
                self.tap("Allow")
                time.sleep(0.3)
                continue
            break
        self.reveal("Create Account")
        self.tap("Create Account")
        self.wait_text("Your Details")
        for index, value in enumerate([credentials["email"], credentials["password"], credentials["password"]]):
            self.fill(index, value)
        self.tap("Create Account")
        self.wait_text("Welcome to Wallet Trackers")

    def login(self, credentials):
        self.wait_text("Authentication")
        self.fill(0, credentials["email"])
        self.fill(1, credentials["password"])
        self.tap("Sign In")
        self.finish_intro()

    def finish_intro(self):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            texts = self.texts()
            if "TOTAL BALANCE" in texts:
                return
            skip = next((label for label in texts if label.startswith("Skip")), None)
            if skip:
                self.tap(skip)
                self.wait(lambda: skip not in self.texts(), "leave onboarding skip screen")
            elif "Enter Dashboard" in texts:
                self.tap("Enter Dashboard")
                self.wait(lambda: "Enter Dashboard" not in self.texts(), "leave onboarding completion")
            elif "Discover What's Inside" in texts:
                size = self.driver.get_window_size()
                self.adb("shell", "input", "tap", size["width"] // 2, int(size["height"] * 0.08))
            else:
                time.sleep(0.2)
        raise AssertionError(f"Intro did not reach dashboard: {self.texts()}")

    def home(self):
        if self.driver.current_package != PACKAGE:
            self.driver.activate_app(PACKAGE)
        for _ in range(5):
            texts = self.texts()
            if any(label in texts for label in ("Welcome to Wallet Trackers", "Accounts Discovered", "No Accounts Found", "Discover What's Inside", "Enter Dashboard")):
                self.finish_intro()
                return
            if "TOTAL BALANCE" in texts:
                return
            self.driver.back()
        self.wait_text("TOTAL BALANCE")

    def profile_action(self, label):
        self.home()
        self.tap("Profile", description=True)
        self.reveal(label)
        self.tap(label)

    def signout(self):
        self.profile_action("Sign Out")
        self.wait_text("Authentication")

    def delete_user(self, credentials):
        self.profile_action("Delete Account")
        self.wait_text("This will permanently delete your account and all data. This cannot be undone.")
        self.tap("Delete")
        self.wait_text("Confirm account deletion")
        self.fill(0, credentials["password"])
        self.tap("Verify and delete")
        self.wait_text("Authentication")

    def seed_sms(self, messages):
        self.fixture_sms_ids = []
        if "No result found" not in self.adb("shell", "content", "query", "--uri", "content://sms", "--projection", "_id"):
            raise AssertionError("Historical fixture requires an empty QA inbox")
        self.adb("shell", "appops", "set", "com.android.shell", "WRITE_SMS", "allow")
        if int(self.adb("shell", "getprop", "ro.build.version.sdk")) >= 37:
            self.adb("shell", "appops", "set", PACKAGE, "READ_RESTRICTED_MESSAGES", "allow")
        for index, message in enumerate(messages):
            sender = message.get("sender", "HSBC")
            body = message["body"]
            timestamp = message.get("date", 1788177600000 + index * 60000)
            arguments = ["content", "insert", "--uri", "content://sms/inbox", "--bind", f"address:s:{sender}", "--bind", f"body:s:{body}", "--bind", f"date:l:{timestamp}", "--bind", "read:i:1"]
            command = " ".join("'" + value.replace("'", "'\"'\"'") + "'" for value in arguments)
            inserted = self.adb("shell", command)
            if "Error" in inserted or "Exception" in inserted:
                raise AssertionError(f"SMS provider rejected fixture row {index + 1}: {inserted}")
            rows = self.adb("shell", "content", "query", "--uri", "content://sms", "--projection", "_id")
            self.fixture_sms_ids = re.findall(r"_id=(\d+)", rows)
        rows = self.adb("shell", "content", "query", "--uri", "content://sms", "--projection", "_id")
        ids = re.findall(r"_id=(\d+)", rows)
        if len(ids) != len(messages):
            raise AssertionError(f"Seeded {len(messages)} messages but found {len(ids)}")
        return ids

    def delete_sms(self, ids):
        self.adb("shell", "appops", "set", "com.android.shell", "WRITE_SMS", "allow")
        for row_id in ids:
            self.adb("shell", "content", "delete", "--uri", f"content://sms/{int(row_id)}")
        remaining = self.adb("shell", "content", "query", "--uri", "content://sms", "--projection", "_id")
        if "No result found" not in remaining:
            raise AssertionError("Fixture SMS cleanup incomplete")

    def restore_fixture_access(self):
        self.adb("shell", "appops", "set", "com.android.shell", "WRITE_SMS", "ignore")
        if int(self.adb("shell", "getprop", "ro.build.version.sdk")) >= 37:
            self.adb("shell", "appops", "set", PACKAGE, "READ_RESTRICTED_MESSAGES", "ignore")
