package com.example.drivesyncfix

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.TextView

class SyncPairAdapter(
    private var pairs: List<SyncPair>,
    private val onRemove: (SyncPair) -> Unit,
    private val onClick: (SyncPair) -> Unit = {}
) : BaseAdapter() {

    fun update(newPairs: List<SyncPair>) {
        pairs = newPairs
        notifyDataSetChanged()
    }

    override fun getCount() = pairs.size
    override fun getItem(position: Int) = pairs[position]
    override fun getItemId(position: Int) = pairs[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sync_pair, parent, false)

        val pair = pairs[position]

        // Local folder name (just the last segment for readability)
        val localName = pair.localPath.substringAfterLast("/").ifEmpty { "Internal Storage" }
        view.findViewById<TextView>(R.id.tvPairLabel).text = localName

        // Drive folder ID (truncated)
        val driveDisplay = if (pair.driveFolderName.length > 20) {
            "Drive: ${pair.driveFolderName.take(20)}…"
        } else {
            "Drive: ${pair.driveFolderName}"
        }
        view.findViewById<TextView>(R.id.tvPairDrive).text = driveDisplay

        view.findViewById<ImageButton>(R.id.btnRemovePair).setOnClickListener {
            onRemove(pair)
        }

        // Tap the item (not the remove button) to see backup status
        view.setOnClickListener {
            onClick(pair)
        }

        return view
    }
}
