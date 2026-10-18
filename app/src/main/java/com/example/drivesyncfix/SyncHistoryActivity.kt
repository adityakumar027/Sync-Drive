package com.example.drivesyncfix

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip

class SyncHistoryActivity : AppCompatActivity() {

    private lateinit var adapter: SyncLogAdapter
    private val db by lazy { SyncDatabase.getInstance(this) }
    private var allLogs = listOf<SyncLog>()
    private lateinit var btnRetry: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_history)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        adapter = SyncLogAdapter()
        val rv = findViewById<RecyclerView>(R.id.rvSyncLogs)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        btnRetry = findViewById(R.id.btnRetryFailedHistory)
        btnRetry.setOnClickListener {
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
            Toast.makeText(this, "Retrying failed uploads…", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Filter chips
        findViewById<Chip>(R.id.chipAll).setOnClickListener { filterLogs(null) }
        findViewById<Chip>(R.id.chipUploaded).setOnClickListener { filterLogs("uploaded") }
        findViewById<Chip>(R.id.chipSkipped).setOnClickListener { filterLogs("skipped") }
        findViewById<Chip>(R.id.chipFailed).setOnClickListener { filterLogs("failed") }

        findViewById<View>(R.id.btnClearHistory).setOnClickListener {
            Thread {
                db.syncLogDao().clearAll()
                runOnUiThread {
                    allLogs = emptyList()
                    filterLogs(null)
                }
            }.start()
        }

        loadLogs()
    }

    private fun loadLogs() {
        Thread {
            allLogs = db.syncLogDao().getRecent(500)
            runOnUiThread { filterLogs(null) }
        }.start()
    }

    private fun filterLogs(action: String?) {
        val filtered = if (action == null) allLogs else allLogs.filter { it.action == action }
        adapter.submitList(filtered)

        val failedCount = allLogs.count { it.action == "failed" }
        btnRetry.visibility = if (failedCount > 0) View.VISIBLE else View.GONE
        btnRetry.text = "Retry Failed Files ($failedCount)"

        val emptyView = findViewById<TextView>(R.id.tvEmpty)
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        findViewById<RecyclerView>(R.id.rvSyncLogs).visibility =
            if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }
}
