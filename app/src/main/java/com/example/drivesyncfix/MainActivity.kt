package com.example.drivesyncfix

import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.*
import com.example.drivesyncfix.databinding.ActivityMainBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pairAdapter: SyncPairAdapter
    private val logAdapter = SyncLogAdapter()
    private var allHistoryLogs = listOf<SyncLog>()
    private val db by lazy { SyncDatabase.getInstance(this) }
    private var isSyncing = false

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        runCatching { task.getResult(com.google.android.gms.common.api.ApiException::class.java) }
            .onSuccess { updateSignInState() }
            .onFailure { Toast.makeText(this, "Sign-in failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val treeUri = result.data?.data ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val localFile = treeUriToFile(treeUri)
        if (localFile == null || !localFile.exists()) {
            AlertDialog.Builder(this)
                .setTitle("Folder Access Restricted")
                .setMessage("Android's system file picker restricted access to that folder.\n\nWould you like to select the folder using the built-in Device Folder Browser instead?")
                .setPositiveButton("Open Folder Browser") { _, _ ->
                    showFolderBrowser(Environment.getExternalStorageDirectory())
                }
                .setNegativeButton("Cancel", null)
                .show()
            return@registerForActivityResult
        }
        promptForDriveDestination(localFile)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pairAdapter = SyncPairAdapter(
            emptyList(),
            onRemove = { pair -> removePair(pair) },
            onClick = { pair -> openPairDetail(pair) }
        )
        binding.lvSyncPairs.adapter = pairAdapter

        binding.btnSignIn.setOnClickListener {
            signInLauncher.launch(AuthManager.getSignInClient(this).signInIntent)
        }

        binding.btnGrantStorage.setOnClickListener { requestAllFilesAccess() }

        binding.btnAddSyncFolder.setOnClickListener { openFolderPicker() }

        binding.btnSyncNow.setOnClickListener {
            if (isSyncing) {
                cancelSync()
            } else {
                triggerSyncNow()
            }
        }

        binding.btnScheduleDaily.setOnClickListener { scheduleDailySync() }
        binding.btnThemeToggle.setOnClickListener { showThemeSelectionDialog() }
        setupPeriodicSyncUi()
        setupFilterSettingsUi()

        binding.btnViewHistory.setOnClickListener {
            switchTab(2)
            binding.bottomNav.selectedItemId = R.id.nav_history
        }

        binding.btnRetryFailed.setOnClickListener {
            startRetryFailedSync()
        }

        binding.btnHeaderSync.setOnClickListener {
            triggerSyncNow()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_status -> { switchTab(0); true }
                R.id.nav_folders -> { switchTab(1); true }
                R.id.nav_history -> { switchTab(2); true }
                else -> false
            }
        }

        setupHistoryTab()
        switchTab(0)

        updateSignInState()
        refreshPairsList()
        observeSyncWork()
    }

    override fun onResume() {
        super.onResume()
        updateSignInState()
        refreshPairsList()
        updateRetryFailedButton()
        if (binding.layoutTabHistory.visibility == View.VISIBLE) {
            loadHistoryTabLogs()
        }
    }

    private fun switchTab(tabIndex: Int) {
        binding.layoutTabStatus.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        binding.layoutTabFolders.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        binding.layoutTabHistory.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE

        when (tabIndex) {
            0 -> {
                val acc = AuthManager.getLastSignedInAccount(this)
                binding.tvHeaderSubtitle.text = acc?.email ?: "Google Drive Cloud Sync"
            }
            1 -> {
                binding.tvHeaderSubtitle.text = "Synced Folder Pairs"
                refreshPairsList()
            }
            2 -> {
                binding.tvHeaderSubtitle.text = "Transfer & Activity History"
                loadHistoryTabLogs()
            }
        }
    }

    private fun setupHistoryTab() {
        binding.rvSyncLogsMain.layoutManager = LinearLayoutManager(this)
        binding.rvSyncLogsMain.adapter = logAdapter

        binding.chipAllMain.setOnClickListener { filterHistoryLogs(null) }
        binding.chipUploadedMain.setOnClickListener { filterHistoryLogs("uploaded") }
        binding.chipSkippedMain.setOnClickListener { filterHistoryLogs("skipped") }
        binding.chipFailedMain.setOnClickListener { filterHistoryLogs("failed") }

        binding.btnRetryFailedTabHistory.setOnClickListener { startRetryFailedSync() }

        binding.btnClearHistoryMain.setOnClickListener {
            Thread {
                db.syncLogDao().clearAll()
                runOnUiThread {
                    allHistoryLogs = emptyList()
                    filterHistoryLogs(null)
                }
            }.start()
        }
    }

    private fun loadHistoryTabLogs() {
        Thread {
            allHistoryLogs = db.syncLogDao().getRecent(500)
            runOnUiThread { filterHistoryLogs(null) }
        }.start()
    }

    private fun filterHistoryLogs(action: String?) {
        val filtered = if (action == null) allHistoryLogs else allHistoryLogs.filter { it.action == action }
        logAdapter.submitList(filtered)

        val failedCount = allHistoryLogs.count { it.action == "failed" }
        binding.btnRetryFailedTabHistory.visibility = if (failedCount > 0) View.VISIBLE else View.GONE
        binding.btnRetryFailedTabHistory.text = "Retry Failed Files ($failedCount)"

        binding.tvEmptyHistoryMain.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.rvSyncLogsMain.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateSignInState() {
        val account = AuthManager.getLastSignedInAccount(this)
        binding.tvStatus.text = if (account != null) {
            "Connected: ${account.email}"
        } else {
            getString(R.string.not_signed_in)
        }
        if (binding.layoutTabStatus.visibility == View.VISIBLE) {
            binding.tvHeaderSubtitle.text = account?.email ?: "Google Drive Cloud Sync"
        }
        binding.btnSyncNow.isEnabled = account != null
        binding.btnScheduleDaily.isEnabled = account != null
        binding.switchScheduleSync.isEnabled = account != null
        binding.btnHeaderSync.isEnabled = account != null

        // Show/hide storage permission card
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.cardStoragePermission.visibility =
                if (Environment.isExternalStorageManager()) View.GONE else View.VISIBLE
        } else {
            binding.cardStoragePermission.visibility = View.GONE
        }

        // Show/hide sign-in button based on state
        binding.btnSignIn.visibility = if (account != null) View.GONE else View.VISIBLE
    }

    // ═══════ Sync status observation ═══════

    private fun observeSyncWork() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SyncWorker.WORK_NAME_MANUAL_SYNC)
            .observe(this, Observer { workInfos ->
                val workInfo = workInfos?.firstOrNull() ?: return@Observer
                handleWorkInfo(workInfo)
            })

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData("daily_sync")
            .observe(this, Observer { workInfos ->
                val workInfo = workInfos?.firstOrNull() ?: return@Observer
                if (workInfo.state == WorkInfo.State.RUNNING) {
                    handleWorkInfo(workInfo)
                }
            })
    }

    private fun handleWorkInfo(workInfo: WorkInfo) {
        when (workInfo.state) {
            WorkInfo.State.RUNNING -> {
                isSyncing = true
                binding.btnSyncNow.text = "Cancel Sync"
                binding.cardSyncStatus.visibility = View.VISIBLE

                val progress = workInfo.progress
                val status = progress.getString(SyncWorker.KEY_STATUS) ?: return
                val currentFile = progress.getString(SyncWorker.KEY_CURRENT_FILE) ?: ""
                val done = progress.getInt(SyncWorker.KEY_FILES_DONE, 0)
                val total = progress.getInt(SyncWorker.KEY_FILES_TOTAL, 0)
                val uploaded = progress.getInt(SyncWorker.KEY_FILES_UPLOADED, 0)
                val skipped = progress.getInt(SyncWorker.KEY_FILES_SKIPPED, 0)
                val failed = progress.getInt(SyncWorker.KEY_FILES_FAILED, 0)
                val activeCount = progress.getInt(SyncWorker.KEY_ACTIVE_COUNT, 0)

                when (status) {
                    SyncWorker.STATUS_SCANNING -> {
                        binding.tvSyncStatusLabel.text = "Checking with Drive…"
                        binding.progressSpinner.visibility = View.VISIBLE
                        if (total > 0) {
                            binding.progressBarSync.visibility = View.VISIBLE
                            binding.progressBarSync.max = total
                            binding.progressBarSync.progress = done
                            binding.tvSyncProgress.visibility = View.VISIBLE
                            binding.tvSyncProgress.text = "$done of $total files verified"
                        } else {
                            binding.progressBarSync.visibility = View.GONE
                            binding.tvSyncProgress.visibility = View.GONE
                        }
                        if (currentFile.isNotEmpty()) {
                            binding.tvCurrentFile.visibility = View.VISIBLE
                            binding.tvCurrentFile.text = currentFile
                        } else {
                            binding.tvCurrentFile.visibility = View.GONE
                        }
                        binding.layoutActiveTransfers.visibility = View.GONE
                        binding.layoutSyncSummary.visibility = View.GONE
                    }
                    SyncWorker.STATUS_SYNCING -> {
                        binding.tvSyncStatusLabel.text = getString(R.string.syncing)
                        binding.progressSpinner.visibility = View.VISIBLE

                        if (total > 0) {
                            binding.progressBarSync.visibility = View.VISIBLE
                            binding.progressBarSync.max = total
                            binding.progressBarSync.progress = done
                            val percent = (done * 100) / total
                            binding.tvSyncProgress.visibility = View.VISIBLE
                            binding.tvSyncProgress.text = "$done of $total files ($percent%)"
                        } else {
                            binding.progressBarSync.visibility = View.GONE
                            binding.tvSyncProgress.visibility = View.GONE
                        }

                        if (currentFile.isNotEmpty()) {
                            binding.tvCurrentFile.visibility = View.VISIBLE
                            binding.tvCurrentFile.text = currentFile
                        } else {
                            binding.tvCurrentFile.visibility = View.GONE
                        }

                        if (activeCount > 0) {
                            binding.layoutActiveTransfers.visibility = View.VISIBLE
                            binding.tvActiveTransfersTitle.text = "Active Uploads ($activeCount simultaneous)"
                            updateSlot(binding.slot0, binding.tvSlot0Name, binding.tvSlot0Progress, binding.pbSlot0, progress.getString(SyncWorker.KEY_SLOT_0))
                            updateSlot(binding.slot1, binding.tvSlot1Name, binding.tvSlot1Progress, binding.pbSlot1, progress.getString(SyncWorker.KEY_SLOT_1))
                            updateSlot(binding.slot2, binding.tvSlot2Name, binding.tvSlot2Progress, binding.pbSlot2, progress.getString(SyncWorker.KEY_SLOT_2))
                            updateSlot(binding.slot3, binding.tvSlot3Name, binding.tvSlot3Progress, binding.pbSlot3, progress.getString(SyncWorker.KEY_SLOT_3))
                        } else {
                            binding.layoutActiveTransfers.visibility = View.GONE
                        }

                        binding.layoutSyncSummary.visibility = View.VISIBLE
                        binding.tvUploaded.text = "↑ $uploaded uploaded"
                        binding.tvSkipped.text = "⊘ $skipped skipped"
                        binding.tvFailed.text = "✕ $failed failed"
                    }
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                isSyncing = false
                binding.btnSyncNow.text = getString(R.string.sync_now)
                binding.cardSyncStatus.visibility = View.VISIBLE
                binding.progressSpinner.visibility = View.GONE
                binding.progressBarSync.visibility = View.GONE
                binding.tvCurrentFile.visibility = View.GONE
                binding.layoutActiveTransfers.visibility = View.GONE

                val output = workInfo.outputData
                val uploaded = output.getInt(SyncWorker.KEY_FILES_UPLOADED, 0)
                val skipped = output.getInt(SyncWorker.KEY_FILES_SKIPPED, 0)
                val failed = output.getInt(SyncWorker.KEY_FILES_FAILED, 0)
                val total = output.getInt(SyncWorker.KEY_FILES_TOTAL, 0)

                binding.tvSyncStatusLabel.text = "✓ " + getString(R.string.sync_complete)
                binding.tvSyncProgress.visibility = View.VISIBLE
                binding.tvSyncProgress.text = "$total files processed"

                binding.layoutSyncSummary.visibility = View.VISIBLE
                binding.tvUploaded.text = "↑ $uploaded uploaded"
                binding.tvSkipped.text = "⊘ $skipped skipped"
                binding.tvFailed.text = "✕ $failed failed"

                updateRetryFailedButton()
            }
            WorkInfo.State.FAILED -> {
                isSyncing = false
                binding.btnSyncNow.text = getString(R.string.sync_now)
                binding.cardSyncStatus.visibility = View.VISIBLE
                binding.progressSpinner.visibility = View.GONE
                binding.progressBarSync.visibility = View.GONE
                binding.tvCurrentFile.visibility = View.GONE
                binding.layoutActiveTransfers.visibility = View.GONE

                val error = workInfo.outputData.getString("error") ?: "Unknown error"
                binding.tvSyncStatusLabel.text = "✕ Sync Failed"
                binding.tvSyncProgress.visibility = View.VISIBLE
                binding.tvSyncProgress.text = error
                binding.tvCurrentFile.visibility = View.GONE

                val uploaded = workInfo.outputData.getInt(SyncWorker.KEY_FILES_UPLOADED, 0)
                val skipped = workInfo.outputData.getInt(SyncWorker.KEY_FILES_SKIPPED, 0)
                val failed = workInfo.outputData.getInt(SyncWorker.KEY_FILES_FAILED, 0)
                if (uploaded > 0 || skipped > 0 || failed > 0) {
                    binding.layoutSyncSummary.visibility = View.VISIBLE
                    binding.tvUploaded.text = "↑ $uploaded uploaded"
                    binding.tvSkipped.text = "⊘ $skipped skipped"
                    binding.tvFailed.text = "✕ $failed failed"
                }

                updateRetryFailedButton()
            }
            WorkInfo.State.CANCELLED -> {
                isSyncing = false
                binding.btnSyncNow.text = getString(R.string.sync_now)
                binding.tvSyncStatusLabel.text = "Sync cancelled"
                binding.progressSpinner.visibility = View.GONE
                binding.progressBarSync.visibility = View.GONE
                binding.layoutActiveTransfers.visibility = View.GONE
                updateRetryFailedButton()
            }
            else -> {}
        }
    }

    private fun updateRetryFailedButton() {
        if (isSyncing) return
        Thread {
            val failedCount = db.syncLogDao().countFailedFiles()
            runOnUiThread {
                if (failedCount > 0 && !isSyncing) {
                    binding.btnRetryFailed.visibility = View.VISIBLE
                    binding.btnRetryFailed.text = "Retry Failed Files ($failedCount)"
                } else {
                    binding.btnRetryFailed.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun startRetryFailedSync() {
        if (isSyncing) return
        val account = AuthManager.getLastSignedInAccount(this)
        if (account == null) {
            Toast.makeText(this, "Please sign in first", Toast.LENGTH_SHORT).show()
            return
        }

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_RETRY_FAILED_ONLY to true))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            SyncWorker.WORK_NAME_MANUAL_SYNC,
            ExistingWorkPolicy.REPLACE,
            request
        )

        binding.cardSyncStatus.visibility = View.VISIBLE
        binding.tvSyncStatusLabel.text = "Retrying failed files…"
        binding.btnRetryFailed.visibility = View.GONE
        binding.progressSpinner.visibility = View.VISIBLE
    }

    private fun updateSlot(
        slotLayout: View,
        tvName: TextView,
        tvProgress: TextView,
        pb: ProgressBar,
        dataStr: String?
    ) {
        if (dataStr.isNullOrEmpty()) {
            slotLayout.visibility = View.GONE
            return
        }
        val parts = dataStr.split("|")
        if (parts.size >= 3) {
            val name = parts[0]
            val bytes = parts[1].toLongOrNull() ?: 0L
            val total = parts[2].toLongOrNull() ?: 0L
            val percent = if (total > 0) ((bytes * 100) / total).toInt() else 0

            slotLayout.visibility = View.VISIBLE
            tvName.text = name
            tvProgress.text = "${formatFileSize(bytes)} / ${formatFileSize(total)} ($percent%)"
            pb.progress = percent
        } else {
            slotLayout.visibility = View.GONE
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }

    // ═══════ Pair detail ═══════

    private fun openPairDetail(pair: SyncPair) {
        val intent = Intent(this, SyncPairDetailActivity::class.java).apply {
            putExtra(SyncPairDetailActivity.EXTRA_PAIR_ID, pair.id)
            putExtra(SyncPairDetailActivity.EXTRA_LOCAL_PATH, pair.localPath)
            putExtra(SyncPairDetailActivity.EXTRA_DRIVE_FOLDER_ID, pair.driveFolderId)
            putExtra(SyncPairDetailActivity.EXTRA_DISPLAY_NAME, pair.localDisplayName)
        }
        startActivity(intent)
    }

    // ═══════ Permissions ═══════

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } else {
                Toast.makeText(this, "Storage access already granted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ═══════ Folder picking ═══════

    private fun openFolderPicker() {
        val options = arrayOf(
            "📂 Browse Device Folders…",
            "📸 Camera (DCIM)",
            "📥 Downloads",
            "🖼️ Pictures",
            "📄 Documents",
            "💾 Internal Storage (Root)",
            "🔍 System File Picker…",
            "✏️ Enter path manually…"
        )
        AlertDialog.Builder(this)
            .setTitle("Select folder to sync")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showFolderBrowser(Environment.getExternalStorageDirectory())
                    1 -> promptForDriveDestination(File(Environment.getExternalStorageDirectory(), "DCIM"))
                    2 -> promptForDriveDestination(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
                    3 -> promptForDriveDestination(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES))
                    4 -> promptForDriveDestination(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
                    5 -> promptForDriveDestination(Environment.getExternalStorageDirectory())
                    6 -> {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        folderPickerLauncher.launch(intent)
                    }
                    7 -> promptForManualPath()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFolderBrowser(currentDir: File) {
        val rootDir = Environment.getExternalStorageDirectory()
        val dir = if (currentDir.exists() && currentDir.isDirectory) currentDir else rootDir

        val subDirs = (dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
            .toMutableList()

        val items = mutableListOf<String>()
        val itemFiles = mutableListOf<File?>()

        val canGoUp = dir.path != rootDir.path && dir.parentFile != null && dir.path.startsWith("/storage")
        if (canGoUp) {
            items.add("⬆️  .. (Go up)")
            itemFiles.add(dir.parentFile)
        }

        for (sub in subDirs) {
            val count = sub.listFiles()?.size ?: 0
            items.add("📁  ${sub.name}  ($count items)")
            itemFiles.add(sub)
        }

        val title = if (dir.path == rootDir.path) "📂 Internal Storage" else "📂 ${dir.name}"
        val message = "Current location:\n${dir.path}"

        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)

        if (items.isEmpty()) {
            builder.setMessage("$message\n\n(No subfolders found)")
        } else {
            builder.setItems(items.toTypedArray()) { _, which ->
                val selected = itemFiles[which]
                if (selected != null) {
                    showFolderBrowser(selected)
                }
            }
        }

        builder.setPositiveButton("✔ Select This Folder") { _, _ ->
            promptForDriveDestination(dir)
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun promptForManualPath() {
        val input = EditText(this).apply {
            hint = "/storage/emulated/0/MyFolder"
            setText(Environment.getExternalStorageDirectory().path + "/")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Enter folder path")
            .setMessage("Type or paste the full device path of the folder you want to sync.")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val path = input.text.toString().trim()
                val folder = File(path)
                if (!folder.exists() || !folder.isDirectory) {
                    Toast.makeText(this, "Folder not found: $path", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                promptForDriveDestination(folder)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun treeUriToFile(treeUri: Uri): File? {
        try {
            val authority = treeUri.authority ?: ""

            if (treeUri.scheme == "file") {
                treeUri.path?.let { path ->
                    val f = File(path)
                    if (f.exists()) return f
                }
            }

            var docId: String? = null
            if (DocumentsContract.isTreeUri(treeUri)) {
                docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            }
            if (docId == null) {
                docId = runCatching { DocumentsContract.getDocumentId(treeUri) }.getOrNull()
            }

            if (docId == null) {
                val path = treeUri.path ?: ""
                if (path.contains("/tree/")) {
                    docId = Uri.decode(path.substringAfter("/tree/").substringBefore("/document/"))
                } else if (path.contains("/document/")) {
                    docId = Uri.decode(path.substringAfter("/document/"))
                }
            }

            if (authority == "com.android.externalstorage.documents" || authority.contains("externalstorage")) {
                if (docId != null) {
                    val parts = docId.split(":")
                    val volume = parts[0]
                    val relativePath = if (parts.size > 1) parts[1] else ""

                    if (volume.equals("primary", ignoreCase = true)) {
                        val base = Environment.getExternalStorageDirectory()
                        return if (relativePath.isEmpty()) base else File(base, relativePath)
                    } else {
                        val candidates = listOf(
                            File("/storage/$volume/$relativePath"),
                            File("/mnt/media_rw/$volume/$relativePath")
                        )
                        for (candidate in candidates) {
                            if (candidate.exists()) return candidate
                        }
                        File("/storage").listFiles()?.firstOrNull { it.name.equals(volume, ignoreCase = true) }?.let {
                            return if (relativePath.isEmpty()) it else File(it, relativePath)
                        }
                        return File("/storage/$volume/$relativePath")
                    }
                }
            }

            if (authority == "com.android.providers.downloads.documents" || authority.contains("downloads")) {
                if (docId != null) {
                    if (docId.startsWith("raw:")) {
                        val rawPath = docId.substringAfter("raw:")
                        return File(rawPath)
                    }
                    if (docId.startsWith("/storage/")) {
                        return File(docId)
                    }
                    val downloadId = docId.substringAfterLast(":")
                    if (downloadId.toLongOrNull() != null) {
                        val contentUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"),
                            downloadId.toLong()
                        )
                        getDataColumn(contentUri)?.let { return File(it) }
                    }
                }
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadDir.exists()) return downloadDir
            }

            if (authority == "com.android.providers.media.documents" || authority.contains("media")) {
                if (docId != null) {
                    val parts = docId.split(":")
                    val type = parts.getOrNull(0)
                    val id = parts.getOrNull(1)?.toLongOrNull()
                    if (id != null) {
                        val contentUri = when (type) {
                            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            else -> MediaStore.Files.getContentUri("external")
                        }
                        val itemUri = ContentUris.withAppendedId(contentUri, id)
                        getDataColumn(itemUri)?.let { path ->
                            val f = File(path)
                            return if (f.isDirectory) f else f.parentFile
                        }
                    }
                }
            }

            getDataColumn(treeUri)?.let { path ->
                val f = File(path)
                return if (f.isDirectory) f else f.parentFile
            }

            val decoded = Uri.decode(treeUri.toString())
            if (decoded.contains("/storage/")) {
                val sub = decoded.substring(decoded.indexOf("/storage/"))
                val cleaned = sub.substringBefore("?").substringBefore("#").trim()
                val f = File(cleaned)
                if (f.exists()) return f
            }

            if (docId != null && docId.contains("primary:")) {
                val rel = docId.substringAfter("primary:")
                return File(Environment.getExternalStorageDirectory(), rel)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to resolve tree URI", e)
        }
        return null
    }

    private fun getDataColumn(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractDriveFolderId(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ""
        val folderRegex = Regex(".*/folders/([a-zA-Z0-9_-]+).*")
        val folderMatch = folderRegex.find(trimmed)
        if (folderMatch != null) {
            return folderMatch.groupValues[1]
        }
        val idRegex = Regex(".*[?&]id=([a-zA-Z0-9_-]+).*")
        val idMatch = idRegex.find(trimmed)
        if (idMatch != null) {
            return idMatch.groupValues[1]
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val lastSegment = trimmed.substringBefore("?").substringBefore("#").substringAfterLast("/")
            if (lastSegment.isNotEmpty() && lastSegment != "folders") {
                return lastSegment
            }
        }
        return trimmed.substringBefore("?").substringBefore("#")
    }

    private fun promptForDriveDestination(localFolder: File) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, (12 * resources.displayMetrics.density).toInt(), pad, 0)
        }

        val explanation = TextView(this).apply {
            text = "Copy your Google Drive folder's link or ID and paste it below:\n\n" +
                   "📌 Example URL:\n" +
                   "https://drive.google.com/drive/folders/1A2B3C-xyz123...\n\n" +
                   "💡 Tip: You can paste the entire link. The folder ID will be extracted automatically!"
            textSize = 13.5f
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }

        val input = EditText(this).apply {
            hint = "Paste Drive folder link or ID here"
            isSingleLine = true
        }

        container.addView(explanation)
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Sync \"${localFolder.name}\" to Drive")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val raw = input.text.toString()
                val driveFolderId = extractDriveFolderId(raw)
                if (driveFolderId.isEmpty()) {
                    Toast.makeText(this, "Drive folder ID or link is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addPair(localFolder, driveFolderId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addPair(localFolder: File, driveFolderId: String) {
        Thread {
            db.syncPairDao().insert(
                SyncPair(
                    localPath = localFolder.path,
                    localDisplayName = localFolder.path,
                    driveFolderId = driveFolderId,
                    driveFolderName = driveFolderId
                )
            )
            runOnUiThread {
                refreshPairsList()
                Toast.makeText(this, "Added sync folder", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun removePair(pair: SyncPair) {
        Thread {
            db.syncPairDao().delete(pair.id)
            runOnUiThread { refreshPairsList() }
        }.start()
    }

    private fun refreshPairsList() {
        Thread {
            val pairs = db.syncPairDao().getAll()
            runOnUiThread { pairAdapter.update(pairs) }
        }.start()
    }

    // ═══════ Sync triggers ═══════

    private fun triggerSyncNow() {
        Thread {
            val pairs = db.syncPairDao().getAll()
            runOnUiThread {
                if (pairs.isEmpty()) {
                    Toast.makeText(this, "Add a sync folder first!", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val request = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(this).enqueueUniqueWork(
                    SyncWorker.WORK_NAME_MANUAL_SYNC, ExistingWorkPolicy.REPLACE, request
                )

                binding.cardSyncStatus.visibility = View.VISIBLE
                binding.tvSyncStatusLabel.text = "Starting sync…"
                binding.progressSpinner.visibility = View.VISIBLE
                binding.progressBarSync.visibility = View.GONE
                binding.tvSyncProgress.visibility = View.GONE
                binding.tvCurrentFile.visibility = View.GONE
                binding.layoutSyncSummary.visibility = View.GONE
            }
        }.start()
    }

    private fun cancelSync() {
        WorkManager.getInstance(this).cancelUniqueWork(SyncWorker.WORK_NAME_MANUAL_SYNC)
        Toast.makeText(this, "Sync cancelled", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val WORK_NAME_PERIODIC_SYNC = "daily_sync"
        private const val PREFS_PERIODIC = "syncdrive_periodic_prefs"
        private const val KEY_PERIODIC_ENABLED = "periodic_enabled"
        private const val KEY_PERIODIC_INTERVAL = "periodic_interval_hours"
        private const val KEY_PERIODIC_WIFI_ONLY = "periodic_wifi_only"
    }

    private fun getPeriodicIntervalHours(): Int {
        val prefs = getSharedPreferences(PREFS_PERIODIC, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PERIODIC_INTERVAL, 24)
    }

    private fun getPeriodicWifiOnly(): Boolean {
        val prefs = getSharedPreferences(PREFS_PERIODIC, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PERIODIC_WIFI_ONLY, false)
    }

    private fun setupPeriodicSyncUi() {
        val prefs = getSharedPreferences(PREFS_PERIODIC, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(KEY_PERIODIC_ENABLED, false)
        val intervalHours = getPeriodicIntervalHours()
        val wifiOnly = getPeriodicWifiOnly()

        // Set initial state without triggering listener
        binding.switchScheduleSync.setOnCheckedChangeListener(null)
        binding.switchScheduleSync.isChecked = isEnabled
        updateScheduleUi(isEnabled, intervalHours, wifiOnly)

        binding.switchScheduleSync.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val account = AuthManager.getLastSignedInAccount(this)
                if (account == null) {
                    binding.switchScheduleSync.isChecked = false
                    Toast.makeText(this, "Please connect Google account first", Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                enablePeriodicSync(getPeriodicIntervalHours(), getPeriodicWifiOnly())
            } else {
                disablePeriodicSync()
            }
        }

        binding.btnScheduleInterval.setOnClickListener {
            showIntervalPicker()
        }

        binding.btnScheduleNetwork.setOnClickListener {
            showNetworkPicker()
        }
    }

    private fun enablePeriodicSync(intervalHours: Int, wifiOnly: Boolean) {
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC_SYNC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )

        val prefs = getSharedPreferences(PREFS_PERIODIC, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_PERIODIC_ENABLED, true)
            .putInt(KEY_PERIODIC_INTERVAL, intervalHours)
            .putBoolean(KEY_PERIODIC_WIFI_ONLY, wifiOnly)
            .apply()

        updateScheduleUi(true, intervalHours, wifiOnly)
        val netStr = if (wifiOnly) "WiFi only" else "Any network"
        Toast.makeText(this, "Periodic sync scheduled: every ${intervalHours}h ($netStr) ✓", Toast.LENGTH_SHORT).show()
    }

    private fun disablePeriodicSync() {
        WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME_PERIODIC_SYNC)
        val prefs = getSharedPreferences(PREFS_PERIODIC, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PERIODIC_ENABLED, false).apply()
        updateScheduleUi(false, getPeriodicIntervalHours(), getPeriodicWifiOnly())
        Toast.makeText(this, "Periodic background sync disabled", Toast.LENGTH_SHORT).show()
    }

    private fun updateScheduleUi(enabled: Boolean, intervalHours: Int, wifiOnly: Boolean) {
        val netDesc = if (wifiOnly) "WiFi only" else "Any network"
        val intervalDesc = when (intervalHours) {
            1 -> "Every 1h"
            3 -> "Every 3h"
            6 -> "Every 6h"
            12 -> "Every 12h"
            else -> "Every 24h"
        }

        if (enabled) {
            binding.tvScheduleStatus.text = "🟢 Active • $intervalDesc ($netDesc)"
            binding.layoutScheduleOptions.visibility = View.VISIBLE
        } else {
            binding.tvScheduleStatus.text = "⚪ Off • Tap switch to enable"
            binding.layoutScheduleOptions.visibility = View.GONE
        }
        binding.btnScheduleInterval.text = intervalDesc
        binding.btnScheduleNetwork.text = if (wifiOnly) "WiFi Only" else "Any Network"
    }

    private fun showIntervalPicker() {
        val options = arrayOf("Every 1 hour", "Every 3 hours", "Every 6 hours", "Every 12 hours", "Every 24 hours (Daily)")
        val hours = arrayOf(1, 3, 6, 12, 24)
        val currentHours = getPeriodicIntervalHours()
        val currentIndex = hours.indexOf(currentHours).let { if (it >= 0) it else 4 }

        AlertDialog.Builder(this)
            .setTitle("Sync Frequency")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selectedHour = hours[which]
                val prefs = getSharedPreferences(PREFS_PERIODIC, Context.MODE_PRIVATE)
                prefs.edit().putInt(KEY_PERIODIC_INTERVAL, selectedHour).apply()
                if (binding.switchScheduleSync.isChecked) {
                    enablePeriodicSync(selectedHour, getPeriodicWifiOnly())
                } else {
                    updateScheduleUi(false, selectedHour, getPeriodicWifiOnly())
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNetworkPicker() {
        val options = arrayOf("Any Network (WiFi or Mobile Data)", "WiFi Only")
        val currentWifiOnly = getPeriodicWifiOnly()
        val currentIndex = if (currentWifiOnly) 1 else 0

        AlertDialog.Builder(this)
            .setTitle("Network Requirement")
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val selectedWifiOnly = which == 1
                val prefs = getSharedPreferences(PREFS_PERIODIC, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_PERIODIC_WIFI_ONLY, selectedWifiOnly).apply()
                if (binding.switchScheduleSync.isChecked) {
                    enablePeriodicSync(getPeriodicIntervalHours(), selectedWifiOnly)
                } else {
                    updateScheduleUi(false, getPeriodicIntervalHours(), selectedWifiOnly)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showThemeSelectionDialog() {
        val options = arrayOf("☀️ Light Mode", "🌙 Dark Mode", "🌓 System Default")
        val modes = arrayOf(
            ThemeManager.THEME_LIGHT,
            ThemeManager.THEME_DARK,
            ThemeManager.THEME_SYSTEM
        )
        val currentMode = ThemeManager.getThemeMode(this)
        val selectedIndex = when (currentMode) {
            ThemeManager.THEME_LIGHT -> 0
            ThemeManager.THEME_DARK -> 1
            else -> 2
        }

        AlertDialog.Builder(this)
            .setTitle("App Theme")
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val chosenMode = modes[which]
                ThemeManager.setThemeMode(this, chosenMode)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scheduleDailySync() {
        binding.switchScheduleSync.isChecked = true
    }

    private fun setupFilterSettingsUi() {
        val prefs = getSharedPreferences("syncdrive_filter_prefs", Context.MODE_PRIVATE)
        val includeHidden = prefs.getBoolean("pref_include_hidden_files", true)

        binding.switchIncludeHidden.isChecked = includeHidden
        updateFilterStatusText(includeHidden)

        binding.switchIncludeHidden.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_include_hidden_files", isChecked).apply()
            updateFilterStatusText(isChecked)
            val msg = if (isChecked) "Hidden media files will be included ✓" else "Strict mode: Hidden files skipped"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.cardFilterSettings.setOnClickListener {
            showFilterInfoDialog()
        }
    }

    private fun updateFilterStatusText(includeHidden: Boolean) {
        binding.tvFilterStatus.text = if (includeHidden) {
            "Include photos, audio & docs starting with dot (.)"
        } else {
            "Strict mode: Skip all files starting with dot (.)"
        }
    }

    private fun showFilterInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("File Filter Settings")
            .setMessage("When enabled (Default), user photos, audio recordings, videos, and documents starting with a dot (e.g. .photo.jpg, .audio.mp3) are included in sync.\n\nWhen disabled, all files starting with a dot (.) are strictly skipped.\n\nNote: Gallery preview thumbnails (.thumbnails/), system trash, and temporary chunk files (.chunk, .tmp, .part) are ALWAYS safely excluded in both modes.")
            .setPositiveButton("OK", null)
            .show()
    }
}
