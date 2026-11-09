# Tabslify v1.0.0 — Release Notes

> 🎉 **First official release** — the successor to the `v1.0.0-beta01` preview from July 2026.
>
> 🌐 **New in this release:** Tabslify is now fully available in German **and** English — the beta was German-only. Pick your language during onboarding or in the settings.

**One app to replace them all:** media player, browser, password & 2FA manager, notes, weather, movies, gallery, recorder, virus scanning, vocab trainer, AI chat — bundled into a single, privacy-first Android app.

- **Requirements:** Android 15 or newer (minSdk 35)
- **Development:** 668 commits since the first beta — July 13 → November 9, 2026

---

## ⭐ Highlights

- 🌐 **German & English** — v1.0.0 is the first fully bilingual release: complete translation, in-app language selection and a reworked first-run experience
- 🛡️ **VirusTotal** — scan files and links for malware, right inside the app
- 🗺️ **Explore** — now a real travel tracker: timeline, map, stats, export & share
- 🤖 **AI tab** — multi-chat history, model selection, and "Send to AI" from any app
- 🎵 **Podcasts** — smarter feed handling, download states and unread markers, all inside the Media Player
- 🎨 **New look** — warm dark palette, redesigned landing page and onboarding
- 🔄 **In-app updates** — update Tabslify straight from the settings screen
- 🔒 **Data safety** — your passwords and 2FA secrets never leave the device via Android backup

---

## 🆕 What's inside

### 🌐 Languages & first run — new in v1.0.0

- The entire UI is now fully translated into **German and English** — including all detail texts and error messages. The beta was German-only.
- Pick your language during onboarding, change it anytime (per-app language).
- Rebuilt onboarding: language, notification permission and optional features, with smooth page animations and remembered progress.
- The permissions screen was redesigned: grouped permissions (runtime / special / automatic), live status badges, plain-language explanations and one-tap grants — including "request everything at once".

### 🎨 Design

- A warm dark palette rolled out across all tabs, with frosted floating headers, edge-to-edge layouts and shared day/night backgrounds.
- Redesigned landing page and settings.
- Shared back hint and proper back navigation inside tabs; the browser now handles web full-screen correctly.

### 🤖 AI Tab

- **Multi-chat history** — keep several parallel chats instead of one endless thread.
- **Model selection** with additional providers (incl. OpenRouter) and fast free models.
- **Notification replies** — answer the AI directly from the notification.
- **Send to AI** — select text in any app and dispatch it straight into the AI tab.
- Markdown callouts render in AI summaries, with a copy button for the summary.

### 🛡️ VirusTotal (new tab)

- Scan **files and URLs** against VirusTotal without leaving the app.
- Scans keep running in the background: a persistent scan bar shows active and completed jobs, and finished scans notify you with a direct link to the report.
- The API key stays on the server — nothing secret ships inside the APK.

### 🎵 Media Player & Recorder

- **Podcasts are now part of the Media Player** — download podcasts by name, mark favourites, and manage episodes in one place.
- Smarter feeds: parallel feed checks, redirects followed, new-episode tracking per episode, unread markers.
- Per-episode download progress and state right in the feed card, plus cleanup of local files you no longer need.
- Local podcast playback with proper song metadata and ID3 tag writing.
- **Recorder:** capture system audio — what's playing on your phone — via media projection, alongside regular voice recordings.
- Optional listening stats for your music *(opt-in, off by default)*.

### 🗺️ Explore (opt-in, off by default)

- More than a "world percentage": Explore now records your **travel segments and stays** with battery-aware, activity-recognition-based tracking.
- Browse each day with stats, a segment timeline and colored map polylines.
- Distance aggregates and **export & share** for your data.
- Enable it whenever you want — tracking only runs while the feature is on.

### 🎓 Vocabs & School

- **AI OCR:** upload an image and get structured vocabulary out of it.
- **Learn:** go through your vocab, mark what you knew, repeat your mistakes.
- **Materials:** keep your school material in one place.
- **Grades (new):** track your grades per subject right next to your vocab.

### 🔐 Authenticator & passwords

- 2FA codes (TOTP), password vault and **autofill** — now with smarter dataset ordering (last used first) and smoother biometric prompts.

### 🔄 Updates & data safety

- **In-app updates:** check for, download and install Tabslify updates from the settings screen — with SHA-256 verification of every part.
- **Auto Backup denylist:** the password vault, the 2FA database and other secrets are excluded from Android backup, so they never leave the device that way.

---

## 🧩 Included tabs

| Tab | What it does |
|---|---|
| **AI Tab** | Chat with AI models (text & image), with full chat context. |
| **Browser** | Optimized WebView, quick-link buttons, desktop mode. |
| **Quick Access** | Network, display, battery and device info at a glance. |
| **Gallery** | View & delete pictures and videos from local storage. |
| **Authenticator** | 2FA codes (TOTP), password vault, autofill. |
| **Weather** | Current weather & forecast for your location. |
| **Recorder** | Voice recordings & recordings of played-back media. |
| **Movie Discovery** | Discover movies by genre (powered by TMDB). |
| **Notes** | Notes with color labels. |
| **Media Player** | Music & podcasts: play, sort, download, favourite. |
| **Vocabs & School** | AI OCR vocab extraction, learning, materials, grades. |
| **Explore** *(opt-in)* | See how much of the world you've explored — timeline, map & stats. |
| **VirusTotal** | Scan files and links for malware. |

---

## ⚙️ Background services

- **Media Player Service** — Foreground service (MediaSession) for music & podcasts, controllable via notification controls, lock screen and Bluetooth/headset buttons.
- **Quiet Hours Notification Service** — Central command executor: receives text commands from a notification and runs them — start an AI chat, query the weather, control media playback and much more.
- **Explore Tracking Service** *(opt-in)* — Location foreground service that records your travel segments and stays while Explore is enabled.

---

## 🔒 Privacy & security

- **No ads, no ad or behavioral tracking.** Optional local analysis (e.g. music listening stats) is opt-in and off by default.
- Passwords, 2FA secrets and other sensitive data are excluded from Android Auto Backup.
- No API keys inside the APK — external scan services are called through a server-side proxy.
- Hardened internals: explicit PendingIntents everywhere, XXE-safe podcast feed parsing, automatic secret redaction in diagnostics.

## ⚡ Fixes & performance

- Startup freeze fixed; large JSON documents no longer crash the app.
- Memory-leak audit across the app (bitmaps, media metadata, background state).
- Code shrinking & minification enabled for the release build.
- Modernized stack: Kotlin 2.4, current Compose & Material 3, updated Gradle and dependencies.

---

## 🗑️ Changed since the beta

- The **Contacts** tab has been removed — Tabslify no longer manages your contacts.
- **Calendar** and **Date Calculator** are not part of v1.0.0.
- The standalone **Podcasts** tab is now folded into the **Media Player**.

---

*Feedback is very welcome — thank you for using Tabslify!*
