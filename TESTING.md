# Real-app automation

This project uses Appium with UiAutomator2 to exercise the installed Wallet app through its actual Android UI. Setup and commands are in [scripts/README.md](scripts/README.md); the requirements-based scenario matrix is in [scripts/SCENARIOS.md](scripts/SCENARIOS.md).

The long-term feature tree, requested account/category/bank/credit-due-date/currency coverage, numeric oracles, and release rollout are in [TEST_PLAN.md](TEST_PLAN.md). The added QA-01–QA-12 black-box scenarios and current results are in [TEST_CASES_REPORT.md](TEST_CASES_REPORT.md); the filterable [HTML test report](TEST_CASES_REPORT.html) is generated from the case report and latest recorded emulator results. These new cases are planned inventory entries and are not yet executed/automated; use a clean disposable QA identity because the previously used profile is mutated and its deletion behavior was inconsistent.

## End-to-end lifecycle plan

“All possible scenarios” is not a finite set, so coverage is risk-based and grows by user journey. Keep every case black-box through the installed APK; validate visible state and persisted results after navigation/restart rather than calling app internals.

| Phase | User journey | Main scenarios | Automation / gate |
| --- | --- | --- | --- |
| 0. Environment | Install, upgrade, launch, permissions, interrupted startup | Fresh install vs upgrade; supported Android versions; SMS/notification/photo permission allow, deny, revoke; network unavailable at launch | Appium + ADB; clean install requires a disposable account and device state |
| 1. First use | Sign up/sign in, onboarding and SMS account discovery | Empty inbox, permission denied/granted, import representative fixtures, account grouping/merging, select/edit accounts, Cash setup, chronological history, repeat/interrupted import | Appium + emulator-injected SMS/fixture setup; requires an isolated backend identity and reviewed expected results |
| 2. Daily money activity | Dashboard, manual records, incoming SMS, transfers | Expense/income create-edit-delete, categories, linked/unlinked cards, duplicate vs distinct SMS, printed balances, ATM and credit payments, foreign charges | Appium + controlled emulator SMS; requires test-owned accounts/records and exact before/after balance assertions |
| 3. Money management | Accounts, budgets, bills, debts, goals and categories | CRUD, validation boundaries, due dates/reminders, contributions/repayments, rules and category precedence, deletion safeguards | Appium UI flows; write cases require isolated data and verified cleanup |
| 4. Review and exports | Records, search/filter, calendar, Statistics, reports, converter, receipts | Date/account/type filters, month/leap-day boundaries, totals, CSV edge values, rate/error states, attach/cancel/reopen receipt | Appium with deterministic records; assert rendered values and exported output |
| 5. Daily reliability | Background, notifications, lock, offline and sync | Force-stop/restart, app upgrade, offline save/reconnect, duplicate/lost sync, reminder delivery/cancel, PIN/biometric, sign-out/account switch, second-device consistency | Appium + ADB/network controls; notification/auth cases need device configuration; cross-device needs a second emulator |
| 6. Release gate | Repeat critical journeys on target builds/devices | P0 onboarding and transaction flows, P1 feature matrix, regression rerun, screenshots/logs and defect triage | Run against the release-candidate APK on a clean QA account; emulator coverage does not replace physical-device checks |

### Automation increments

1. Keep the existing read-only smoke suite green and make its locators stable; the current zero/negative budget automation is not yet trustworthy.
2. Add isolated onboarding tests and a small, independently reviewed SMS fixture set before replaying the full exports.
3. Add daily transaction and transfer journeys with unique `AUTOTEST` markers, visible balance reconciliation, restart checks, and cleanup verification.
4. Add the remaining feature modules and failure/recovery paths from `scripts/SCENARIOS.md`; track each as `Not automated`, `Automated`, or `Blocked`, with a result artifact.
5. Run the full matrix only against a disposable QA identity/backend. Never clear app data, re-import histories, or bulk-delete records on the signed-in personal account.

**Current executable coverage:** the six core navigation/session flows, five Statistics-tab interactions, and empty-budget validation. The broader onboarding, SMS, CRUD, sync, notification, and feature matrix is planned, not yet automated end to end. Current coverage results are in `scripts/SCENARIOS.md`.

Quick run (with Appium server already running at `http://127.0.0.1:4723`):

```powershell
adb devices -l
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
.\.venv-appium\Scripts\python.exe scripts/device_smoke.py --device emulator-5554 --extended
```

Artifacts are written under `app/build/device-smoke/<timestamp>/`. The runner retains the signed-in app state and cancels forms rather than saving financial data. Never treat a passing UI smoke run as coverage for the whole SMS flow or as certification of a release build.

## Full SMS export evaluation

The JVM evaluator invokes the production `SmsParser` on every message in `app/src/test/resources/sms_export.txt` (1,088 messages) and writes per-message historical-versus-current output to `app/build/reports/sms-export-evaluation.json`. Generate the searchable review page with:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.wallettrackers.RealSmsExportFileTest' --no-daemon
.venv-appium\Scripts\python.exe scripts/build_sms_export_evaluation_report.py
```

Open `SMS_EXPORT_EVALUATION.html` to inspect every SMS and its parser output. Archived “App extracted” fields are historical outputs, not independent ground truth; deltas are review candidates. This parser-wide pass is not a live SMS-receiver/UI replay. Real-app replay remains necessary to validate account matching, notifications, persistence, and balance changes.
