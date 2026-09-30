import json
import re
import secrets
import time
from datetime import datetime, timezone
from decimal import Decimal

from appium.webdriver.common.appiumby import AppiumBy
from qa_campaign_support import Device, FirebaseUser, ROOT


def main():
    import argparse

    parser = argparse.ArgumentParser(description="APP-09 real-app offline, recovery, and stale-rate validation")
    parser.add_argument("--device", default="emulator-5554")
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        parser.error("Pass --execute to register/delete a disposable Firebase QA identity and toggle QA AVD network")

    output = ROOT / "app/build/device-smoke" / ("currency-converter-" + time.strftime("%Y%m%d-%H%M%S"))
    output.mkdir(parents=True, exist_ok=True)
    credentials = {
        "email": f"wallet.qa.converter.{time.time_ns()}@example.com",
        "password": secrets.token_hex(16),
    }
    (output / "credentials.json").write_text(json.dumps(credentials, indent=2), encoding="utf-8")
    result = {
        "case": "APP-09",
        "status": "IN_PROGRESS",
        "device": args.device,
        "started_at": datetime.now(timezone.utc).isoformat(),
        "steps": [],
        "cleanup": {},
    }
    (output / "results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    device = Device(args.device, output)
    user = None
    network_disabled = False

    def save():
        (output / "results.json").write_text(json.dumps(result, indent=2), encoding="utf-8")

    def set_network(enabled):
        nonlocal network_disabled
        device.adb("shell", "svc", "wifi", "enable" if enabled else "disable")
        device.adb("shell", "svc", "data", "enable" if enabled else "disable")
        network_disabled = not enabled

    def open_converter():
        device.home()
        device.tap("Menu", description=True)
        device.tap("Currency Converter")
        device.wait_text("Exchange Rates")

    def verify_conversion_available(description):
        device.wait(lambda: any("USD equivalent:" in item for item in device.texts()), description)
        texts = device.texts()
        egp = next((item for item in texts if re.fullmatch(r"\d[\d,]*\.\d{2} EGP", item)), None)
        usd = next((item for item in texts if item.startswith("USD equivalent: ")), None)
        eur = next((item for item in texts if item.startswith("EUR equivalent: ")), None)
        assert egp == "100.00 EGP" and usd and eur, f"Unexpected conversion output: {texts}"
        usd_amount = Decimal(usd.removeprefix("USD equivalent: ").removesuffix(" USD"))
        eur_amount = Decimal(eur.removeprefix("EUR equivalent: ").removesuffix(" EUR"))
        assert usd_amount > 0 and eur_amount > 0, f"Invalid conversion result: {usd}, {eur}"
        return {"egp": egp, "usd": usd, "eur": eur}

    def wait_for_authentication():
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            texts = device.texts()
            if "Authentication" in texts:
                return
            if "Allow" in texts and any("Allow WalletTrackers" in text for text in texts):
                device.tap("Allow")
            else:
                time.sleep(0.2)
        raise AssertionError(f"Authentication screen did not appear: {device.texts()}")

    try:
        wait_for_authentication()
        device.signup(credentials)
        user = FirebaseUser(credentials)
        device.finish_intro()
        result["steps"].append("Created a fresh disposable user through the production signup flow; verified its Firestore account/record collections are empty")

        set_network(False)
        open_converter()
        device.wait_text("Failed to fetch rates. Check your connection.")
        device.wait_text("Quote date unavailable")
        device.wait_text("Conversion unavailable until USD and EUR rates load")
        texts = device.texts()
        assert not any(item.startswith(("USD equivalent:", "EUR equivalent:")) for item in texts), f"Offline launch fabricated conversion: {texts}"
        assert "US Dollar" not in texts and "Euro" not in texts, f"Offline launch fabricated rate cards: {texts}"
        device.capture("offline_first_launch")
        result["steps"].append("Offline first launch disclosed network failure, withheld quote date/rate cards, and showed conversion unavailable")
        save()

        set_network(True)
        time.sleep(2)
        device.tap("Refresh", description=True)
        device.wait(lambda: any(re.fullmatch(r"Rates as of \d{4}-\d{2}-\d{2}", item) for item in device.texts()), "online quote date")
        amount_field = device.driver.find_elements(AppiumBy.CLASS_NAME, "android.widget.EditText")[0]
        amount_field.clear()
        amount_field.send_keys("100")
        online_values = verify_conversion_available("online conversion values")
        device.capture("online_rates_and_conversion")
        result["online_conversion"] = online_values
        result["steps"].append("Restored connectivity and verified live USD/EUR quotes plus positive conversions for EGP 100")
        save()

        set_network(False)
        device.tap("Refresh", description=True)
        device.wait_text("Failed to refresh rates. Showing previously fetched rates.")
        device.wait(lambda: any("Rates as of " in item for item in device.texts()), "retained prior quote date")
        retained_values = verify_conversion_available("conversion from retained rates")
        assert retained_values == online_values, f"Offline refresh changed the cached conversion: {retained_values} != {online_values}"
        device.capture("offline_refresh_retains_rates")
        result["retained_conversion"] = retained_values
        result["steps"].append("Offline refresh retained the previous quotes and exact calculated conversion while showing an explicit refresh error")
        result["status"] = "PASS"
    except Exception as error:
        result["status"] = "FAIL"
        result["error"] = f"{type(error).__name__}: {error}"
        try:
            device.capture("failure")
        except Exception:
            pass
    finally:
        if network_disabled:
            try:
                set_network(True)
                result["cleanup"]["network"] = "WIFI_AND_MOBILE_DATA_RESTORED"
            except Exception as error:
                result["cleanup"]["network"] = f"FAILED: {type(error).__name__}: {error}"
                result["status"] = "FAIL"
        if user:
            try:
                device.delete_user(credentials)
                assert user.documents("accounts") == [] and user.documents("records") == [], "Temporary user retained account/record documents"
                result["cleanup"]["identity"] = "DELETED_THROUGH_APP_AND_ACCOUNT_RECORDS_VERIFIED_EMPTY"
            except Exception as error:
                result["cleanup"]["identity"] = f"FAILED: {type(error).__name__}: {error}"
                result["status"] = "FAIL"
        result["finished_at"] = datetime.now(timezone.utc).isoformat()
        result["evidence"] = str(output)
        save()
        device.driver.quit()
    print(json.dumps({"case": result["case"], "status": result["status"], "cleanup": result["cleanup"], "evidence": str(output)}, indent=2))
    raise SystemExit(0 if result["status"] == "PASS" and all(not value.startswith("FAILED") for value in result["cleanup"].values()) else 1)


if __name__ == "__main__":
    main()
