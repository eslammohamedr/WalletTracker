# WalletTrackers Requirements Specification

Document version: 1.0
Date: 2026-05-02
Project: WalletTrackers

Each requirement has a unique ID. Status values are based on the current source code review.

## Requirement ID Format

- `AUTH-*`: Authentication and user session.
- `ONB-*`: Onboarding.
- `ACC-*`: Account management.
- `REC-*`: Transaction records.
- `SMS-*`: SMS automation.
- `AI-*`: AI classification.
- `CC-*`: Credit card statements and payments.
- `BILL-*`: Monthly bills and recurring detection.
- `BUD-*`: Budgets.
- `STAT-*`: Statistics.
- `GOAL-*`: Savings goals.
- `DEBT-*`: Debts.
- `TRF-*`: Transfers.
- `CAT-*`: Categories and rules.
- `CUR-*`: Currency conversion.
- `SEC-*`: Security and privacy.
- `NOTIF-*`: Notifications and reminders.
- `DATA-*`: Data persistence and sync.
- `NFR-*`: Non-functional requirements.

## Functional Requirements

| ID | Priority | Requirement | Status |
|---|---|---|---|
| AUTH-001 | Must | The app shall allow users to sign in with Google. | Implemented |
| AUTH-002 | Must | The app shall allow users to sign in with Facebook. | Implemented |
| AUTH-003 | Must | The app shall allow users to sign in with email and password. | Implemented |
| AUTH-004 | Must | The app shall allow users to create an account with email and password. | Implemented |
| AUTH-005 | Must | The app shall route signed-in first-time users to onboarding. | Implemented |
| AUTH-006 | Must | The app shall route returning signed-in users to the home screen. | Implemented |
| AUTH-007 | Should | The app shall allow users to sign out. | Implemented |
| AUTH-008 | Should | The app shall allow users to delete their account data. | Implemented |
| ONB-001 | Must | The app shall provide a first-run onboarding flow for new users. | Implemented |
| ONB-002 | Should | The onboarding flow shall support account discovery from SMS data. | Implemented |
| ACC-001 | Must | The app shall allow users to create financial accounts. | Implemented |
| ACC-002 | Must | The app shall allow users to update financial accounts. | Implemented |
| ACC-003 | Must | The app shall allow users to delete financial accounts. | Implemented |
| ACC-004 | Should | The app shall allow users to archive and unarchive accounts. | Implemented |
| ACC-005 | Must | The app shall store account name, type, last digits, balance, currency, and user ownership. | Implemented |
| ACC-006 | Should | The app shall support credit card account metadata including credit limit and billing day. | Implemented |
| REC-001 | Must | The app shall allow users to manually add income and expense records. | Implemented |
| REC-002 | Must | The app shall allow users to edit existing records. | Implemented |
| REC-003 | Must | The app shall allow users to delete existing records. | Implemented |
| REC-004 | Must | The app shall update account balances when records are added. | Implemented |
| REC-005 | Must | The app shall adjust account balances when records are edited. | Implemented |
| REC-006 | Must | The app shall restore account balances when records are deleted. | Implemented |
| REC-007 | Should | The app shall preserve imported SMS transaction timestamps. | Implemented |
| REC-008 | Should | The app shall export records to CSV format. | Implemented |
| REC-009 | Must | The app shall list all records for review and management. | Implemented |
| CAT-001 | Must | The app shall provide parent categories and subcategories for records. | Implemented |
| CAT-002 | Should | The app shall allow selecting a category while adding or editing a record. | Implemented |
| CAT-003 | Should | The app shall allow users to save merchant category rules. | Implemented |
| CAT-004 | Should | Saved category rules shall resync matching historical records. | Implemented |
| CAT-005 | Should | The app shall allow deletion of category rules. | Implemented |
| SMS-001 | Must | The app shall request `RECEIVE_SMS` permission. | Implemented |
| SMS-002 | Must | The app shall request `READ_SMS` permission. | Implemented |
| SMS-003 | Must | The app shall receive incoming SMS messages using a broadcast receiver. | Implemented |
| SMS-004 | Must | The app shall ignore duplicate SMS messages already saved as records or statements. | Implemented |
| SMS-005 | Must | The app shall ignore declined transaction SMS messages. | Implemented |
| SMS-006 | Should | The app shall ignore promotional SMS messages that are not financial transactions. | Implemented |
| SMS-007 | Must | The app shall extract amount, transaction type, card/account digits, category, and comment from bank SMS. | Implemented |
| SMS-008 | Must | The app shall match SMS transactions to accounts by last digits where possible. | Implemented |
| SMS-009 | Should | The app shall extract balance-after values from SMS when present. | Implemented |
| SMS-010 | Should | The app shall support manual SMS review/import. | Implemented |
| AI-001 | Must | The app shall use Gemini to classify SMS categories when rule-based parsing is insufficient. | Implemented |
| AI-002 | Must | The app shall parse AI responses into structured transaction data. | Implemented |
| AI-003 | Should | The app shall fall back to keyword parsing when AI analysis fails. | Implemented |
| CC-001 | Must | The app shall detect credit card statement SMS messages. | Implemented |
| CC-002 | Must | The app shall store detected credit card statements. | Implemented |
| CC-003 | Must | The app shall detect credit card payments. | Implemented |
| CC-004 | Must | The app shall support linking debit-side and credit-side SMS messages for the same card payment. | Implemented |
| CC-005 | Must | The app shall update debit and credit account balances for credit card payments when accounts are known. | Implemented |
| CC-006 | Should | The app shall mark matching unpaid statements as paid after payment. | Implemented |
| CC-007 | Should | The app shall allow users to pay a credit statement from a selected debit account. | Implemented |
| BILL-001 | Must | The Monthly Bills screen shall show detected paid subscriptions from the last month. | Implemented |
| BILL-002 | Must | The Monthly Bills screen shall detect non-subscription recurring payments with the same amount, day, and currency over the last two months. | Implemented |
| BILL-003 | Must | Recurring detection shall exclude transfers, credit payments, credit records, Instapay income, and Instapay outcome. | Implemented |
| BILL-004 | Must | Users shall be able to confirm a detected subscription or recurring payment as a bill. | Implemented |
| BILL-005 | Must | Users shall be able to dismiss a detected subscription or recurring payment suggestion. | Implemented |
| BILL-006 | Should | Confirmed bills shall schedule bill reminders. | Implemented |
| BILL-007 | Should | Users shall be able to manually add a monthly bill. | Implemented |
| BILL-008 | Should | The Monthly Bills tab shall use a GUI consistent with the app color palette. | Implemented |
| BUD-001 | Must | The app shall allow users to create budgets. | Implemented |
| BUD-002 | Must | The app shall allow users to update budgets. | Implemented |
| BUD-003 | Must | The app shall allow users to delete budgets. | Implemented |
| BUD-004 | Should | The app shall calculate current-month spend for budget categories. | Implemented |
| BUD-005 | Should | The app shall notify users when spending reaches at least 80 percent of a budget. | Implemented |
| STAT-001 | Must | The app shall show financial statistics based on records. | Implemented |
| STAT-002 | Should | The app shall calculate monthly total expense. | Implemented |
| STAT-003 | Should | The app shall calculate the top spending category for the current month. | Implemented |
| STAT-004 | Should | The app shall show actionable credit statement payment controls in statistics. | Implemented |
| GOAL-001 | Must | The app shall allow users to create savings goals. | Implemented |
| GOAL-002 | Must | The app shall allow users to update savings goals. | Implemented |
| GOAL-003 | Must | The app shall allow users to delete savings goals. | Implemented |
| DEBT-001 | Must | The app shall allow users to create debts. | Implemented |
| DEBT-002 | Must | The app shall allow users to update debts. | Implemented |
| DEBT-003 | Must | The app shall allow users to delete debts. | Implemented |
| TRF-001 | Must | The app shall allow users to transfer money between accounts. | Implemented |
| TRF-002 | Must | Transfers shall update both source and destination account balances. | Implemented |
| TRF-003 | Must | Transfers shall create a transfer-style record. | Implemented |
| CUR-001 | Should | The app shall provide a currency converter screen. | Implemented |
| NOTIF-001 | Must | The app shall request notification permission on Android versions that require it. | Implemented |
| NOTIF-002 | Should | The app shall send notifications for automatically tracked transactions. | Implemented |
| NOTIF-003 | Should | The app shall schedule reminders for credit card statements. | Implemented |
| NOTIF-004 | Should | The app shall schedule reminders for monthly bills. | Implemented |
| SEC-001 | Must | The app shall store data by authenticated Firebase user ID. | Implemented |
| SEC-002 | Should | The app shall support biometric lock. | Implemented |
| SEC-003 | Must | The app shall not hardcode the Gemini API key in source code. | Implemented |
| DATA-001 | Must | The app shall store accounts in Firestore. | Implemented |
| DATA-002 | Must | The app shall store records in Firestore. | Implemented |
| DATA-003 | Must | The app shall store credit statements in Firestore. | Implemented |
| DATA-004 | Must | The app shall store budgets in Firestore. | Implemented |
| DATA-005 | Must | The app shall store savings goals in Firestore. | Implemented |
| DATA-006 | Must | The app shall store debts in Firestore. | Implemented |
| DATA-007 | Must | The app shall store bills in Firestore. | Implemented |
| DATA-008 | Should | The app shall synchronize user data in real time using Firestore listeners. | Implemented |

## Non-Functional Requirements

| ID | Priority | Requirement | Status |
|---|---|---|---|
| NFR-001 | Must | The app shall run on Android SDK 29 and above. | Implemented |
| NFR-002 | Must | The app shall use a responsive Compose UI suitable for mobile devices. | Implemented |
| NFR-003 | Should | The app shall keep UI state synchronized with Firestore updates. | Implemented |
| NFR-004 | Should | The app shall avoid saving duplicate SMS transactions. | Implemented |
| NFR-005 | Should | The app shall continue SMS processing asynchronously after receiving a broadcast. | Implemented |
| NFR-006 | Should | The app shall handle AI/network failures without crashing transaction processing. | Implemented |
| NFR-007 | Must | The project shall build with Gradle. | Implemented |
| NFR-008 | Should | The project shall include unit and instrumentation test entry points. | Partially implemented |
| NFR-009 | Should | The project shall expand automated tests for SMS parsing, balance math, recurring detection, and credit card payment linking. | Recommended |
| NFR-010 | Must | Firestore security rules shall restrict users to their own data. | External configuration required |
| NFR-011 | Must | Release builds shall use a secure signing configuration. | External configuration required |

