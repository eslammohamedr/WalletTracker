# Real-app automation

This project uses Appium with UiAutomator2 to exercise the installed Wallet app through its actual Android UI. Setup and commands are in [scripts/README.md](scripts/README.md); the requirements-based scenario matrix is in [scripts/SCENARIOS.md](scripts/SCENARIOS.md).

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
