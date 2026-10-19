package com.example.drivesyncfix

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import java.io.File

/**
 * Shows real-time backup status for a sync pair — which files are backed up
 * and which are still pending. Compares local files against the synced_files
 * Room table (no Drive API calls needed).
 */
class SyncPairDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAIR_ID = "pair_id"
        const val EXTRA_LOCAL_PATH = "local_path"
        const val EXTRA_DRIVE_FOLDER_ID = "drive_folder_id"
        const val EXTRA_DISPLAY_NAME = "display_name"
    }

    data class FileStatus(
        val file: File,
        val relativePath: String,
        val isBackedUp: Boolean,
        val syncedAt: Long? = null
    )

    private lateinit var adapter: FileStatusAdapter
    private val db by lazy { SyncDatabase.getInstance(this) }
    private var allFiles = listOf<FileStatus>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_pair_detail)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val localPath = intent.getStringExtra(EXTRA_LOCAL_PATH) ?: return finish()
        val driveFolderId = intent.getStringExtra(EXTRA_DRIVE_FOLDER_ID) ?: ""
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: localPath

        // Set folder info
        val folderName = localPath.substringAfterLast("/").ifEmpty { "Internal Storage" }
        toolbar.title = folderName
        findViewById<TextView>(R.id.tvFolderName).text = localPath
        findViewById<TextView>(R.id.tvDriveFolder).text = "→ Drive folder: $driveFolderId"

        // Setup RecyclerView
        adapter = FileStatusAdapter()
        val rv = findViewById<RecyclerView>(R.id.rvFiles)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        // Filter chips
        findViewById<Chip>(R.id.chipAll).setOnClickListener { filterFiles(null) }
        findViewById<Chip>(R.id.chipBackedUp).setOnClickListener { filterFiles(true) }
        findViewById<Chip>(R.id.chipPending).setOnClickListener { filterFiles(false) }

        // Start scanning in background
        scanFiles(localPath)
    }

    private fun scanFiles(localPath: String) {
        val scanningLayout = findViewById<View>(R.id.layoutScanning)
        val scanningStatus = findViewById<TextView>(R.id.tvScanningStatus)
        scanningLayout.visibility = View.VISIBLE
        scanningStatus.text = "Scanning files…"

        Thread {
            val root = File(localPath)
            if (!root.exists()) {
                runOnUiThread {
                    scanningStatus.text = "Folder not found"
                }
                return@Thread
            }

            val filterPrefs = getSharedPreferences("syncdrive_filter_prefs", Context.MODE_PRIVATE)
            val includeHidden = filterPrefs.getBoolean("pref_include_hidden_files", true)
            val scanner = FileScanner(includeHidden = includeHidden)
            val scannedFiles = scanner.scan(root)

            runOnUiThread {
                scanningStatus.text = "Checking backup status… (${scannedFiles.size} files found)"
            }

            // Check each file against the DB (load once into memory for instant O(1) lookups)
            val dao = db.syncedFileDao()
            val allSyncedMap = dao.getAll().associateBy { it.localPath }
            val statusList = mutableListOf<FileStatus>()
            var backedUpCount = 0
            var pendingCount = 0
            var totalSize = 0L
            var backedUpSize = 0L

            for ((index, scanned) in scannedFiles.withIndex()) {
                val synced = allSyncedMap[scanned.file.path]
                val isBackedUp = synced != null &&
                    synced.size == scanned.file.length() &&
                    synced.lastModified == scanned.file.lastModified()

                statusList.add(
                    FileStatus(
                        file = scanned.file,
                        relativePath = scanned.relativePath,
                        isBackedUp = isBackedUp,
                        syncedAt = synced?.syncedAt
                    )
                )

                totalSize += scanned.file.length()
                if (isBackedUp) {
                    backedUpCount++
                    backedUpSize += scanned.file.length()
                } else {
                    pendingCount++
                }

                // Update UI periodically (every 500 files) so it feels responsive
                if (index % 500 == 0 && index > 0) {
                    val progress = index
                    runOnUiThread {
                        scanningStatus.text = "Checking backup status… ($progress / ${scannedFiles.size})"
                    }
                }
            }

            // Sort: pending files first, then backed up
            val sorted = statusList.sortedWith(
                compareBy<FileStatus> { it.isBackedUp }
                    .thenByDescending { it.file.lastModified() }
            )

            val total = scannedFiles.size
            val percent = if (total > 0) (backedUpCount * 100 / total) else 0

            runOnUiThread {
                allFiles = sorted

                // Update stats
                findViewById<TextView>(R.id.tvTotalCount).text = "$total"
                findViewById<TextView>(R.id.tvBackedUpCount).text = "$backedUpCount"
                findViewById<TextView>(R.id.tvPendingCount).text = "$pendingCount"
                findViewById<TextView>(R.id.tvTotalSize).text = formatSize(totalSize)

                // Update progress bar
                val progressBar = findViewById<android.widget.ProgressBar>(R.id.progressBackup)
                progressBar.max = total
                progressBar.progress = backedUpCount
                findViewById<TextView>(R.id.tvProgressPercent).text =
                    "$percent% backed up (${ formatSize(backedUpSize)} of ${formatSize(totalSize)})"

                // Hide scanning indicator
                scanningLayout.visibility = View.GONE

                // Show files
                filterFiles(null)
            }
        }.start()
    }

    private fun filterFiles(backedUp: Boolean?) {
        val filtered = when (backedUp) {
            true -> allFiles.filter { it.isBackedUp }
            false -> allFiles.filter { !it.isBackedUp }
            null -> allFiles
        }
        adapter.submitList(filtered)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
            else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }

    // ---- Adapter ----

    inner class FileStatusAdapter : RecyclerView.Adapter<FileStatusAdapter.VH>() {
        private var items = listOf<FileStatus>()

        fun submitList(newItems: List<FileStatus>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file_status, parent, false)
            return VH(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val tvIcon = view.findViewById<TextView>(R.id.tvStatusIcon)
            private val tvName = view.findViewById<TextView>(R.id.tvFileName)
            private val tvPath = view.findViewById<TextView>(R.id.tvFilePath)
            private val tvSize = view.findViewById<TextView>(R.id.tvFileSize)

            fun bind(status: FileStatus) {
                tvName.text = status.file.name
                tvPath.text = status.relativePath
                tvSize.text = formatSize(status.file.length())

                if (status.isBackedUp) {
                    tvIcon.text = "✓"
                    tvIcon.setTextColor(0xFF4CAF50.toInt())
                } else {
                    tvIcon.text = "⏳"
                    tvIcon.setTextColor(0xFFFF9800.toInt())
                }
            }
        }
    }
}
