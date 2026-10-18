<div align="center">
  <img src="assets/logo.png" width="130" height="130" alt="Sync Drive Logo" style="border-radius: 26px;" />
  <h1>🔄 Sync Drive</h1>
  <p><b>Mirror Android device folders to Google Drive with resilient, background multi-thread sync</b></p>

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/Min%20API-26%20(Oreo)-blue.svg)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![GitHub Release](https://img.shields.io/github/v/release/adityakumar027/Sync-Drive?color=blue&logo=github)](https://github.com/adityakumar027/Sync-Drive/releases/latest)

</div>

A robust Android app that **mirrors your device folders to Google Drive** with folder structure preserved — featuring **resumable chunked uploads**, **4 simultaneous upload workers**, and **automatic retry with exponential backoff**.

Built to solve the #1 problem with lightweight sync apps: **large files (>20 MB) silently failing** due to single-POST timeouts, OS process kills, or Drive API rate limits.

---

## ✨ Features

### 🚀 Resilient Upload Engine
- **Resumable chunked uploads** — files go up in 8 MB chunks; if a chunk fails, it resumes from the last confirmed byte instead of restarting
- **Direct upload** for small files (<5 MB) for speed, automatic chunked mode for everything else
- **No file size limit** — tested with multi-GB videos
- **Exponential backoff** (1s → 2s → 4s → 8s → 16s, up to 60s) on HTTP 403/429 rate limits and 5xx server errors
- **Automatic OAuth token refresh** on 401 Unauthorized — no manual re-login needed mid-sync
- **Network error recovery** — retries on socket timeouts and connection resets

### ⚡ Parallel Sync Engine
- **4 simultaneous upload workers** running in parallel (configurable)
- **Real-time progress UI** showing each active upload slot with filename, bytes transferred, and percentage
- **WorkManager-based** foreground service — survives app backgrounding and device sleep
- **Foreground notification** with live progress

### 🧠 Smart Deduplication
- **Batch folder verification** — fetches Drive folder contents in bulk (up to 1000 files/page), cached in memory for O(1) lookups
- **Skip detection** by filename + exact byte size — avoids re-uploading what's already on Drive
- **Instant re-sync** — completed files recorded in SQLite so subsequent syncs skip them in milliseconds

### 📂 Multi-Folder Sync Pairs
- Map **any device folder** to **any Drive folder** — DCIM, Downloads, custom paths, or entire internal storage
- Each pair syncs independently with its own Drive destination
- **Folder structure mirrored** automatically — subfolders recreated on Drive

### 🔁 Retry & Recovery
- **Dedicated "Retry Failed Files" mode** — retries only previously failed uploads without re-scanning everything
- **Per-file error tracking** in SQLite with detailed error messages
- **Sync history** with filterable logs (Uploaded / Skipped / Failed)

### 📊 Detailed Status UI
- **3-tab bottom navigation**: Status, Synced Folders, History
- **Per-folder file inspector** — tap any sync pair to see which files are backed up (✓) vs pending (⏳)
- **Filter chips** to quickly find uploaded, skipped, or failed files
- **Progress breakdown**: uploaded count, skipped count, failed count

---

## 📥 Quick Install

Download the pre-built debug APK directly from GitHub Releases:

→ **[Download DriveSyncFix-v1.0-debug.apk](https://github.com/adityakumar027/Sync-Drive/releases/download/v1.0.0/DriveSyncFix-v1.0-debug.apk)**

Or find it in the repository under [`releases/DriveSyncFix-v1.0-debug.apk`](releases/DriveSyncFix-v1.0-debug.apk).

> ⚠️ You must complete the [One-Time Setup](#-one-time-setup) below before the app will work — it requires your own Google Cloud OAuth credentials.

---

## 🔧 One-Time Setup

This app has **no bundled credentials** — you must register your own OAuth client in Google Cloud Console (takes ~5 minutes):

### 1. Create a Google Cloud Project
- Go to [console.cloud.google.com](https://console.cloud.google.com) → **New Project**

### 2. Enable the Drive API
- **APIs & Services** → **Library** → search "Google Drive API" → **Enable**

### 3. Configure OAuth Consent Screen
- **APIs & Services** → **OAuth consent screen** → **External** (or Internal if you have Workspace)
- Fill in app name (`Sync Drive`) and email → **Save**
- Add yourself as a **test user** (avoids needing Google review for personal use)

### 4. Create an OAuth Client ID
- **APIs & Services** → **Credentials** → **Create Credentials** → **OAuth client ID**
- Application type: **Android**
- Package name: `com.example.drivesyncfix`
- SHA-1 fingerprint — get it by running:
  ```bash
  keytool -list -v -keystore ~/.android/debug.keystore \
    -alias androiddebugkey -storepass android -keypass android
  ```
- Paste the SHA-1 into the console

> No `google-services.json` is needed — the app uses manual `GoogleSignInOptions` setup. But the client ID **must** be registered against this exact package name + SHA-1 or sign-in will fail.

### 5. Build & Run
```bash
# Clone the repo
git clone https://github.com/adityakumar027/Sync-Drive.git
cd Sync-Drive

# Open in Android Studio (Hedgehog+), let Gradle sync, then run on device
# Or build from command line:
./gradlew assembleDebug
```

---

## 📱 How to Use

1. **Sign in** with your Google account and grant all-files storage access
2. Tap **"+ Add Sync Folder"** → choose a device folder (DCIM, Downloads, or browse/type any path)
3. Enter the **destination Drive folder ID** — get it from the folder's URL:
   ```
   https://drive.google.com/drive/folders/<THIS_PART>
   ```
4. Repeat for as many folders as you want synced
5. Tap **"Sync Now"** or **"Schedule Periodic Sync"** (daily, WiFi-only)

---

## 🏗️ Architecture

```
app/src/main/java/com/example/drivesyncfix/
```

| File | Purpose |
|------|---------|
| [`DriveUploadManager.kt`](app/src/main/java/com/example/drivesyncfix/DriveUploadManager.kt) | **Core upload engine.** Resumable chunked upload with exponential backoff, token refresh, and network error recovery. |
| [`SyncWorker.kt`](app/src/main/java/com/example/drivesyncfix/SyncWorker.kt) | **Sync orchestrator.** WorkManager CoroutineWorker: scan → batch-verify → 4-thread parallel upload → record results. |
| [`DriveFolderResolver.kt`](app/src/main/java/com/example/drivesyncfix/DriveFolderResolver.kt) | **Drive folder management.** Mirrors local folder structure into Drive, batch-fetches folder contents for dedup checks. |
| [`FileScanner.kt`](app/src/main/java/com/example/drivesyncfix/FileScanner.kt) | **Local file discovery.** Recursive directory walker with configurable exclusion filters. |
| [`SyncDatabase.kt`](app/src/main/java/com/example/drivesyncfix/SyncDatabase.kt) | **Room database.** Stores sync pairs, synced file records, and sync history logs. |
| [`AuthManager.kt`](app/src/main/java/com/example/drivesyncfix/AuthManager.kt) | **Authentication.** Google Sign-In + OAuth2 access token retrieval and refresh. |
| [`MainActivity.kt`](app/src/main/java/com/example/drivesyncfix/MainActivity.kt) | **Main UI.** 3-tab bottom navigation (Status, Synced Folders, History) with real-time sync progress. |
| [`SyncPairDetailActivity.kt`](app/src/main/java/com/example/drivesyncfix/SyncPairDetailActivity.kt) | **Folder inspector.** Per-file backup status view with backed-up vs pending breakdown. |
| [`SyncHistoryActivity.kt`](app/src/main/java/com/example/drivesyncfix/SyncHistoryActivity.kt) | **History viewer.** Filterable sync log with retry-failed-files support. |

### Sync Flow

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────────┐
│ FileScanner  │────▶│ DriveFolderResolver│────▶│  DriveUploadManager │
│ Walk local   │     │ Batch-verify with │     │  Chunked resumable  │
│ directories  │     │ Drive (skip dups) │     │  upload (4 parallel)│
└─────────────┘     └──────────────────┘     └─────────────────────┘
       │                     │                          │
       └─────────────────────┴──────────────────────────┘
                             │
                      ┌──────▼──────┐
                      │ SyncWorker  │
                      │ (WorkManager│
                      │  foreground │
                      │  service)   │
                      └──────┬──────┘
                             │
                      ┌──────▼──────┐
                      │ SyncDatabase│
                      │ (Room SQLite│
                      │  - pairs    │
                      │  - history  │
                      │  - records) │
                      └─────────────┘
```

---

## ⚙️ Configuration

Tunable constants in the source code:

| Constant | File | Default | Description |
|----------|------|---------|-------------|
| `CHUNK_SIZE` | `DriveUploadManager.kt` | 8 MB | Upload chunk size — lower for flaky connections |
| `DIRECT_UPLOAD_LIMIT` | `DriveUploadManager.kt` | 5 MB | Files below this use direct upload (faster for small files) |
| `MAX_RETRIES` | `DriveUploadManager.kt` | 5 | Max retry attempts per HTTP request |
| `PARALLEL_UPLOADS` | `SyncWorker.kt` | 4 | Number of simultaneous upload workers |
| `DEFAULT_EXCLUDED_DIRS` | `FileScanner.kt` | See source | Directories excluded from scanning |
| `DEFAULT_EXCLUDED_EXTENSIONS` | `FileScanner.kt` | See source | File extensions excluded from scanning |

---

## 🔒 Privacy & Security

- **No bundled credentials** — you use your own Google Cloud OAuth client
- **No analytics or tracking** — zero third-party SDKs beyond Google's official libraries
- **No data leaves your device** except to your own Google Drive
- **All files access** (`MANAGE_EXTERNAL_STORAGE`) is required to discover files across all device folders — the app reads but never modifies local files

---

## 📋 Tech Stack

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target SDK**: 34 (Android 14)
- **UI**: Material Design 3 with ViewBinding
- **Networking**: Google Drive API v3 (official Java client library)
- **Auth**: Google Sign-In (`play-services-auth`)
- **Background**: WorkManager with foreground service
- **Database**: Room (SQLite)
- **Concurrency**: Kotlin Coroutines + Channels

---

## 🛠️ Known Limitations

- **SD card folders** — the folder picker's path conversion only supports internal/primary storage
- **Drive folder names** — displayed as raw folder IDs in the pairs list (not human-readable names)
- **File matching** — deduplication uses name + exact size, not checksums (reliable for backups but not cryptographically certain)
- **No two-way sync** — this is a one-way mirror (device → Drive only)

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
