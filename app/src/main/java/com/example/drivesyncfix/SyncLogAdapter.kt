package com.example.drivesyncfix

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

/**
 * Shared adapter for sync history logs, used in MainActivity's History tab
 * and in SyncHistoryActivity.
 */
class SyncLogAdapter : RecyclerView.Adapter<SyncLogAdapter.VH>() {
    private var items = listOf<SyncLog>()

    fun submitList(newItems: List<SyncLog>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sync_log, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvIcon = view.findViewById<TextView>(R.id.tvIcon)
        private val tvFileName = view.findViewById<TextView>(R.id.tvFileName)
        private val tvDetail = view.findViewById<TextView>(R.id.tvLogDetail)
        private val tvTimestamp = view.findViewById<TextView>(R.id.tvTimestamp)

        fun bind(log: SyncLog) {
            tvFileName.text = log.fileName

            when (log.action) {
                "uploaded" -> {
                    tvIcon.text = "↑"
                    tvIcon.setTextColor(0xFF4CAF50.toInt())
                    tvDetail.text = "Uploaded • ${formatSize(log.fileSize)}"
                }
                "skipped" -> {
                    tvIcon.text = "⊘"
                    tvIcon.setTextColor(0xFFFF9800.toInt())
                    tvDetail.text = "Skipped (already on Drive)"
                }
                "failed" -> {
                    tvIcon.text = "✕"
                    tvIcon.setTextColor(0xFFF44336.toInt())
                    tvDetail.text = log.errorMessage ?: "Upload failed"
                }
                else -> {
                    tvIcon.text = "?"
                    tvDetail.text = log.action
                }
            }

            tvTimestamp.text = formatTime(log.timestamp)
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
                else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
            }
        }

        private fun formatTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            return if (diff < 24 * 60 * 60 * 1000) {
                timeFormat.format(Date(timestamp))
            } else {
                dateFormat.format(Date(timestamp))
            }
        }

        companion object {
            private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        }
    }
}
