# Requirements-based device test coverage

For the long-term coverage tree, rollout stages, fixture design, and expanded QA-01–QA-12 plan, see [TEST_PLAN.md](../TEST_PLAN.md) and [TEST_CASES_REPORT.md](../TEST_CASES_REPORT.md).

Source: `WalletTrackers_SMS_Flow_Documentation.docx` (May 2026), sections 1–4. All scenarios target the real installed app and configured backend. No unit-test results count as device coverage.

Detailed numbered steps and deterministic numeric/account oracles are in [TEST_CASES_REPORT.md](../TEST_CASES_REPORT.md); see its NUM-01–NUM-18 cases for record values/categories, dashboard math, multiple accounts, credit-card flows, Statistics, and notifications.

Target supplied by user: `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `9CA1966C3753AE18594DD5AA396673D67F156954FCCFAE8731ACD60FB5965632`. Installed with `adb install -r` on `emulator-5554` on 2026-09-24. This is a debug APK; tests of it do not certify a separately built release.

## Execution groups

- **Automated read-only:** `device_smoke.py --extended`; actual outcomes appear in timestamped `results.json`, not inferred from this checklist.
- **Write scenarios:** the user authorized controlled testing on the signed-in account. Continue only with uniquely tagged, low-value emulator-injected events and verify rollback after each write. Bulk import/retrack, statement settlement, transfers, and destructive actions remain excluded unless separately designed with reversible test data.
- **Controlled environment required:** provider-specific failures, 48-hour expiration, fresh onboarding, logged-out reception and device permissions need additional isolated setup. They remain unexecuted until that setup exists.
- **Physical-device checks:** carrier multipart delivery, OEM background restrictions and real biometrics require a phone; emulator results alone cannot certify these.

## Emulator run results

Scope completed across the original smoke run and later software-rendered reruns. Read-only smoke actions did not submit forms; normal startup read/sync behavior was not disabled. See the live account exercise below for the one explicitly authorized write and cleanup.

Appium runner verification (2026-09-24): the six core flows (launch, Records, Statistics round-trip, cancel Add Record, background/resume, process restart) passed together in `app/build/device-smoke/20260924-035810/`. A later `--only statistics` run passed all five Statistics tab interactions in `app/build/device-smoke/20260924-040924/`. Empty-budget disabled passed in `app/build/device-smoke/20260924-040211/`. Zero/negative amount cases are not yet reliable: the budget form disappeared during amount/category interaction, so those are automation failures, not confirmed product defects. Statistics accessibility does not expose a selected state; the current Appium checks confirm the tab remains visible and no NaN/Infinity is shown, not that styling/selection changed.

Latest extended Appium run on the latest debug APK: `app/build/device-smoke/20260925-084919/` — **14 passed**. All six core UI flows, empty/zero/negative-budget disabled-state checks, and all five Statistics tabs passed. This supersedes earlier inconclusive zero/negative-budget locator attempts; no records or budgets were saved.

Latest screenshots and XML: `app/build/device-smoke/20260924-071253/`. Earlier software-rendered zero-limit evidence is `app/build/device-smoke/20260924-032003/`; the initial run also reproduced it in `app/build/device-smoke/20260924-021455/results.json`.

## Live account exercise

Historical fresh-fixture regression runs (2026-09-25) used a signed-in disposable QA profile with seven verified accounts and EGP dashboard baseline 16,000.00. Seed evidence: `app/build/device-smoke/fresh-qa-setup-20260925-044127/results.json` (**PASS**). A real UI/emulator-SMS test then injected a uniquely tagged EGP 1.00 Carrefour Groceries debit on `MainBank` (balance 10,000.00→9,999.00; dashboard 16,000.00→15,999.00), deleted that record, relaunched, and verified exact restoration to 10,000.00/16,000.00: `app/build/device-smoke/20260925-044444/live_sms_expense/results.json` (**PASS**). After the server-confirmed Firestore snapshot guard was added, the same regression passed again for EGP 0.50 (10,000.00→9,999.50; dashboard 16,000.00→15,999.50→16,000.00) in `app/build/device-smoke/20260925-044813/live_sms_expense/results.json` (**PASS**). These runs cover only one bank-linked debit template and Groceries category, not all banks/categories. **Current fixture warning:** the later printed-balance test exposed a record deletion defect. The fixed APK restored the later 9,999.83 case baseline, but the profile remains EGP 0.17 below its original MainBank balance; it must be reinitialized before claiming fresh-baseline coverage. Synthetic SMS Inbox rows remain even when Wallet financial records were removed.

One earlier run used the wrong account suffix and surfaced a test-harness assertion hard-coded to “HSBC” despite `MainBank` being the fixture. This was corrected in `scripts/device_sms_expense_case.py`; it was not an app defect. The subsequent correctly configured tests passed.

Latest additional live financial cases (2026-09-25) use the fixture above and restore balances after each write:

- Real incoming SMS fallback: MainBank EGP 0.02 Groceries/Carrefour created the expected record and changed MainBank/Home by -EGP 0.02; exact deletion and relaunch restored baseline. Evidence: `app/build/device-smoke/20260925-083614/live_sms_expense_egp_and_rollback/`.
- Same-timestamp burst: MainBank EGP 0.03 Groceries/Carrefour and SecondBank EGP 0.04 Restaurants/KFC both created distinct records, with Home EGP 16,000.00→15,999.93; both were deleted separately and all balances restored. This passed after replacing timestamp-only SMS IDs. Earlier failure evidence is retained at `app/build/device-smoke/20260925-084300/live_sms_burst_distinct_same_timestamp/`; passing regression evidence is `app/build/device-smoke/20260925-084700/live_sms_burst_distinct_same_timestamp/`.
- Credit statement: automatic SMS handling created TestCard ****3333 statement EGP 1.11 due 05 Oct 2026; Credit Statistics showed the matching card/amount/date, three future reminders were queued, and `Credit Card Bill Issued` notification content was correct. MainBank payment created one `Credit` record, raised available credit by EGP 1.11, excluded it from spending, and exact record deletion/relaunch restored baseline. The picker excluded USD/EUR accounts. Evidence: `app/build/device-smoke/20260925-083900/live_credit_statement_auto_receive_payment_and_rollback/`.
- Initial empty-PDU failure evidence (`STRIPPED=1`) led to a READ_SMS recent-Inbox fallback. That fix was verified by the later real expense and statement SMS tests above; old provider SMS rows remain in Android Inbox because shell deletion is ineffective.

The developer-side sync fix ignores cache-only Firestore record snapshots when replacing Room rows; focused and full JVM suites passed in prior work. Device coverage remains pending for the full bank/category matrix, duplicate identity, payment order/mismatch permutations, provider faults, all planning tools, and physical-device behavior.

The signed-in account was explicitly authorized for controlled testing. On 2026-09-24, one local-emulator-only synthetic bank SMS (EGP 0.01 expense, unique `AUTOTEST...` marker) was injected; no carrier SMS was sent. Wallet parsed it as an expense, matched the linked account by suffix, persisted a record, and adjusted the displayed balance by EGP 0.01. Deleting the uniquely marked record restored the displayed balance. The deleted marker briefly remained visible after navigation, but search returned no record after a force-stop/relaunch. The test record is therefore absent after restart.

The synthetic SMS was subsequently removed from the emulator Inbox; querying its exact provider row returned no result. During the Wallet process restart, the dashboard showed account data different from the earlier test baseline. This is an unresolved account/data consistency observation; no additional writes were made. Do not clear app data or mutate the account pending investigation. Evidence under `app/build/device-smoke/20260924-034220/live_sms_expense/` and `app/build/device-scenarios/` can include financial data.

## Real UI smoke and input validation

| ID | Scenario | Assertion | Execution |
| --- | --- | --- | --- |
| UI-01 | Launch app with existing session | Dashboard appears | Automated |
| UI-02 | Open Records and return | Records title appears; dashboard returns | Automated |
| UI-03 | Open Statistics and return | Statistics title appears; dashboard returns | Automated |
| UI-04 | Open Add Record then Back | Returns without submitting a record | Automated |
| UI-05 | Home button then resume | Existing unlocked session returns | Automated |
| UI-06 | Kill process then relaunch | Session persists and dashboard loads | Automated |
| UI-07 | Empty budget form | Create disabled | Automated |
| UI-08 | Zero budget limit with category selected | Create disabled | Passed in latest extended run; no budget submitted |
| UI-09 | Negative budget limit with category selected | Create disabled for a nonpositive limit | Passed in latest extended run; no budget submitted |
| UI-10–14 | Balance, Spending, Net Worth, Credit, Reports tabs | Requested tab selected; no visible NaN/Infinity | Automated; does not assert financial totals |

## SMS discovery and onboarding — document section 1

| ID | Scenario | Expected result |
| --- | --- | --- |
| ON-01 | Fresh account, empty inbox | No discovered accounts; usable empty state |
| ON-02 | Deny SMS permission, then grant and retry | No crash; scan available after grant |
| ON-03 | Corpus scan | Bank messages retained; promos and declined messages excluded |
| ON-04 | Same account represented by 001 and 6001 | One group, longer canonical key |
| ON-05 | Different banks with similar suffixes | Review account separation; do not silently merge distinct accounts (additional correctness expectation) |
| ON-06 | Credit/debit inference | Correct account type and bank from sender/body |
| ON-07 | Renewed card with non-overlapping dates | Merge suggestion; user confirmation controls merge |
| ON-08 | Edit account names/limits and deselect a card | Confirmed values persist; deselected card not created |
| ON-09 | No Cash account, then existing Cash account | Create Cash once; never duplicate it |
| ON-10 | Historical income/expense chronology | Replay oldest to newest; correct running balance |
| ON-11 | History with printed balances | Printed balance overrides arithmetic; final bank balance matches latest printed value |
| ON-12 | Statement/card payment/card received in history | Not imported as ordinary expenses; pending statement handled separately |
| ON-13 | Historical ATM | Source debit decreases; Cash not credited in bulk mode per document |
| ON-14 | Unselected card income/expense | Empty accountId; visible unlinked banner; assignment persists |
| ON-15 | Unknown category and known category | Only Others enters AI phase; keyword phase completes independently |
| ON-16 | AI corrects expense to income | Category/type updated; validate balance consistency and flag documentation ambiguity if balances are not reconciled |
| ON-17 | Repeat import / interrupt and restart | No duplicate financial effect; interrupted progress recoverable (additional resilience expectation) |

## Live SMS and balance handling — document sections 2–3

| ID | Scenario | Expected result |
| --- | --- | --- |
| SMS-01 | Matching-account income without printed balance | One income record; balance increases by amount |
| SMS-02 | Matching-account expense without printed balance | One expense record; balance decreases by amount. **Passed sampled cases after precision fix:** EGP MainBank/Uber, MainBank/Carrefour, SecondBank, USD USDBank, EUR EURBank, and TestCard credit cases; TestCard covered Carrefour/Groceries and KFC/Restaurants. Latest-build USDBank USD 1,000.00 -> 999.75 and EURBank EUR 500.00 -> 499.75 kept Home unchanged and rollback restored both. MainBank/Netflix initially exposed unrounded `balanceAfter`; after `BalanceAmountFormatter`, live EGP 0.04 Subscriptions charge showed exact MainBank EGP 9,999.79 and Home EGP 15,999.79, then deletion/restart restored baseline. Evidence: `app/build/device-smoke/20260925-110814/live_sms_expense_usd_and_rollback/results.json`, `app/build/device-smoke/20260925-111215/live_sms_expense_eur_and_rollback/results.json`, fixed precision `app/build/device-smoke/20260925-104916/live_sms_expense_egp_and_rollback/results.json`, original issue screenshot `app/build/device-smoke/20260925-103905/live_sms_expense_egp_and_rollback/02_record_created.png`. Not a full bank×category matrix. |
| SMS-03 | Expense with printed balance | Exact printed balance; no second subtraction. **Passed after fix:** the original MainBank 10,000.00→printed 9,999.80 case exposed a delete rollback defect (`app/build/device-smoke/20260925-085847/live_sms_expense_egp_and_rollback/results.json`). After adding a persisted pre-SMS balance snapshot, live retest from current 9,999.83 applied printed 9,999.70 then deleted/restarted back to exactly 9,999.83 and Home 15,999.83: `app/build/device-smoke/20260925-091418/live_sms_expense_egp_and_rollback/results.json`. This validates same-case rollback but not reset to the original account fixture; row `_id=60` remains in Android Inbox. |
| SMS-04 | Large and small balance drift | Correct balance; notification only for documented threshold and positive prior balance |
| SMS-05 | Declined/insufficient-funds SMS | No transaction; document allows immediate printed-balance sync before guard. **Observed pass:** declined EGP 23.45 MainBank alert with printed EGP 9,999.83 created no record and changed neither MainBank nor Home. Synthetic Inbox row `_id=85` remains. Evidence: `app/build/device-smoke/20260925-104724/live_sms_declined_no_record/results.json`. |
| SMS-06 | Same SMS identity redelivered | One record and one financial effect |
| SMS-07 | Identical amount/body, different genuine events | Separate valid transactions; distinguish from duplicate delivery. **Observed pass:** two separately delivered identical EGP 0.02 MainBank/Carrefour SMS bodies created two Groceries records and debited EGP 0.04 total. The app displayed its non-blocking `Possible duplicate charge` anomaly; both records were deleted and restart restored the exact case balances. Evidence: `app/build/device-smoke/20260925-095754/live_sms_identical_body_distinct_events/results.json`. Synthetic Inbox rows `_id=72,73` remain. |
| SMS-08 | Unrecognized card | Unlinked record; Action Required notification; no unrelated account mutation. **Observed pass:** EGP 0.02 suffix 9998 displayed as `Imported Card (9998)` with the unlinked banner/notification, left MainBank/Home unchanged, and was deleted with restart verification. Evidence: `app/build/device-smoke/20260925-094646/live_sms_unknown_account_unlinked_record/results.json`. Exact EGP 100 and notification-tap variant remain unrun. |
| SMS-09 | Missing transaction amount | No fabricated transaction amount; no account change. **Observed pass:** injected bank-related amount-less alert for MainBank ****1111; Records remained empty and Home/MainBank remained EGP 15,999.83 / 9,999.83. Inbox row `_id=62` remains. Evidence: `app/build/device-smoke/20260925-092922/live_sms_missing_amount_no_record/results.json` |
| SMS-10 | Logged-out reception | No records written without user context |
| SMS-11 | ATM with and without printed balance | Bank decreases, Cash increases once; transfer excluded from spending |
| SMS-12 | Statement | Statement amount/due date persist; reminder scheduled; no expense subtraction. **Observed pass:** EGP 1.11/TestCard ****3333 due 05 Oct 2026; notification, three future reminders, UI payment and rollback verified: `app/build/device-smoke/20260925-083900/live_credit_statement_auto_receive_payment_and_rollback/` |
| SMS-13 | Credit payment: debit then credit SMS | One complete transfer; both balances change once. **Passed after fix:** debit-first exposed destination-card suffix misattribution (`SecondBank -> SecondBank`, card unchanged). Fix now independently parses `payment to card ****3333`; retest on precision-fixed APK produced one `SecondBank -> TestCard` transfer, correct EGP 613.37 balance effects/notification, and exact cleanup after restart. Evidence: `app/build/device-smoke/20260925-105334/credit_payment/results.json`; original defect: `app/build/device-smoke/20260925-100743/credit_payment/results.json`. |
| SMS-14 | Credit payment: credit then debit SMS | Partial transfer upgrades; no duplicate record or balance change. **Observed pass:** TestCard received EGP 613.37 first without source debit/Home change; SecondBank debit then completed one `Credit Payment` record, raised card availability to EGP 3,613.37, lowered SecondBank to EGP 4,386.63, and reduced Home to EGP 15,386.46 without spending. Completion notification verified; delete/restart restored SecondBank EGP 5,000.00, TestCard EGP 3,000.00, and the current Home baseline EGP 15,999.83. Evidence: `app/build/device-smoke/20260925-100116/credit_payment/results.json`. |
| SMS-15 | Cross-bank credit payment | Correct debit source and credit destination; one transfer |
| SMS-16 | Payment fee tolerance boundaries | Matching within documented tolerance; unrelated amounts stay separate |
| SMS-17 | Pending payment expires after 48h | Stale pending entry cannot match a new payment |
| SMS-18 | Two equal-amount payments to different cards | No cross-linking between distinct payments (additional correctness expectation) |
| SMS-19 | Foreign charge with inline EGP equivalent | Store original transaction currency; apply home-currency equivalent |
| SMS-20 | Foreign charge with printed balance | Printed balance wins; derived difference handled consistently |
| SMS-21 | Foreign charge without conversion/balance evidence | Record saved; account balance unchanged; empty balanceAfter |
| SMS-22 | Numeric suffix matching | Correct account selected for partial digits |
| SMS-23 | Arabic / English / multiline / multipart SMS | Complete body interpreted once, correct amount and account |
| SMS-24 | Burst of income/expense arrivals | No lost updates; one record per genuine event. **Observed pass:** two distinct same-sender, same-timestamp EGP expense SMSs after stable SMS IDs; mixed income+expense burst remains not run: `app/build/device-smoke/20260925-084700/live_sms_burst_distinct_same_timestamp/` |

## Rules, providers and persistence — document section 4 plus resilience checks

| ID | Scenario | Expected result |
| --- | --- | --- |
| AI-01 | User merchant rule conflicts with AI | User category wins |
| AI-02 | Groq fails | Gemini attempted; one final record |
| AI-03 | Groq and Gemini fail | Cerebras attempted; one final record |
| AI-04 | All providers fail | Controlled Groq/Gemini/Cerebras failures; recognized bank SMS still creates one deterministic-parser `Others` expense with exact amount, category, balance, provider-sequence and rollback assertions |
| AI-05 | Inject unknown-format SMS; return controlled AI extractions with `isBankRelated=false` and `true` | False extraction saves nothing and leaves balance unchanged; true extraction saves one correctly linked record with exact amount/category and stable restart state |
| AI-06 | Statement, ATM, credit-first/debit-second payment SMS | Zero AI calls; correct statement amount/card/paid cleanup, ATM transfer and before/after snapshots, one linked card-payment row, exact account deltas, and restart persistence |
| DATA-01 | Save offline then reconnect | Changes persist locally and sync once; balances reconcile |
| DATA-02 | Restart during write | Record and balances remain consistent |
| DATA-03 | Second-device read after save/edit/delete | Remote state matches first device; no stale resurrected record |
| DATA-04 | Manual expense/income CRUD | Balance delta, edit reversal and delete reversal are correct |
| DATA-05 | Transfer CRUD | Source/destination reconcile; no spending inflation |
| DATA-06 | CSV export of commas/quotes/newlines | Round-trip values preserved in exported artifact |
| DATA-07 | Budget thresholds, month boundaries, currency | Correct spending scope, alerts and totals |

## Remaining feature acceptance cases

These extend beyond the SMS document and need product-rule review plus test-owned data before execution.

| ID | Scenario | Expected result |
| --- | --- | --- |
| APP-01 | Account create/edit/archive/unarchive | Required fields validated; balances and record links preserved |
| APP-02 | Delete account with existing records | Defined orphan/reassignment behavior; explicit confirmation; no unrelated deletion |
| APP-03 | Savings goal create/contribute/edit/delete | Progress and target consistent; zero/negative/oversized inputs handled |
| APP-04 | Debt create/repay/edit/delete | Remaining amount correct; direction and overpayment validated |
| APP-05 | Recurring bill create/mark paid/reminder/delete | Correct next due date; one payment effect; canceled reminder removed |
| APP-06 | Categories/subcategories/custom rules CRUD | Valid names; existing records and precedence handled consistently |
| APP-07 | Records search/filter/date/account/type | Visible set and totals match controlled fixtures; clearing restores set |
| APP-08 | Calendar day/month/year boundaries | Correct day allocation and leap-day behavior |
| APP-09 | Currency converter online/offline/invalid inputs | Currency direction and rate freshness clear; errors do not fabricate results |
| APP-10 | Receipt attachment/cancel/reopen | Photo associates with correct record; cancel causes no orphaned transaction |
| APP-11 | PIN/biometric lock and background return | Protected content inaccessible until authentication; canceled authentication remains locked |
| APP-12 | Sign-in/sign-out/account switch | Correct user data isolation; no previous-user data visible |

## Fixture and oracle rules

Run `python scripts/sms_corpus_inventory.py` to validate export counts and create a local manifest. The supplied files contain 2,144 entries and 2,116 unique sender/body pairs. The manifest keeps all source references and exact message bodies. `expected` is intentionally null until independently reviewed against requirements; exported App extracted fields may contain the bugs under investigation.

For each executed write case, record before/after balances, account identity, record count, amount, type, currency, category, transfer endpoints, and restart/remote persistence. Record account state before cleanup. Delete only test-owned records and verify cleanup balances; never use Delete All or Retrack All as cleanup on an existing financial account.

Emulator text SMS changes reception timestamps and may not preserve alphanumeric senders or multipart encoding. Sender/date-sensitive cases need PDU fixtures or an isolated inbox fixture. Replaying the same text at a different timestamp is not a valid proof of same-identity deduplication. Historical records replayed as live messages also do not validate onboarding chronology.

The document's 350ms AI delay and stated 30 requests/minute limit conflict: 350ms alone permits roughly 171 requests/minute. Treat the intended rate-limit behavior as an unresolved requirement, not a proven runtime bug. The source documentation is older than current working-tree edits; behavior differences must be classified as requirement drift or bugs with evidence.

The zero-budget finding is a confirmed **validation gap against the inferred test rule**: the form enables Create with category `Food & Drinks` and limit `0`. The flow DOCX does not specify whether zero limits are allowed, so request a product decision before treating this as a production defect. No budget was saved. Evidence from the software-rendered run is `app/build/device-smoke/20260924-032003/08_zero_budget_disabled.png` and the associated XML/results. Negative-budget input remains untested because UI text injection did not display `-1`.
