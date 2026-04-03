# 💳 Wallet Tracker (AI-Powered)

**Wallet Tracker** is a modern, intelligent personal finance manager for Android. Unlike traditional expense trackers, it leverages **Google Gemini AI** to automatically intercept and parse bank SMS messages, transforming unstructured text into organized financial data.

Built with **Jetpack Compose** and **Firebase**, it offers a seamless, real-time experience for managing multiple accounts, tracking spending habits, and never missing a credit card payment.

---

## ✨ Key Features

- **🤖 AI SMS Interceptor:** Automatically detects bank SMS messages and uses Gemini AI to extract:
  - Transaction amount & type (Income/Expense/ATM/Transfer).
  - Category (Groceries, Salary, Subscriptions, etc.).
  - Account/Card identification via last 4 digits.
  - Merchant names and comments.
- **🏦 Multi-Account Management:** Track balances across Cash, Debit, and Credit accounts in one place.
- **📊 Interactive Analytics:** Visualize your spending patterns with beautiful charts powered by **Vico**.
- **📅 Credit Statement Reminders:** Automatically schedules notifications for credit card bill due dates extracted from SMS.
- **💱 Real-time Currency Converter:** Integrated Ktor-based converter for international transactions.
- **☁️ Real-time Sync:** All data is safely backed up and synced across devices using **Firebase Firestore**.
- **🔐 Secure Auth:** Sign in easily with Google, Facebook, or Email.

---

## 🛠 Tech Stack

- **UI:** [Jetpack Compose](https://developer.android.com/jetpack/compose) with Material 3.
- **Architecture:** MVVM (Model-View-ViewModel) with a Repository pattern.
- **AI Engine:** [Google Gemini AI](https://ai.google.dev/) (gemini-1.5-flash).
- **Backend:** Firebase Firestore & Firebase Authentication.
- **Networking:** [Ktor](https://ktor.io/) for API requests.
- **Charts:** [Vico](https://github.com/patrykandpatrick/vico).
- **Background Tasks:** [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) for reminders.
- **Local Utilities:** BroadcastReceivers for SMS listening.

---

## 🚀 Getting Started

### Prerequisites
1.  **Android Studio** (Koala or newer recommended).
2.  **Google AI Studio API Key:** Get one for free at [aistudio.google.com](https://aistudio.google.com/).
3.  **Firebase Project:** Create a project at [console.firebase.google.com](https://console.firebase.google.com/).

### Setup Instructions
1.  **Clone the repository:**
    ```bash
    git clone https://github.com/your-username/wallet-tracker.git
    ```
2.  **Add Firebase:**
    - Place your `google-services.json` in the `app/` directory.
    - Enable **Firestore** and **Anonymous/Google/Email Auth** in the Firebase console.
3.  **Configure API Keys:**
    - Open `app/src/main/java/com/example/wallettrackers/receiver/SmsReceiver.kt`.
    - Replace `"YOUR_GEMINI_API_KEY"` with your actual key from Google AI Studio.
4.  **Build and Run:**
    - Sync Gradle and run the app on a physical device (SMS features require a real SIM/device).

---

## 📸 Screenshots

| Home Screen | Statistics | Add Record |
|:---:|:---:|:---:|
| ![Home](https://via.placeholder.com/300x600?text=Home+Screen) | ![Stats](https://via.placeholder.com/300x600?text=Statistics) | ![Add](https://via.placeholder.com/300x600?text=Add+Record) |

---

## 🛡 Permissions
To enable the AI tracking features, the app requires:
- `RECEIVE_SMS` & `READ_SMS`: To detect and process bank notifications.
- `INTERNET`: For Gemini AI analysis and Firebase sync.

---

## 🤝 Contributing
Contributions are welcome! If you have ideas for new features or improvements, feel free to open an issue or submit a pull request.

---

## 📜 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
