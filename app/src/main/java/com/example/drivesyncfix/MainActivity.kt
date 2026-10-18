package com.example.drivesyncfix

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.example.drivesyncfix.databinding.ActivityMainBinding
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
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        runCatching { task.getResult(com.google.android.gms.common.api.ApiException::class.java) }
            .onSuccess { updateSignInState() }
            .onFailure { Toast.makeText(this, "Sign-in failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private val folderPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val treeUri = result.data?.data ?: return@registerForActivityResult
        val localFile = treeUriToFile(treeUri)
        if (localFile == null) {
            Toast.makeText(this, "Couldn't resolve that folder to a local path (only internal storage folders are supported)", Toast.LENGTH_LONG).show()
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

        binding.btnViewHistory.setOnClickListener {
            startActivity(Intent(this, SyncHistoryActivity::class.java))
        }

        binding.btnRetryFailed.setOnClickListener {
            startRetryFailedSync()
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
            0 -> binding.toolbar.title = "DriveSync • Status"
            1 -> {
                binding.toolbar.title = "DriveSync • Synced Folders"
                refreshPairsList()
            }
            2 -> {
                binding.toolbar.title = "DriveSync • History"
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
            "Signed in as ${account.email}"
        } else {
            getString(R.string.not_signed_in)
        }
        binding.btnSyncNow.isEnabled = account != null
        binding.btnScheduleDaily.isEnabled = account != null

        // Show/hide storage permission card
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            binding.cardStoragePermission.visibility =
                if (android.os.Environment.isExternalStorageManager()) View.GONE else View.VISIBLE
        } else {
            binding.cardStoragePermission.visibility = View.GONE
        }

        // Show/hide sign-in button based on state
        binding.btnSignIn.visibility = if (account != null) View.GONE else View.VISIBLE
    }

    // ═══════ Sync status observation ═══════

    private fun observeSyncWork() {
        // Observe the manual sync work
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SyncWorker.WORK_NAME_MANUAL_SYNC)
            .observe(this, Observer { workInfos ->
                val workInfo = workInfos?.firstOrNull() ?: return@Observer
                handleWorkInfo(workInfo)
            })

        // Also observe periodic sync
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

                            binding.tvSyncProgress.visibility = View.VISIBLE
                            binding.tvSyncProgress.text = "$done of $total files processed"
                        }

                        if (currentFile.isNotEmpty()) {
                            binding.tvCurrentFile.visibility = View.VISIBLE
                            binding.tvCurrentFile.text = currentFile
                        } else {
                            binding.tvCurrentFile.visibility = View.GONE
                        }

                        // Render active simultaneous upload slots
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

                binding.tvSyncStatusLabel.text = "✓ ${getString(R.string.sync_complete)}"
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

            else -> { /* ENQUEUED, BLOCKED */ }
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
            .setInputData(
                workDataOf(SyncWorker.KEY_RETRY_FAILED_ONLY to true)
            )
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
            if (!android.os.Environment.isExternalStorageManager()) {
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
            "📂 Internal Storage (root)",
            "📸 DCIM",
            "📥 Downloads",
            "🔍 Browse with picker…",
            "✏️ Enter path manually…"
        )
        AlertDialog.Builder(this)
            .setTitle("Select folder to sync")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> promptForDriveDestination(android.os.Environment.getExternalStorageDirectory())
                    1 -> promptForDriveDestination(File(android.os.Environment.getExternalStorageDirectory(), "DCIM"))
                    2 -> promptForDriveDestination(File(android.os.Environment.getExternalStorageDirectory(), "Download"))
                    3 -> {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        folderPickerLauncher.launch(intent)
                    }
                    4 -> promptForManualPath()
                }
            }
            .show()
    }

    private fun promptForManualPath() {
        val input = EditText(this).apply {
            hint = "/storage/emulated/0/MyFolder"
            setText("/storage/emulated/0/")
        }
        AlertDialog.Builder(this)
            .setTitle("Enter folder path")
            .setMessage("Type the full path of the device folder you want to sync.")
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
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parts = docId.split(":")
        if (parts.isEmpty()) return null
        val volume = parts[0]
        val relativePath = if (parts.size > 1) parts[1] else ""

        return if (volume == "primary") {
            val base = android.os.Environment.getExternalStorageDirectory()
            if (relativePath.isEmpty()) base else File(base, relativePath)
        } else {
            null
        }
    }

    private fun promptForDriveDestination(localFolder: File) {
        val input = EditText(this).apply {
            hint = "Drive folder ID for this folder (from its URL)"
        }
        AlertDialog.Builder(this)
            .setTitle("Sync \"${localFolder.name}\" to which Drive folder?")
            .setMessage("Paste the destination Drive folder's ID (the part after /folders/ in its URL). Files from \"${localFolder.name}\" will mirror into this folder.")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val driveFolderId = input.text.toString().trim()
                if (driveFolderId.isEmpty()) {
                    Toast.makeText(this, "Drive folder ID is required", Toast.LENGTH_SHORT).show()
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
        // Warn if no sync pairs configured
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

                // Show the status card immediately
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

    private fun scheduleDailySync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_sync", ExistingPeriodicWorkPolicy.UPDATE, request
        )
        Toast.makeText(this, "Daily WiFi sync scheduled ✓", Toast.LENGTH_SHORT).show()
    }
}
