# WalletTrackers — Android App

## Overview

WalletTrackers is a **native Android application** built with Kotlin and Jetpack Compose. It is an AI-powered personal finance manager that automatically intercepts bank SMS messages, categorizes transactions, and syncs everything to the cloud in real-time.

**This is NOT a web app.** It cannot run in a browser or Replit's preview pane. It must be built and run using Android Studio or the Android build toolchain targeting a physical Android device (API 29+) or emulator.

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Architecture**: MVVM
- **Backend/Cloud**: Firebase Auth + Firestore (real-time sync)
- **AI**: Google Gemini AI (SMS parsing and transaction categorization)
- **HTTP Client**: Ktor (currency conversion)
- **Charts**: Vico
- **Background Jobs**: WorkManager
- **Auth**: Google, Facebook, Email via Firebase Auth
- **Biometric**: AndroidX BiometricPrompt

## Project Structure

```
WalletTrackers/
├── app/
│   ├── build.gradle.kts          # App-level Gradle config (compileSdk 36, minSdk 29)
│   ├── google-services.json      # Firebase config (project: wallettracker-9fc44)
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/example/wallettrackers/
│   │   │   ├── auth/             # Authentication logic
│   │   │   ├── components/       # Reusable Compose components
│   │   │   ├── converters/       # Data converters
│   │   │   ├── model/            # Data models (Account, Budget, Record, etc.)
│   │   │   ├── navigation/       # Navigation graph
│   │   │   ├── receiver/         # SMS BroadcastReceiver
│   │   │   ├── remote/           # Ktor API clients
│   │   │   ├── repository/       # Data repositories (Firebase/Firestore)
│   │   │   ├── screens/          # All Compose UI screens
│   │   │   ├── service/          # Background services
│   │   │   ├── ui/theme/         # Material 3 theme
│   │   │   ├── util/             # Utility classes
│   │   │   ├── viewmodel/        # ViewModels + Factories
│   │   │   ├── worker/           # WorkManager workers
│   │   │   └── MainActivity.kt
│   │   └── res/                  # Android resources
├── build.gradle.kts              # Root Gradle config
├── settings.gradle.kts           # Module includes + repository config
├── gradle.properties             # Gradle JVM/build settings
├── detekt.yml                    # Static analysis config
└── sms_export.txt                # Sample SMS data for testing
```

## Firebase Configuration

- **Project**: wallettracker-9fc44
- **Package**: com.example.wallettrackers
- `google-services.json` is already present in `app/`
- Services used: Firebase Auth, Firestore

## Gemini API Key

The app reads the Gemini API key from `local.properties` (not committed):
```properties
gemini.api.key=YOUR_KEY_HERE
```

## Building

This project requires the Android SDK and Gradle. It cannot be built or run in the Replit web environment directly.

To build locally:
1. Open in Android Studio (Koala or newer)
2. Sync Gradle
3. Add `google-services.json` to `app/` (already present)
4. Add Gemini API key to `local.properties`
5. Run on physical device (API 29+) or emulator

```bash
./gradlew assembleDebug
```

## Animation System (Jetpack Compose)

All major screens now feature polished Compose animations:

- **`components/AnimatedUtils.kt`** — Shared reusable animation primitives:
  - `AnimatedCounter` — count-up number animation for balance display
  - `shimmerEffect` — loading shimmer modifier
  - `PulseRing`, `FloatingAnimation` — infinite pulse/float effects
  - `bounceClick`, `CountUpText`, `staggerDelay` helpers

- **`components/NumberPad.kt`** — Spring-bounce scale on every key press

- **`components/BottomNavBar.kt`** — Sliding pill tab indicator, pulsing FAB glow ring, spring-press FAB scale

- **`screens/HomeScreen.kt`** — `AnimatedCounter` for total balance, `SpringQuickActionButton` with staggered `AnimatedVisibility` fade-in + spring press scale

- **`screens/LoginScreen.kt`** — Floating logo (infinite Y translation + radial glow alpha pulse), fade+slide entry for title and card

- **`screens/AddRecordScreen.kt`** — `AnimatedContent` on amount digits (scale-in on change), currency and account name transitions

- **`screens/OnboardingScreen.kt`**:
  - `WelcomeStep` — Floating logo with double glow ring, staggered fade+slide content entry
  - `ScanningStep` — Rotating `CircularProgressIndicator` + pulsing outer ring
  - `DoneStep` — Spring scale-in check icon, pulsing green glow, fade+slide text and button

- **`screens/StatisticsScreen.kt`** — `SimpleBalanceBar` and `AccountBalanceRow` use `animateFloatAsState` with `FastOutSlowInEasing` so progress bars grow smoothly when the screen loads; bars now use a horizontal gradient fill

## Key Features

- AI SMS interception using Gemini AI
- Multi-account management (Cash, Debit, Credit)
- Interactive analytics with Vico charts
- Credit statement reminders (WorkManager)
- Real-time currency conversion via Ktor
- Firebase Firestore real-time sync
- Google / Facebook / Email authentication
- Biometric (fingerprint) lock
- Monthly bills detection
- Budgets & savings goals

## Screens

Login, Sign Up, Onboarding, Home/Dashboard, Add Record, All Records, SMS Import, Statistics, Categories, Sub-Categories, Budget Management, Savings Goals, Debt Tracking, Monthly Bills, Transfer, Calendar View, Currency Converter, PIN/Biometric Lock

## Permissions Required

- `RECEIVE_SMS` & `READ_SMS` — bank SMS interception
- `INTERNET` — Gemini AI + Firebase sync
- `USE_BIOMETRIC` — fingerprint lock
- `POST_NOTIFICATIONS` — transaction alerts and bill reminders
