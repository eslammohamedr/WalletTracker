<div align="center">

![Android](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)
![Firebase](https://img.shields.io/badge/Firebase-DD2C00?style=for-the-badge&logo=firebase&logoColor=white)
![Gemini AI](https://img.shields.io/badge/Gemini_AI-8E75B2?style=for-the-badge&logo=google&logoColor=white)

# 💳 WalletTrackers

**An AI-powered personal finance manager that automatically intercepts bank SMS messages, categorizes transactions, and syncs everything to the cloud in real-time.**

*Built with Jetpack Compose · Firebase · Google Gemini AI*

</div>

---

## ✨ Key Features

| | Feature | Description |
|---|---|---|
| 🤖 | **AI SMS Interceptor** | Automatically detects bank SMS messages and uses Google Gemini AI to extract transaction amount, type, category, account, merchant name, and comments. |
| 🏦 | **Multi-Account Management** | Track balances across Cash, Debit, and Credit accounts in one place. Supports multiple currencies and credit limits. |
| 📊 | **Interactive Analytics** | Visualize spending patterns with beautiful charts powered by Vico. Monthly insights, top categories, and spending trends. |
| 📅 | **Credit Statement Reminders** | Automatically schedules notifications for credit card bill due dates extracted directly from bank SMS messages. |
| 💱 | **Real-time Currency Converter** | Integrated Ktor-based converter for international transactions using live exchange rates. |
| ☁️ | **Real-time Sync** | All data safely backed up and synced across devices using Firebase Firestore with snapshot listeners. |
| 🔐 | **Secure Auth + Biometric Lock** | Sign in with Google, Facebook, or Email. Optional biometric (fingerprint) lock to protect your financial data. |
| 🔁 | **Monthly Bills Detection** | Automatically detects recurring payments and subscriptions (Netflix, Spotify, YouTube, etc.) from transaction history. |
| 🎯 | **Budgets & Savings Goals** | Set per-category budgets with 80% alert notifications and track progress toward savings targets. |

---

## 🛠 Tech Stack

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)
![Material 3](https://img.shields.io/badge/Material_3-757575?style=flat-square&logo=material-design&logoColor=white)
![MVVM](https://img.shields.io/badge/MVVM_Architecture-37474F?style=flat-square)
![Firebase Auth](https://img.shields.io/badge/Firebase_Auth-DD2C00?style=flat-square&logo=firebase&logoColor=white)
![Firestore](https://img.shields.io/badge/Firestore-FFA000?style=flat-square&logo=firebase&logoColor=white)
![Gemini AI](https://img.shields.io/badge/Gemini_AI-8E75B2?style=flat-square&logo=google&logoColor=white)
![Ktor](https://img.shields.io/badge/Ktor-087CFA?style=flat-square&logo=ktor&logoColor=white)
![Vico](https://img.shields.io/badge/Vico_Charts-6200EE?style=flat-square)
![WorkManager](https://img.shields.io/badge/WorkManager-0F9D58?style=flat-square&logo=android&logoColor=white)
![Navigation Compose](https://img.shields.io/badge/Navigation_Compose-4285F4?style=flat-square&logo=android&logoColor=white)
![Biometric](https://img.shields.io/badge/BiometricPrompt-37474F?style=flat-square&logo=android&logoColor=white)

---

## 📱 App Screens

<table>
  <tr>
    <td>🔑 Login</td>
    <td>📝 Sign Up</td>
    <td>🚀 Onboarding</td>
    <td>🏠 Home / Dashboard</td>
    <td>➕ Add Record</td>
    <td>📋 All Records</td>
  </tr>
  <tr>
    <td>💬 SMS Import</td>
    <td>📊 Statistics</td>
    <td>🗂 Categories</td>
    <td>📁 Sub-Categories</td>
    <td>💰 Budget Management</td>
    <td>🎯 Savings Goals</td>
  </tr>
  <tr>
    <td>💸 Debt Tracking</td>
    <td>🔁 Monthly Bills</td>
    <td>↔️ Transfer</td>
    <td>📅 Calendar View</td>
    <td>💱 Currency Converter</td>
    <td>🔒 PIN / Biometric Lock</td>
  </tr>
</table>

---

## 🚀 Getting Started

### Step 1 — Clone & Open in Android Studio

```bash
git clone https://github.com/your-username/wallet-tracker.git
```

Open the project in **Android Studio** (Koala or newer). Sync Gradle to download all dependencies.

---

### Step 2 — Add Firebase Configuration

1. Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com/).
2. Enable **Firestore** and **Authentication** (Google / Facebook / Email).
3. Place your `google-services.json` in the `app/` directory.

---

### Step 3 — Configure Gemini API Key

Get a free API key from [aistudio.google.com](https://aistudio.google.com/), then create `local.properties` in the project root:

```properties
gemini.api.key=YOUR_KEY_HERE
```

---

### Step 4 — Build & Run

```bash
./gradlew assembleDebug
```

> Run on a **physical Android device** (API 29+) for full SMS interception features, or use an emulator for UI testing.

---

## 🗄 Firebase Data Structure

```
users/{userId}/
├── accounts/          # Wallet, debit, credit accounts
├── records/           # Income & expense transactions
├── creditStatements/  # Credit card bill statements
├── budgets/           # Per-category spending limits
├── savingsGoals/      # Savings targets & progress
├── debts/             # Money owed by/to user
├── bills/             # Monthly bills & subscriptions
└── categoryRules/     # Merchant → category mappings
```

---

## 🛡 Permissions

To enable AI tracking features, the app requires:

| Permission | Purpose |
|---|---|
| `RECEIVE_SMS` & `READ_SMS` | Detect and process bank notifications |
| `INTERNET` | Gemini AI analysis and Firebase sync |
| `USE_BIOMETRIC` | Optional fingerprint lock |
| `POST_NOTIFICATIONS` | Transaction alerts and bill reminders |

---

## 🤝 Contributing

Contributions are welcome! If you have ideas for new features or improvements, feel free to open an issue or submit a pull request.

---



---

<div align="center">
  <sub>WalletTrackers — Android Personal Finance App · Built with Kotlin & Jetpack Compose</sub>
</div>
