# Privacy Policy for Google Drive Sync

**Effective Date:** October 18, 2026  
**Last Updated:** October 18, 2026

Google Drive Sync ("we", "our", or "the app") is an open-source Android application designed to mirror local device folders to your Google Drive account.

We value your privacy. This policy outlines how your information is handled when using Google Drive Sync.

---

## 1. Information We Access and How It Is Used

### Google Account & Drive Access
- **Google Sign-In & Authentication:** The app uses Google Sign-In to authenticate your account and obtain an OAuth2 access token. We access your email address and profile identity solely to display your active account in the application.
- **Google Drive Data (`https://www.googleapis.com/auth/drive`):** The app accesses your Google Drive solely to upload files and mirror folder structures from your local device to the destination Drive folders you specify.
- **Local Device Storage:** The app requests storage permissions exclusively to scan, read, and upload the folders and files that you select for synchronization.

---

## 2. Data Storage and Transfer

- **Direct Communication:** All file transfers and API requests occur directly between your Android device and Google's official servers (`https://www.googleapis.com`).
- **No Third-Party Servers:** We do **not** operate any intermediary servers, databases, or analytics services. Your files, access tokens, and metadata are **never** transmitted to us or any third party.
- **Local Storage:** The app stores your sync configurations, folder IDs, and sync history (file names, upload timestamps, and error messages) locally on your device in a private SQLite database.

---

## 3. Google API Limited Use Disclosure

Google Drive Sync's use and transfer to any other app of information received from Google APIs will adhere to the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including the **Limited Use** requirements.

Specifically:
- We do not use Google user data to serve advertisements.
- We do not allow humans to read your data unless required by law or with your explicit consent for technical support.
- We do not sell or transfer your data to third parties.

---

## 4. Permissions Used

- **`INTERNET` & `ACCESS_NETWORK_STATE`:** Required to communicate directly with Google Drive APIs.
- **`READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`:** Required to read the files you choose to backup.
- **`FOREGROUND_SERVICE` & `POST_NOTIFICATIONS`:** Used to run sync operations reliably in the background and display real-time upload progress.
- **`RECEIVE_BOOT_COMPLETED`:** Allows scheduled periodic sync tasks to persist across device reboots if configured by the user.

---

## 5. Data Retention and Deletion

- You can revoke the app's access to your Google account at any time via [Google Account Security Settings](https://myaccount.google.com/permissions).
- Uninstalling the application or clearing app data deletes all locally stored sync pairs, history, and cached credentials immediately.

---

## 6. Open Source & Transparency

Google Drive Sync is free, open-source software. You can inspect the source code and verify our privacy and security practices at:  
[https://github.com/adityakumar027/Google-Drive-Sync](https://github.com/adityakumar027/Google-Drive-Sync)

---

## 7. Contact Us

If you have questions about this Privacy Policy, please contact the developer via GitHub Issues at:  
[https://github.com/adityakumar027/Google-Drive-Sync/issues](https://github.com/adityakumar027/Google-Drive-Sync/issues)
