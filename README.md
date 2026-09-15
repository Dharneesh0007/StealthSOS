# 🛡️ StealthSOS — Voice-Triggered Covert Emergency Response System

> **Smart India Hackathon 2026** | **Problem Statement ID:** SIH26204  
> **Team:** VoxSentinel  

---

## 📌 Overview
During acute emergencies (physical attacks, stalking, medical crises), victims often cannot safely pull out or unlock their smartphones to call for help. **StealthSOS** solves this by offering a completely hands-free, zero-interaction emergency lifeline triggered silently by a pre-configured secret wake word.

---

## 🚀 Key Features

* 🎙️ **Hands-Free Wake Word Trigger:** Runs continuous, low-latency background audio monitoring (`SpeechRecognizer`) to detect distress words hands-free.
* 📱 **Fake "Power-Off" Stealth Screen:** Instantly renders a believable power-off interface via `WindowManager` overlay flags, masking all ongoing SOS background operations from aggressors.
* 📍 **Automated GPS & SMS Tracking:** Utilizes `FusedLocationProviderClient` to fetch high-precision live coordinates and immediately dispatches SOS alert loops to trusted contacts.
* 📸 **Covert Snapshot Capture:** Employs the native `Camera2 API` to capture 5 background evidence photos silently without ever launching a camera preview UI.
* 📂 **Secure Local Storage:** Saves voice loops and captured images into local storage via `MediaStore` and `ContentResolver` as tamper-resistant digital evidence.
* 🛑 **Fail-Safe Exit Gesture:** Deactivates stealth mode only through a user-defined long-press gesture (5-second hold).

---

## 🛠️ Tech Stack & Android Architecture

* **Language:** Kotlin
* **UI Design:** XML Layouts
* **IDE:** Android Studio
* **Core APIs & Components:**
  * `Camera2 API` — Covert multi-frame background photo capture
  * `FusedLocationProviderClient` — High-accuracy, battery-optimized GPS coordinates
  * `SpeechRecognizer` & Audio Amplitude Listener — Sound threshold & wake-word detection
  * `MediaStore` / `ContentResolver` — Scoped storage for encrypted evidence management
  * Android Foreground Services & `WindowManager` Overlays

---

## ⚙️ Operational Workflow

1. **Background Listening:** Service listens passively for high-amplitude spikes (>30,000 amplitude).
2. **Verification:** System activates speech recognition to verify user-defined distress keyword.
3. **Stealth Trigger:** Fake shut-down UI displays while background threads start immediately.
4. **Action Sequence:** 
   * Live GPS location link SMS sent to emergency contacts.
   * Camera2 takes silent background photos.
   * Ambient audio recording loop stored securely.
5. **Safe Exit:** 5-second touch hold deactivates emergency sequence.

---

## 👥 Target Beneficiaries & Impact
* **Target Audience:** Women, senior citizens, solo night commuters, and individuals in high-risk zones.
* **Economic Advantage:** Zero additional cost—eliminates the need to buy standalone, expensive panic button hardware by transforming existing Android hardware into a covert personal sentinel.

---

## 📄 License
Developed for Smart India Hackathon 2026 by Team **VoxSentinel**.
