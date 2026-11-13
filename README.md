# MYRAA AI — Premium Voice-Controlled Android AI Automation Assistant

**Tagline:** *Your Voice. Your Intelligence. Your Control.*

MYRAA AI is a native Android (Kotlin + Jetpack Compose + Material 3) voice-controlled AI assistant supporting natural **Hindi, Hinglish, and English** commands, powered by the user's own **Gemini API** key and **ElevenLabs** (default assistant voice: **Mahi**), with modular device automation agents and transparent on-device security & audit logs.

---

## Key Features

- **Conversational Gemini Intelligence (`GeminiClient`)**:
  - Strict structured JSON intent parsing (`intent`, `actionType`, `target`, `extraData`, `riskLevel`, `requiresConfirmation`, `spokenResponse`).
  - Exponential backoff retries (`NetworkRetryHelper`), timeout handling, and offline deterministic reflex support.
- **Mahi Default Assistant Voice (`ElevenLabsClient`)**:
  - Mahi (`21m00Tcm4TlvDq8ikWAM`) configured as the default Indian conversational voice preset.
  - Supports custom Mahi Voice ID override, model selection (`eleven_multilingual_v2`, `eleven_flash_v2_5`), speed control, error reporting, and optional Android TTS fallback.
- **11 Modular Sub-Agents (`com.example.agents`)**:
  1. Gemini Intelligence Agent
  2. Voice Recognition Agent
  3. Mahi Voice Output Agent
  4. App Launcher Agent
  5. Device Action Agent
  6. Permission Manager Agent
  7. Accessibility Agent *(optional)*
  8. Shizuku Integration Agent *(optional)*
  9. Floating Assistant Agent *(optional)*
  10. Activity Logger Agent
  11. System Health Agent
- **11-Step Security-Allowlisted Action Pipeline**:
  - Enforces `ActionSecurityPolicy` allowlist, blocks executable code/shell injection, requires explicit user confirmation for `HIGH` risk or irreversible actions, speaks actual results via Mahi, and persists every trace locally.
- **Supported Device Automation**:
  - Launch installed or user-selected apps via official Android intents (`InstalledAppsDialog`).
  - Open **Wi-Fi**, **Bluetooth**, **Display**, **Sound**, **Location**, **App**, and **Notification** settings (never claiming a setting was toggled automatically).
  - Open **Camera**, **Calculator**, **Clock**, **Flashlight**, **Volume**, **Pause/Resume Listening**, and **Stop AI**.
- **Security & Local Audit Logs (Room DB v2)**:
  - Hardware **AndroidKeyStore AES-256-GCM** encryption at rest for Gemini & ElevenLabs credentials, plus credential deletion and privacy command-text redaction toggles.
  - Filterable Activity History (All / Successful / Failed / Cancelled, date filters, search, entry details, delete entry, JSON export, clear history).
  - Dedicated Privacy & Security screen showing live mic, foreground service, permissions, stored credential status, and **Stop All Background Activity**.

---

## Android Compatibility & Build Requirements

- **minSdk:** 24 (Android 7.0)
- **targetSdk / compileSdk:** 36 (Android 14 & Android 15 foreground service types `microphone`, `mediaProjection`, `specialUse` configured with required manifest metadata & callbacks)
- **Language / UI:** Kotlin 2.2, Jetpack Compose, Material 3, Room 2.7, OkHttp 4.10, Coroutines 1.10

---

## Build & Testing

### Configure API Keys Inside the App
- Open **MYRAA AI -> Setup Wizard** (or **Settings -> API Configuration**) and enter:
  - Your personal **Gemini API Key** (plus select model & Test Connection).
  - Your **ElevenLabs API Key** and confirm the **Mahi Voice ID**.
- Keys are stored locally encrypted via Android Keystore (`KeystoreSecurityVault`). Never hardcode keys in source code.

### Gradle Commands
```bash
# Run local unit & Robolectric verification tests
gradle :app:testDebugUnitTest

# Build Debug APK (app/build/outputs/apk/debug/app-debug.apk)
gradle :app:assembleDebug

# Build Release APK (when release signing env vars KEYSTORE_PATH / STORE_PASSWORD / KEY_PASSWORD are configured)
gradle :app:assembleRelease
```

---

## Security Disclosure

- Local encryption via Android Keystore AES-256-GCM protects stored credentials at rest on standard devices, **but local encryption cannot prevent data extraction on a rooted or fully compromised device.**
- Raw microphone audio is never stored in local logs.
- Optional features (Accessibility, Floating Overlay, Shizuku) remain strictly user-approved and never silently enabled.
