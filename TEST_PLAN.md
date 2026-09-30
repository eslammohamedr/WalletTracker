# WalletTrackers Long-Term Real-App Test Plan

## 1. Product purpose (from the app)

WalletTrackers is a personal-finance ledger and assistant for tracking a user's money across bank/debit accounts, cash, credit cards, and gold. Its central promise is to turn bank SMS messages into correctly classified, correctly linked financial events, keep account balances and credit statements in sync, and show trustworthy dashboard/statistics views. It also supports manually entered records, transfers/ATM cash movement, budgets, bills, goals, debts, categories/rules, SMS history/onboarding, exports, reminders/notifications, currency conversion, authentication, offline-first persistence, and Firebase sync.

This is a source-based description, not an assertion that each feature currently works. `docs/REQUIREMENTS.md` is the requirements catalog; `docs/PROJECT_DOCUMENTATION.md` and the implementation are the behavior sources. Conflicts between them need a product decision and must not be hidden as test failures or silently accepted.

## 2. Coverage tree

```text
WalletTrackers real-app acceptance
|
+-- A. Release, device, and identity foundations
|   +-- APK/build identity, clean install, upgrade, launch, supported Android versions
|   +-- QA user A/B isolation, sign-in/out, first-use vs returning user
|   +-- SMS / notification / photo permissions: allow, deny, revoke, later grant
|   +-- Network online/offline/reconnect; background, force-stop, restart, low storage
|
+-- B. Account and balance foundations
|   +-- Every account type: Debit / Credit Card / Cash / Gold
|   +-- Every account currency option: EGP / USD / EUR / GBP / SAR / AED
|   +-- Credit limit, available credit, billing day; Gold grams and valuation
|   +-- Create/edit/archive/restore/delete, required fields, duplicate names/suffixes
|   +-- Last-four match: unique / missing / unknown / ambiguous / same suffix cross-bank
|   +-- Home dashboard, account cards, currency breakdown, net worth and conversions
|
+-- C. SMS ingestion and classification (core product path)
|   +-- Live Android SMS, onboarding history, manual review/import, bulk safeguards
|   +-- Bank sender/template acceptance; non-bank, promo, OTP, declined false positives
|   +-- Event type: income / expense / ATM / statement / credit payment / transfer
|   +-- Amount, currency, account suffix, merchant/comment, timestamp, balance-after
|   +-- Every configured parent + leaf category; user rule > AI > deterministic fallback
|   +-- Each supported bank format × category corpus, exact numeric oracles
|   +-- Duplicates vs distinct identical transactions; rapid burst and out-of-order SMS
|   +-- Arabic/English, symbols, commas, decimals, multiline, multipart, malformed body
|   +-- Unknown format, missing amount, printed balance, insufficient/declined, foreign charge
|   +-- Notification content and action after successful/unknown-account parsing
|
+-- D. Ledger, account arithmetic, and money movement
|   +-- Manual income/expense add, edit, delete, account change, category change
|   +-- SMS record sign/type, balance delta, printed balance precedence, rollback exactly once
|   +-- Internal transfers: same currency, cross currency, fees, invalid source/destination
|   +-- ATM: source debit + cash credit once, no spending inflation
|   +-- Credit purchases: category, available-credit decrement, foreign/home currency rules
|   +-- Restart/offline/sync reconciliation; no stale row resurrection or double balance delta
|
+-- E. Credit statements, payments, due dates, and reminders
|   +-- Statement amount/card/minimum/due date/paid status/deduplication
|   +-- Due date: past/today/future, month/year boundary, leap day, timezone, invalid/missing
|   +-- Reminder schedule, delivery, tap destination, reschedule/cancel after paid/delete
|   +-- In-app full/partial payment: source, available credit, statement, record, dashboard
|   +-- SMS pair both orders; wrong amount/card, interleaving, fee tolerance, 48h expiry
|   +-- Notifications: correct source, destination card, amount, no duplicate/stale action
|
+-- F. Dashboard, reporting, and time windows
|   +-- Dashboard account inclusion and EGP total vs per-currency breakdown
|   +-- Statistics Balance / Spending / Net Worth / Credit / Reports reconciliation
|   +-- Expense categories, income, transfer exclusions, credit-payment exclusions
|   +-- Today/week/month/year/custom periods, timezone, midnight, month/year/leap boundaries
|   +-- Calendar day/month/year totals, empty states, sort/search/filter
|   +-- CSV/PDF output exact records, currency, escaping, date range, permissions
|
+-- G. Planning and supporting financial tools
|   +-- Budgets CRUD, category/month/currency, 0/negative/rounding, 80%/100%/>100% alerts
|   +-- Bills/subscriptions recurrence, confirmation/dismissal, due date and reminder lifecycle
|   +-- Savings goals CRUD/contributions/progress/over-contribution
|   +-- Debts CRUD/repayment/overpayment/direction/due date
|   +-- Category/subcategory CRUD, merchant rule precedence, historical resync
|   +-- Currency converter direction, supported currencies, rate freshness/error/offline
|   +-- Receipts/split receipt attach/cancel/reopen; AI chat/cash-flow fallback
|
+-- H. Security, resilience, and production readiness
    +-- User-data isolation, Firestore access rules, account deletion, sign-out cleanup
    +-- PIN/biometric lock, background return, canceled auth, screen privacy
    +-- Firestore sync conflict/offline write/reconnect/multiple devices
    +-- WorkManager retries, process death, notification permission/channel settings
    +-- Accessibility, small/large screens, locale, contrast, performance, crash-free burst
    +-- Release-signed APK smoke + physical-device/carrier SMS/OEM background checks
```

## 3. Specific coverage for the five requested areas

### A. All account types

The account form currently offers exactly `Debit`, `Credit Card`, `Cash`, and `Gold`. Create one of each with test-owned values, then verify cards, account details, updates and ledger effects. Test Credit Card's total limit, available credit, billing day, charges and payments; Gold's weight-in-grams storage/display and net-worth conversion; Cash's absence of card digits and ATM deposits; Debit's suffix matching and deposits/withdrawals. Cross each applicable type with all currency dropdown entries (`EGP`, `USD`, `EUR`, `GBP`, `SAR`, `AED`), not by summing different currency units together.

Current code behavior that defines the initial dashboard oracle: Home `TOTAL BALANCE` sums active EGP Debit + Cash balances only. It excludes Credit Card, Gold, and non-EGP nominal balances from that EGP headline. The Home view has a separate currency breakdown; Statistics Balance converts non-credit assets to EGP and values Gold from a live gold-rate request. Assertions must preserve these distinctions, capture the FX/gold rate and timestamp, and never compare a changing live conversion to an unpinned hardcoded value.

### B. Every SMS category, amount, and bank template

Use one data-driven row per parent and leaf category defined in `Categories.kt`. For every row freeze the exact SMS body, sender, expected event type, currency, amount, suffix/account, category, expected `balanceAfter`, and expected dashboard/statistics deltas in a reviewed fixture manifest before running the APK. Test all configured categories, including categories not currently emitted by deterministic keyword rules. Those rows must explicitly exercise user rules or the AI path and define a deterministic expected oracle. A model's own output or sample export's “App extracted” label is not an independent oracle.

Cross the category fixture set with representative bank formats from the supplied corpus (HSBC, QNB Egypt, Banque Misr, plus reviewed short-code sender `1861`) and any other supported sender for which the project has a representative message. Add false-positive controls from `e&`, `etisalat`, `Fawry Pay`, `Breadfast`, and `Rabbit mart`; decide each expected case independently because the sender may represent a merchant or telecom, not a bank. Expand to the parser's other recognized bank sender names when real/approved fixture templates are available. Use the full bank-template × category matrix for critical supported categories; where another dimension is added (locale, permission, timing), use pairwise combinations and retain one-factor coverage for each boundary.

Bank identity needs an explicit acceptance oracle: the `Record` model stores account ID/name and suffix match, but no separate bank-issuer field. Tests can prove sender acceptance and that a message links to the intended test account. They cannot prove the UI displays/persists a bank identity unless a bank field/product behavior is specified. The onboarding account name inference is a separate journey from live transaction linking.

### C. Credit-card statement due date, payment, and dashboard

Create a test credit card with a known billing day and controlled baseline, inject a statement SMS for a known total/minimum/due date, and verify statement details, unpaid state, unchanged transaction ledger/bank balances, notification and scheduled reminder. Cover future/today/overdue dates, time zones and month/year/leap-day edges; duplicate same SMS identity; same card with a second statement; two cards with equal amounts; and missing/invalid dates. Then pay full and partial amounts from a selected Debit and Cash account, and assert source debit, available credit increase, remaining/unpaid statement state, one Credit Payment record, and dashboard/Stats inclusion/exclusion rules. Test both payment-SMS orders separately, with mismatches/interleaving to ensure unrelated payments never pair. Verify paid/canceled statements no longer fire reminders and tapping notifications opens the right destination.

### D. USD and EUR accounts/records

Create separate USD and EUR accounts and matching EGP accounts. For each currency test income and expense SMS using code and symbol forms, decimal/thousands separators, exact printed balance, and no printed balance. Assert that account balance remains in its own currency and the record retains the correct currency/amount according to the chosen product rule. For foreign charges on EGP credit cards, test inline EGP equivalent, printed available-credit balance, and no-conversion case. Verify Home's EGP headline does not add raw USD/EUR, the currency breakdown keeps units separate, and Statistics Net Worth converts with the captured exchange rates; exercise API success, stale rate, timeout and offline state. Cross-currency transfer tests must assert the displayed FX quote, destination amount, rounding/fees, and both balances.

## 4. Test data, fixture model, and numeric oracle

Use a disposable Firebase QA identity and a dedicated emulator snapshot. Keep the existing signed-in QA identity out of future baseline runs: prior runs observed deletion/restart inconsistency, and the identity is dirty. Do not use a real account, clear app data on the shared signed-in profile, bulk-import the sample corpus into a personal profile, or assume deleting a UI row rolled back cloud balances.

Seed a deterministic baseline using the real account UI or a documented QA setup path; record before-values and verify server/UI state before each suite:

| Fixture | Type | Currency | Initial amount | Extra data |
|---|---|---|---:|---|
| `BankEGP-1111` | Debit | EGP | 10,000.00 | Main control account |
| `BankEGP-2222` | Debit | EGP | 5,000.00 | Second bank/account routing control |
| `BankUSD-4444` | Debit | USD | 1,000.00 | Separate unit; never add raw to EGP total |
| `BankEUR-5555` | Debit | EUR | 500.00 | Separate unit |
| `Cash-EGP` | Cash | EGP | 1,000.00 | ATM destination |
| `CardEGP-3333` | Credit Card | EGP | 3,000.00 available | Limit 5,000.00; billing day 15 |
| `Gold` | Gold | XAU/grams | 10.00 g | Valued from captured rate |

The automated fixture lives in `scripts/financial_qa_accounts.json`. Its current-code Home EGP `TOTAL BALANCE` oracle is EGP 16,000 (both EGP Debit accounts + Cash only). Record the USD/EUR balances in their respective units and keep them in the Home currency breakdown. Statistics Balance's net-worth total uses non-credit assets converted to EGP plus Gold valuation; calculate using the exact captured USD/EUR/gold rates and timestamp. Credit Card available credit is not an asset or part of that balance total. Revisit these oracles if product requirements change.

For each SMS case, reconcile: record count delta, signed amount/type, category, currency, exact linked account, timestamp, SMS identity, `balanceAfter`, before/after balances for every account, dashboard components, Stats expense/income/transfer/net totals, statement status, notification and restart persistence. Store sanitized UI XML/screenshots/logcat, APK hash, device/OS, fixture revision and independent expected result.

## 5. Prioritized long-term rollout

| Stage | Scope and deliverables | Exit criterion |
|---|---|---|
| 0 — Test safety and oracle | Isolate disposable QA backend identity; add versioned fixture/oracle manifest; remove cleanup assumptions and document reset/reseed procedure. | Each write suite can start from verified balances and cannot touch the shared/personal account. |
| 1 — Protect critical money paths | Automate four account types, one category per core type, multi-bank suffix routing, statement detection, both card-payment orders, USD/EUR balance rules, restart checks. | No P0 money mismatch; every check compares record + account + dashboard + Statistics, not UI presence alone. |
| 2 — Exhaust SMS rules/categories | Inventory all 2,144 exported occurrences; independently label expected outcome; run all category leaves against representative bank templates and all supported event types. | Reviewed oracle covers 100% of configured categories and 100% of corpus messages; outcomes classified pass/fail/inconclusive. |
| 3 — Feature journey coverage | Account CRUD, manual records, transfers/ATM, budgets, bills, goals, debts, categories/rules, converter, calendar, receipts, exports. | One successful, one invalid-boundary, one cancellation/failure, and one persistence case per feature. |
| 4 — Failure, time, and integration | Notifications/reminders, permissions, duplicates vs distinct events, offline sync, process death, and network/AI provider failures. | No lost/double financial effects in controlled failure/retry cases; all scheduled work has verified lifecycle. |
| 5 — Release candidate and field | Run P0/P1 acceptance on release-signed APK; emulator API matrix and selected physical phones/carrier SMS. | All P0 pass; no open critical/high financial defects; evidence and known limitations approved. |

Suggested release priority: **P0** account identity/suffix, amount/type/category/currency, exactly-once balance, card statement/payment, dashboard/Stats financial totals, data isolation. **P1** budgets/bills/reminders/transfers/ATM/CRUD/persistence. **P2** presentation, exports, analytics, predictions, accessibility polish. Any mismatch in amount, account, currency, duplicate state or balance is a financial-integrity issue, not a cosmetic pass.

## 6. Automation architecture and execution policy

- Keep these as real-app tests: install/run the built APK with Appium/UiAutomator2; inject SMS through the Android emulator or a device/PDU fixture; assert rendered screens and notifications; restart the app; inspect persisted state through the supported UI/backend test oracle. Unit tests may supplement but do not count as production-flow coverage.
- Separate read-only smoke, isolated write-path suites, and physical-device checks. Name every write with a unique marker and have a suite-level fresh baseline/reset. If cleanup is not provably correct, discard/reset the QA identity rather than retrying deletes on an inconsistent profile.
- Make amount comparisons decimal-aware (minor units), not formatted-string equality. Compare amounts and currencies separately; never add EGP + USD + EUR as raw numbers. Capture rate and rounding policy whenever conversion participates.
- Keep tests deterministic: freeze clock or use injected clock where available; freeze FX/gold responses for assertions; use controlled AI fixtures for category predictions; preserve real SMS identity for duplicate tests. Emulator text-SMS resend changes receive timestamp and does not prove true provider deduplication.
- **Per-case cleanup contract:** every write case owns a unique marker and a snapshot of all affected balances/counts. In `finally`, delete only that case's created record(s), statement/bill/budget/etc. through the real UI, cancel case-owned reminders when appropriate, and assert the marker is absent and every account/dashboard total returned exactly to its snapshot. The seeded accounts are fixture state and are not deleted during individual cases. If rollback fails, mark the case failed, stop the run, discard the QA identity and recreate the fixture; never continue against a drifted balance. For payment/ATM/transfer cases, verify and reverse both endpoints—not just one visible record.
- Classify each outcome `PASS`, `FAIL`, `INCONCLUSIVE`, `BLOCKED`, or `NOT RUN`; a locator failure or absent product rule is not a bug. Attach evidence and reproduction data; do not mark all possible behavior “covered” based on a sample.
- Execute the high-risk subset on every PR against the test backend; full fixture/category suite nightly; lifecycle/permissions/long-running reminders on scheduled runs; release gate on the release-candidate APK. Use a separate device/image for concurrent suites to prevent SMS/notification cross-talk.

## 7. Current risks discovered from source and prior device evidence

- Prior live QA evidence reproduced incorrect Credit Payment categorization/available-credit handling for a credit purchase, incorrect credit-first payment pairing with an unrelated amount, and record resurrection/balance inconsistency after deletion. Fix and regression-test these before relying on cleanup or credit statistics.
- Category rules cover only a subset of configured category leaves; every other leaf needs an explicit rule/AI/fallback expectation and test oracle.
- Bank matching for live SMS is a sender eligibility check plus last-four account matching; the record schema has no explicit issuer field. Confirm whether bank label display/persistence is a requirement.
- Statements with absent/unparseable dates currently fall back to `Date()` in receiver code. Decide a safe rule and test; arbitrary current-date substitution risks incorrect reminders.
- Home and Statistics use different inclusion/conversion semantics for Debit/Cash, foreign currencies, Gold, and Credit Cards. Preserve separate oracles and request product sign-off before changing totals.
- The installed test profile is already mutated and the previous delete test failed. Do not interpret its state as a baseline or run further write tests there until it is reset/reseeded safely.

## 8. Source map

- Product requirements: `docs/REQUIREMENTS.md`; high-level architecture: `docs/PROJECT_DOCUMENTATION.md`.
- Account form/types and Home totals: `app/src/main/java/com/example/wallettrackers/screens/HomeScreen.kt`.
- Category catalog: `app/src/main/java/com/example/wallettrackers/model/Category.kt`.
- SMS receiver and statement/payment paths: `app/src/main/java/com/example/wallettrackers/receiver/SmsReceiver.kt`.
- SMS parser and deterministic merchant/category rules: `app/src/main/java/com/example/wallettrackers/util/SmsParser.kt`.
- Dashboard/statistics money semantics: `app/src/main/java/com/example/wallettrackers/screens/StatisticsScreen.kt` and `HomeScreen.kt`.
- Existing executable UI/SMS harness instructions: `scripts/README.md`; current cases/results: `TEST_CASES_REPORT.md`.
