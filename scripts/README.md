# Real-app UI automation with Appium

## Remaining 26-case campaign

`device_remaining_campaign.py` covers ON-01–ON-17, AI-01–AI-06, DATA-07, APP-08 and APP-12. These are real app/UI/backend acceptance tests, not unit tests. Each case creates a disposable identity through the UI, initializes explicit fixtures, verifies exact values, then deletes its identity through Profile and verifies backend/SMS cleanup. APP-12 switches between two distinct identities on the same device.

Prerequisites: Appium on port 4723 (started with Android SDK environment variables), the Appium Python virtual environment, `adb` on PATH, configured Firebase test access, and the current debug APK installed. Start signed out with an empty inbox on `Wallet_23Cases_Temp` or `Wallet_Onboarding_QA`; the runner refuses other AVD names. Do not use a personal account. Generated credentials stay in ignored `app/build` evidence directories and must not be published.

```powershell
.\gradlew.bat :app:assembleDebug
adb -s emulator-5558 install -r app/build/outputs/apk/debug/app-debug.apk
Start-Process powershell.exe -WindowStyle Hidden -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File scripts/start_qa_appium.ps1'
.\.venv-appium\Scripts\python.exe scripts/device_remaining_campaign.py --device emulator-5558 --execute
```

The campaign currently contains 26 cases (17 onboarding, six AI/provider, DATA-07, APP-08, APP-12), not counting the separate standalone APP-09 runner below.

Run a selected subset or regenerate the latest evidence-backed HTML:

```powershell
.\.venv-appium\Scripts\python.exe scripts/device_remaining_campaign.py --device emulator-5558 --cases ON-05 ON-07 AI-02 --execute
node scripts/update_remaining_campaign_report.js
node scripts/build_test_case_html.js
```

Ordinary waits and requests are capped at 10 seconds. A multi-record import can continue for up to 90 seconds only while visible progress continues. A setup or cleanup error stops the campaign; inspect that case's `results.json` before retrying. Product assertion failures are reported as failures, never silently converted to passes.

Historical onboarding SMS use dated Android-provider fixtures; the AI and budget cases inject incoming emulator SMS. The disposable Android 37 AVD needs temporary `READ_RESTRICTED_MESSAGES` access for synthetic provider fixtures. The runner also enables shell `WRITE_SMS` for exact cleanup, and resets both test overrides when the campaign exits. This environment is not release/Play Store permission certification.

AI fault injection uses `adb reverse` and a localhost HTTP fixture on port 8765, with dummy credentials and a debug-only private endpoint override. Groq/Gemini/Cerebras fallback order is checked from recorded requests. The release source set cannot activate the override. These tests validate app handling of controlled responses, not real providers' availability or classification quality. The override, reverse mapping, and server are removed after every AI case.

Evidence is under `app/build/device-smoke/<campaign>/<case>/`; `TEST_CASES_REPORT.html` links the latest completed result for each case/branch. Test failures remain visible even when earlier runs passed.

## APP-09 currency converter

`device_currency_converter_case.py` signs up a disposable Firebase identity through the real app, opens Currency Converter from the navigation drawer, tests first launch without network, restores connectivity and checks live conversion, then checks that an offline refresh retains the last quote. It deletes the disposable identity and restores Wi-Fi/mobile data in cleanup. Live quote validation requires internet access from the Android AVD; the runner stops after the standard 10-second wait if rates do not load.

```powershell
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
Start-Process powershell.exe -WindowStyle Hidden -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File scripts/start_qa_appium.ps1'
.\.venv-appium\Scripts\python.exe scripts/device_currency_converter_case.py --device emulator-5554 --execute
```

Generated account credentials, screenshots, UI XML, and `results.json` are kept in the ignored `app/build/device-smoke/currency-converter-<run-id>/` directory. Do not publish credentials.

## DATA-02 offline write/restart

`device_data02_case.py` creates a disposable user with one EGP 10,000 debit account, saves an EGP 0.17 expense with network disabled, force-stops and relaunches Wallet before sync, verifies the local row, reconnects and checks exact post-sync balances, then deletes the record and test identity. Add `--csv-newline` to also run DATA-06 with a note containing a comma, quotes, and a newline, verifying exact eight-column CSV round-trip and cleanup.

For DATA-01's independent server-side assertion, run `device_data02_case.py --data01-server-sync --execute`. The test saves EGP 0.09 offline, reconnects/restarts, checks the exact Firestore record and account balance through the REST API (no second device), removes the row through the app, and verifies the remote record list and balance return to baseline before deleting the disposable identity.

For DATA-04 record type/account editing, run `device_data02_case.py --data04-account-edit --execute`. This creates a disposable user with MainBank EGP 1,000 and SecondBank EGP 500, runs Income→Expense and Expense→Income edits while moving the record between accounts, verifies both balances and Home totals, deletes each exact row, restarts, and removes the account documents and identity. The underlying flow is in `device_manual_record_case.py --edit-to-account ... --edit-account-baseline ... --edit-to-type ... --edit-to-category ...`.

```powershell
.\.venv-appium\Scripts\python.exe scripts/device_data02_case.py --device emulator-5554 --execute
.\.venv-appium\Scripts\python.exe scripts/device_data02_case.py --device emulator-5554 --csv-newline --execute
```

The run requires the debug APK, Appium, Firebase test access, and the dedicated QA AVD. Evidence and generated credentials are stored under `app/build/device-smoke/data-02-offline-restart-<run-id>/`.

## APP-01 to APP-07 feature acceptance

`device_app_acceptance.py` adds opt-in UI acceptance flows for accounts, delete confirmation, savings goals, debts, recurring bills, custom subcategories, and record search/type filtering. It writes XML, screenshots, and `results.json` under `app/build/device-smoke/<run-id>/<case>/`. It requires an explicit `--execute`; `--help` and `--case ...` without `--execute` never connect to Appium or touch a device.

Install the Appium client and start Appium before a run:

```powershell
python -m pip install -r scripts/requirements-appium.txt
python scripts/device_app_acceptance.py --case APP-03 --execute --device emulator-5556
```

Cases are isolated. APP-01 creates and edits a unique EGP 12.34 account, verifies archive removes it from the active list and dashboard total, restarts to verify persistence, restores it from Archived Accounts, restarts again, then deletes it and verifies the starting total. It uses a fresh disposable Firebase identity and removes it after the run. APP-02 only inspects and cancels the account delete confirmation because orphan/reassignment behavior is not specified. APP-03 verifies invalid target/saved/contribution boundaries and creates, contributes to, edits, and deletes one unique goal. APP-04 creates and deletes one unique debt; partial repayments and overpayment are blocked because the UI exposes only Settle. APP-05 creates and removes one unique recurring bill; mark-paid/next-due behavior is unsupported by the current UI. APP-06 creates and deletes one custom subcategory; the screen list is scrolled to the test item and its exact Delete accessibility control is used. APP-07 is read-only and requires an existing distinctive `--search-text`; it checks search clearing and the Expense type chip, but needs controlled data arguments before asserting account/date/category-specific sets. APP-01 archive/restore passed on the dedicated QA AVD after fixing explicit Firestore mapping for `isArchived`; APP-03/04/05/06 supported paths also passed. Details are in `TEST_CASES_REPORT.md`.

`device_smoke.py` drives the installed Wallet app on an Android emulator/device using Appium's UiAutomator2 driver. These are black-box UI tests: they use the real APK, real backend configuration, and the signed-in app state; they do not call app internals or use fake services. The runner keeps app data (`noReset=true`) and does not submit transaction or budget forms.

## One-time setup (Windows / PowerShell)

Prerequisites: Node.js/npm, Python 3, Android Studio with an Android SDK and an AVD, and the Wallet APK installed on the target. Appium also needs `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) and `JAVA_HOME` in its process environment. In this workspace they are:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

Install Appium and its Android driver once:

```powershell
npm install --global appium@3.7.0
appium driver install uiautomator2
appium driver doctor uiautomator2
```

The doctor should report zero required fixes. `bundletool`, `ffmpeg`, and GStreamer checks are optional for this test suite.

Create a local Python environment and install the pinned client:

```powershell
python -m venv .venv-appium
.\.venv-appium\Scripts\python.exe -m pip install -r scripts/requirements-appium.txt
```

## Run tests

Use two PowerShell terminals. In **Terminal 1**, set paths and start the Appium server:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
appium --address 127.0.0.1 --port 4723
```

Leave it running. In **Terminal 2**, boot/unlock the emulator, verify its serial, install the APK if needed, and run the suite:

```powershell
adb devices -l
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
.\.venv-appium\Scripts\python.exe scripts/device_smoke.py --device emulator-5556
```

Run extended input checks and all Statistics tabs with `--extended`. Repeat only one group with `--only budget` or `--only statistics`; launch remains a prerequisite. For example:

```powershell
.\.venv-appium\Scripts\python.exe scripts/device_smoke.py --device emulator-5556 --extended
.\.venv-appium\Scripts\python.exe scripts/device_smoke.py --device emulator-5556 --only statistics
```

The runner exits with code 0 when every executed assertion passes, and nonzero on a failure or blocked launch. Inspect each failure's screenshot and XML before deciding whether it is an app defect, a test locator issue, or an environment/setup issue. A passing run covers only listed UI cases, not every SMS or financial business-rule scenario.

## Manual record create/edit/delete

`device_manual_record_case.py` creates a uniquely signed real UI record, checks the affected account and Home totals, optionally verifies Spending Statistics, then deletes the exact row and confirms baseline restoration after restart. Use the disposable QA fixture only:

```powershell
.\.venv-appium\Scripts\python.exe scripts/device_manual_record_case.py --device emulator-5556 --type Expense --category Groceries --amount 0.23 --edit-to-amount 0.41 --verify-statistics
```

`--edit-to-amount` changes the newly created record through the Records edit UI and asserts that balances and Statistics recalculate to the edited amount before cleanup. The record remains tagged by an exact type/category/account/amount signature; do not run if the baseline differs from the report.

For the DATA-01 offline-local-write/restart path, use the QA fixture's exact current baselines and `--offline-save`:

```powershell
.venv-appium\Scripts\python.exe scripts/device_manual_record_case.py --device emulator-5556 --type Expense --category Groceries --amount 0.09 --account-baseline 10000 --home-baseline 16000 --note DATA01OFFLINE --offline-save
```

This disables emulator Wi-Fi/mobile data immediately before Save, verifies the exact local record, restores connectivity, restarts, and checks the record/balance before exact cleanup. For independent server-side sync and cleanup verification without a second device, use `device_data02_case.py --data01-server-sync --execute`.

## Numeric onboarding logic case

`seeded_onboarding.py` drives a fresh onboarding flow with three emulator SMS events (salary EGP 25,000; groceries EGP 850; ride EGP 150). Its real-UI assertions check the discovered account suffix and balance, the final dashboard balance **EGP 24,000**, signed transaction amounts and categories, and Statistics totals (**income EGP 25,000; expenses EGP 1,000; net EGP 24,000**).

Use only a clean disposable QA AVD and a new disposable Firebase identity. Set `WALLET_QA_EMAIL` and `WALLET_QA_PASSWORD`, then run:

```powershell
\.venv-appium\Scripts\python.exe scripts/seeded_onboarding.py --device emulator-5554
```

This creates and imports data in that QA identity. It is not safe to run with the personal signed-in account. The manual CRUD, transfer, ATM, statement settlement, payment-order, duplicate-SMS, and permission-boundary cases remain **Not run**; see [TEST_CASES_REPORT.md](../TEST_CASES_REPORT.md) for their procedures and current run outcomes.

## Fresh financial QA reset and account fixture

`fresh_qa_setup.py` can reset the signed-in QA user or register a new disposable user without deleting any existing user. Both modes skip SMS-history import, add the eight real-app account fixtures from `financial_qa_accounts.json` (including two distinct EGP credit cards), and verify every account card and the Home EGP `TOTAL BALANCE` oracle (**EGP 16,000**).

The script creates a random `wallet.qa.*@example.com` identity and strong password by default, and saves credentials only under the ignored run directory in `app/build/device-smoke/fresh-qa-setup-<timestamp>/qa-credentials.json`. Do not commit/share this file. You may set `WALLET_QA_EMAIL` and `WALLET_QA_PASSWORD` to choose credentials explicitly. If the current Firebase login is old, set both `WALLET_QA_CURRENT_EMAIL` and `WALLET_QA_CURRENT_PASSWORD`; the script will sign in freshly before deleting to satisfy Firebase's recent-login requirement.

First validate without changes:

```powershell
\.venv-appium\Scripts\python.exe scripts/fresh_qa_setup.py --device emulator-5556 --dry-run
```

To create a fresh disposable account without deleting any existing user:

```powershell
\.venv-appium\Scripts\python.exe scripts/fresh_qa_setup.py --device emulator-5554 --register-new-user
```

Then, with Appium running and only the disposable QA identity signed in, run:

```powershell
\.venv-appium\Scripts\python.exe scripts/fresh_qa_setup.py --device emulator-5556 --delete-signed-in-qa-user
```

`--delete-signed-in-qa-user` is mandatory for the destructive reset path and permanently deletes the active app user and its Firestore data. Email sign-up does not expose the current email in the UI, so verify the emulator is signed into the intended disposable QA account before running. If deletion does not return to login, stop; Firebase may require a fresh sign-in. Use `--register-new-user` to create and seed a fresh random QA identity without deleting any existing account; use `--seed-current-user` to seed an already signed-in empty QA profile. Generated credentials stay only in the ignored run directory.

For every subsequent write test, preserve the initialized eight accounts and balances. Tag each case's transactions uniquely; in `finally`, delete only records/entities created by that case, cancel its reminder work, and assert all affected balances and dashboard totals return to the exact pre-case snapshot. Do not continue to the next case if cleanup or balance restoration fails—discard/reseed the whole QA identity instead. `device_sms_expense_case.py` demonstrates single tagged-record cleanup and persistence verification; complex transfers/payments must verify rollback of both endpoints.

## Controlled live expense-SMS check

For a test-owned debit account with a known balance, inject one tagged SMS and assert its record amount, category, linked account, account balance, and dashboard total:

```powershell
.\.venv-appium\Scripts\python.exe scripts/device_sms_expense_case.py `
  --device emulator-5556 --account-name MainBank --account-suffix 1111 --baseline-balance 10000.00 `
  --baseline-dashboard-total 16000.00 --amount 0.50 --category Groceries --merchant Carrefour
```

The script attempts one confirmed delete, restarts the app, and verifies the tagged record remains absent and balances return to baseline. **Stop if the case reports a deletion/reconciliation failure; do not rerun against that identity.** The 2026-09-24 QA run found tagged records reappearing after deletion, so use a disposable profile and review [the run results](../app/build/device-smoke/20260924-financial-logic/results.json). This runner covers a single expense SMS, not multi-account transfers, credit-card payment pairing, statement settlement, or all notification cases.

Use `--currency USD` or `--currency EUR` with the matching fixture account and baseline to verify foreign-currency record amounts and balances. USD/EUR cases default the EGP Home dashboard delta to zero. For example, the verified USD case used USDBank ending `4444`, a USD 1,000.00 baseline, a USD 0.25 Groceries debit, and an unchanged EGP 16,000.00 Home total. Android denied deletion of some injected Inbox rows in these runs; the script reports the retained exact row ID/marker even after the Wallet record is removed and financial balances are restored.

Use `--printed-balance <amount>` to test SMS balance reconciliation separately from the transaction amount. For example, from MainBank EGP 9,999.83, an EGP 0.03 expense with printed balance EGP 9,999.70 should set the balance to exactly 9,999.70 (not 9,999.80), then deleting the test record should restore the pre-case snapshot. This case now also validates snapshot persistence across an app restart. A prior APK failed its deletion rollback; the fixed APK passed against a non-pristine pre-case baseline. Check the report before assuming the account fixture is at its original baseline. Synthetic Inbox rows may remain after the financial record is cleaned.

For raw bank-format SMS coverage, `--sms-sender` and `--sms-body-template` replay a controlled message while substituting `{suffix}`, `{amount}`, `{currency}`, `{balance}`, `{merchant}`, and `{marker}`. The test runner verifies the account, category, amount, balance effect, and exact record rollback. Latest real-app examples included HSBC `IPN outward` and QNB `IPN transfer sent` on SecondBank; both mapped to `Instapay outcome` and restored the account after record deletion/restart. Banque Misr credit-card groceries also passed with an explicit printed balance. See [the current HTML report](../TEST_CASES_REPORT.html) for evidence paths.

## Mixed SMS income/expense burst

Run SMS-24 on the disposable QA AVD with MainBank at EGP 10,000, Home at EGP 16,000, and empty Records:

```powershell
.venv-appium\Scripts\python.exe scripts/device_sms_mixed_burst_case.py --execute --device emulator-5554
```

The runner sends one EGP 0.15 salary-credit SMS and one EGP 0.10 Carrefour expense SMS in one burst, checks exact rows and net account/Home arithmetic, deletes both records, restarts, and verifies fixture restoration. It stops if the current baseline or empty-Records precondition differs.

## Credit-card statement SMS, payment and rollback

Run NUM-10/11 only from the empty-Records QA fixture (MainBank EGP 10,000, TestCard available credit EGP 3,000, Home EGP 16,000):

```powershell
.venv-appium\Scripts\python.exe scripts/device_sms_statement_case.py --execute --device emulator-5554 --amount 1200 --minimum 100 --due-date 30/09/2026 --source-account MainBank --home-baseline 16000 --source-baseline 10000 --card-baseline 3000
```

The runner verifies statement amount/due date/notification, only still-future reminders, unchanged balances before payment, exact MainBank/card/Home payment deltas, zero ordinary Spending Statistics expense, reminder cancellation, and exact delete/restart restoration. The app exposes total due but not minimum payment separately. Do not run when another unpaid statement or matching payment exists.

## Credit-card payment SMS ordering

The live payment runner checks both arrival orders, exact source/card balances, the Credit Payment row, completion notification, spending totals, and deletion/restart rollback. Run only against the eight-account QA fixture:

```powershell
.\.venv-appium\Scripts\python.exe scripts/device_credit_payment_case.py --execute --device emulator-5556 --order credit-first --verify-statistics --verify-notification-navigation --amount 0.37
.\.venv-appium\Scripts\python.exe scripts/device_credit_payment_case.py --execute --device emulator-5556 --order debit-first --amount 0.19
```

For the combined NUM-15 scenario, run `device_compound_record_case.py --scenario NUM-15`; it keeps the Salary and expense records in place while checking a live transfer and card payment against the same EGP 1,000 expense baseline. It then removes every test record and verifies the fixture after restart. Payment same-identity replay and same-amount two-card pairing fixes have live regression evidence; see the current results in `TEST_CASES_REPORT.md`.

## Amount-less SMS negative case

Run the non-mutating financial negative case on the signed-in QA emulator:

```powershell
.venv-appium\Scripts\python.exe scripts/device_sms_missing_amount_case.py
```

It injects one uniquely tagged bank-related alert without any numeric transaction amount and verifies the Records view remains empty and both Home/MainBank balances are unchanged. If Wallet creates a row anyway, the runner records the unexpected row, reports a failure, and deletes only that exact marker before restarting and verifying baseline restoration. The synthetic Inbox row is retained in evidence because Android may not permit deleting provider rows.

## Expired credit-payment pending match

Run SMS-17 only on the disposable QA emulator with an empty Records view and the fixture balances from the report:

```powershell
.\.venv-appium\Scripts\python.exe scripts\device_sms_payment_expiry_case.py
```

The runner sends a real emulator SMS to create a pending credit-side payment, then ages only that case-owned timestamp in the debuggable app's `pending_cc` SharedPreferences to simulate 49 elapsed hours. It restarts Wallet, sends the matching debit-side SMS, verifies that the expired partial row is not cross-linked to the new payment, removes both uniquely identified test rows, restarts, and checks exact account/Home restoration. It uses Android `run-as` against the debug APK as a controlled time fixture; do not run it against a release APK or a personal account. Evidence is written under `app/build/device-smoke/<timestamp>/live_sms_payment_expiry/`.

## ATM withdrawal transfer and rollback

Run only with the signed-in disposable QA fixture at its recorded EGP baseline and with Appium available:

```powershell
.venv-appium\Scripts\python.exe scripts/device_sms_atm_case.py
.venv-appium\Scripts\python.exe scripts/device_sms_atm_case.py --without-printed-balance
```

Each run injects a uniquely tagged EGP 10 ATM SMS, verifies the MainBank debit, CashWallet credit, unchanged EGP Home total and spending, checks the Cash notification, deletes the transfer, restarts Wallet, and verifies exact baseline restoration. Do not run if Records is not empty or the fixture differs from the configured baseline. The SMS Inbox entry may remain even though the Wallet record is deleted.

## Unknown-account SMS and alert

Run the unknown-suffix case only on the signed-in QA emulator with the recorded Home/MainBank baselines:

```powershell
.venv-appium\Scripts\python.exe scripts/device_sms_unknown_account_case.py
.venv-appium\Scripts\python.exe scripts/device_sms_unknown_account_case.py --device emulator-5554 --baseline-home 16000 --baseline-mainbank 10000 --amount 100 --unknown-suffix 9999 --verify-notification-navigation
```

The runner verifies an unlinked imported-card placeholder, the Home assignment banner, the Android `Action Required: Match Account` notification, unchanged balances, exact record deletion, and restart persistence. With `--verify-notification-navigation`, it expands a grouped WalletTrackers notification, taps the exact alert, and verifies All Records opened. It requires an empty Records view before injecting the test SMS and stops if that precondition is not met.

## SMS export category suite

Inventory all 1,088 messages and 20 historical category labels in the requested export, then execute reviewed representative messages through the installed app:

```powershell
.venv-appium\Scripts\python.exe scripts/sms_category_suite.py
.venv-appium\Scripts\python.exe scripts/sms_category_suite.py --execute --device emulator-5556
.venv-appium\Scripts\python.exe scripts/sms_category_suite.py --execute --device emulator-5556 --rules-only
```

The first command only generates the inventory and HTML report. The second runs 15 amount/category/account/rollback scenarios. The third exercises seven personal-category rules: original classification, save rule, reclassification without a second balance effect, delete record, restart, incoming SMS with the saved category, delete rule, and fallback replay. Rule tests require an APK exposing the `Profile` and `Delete category rule for <merchant>` accessibility labels. Use `--case CORPUS-SNACKS` to select a single case.

The reviewed expectations live in `scripts/sms_category_cases.json`; exported labels are retained for comparison. Amounts, account suffixes, printed balances and rule counterparties are replaced with case-owned fixture values. The suite preserves HSBC and Banque Misr sender names when injecting SMS. Each execution writes an HTML report under `app/build/device-scenarios/categories-<timestamp>/index.html` with links to raw live evidence. It stops on a failed case for cleanup verification. Appium must be running on port 4723; UI/HTTP waits are 10 seconds. Account baselines are those in `scripts/financial_qa_accounts.json`.

The separate live export replay (`scripts/device_sms_export_replay.py`) now selects one earliest source SMS per normalized sender by default (two SMS for the current export). This is a quick sender smoke check, not full category/amount/payment coverage. Use `--all-messages` to opt into the full export replay.

Statement, ATM, payment matching, currencies and onboarding remain separate report scenarios; this representative suite does not claim to execute all 1,088 messages.

## Evidence and safety

Screenshots, accessibility XML, `session.json`, and `results.json` are saved under `app/build/device-smoke/<timestamp>/`. Evidence can show real balances and SMS content; do not share it publicly. The app remains signed in and existing data is retained. Budget forms are cancelled without saving. Do not replay the SMS corpus or use bulk import against a personal account. A debug APK test is not release certification.

See [SCENARIOS.md](SCENARIOS.md) for requirements-based coverage and unexecuted cases. Sample SMS files are fixtures, not expected results; use an isolated account and controlled emulator messages for write-path testing.
