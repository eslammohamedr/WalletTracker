import argparse
import hashlib
import json
import os
import subprocess
import time
from datetime import datetime, timezone

import device_ai_cases
import device_onboarding_campaign
import device_period_cases
import device_user_isolation_case
from qa_campaign_support import Device, ROOT


CASES = [f"ON-{number:02d}" for number in range(1, 18)] + ["AI-01", "AI-02", "AI-03", "AI-04", "AI-05", "AI-06", "DATA-07", "APP-08", "APP-12"]


def main():
    parser = argparse.ArgumentParser(description="Execute the remaining 26 acceptance cases on a dedicated QA emulator")
    parser.add_argument("--device", default="emulator-5558")
    parser.add_argument("--cases", nargs="+", choices=CASES, default=CASES)
    parser.add_argument("--execute", action="store_true")
    arguments = parser.parse_args()
    if not arguments.execute:
        parser.error("Pass --execute to register/delete disposable Firebase users and inject/clean QA SMS")
    os.environ.setdefault("APPIUM_SERVER_URL", "http://127.0.0.1:4724")
    output = ROOT / "app/build/device-smoke" / ("remaining-campaign-" + time.strftime("%Y%m%d-%H%M%S"))
    output.mkdir(parents=True, exist_ok=True)
    ui = Device(arguments.device, output)
    results = []
    apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
    manifest = {"started_at": datetime.now(timezone.utc).isoformat(), "cases": arguments.cases,
                "device": arguments.device, "local_debug_apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                "note": "Install this debug APK before running. AI cases use controlled dependency responses."}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    try:
        ui.wait_text("Authentication")
        for case_id in arguments.cases:
            if case_id.startswith("ON-"):
                current = device_onboarding_campaign.execute(ui, [case_id])
            elif case_id.startswith("AI-"):
                current = [device_ai_cases.run_case(ui, case_id)]
            elif case_id in {"DATA-07", "APP-08"}:
                current = [device_period_cases.run_case(ui, case_id)]
            else:
                ui.output = output / "APP-12"
                ui.output.mkdir(parents=True, exist_ok=True)
                credentials = {label: device_user_isolation_case.new_credentials(label) for label in ("user_a", "user_b")}
                (ui.output / "credentials.json").write_text(json.dumps(credentials), encoding="utf-8")
                current = [device_user_isolation_case.execute(ui, credentials)]
                ui.output = output
            results.extend(current)
            (output / "campaign.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
            subprocess.run(["node", "scripts/update_remaining_campaign_report.js"], cwd=ROOT, check=True, timeout=10)
            subprocess.run(["node", "scripts/build_test_case_html.js"], cwd=ROOT, check=True, timeout=10)
            if any(result["status"] == "ERROR" or any(str(value).startswith("FAILED") for value in result.get("cleanup", {}).values()) for result in current):
                break
    finally:
        ui.restore_fixture_access()
        ui.driver.quit()
    print(json.dumps({"results": [{"case": result["case"], "status": result["status"]} for result in results], "evidence": str(output)}, indent=2))
    raise SystemExit(0 if len({result["case"] for result in results}) == len(set(arguments.cases)) and all(result["status"] == "PASS" for result in results) else 1)


if __name__ == "__main__":
    main()
