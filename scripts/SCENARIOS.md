# Requirements-based device test coverage

Source: `WalletTrackers_SMS_Flow_Documentation.docx` (May 2026), sections 1–4. All scenarios target the real installed app and configured backend. No unit-test results count as device coverage.

Target supplied by user: `app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `9CA1966C3753AE18594DD5AA396673D67F156954FCCFAE8731ACD60FB5965632`. Installed with `adb install -r` on `emulator-5554` on 2026-09-24. This is a debug APK; tests of it do not certify a separately built release.

## Execution groups

- **Automated read-only:** `device_smoke.py --extended`; actual outcomes appear in timestamped `results.json`, not inferred from this checklist.
- **Write scenarios:** the user authorized controlled testing on the signed-in account. Continue only with uniquely tagged, low-value emulator-injected events and verify rollback after each write. Bulk import/retrack, statement settlement, transfers, and destructive actions remain excluded unless separately designed with reversible test data.
- **Controlled environment required:** provider-specific failures, 48-hour expiration, fresh onboarding, logged-out reception and device permissions need additional isolated setup. They remain unexecuted until that setup exists.
- **Physical-device checks:** carrier multipart delivery, OEM background restrictions and real biometrics require a phone; emulator results alone cannot certify these.

## Emulator run results

Scope completed across the original smoke run and later software-rendered reruns. Read-only smoke actions did not submit forms; normal startup read/sync behavior was not disabled. See the live account exercise below for the one explicitly authorized write and cleanup.

Appium runner verification (2026-09-24): the six core flows (launch, Records, Statistics round-trip, cancel Add Record, background/resume, process restart) passed together in `app/build/device-smoke/20260924-035810/`. A later `--only statistics` run passed all five Statistics tab interactions in `app/build/device-smoke/20260924-040924/`. Empty-budget disabled passed in `app/build/device-smoke/20260924-040211/`. Zero/negative amount cases are not yet reliable: the budget form disappeared during amount/category interaction, so those are automation failures, not confirmed product defects. Statistics accessibility does not expose a selected state; the current Appium checks confirm the tab remains visible and no NaN/Infinity is shown, not that styling/selection changed.

Latest extended Appium run: `app/build/device-smoke/20260924-071253/` — **12 passed, 2 inconclusive failures**. The six core flows, empty-budget validation, and all five Statistics interactions passed. Zero/negative-budget checks could not inspect the Create control after amount entry; saved form captures show the Budgets screen rather than a stable edit dialog. Neither is evidence of a Wallet defect until interaction and assertion are reliable. No records or budgets were created.

Latest screenshots and XML: `app/build/device-smoke/20260924-071253/`. Earlier software-rendered zero-limit evidence is `app/build/device-smoke/20260924-032003/`; the initial run also reproduced it in `app/build/device-smoke/20260924-021455/results.json`.

## Live account exercise

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
| UI-08 | Zero budget limit with category selected | Observed: Create remains enabled. Whether zero is invalid needs product confirmation; zero-limit validation expectation is inferred. Never submitted. | Failed assertion, repeated after software-renderer cold boot |
| UI-09 | Negative budget limit with category selected | Create disabled if nonpositive limits are invalid | Blocked: ADB text/key input did not populate `-1`; no app behavior inferred |
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
| SMS-02 | Matching-account expense without printed balance | One expense record; balance decreases by amount |
| SMS-03 | Expense with printed balance | Exact printed balance; no second subtraction |
| SMS-04 | Large and small balance drift | Correct balance; notification only for documented threshold and positive prior balance |
| SMS-05 | Declined/insufficient-funds SMS | No transaction; document allows immediate printed-balance sync before guard |
| SMS-06 | Same SMS identity redelivered | One record and one financial effect |
| SMS-07 | Identical amount/body, different genuine events | Separate valid transactions; distinguish from duplicate delivery |
| SMS-08 | Unrecognized card | Unlinked record; Action Required notification; no unrelated account mutation |
| SMS-09 | Missing transaction amount | No fabricated transaction amount |
| SMS-10 | Logged-out reception | No records written without user context |
| SMS-11 | ATM with and without printed balance | Bank decreases, Cash increases once; transfer excluded from spending |
| SMS-12 | Statement | Statement amount/due date persist; reminder scheduled; no expense subtraction |
| SMS-13 | Credit payment: debit then credit SMS | One complete transfer; both balances change once |
| SMS-14 | Credit payment: credit then debit SMS | Partial transfer upgrades; no duplicate record or balance change |
| SMS-15 | Cross-bank credit payment | Correct debit source and credit destination; one transfer |
| SMS-16 | Payment fee tolerance boundaries | Matching within documented tolerance; unrelated amounts stay separate |
| SMS-17 | Pending payment expires after 48h | Stale pending entry cannot match a new payment |
| SMS-18 | Two equal-amount payments to different cards | No cross-linking between distinct payments (additional correctness expectation) |
| SMS-19 | Foreign charge with inline EGP equivalent | Store original transaction currency; apply home-currency equivalent |
| SMS-20 | Foreign charge with printed balance | Printed balance wins; derived difference handled consistently |
| SMS-21 | Foreign charge without conversion/balance evidence | Record saved; account balance unchanged; empty balanceAfter |
| SMS-22 | Numeric suffix matching | Correct account selected for partial digits |
| SMS-23 | Arabic / English / multiline / multipart SMS | Complete body interpreted once, correct amount and account |
| SMS-24 | Burst of income/expense arrivals | No lost updates; one record per genuine event |

## Rules, providers and persistence — document section 4 plus resilience checks

| ID | Scenario | Expected result |
| --- | --- | --- |
| AI-01 | User merchant rule conflicts with AI | User category wins |
| AI-02 | Groq fails | Gemini attempted; one final record |
| AI-03 | Groq and Gemini fail | Cerebras attempted; one final record |
| AI-04 | All providers fail | Keyword fallback for recognized format; no silent transaction loss |
| AI-05 | Unknown format accepted/rejected by extraction | Save only bank-related extraction; no invented record |
| AI-06 | Statement/ATM/payment | Deterministic handler; no unnecessary AI type call |
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
