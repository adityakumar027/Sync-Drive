# SyncDrive Releases

Official installation binaries for **SyncDrive** (Android).

---

## Current Release

- **Binary**: [`SyncDrive.apk`](./SyncDrive.apk) (also available as [`SyncDrive-v1.3.apk`](./SyncDrive-v1.3.apk))
- **Version**: `v1.3.0`
- **Package Name**: `com.example.drivesyncfix`
- **Application Label**: `SyncDrive`

- **Original Fast File Filtering**: Restored the ultra-fast scanning rules from DriveSyncFix.apk: strictly skips hidden directories (`.*`) and dotfiles at traversal level, preventing chunk file leaks (.chunk, .thumbnails/, .cache/) and ensuring instant folder scans.
- **Pixel-Perfect Circular Toolbar Buttons**: Perfectly defined 40dp circular buttons for Quick Sync (with modern vector icon) and Theme Switcher with crisp alignment.
- **Light & Dark Mode**: One-tap theme toggle in top app bar (Light, Dark, System Default) with smooth real-time theme adaptation across all activities.
- **Interactive Periodic Background Sync**: Dedicated scheduler card with visible ON/OFF switch, customizable sync intervals (1h, 3h, 6h, 12h, 24h), and network constraints (Any Network vs WiFi only).
- **Background Execution Safety**: Fully fixed for Android 12+ / 14+ background execution to prevent foreground service crashes, with completion notifications.
- **Unified Branding & App Logo**: Single modern top app bar displaying **SyncDrive** and the official logo; duplicate headers removed.
- **Launcher Icons**: Multi-density adaptive and round launcher icons (`mdpi` through `xxxhdpi`).
- **Complete Storage Access & Scoped Storage Bypass**: In-app folder browser and direct path resolvers ensure any directory on device can be selected without "can't use this folder" errors.
- **High-Performance Upload Mechanism from DriveSyncFix.apk**: Restored the original fast uploading mechanism with 1-API-call folder batch listing, O(1) in-memory hash verification, direct upload for files <5MB, and 8MB chunked resumable upload for larger files.
- **Material 3 Interface**: Elevated cards, real-time progress indicators, transfer stats, and clean tab navigation.

---

## Older Releases

Previous builds are archived in the [`old_versions/`](./old_versions/) directory:
- [`releases/old_versions/DriveSyncFix-v1.0.apk`](./old_versions/DriveSyncFix-v1.0.apk) (Original v1.0 baseline)
- [`releases/old_versions/SyncDrive-v1.1.apk`](./old_versions/SyncDrive-v1.1.apk) (v1.1 link parser)
- [`releases/old_versions/SyncDrive-v1.2.apk`](./old_versions/SyncDrive-v1.2.apk) (v1.2 launcher icon update)
