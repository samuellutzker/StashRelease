package com.stashapp.ui

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.stashapp.R
import com.stashapp.data.StashItem
import com.stashapp.utils.ContentTypeDetector
import com.stashapp.utils.FileStorage
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class StashAdapter(
    private val onClick: (StashItem) -> Unit,
    private val onLongClick: (StashItem) -> Boolean,
    private val onRetryTransfer: ((StashItem) -> Unit)? = null,
    private val onDoubleClick: ((StashItem) -> Unit)? = null
) : ListAdapter<StashListItem, RecyclerView.ViewHolder>(Diff()) {

    companion object {
        private const val VT_HEADER = 0
        private const val VT_ENTRY = 1
        private const val PAYLOAD_PROGRESS = "progress"
    }

    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val ioExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    val selectedIds = mutableSetOf<Long>()
    var selectionMode = false
        private set

    // IDs immediately marked ghost before Room LiveData propagates
    val pendingGhostIds = mutableSetOf<Long>()

    var syncEnabled: Boolean = true

    private var uploadProgress: Map<String, Int> = emptyMap()
    private var downloadProgress: Map<String, Int> = emptyMap()

    fun setUploadProgress(map: Map<String, Int>) {
        val old = uploadProgress
        uploadProgress = map
        notifyProgressChanged(old, map)
    }

    fun setDownloadProgress(map: Map<String, Int>) {
        val old = downloadProgress
        downloadProgress = map
        notifyProgressChanged(old, map)
    }

    private fun notifyProgressChanged(old: Map<String, Int>, new: Map<String, Int>) {
        val changed = (old.keys + new.keys).filter { old[it] != new[it] }.toSet()
        if (changed.isEmpty()) return
        currentList.forEachIndexed { pos, listItem ->
            if (listItem is StashListItem.Entry && listItem.item.syncId in changed) {
                notifyItemChanged(pos, PAYLOAD_PROGRESS)
            }
        }
    }

    // ── ViewHolders ───────────────────────────────────────────────────────────

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tvHeaderLabel)
    }

    inner class EntryVH(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView = view.findViewById(R.id.stashCard)
        val ivIcon: View = view.findViewById(R.id.ivTypeIcon)
        val thumbBox: View = view.findViewById(R.id.thumbBox)
        val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        val tvEmoji: TextView = view.findViewById(R.id.tvTypeEmoji)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvSubtitle: TextView = view.findViewById(R.id.tvSubtitle)
        val tvDate: TextView = view.findViewById(R.id.tvDate)
        val tvSize: TextView = view.findViewById(R.id.tvSize)
        val tvCloudBadge: TextView = view.findViewById(R.id.tvCloudBadge)
        val checkbox: CheckBox = view.findViewById(R.id.checkbox)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val tvTransferFailed: TextView = view.findViewById(R.id.tvTransferFailed)
        var boundItemId: Long = -1L
    }

    // ── Selection helpers ─────────────────────────────────────────────────────

    fun enterSelectionMode(id: Long) { selectionMode = true; selectedIds.add(id); notifyDataSetChanged() }
    fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        val pos = currentList.indexOfFirst { it is StashListItem.Entry && it.item.id == id }
        if (pos >= 0) notifyItemChanged(pos)
    }
    fun selectAll() { selectedIds.addAll(currentList.filterIsInstance<StashListItem.Entry>().map { it.item.id }); notifyDataSetChanged() }
    fun clearSelection() { selectionMode = false; selectedIds.clear(); notifyDataSetChanged() }

    fun entryAt(pos: Int): StashItem? = (currentList.getOrNull(pos) as? StashListItem.Entry)?.item

    // ── Adapter overrides ─────────────────────────────────────────────────────

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is StashListItem.Header -> VT_HEADER
        is StashListItem.Entry -> VT_ENTRY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VT_HEADER)
            HeaderVH(inflater.inflate(R.layout.item_header, parent, false))
        else
            EntryVH(inflater.inflate(R.layout.item_stash, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isNotEmpty() && holder is EntryVH) {
            val item = (getItem(position) as? StashListItem.Entry)?.item ?: return
            bindProgressBar(holder, item)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val listItem = getItem(position)) {
            is StashListItem.Header -> (holder as HeaderVH).tvLabel.text = listItem.label
            is StashListItem.Entry -> bindEntry(holder as EntryVH, listItem.item)
        }
    }

    private fun bindProgressBar(holder: EntryVH, item: StashItem) {
        val upPct = if (item.syncId.isNotBlank()) uploadProgress[item.syncId] else null
        val dlPct = if (item.syncId.isNotBlank()) downloadProgress[item.syncId] else null

        val uploadFailed = upPct == com.stashapp.sync.PROGRESS_FAILED
        val downloadFailed = dlPct == com.stashapp.sync.PROGRESS_FAILED

        if (uploadFailed || downloadFailed) {
            holder.progressBar.visibility = View.GONE
            holder.tvTransferFailed.visibility = View.VISIBLE
            holder.tvTransferFailed.text =
                if (uploadFailed) "Upload failed — tap to retry" else "Download failed — tap to retry"
            holder.tvTransferFailed.setOnClickListener { onRetryTransfer?.invoke(item) }
            return
        }

        holder.tvTransferFailed.visibility = View.GONE
        holder.tvTransferFailed.setOnClickListener(null)

        val pct = upPct ?: dlPct
        when {
            // Active transfer with a known percentage → determinate bar.
            pct != null && pct >= 0 -> {
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.isIndeterminate = false
                holder.progressBar.progress = pct
            }
            // Queued for upload, or any local file the server doesn't have yet → indeterminate
            // "pending" bar. Only shown when sync is enabled (not disabled) so items don't look
            // stuck when the user has intentionally turned sync off.
            syncEnabled && (pct == com.stashapp.sync.PROGRESS_QUEUED || isPendingUpload(item)) -> {
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.isIndeterminate = true
            }
            else -> holder.progressBar.visibility = View.GONE
        }
    }

    /** A local file not yet confirmed on the server — i.e. still waiting to be uploaded. */
    private fun isPendingUpload(item: StashItem): Boolean =
        item.type !in listOf("link", "text") &&
        item.fileLocal &&
        !item.serverHasFile &&
        !item.removedLocally

    private fun bindEntry(holder: EntryVH, item: StashItem) {
        holder.boundItemId = item.id

        val color = ContextCompat.getColor(
            holder.itemView.context,
            ContentTypeDetector.typeColorRes(item.type)
        )
        holder.ivIcon.backgroundTintList = ColorStateList.valueOf(color)
        holder.thumbBox.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.blendARGB(color, Color.WHITE, 0.86f))
        holder.tvEmoji.text = if (item.isPinned) "📌" else ContentTypeDetector.typeEmoji(item.type)
        holder.tvDate.text = dateFmt.format(Date(item.createdAt))

        // For files the title line is always the filename; the user's editable comment (stored in
        // `title`) rides in the subtitle. Links/text keep title=title, subtitle=domain/label.
        val isFileItem = item.type !in listOf("link", "text")
        holder.tvTitle.text = if (isFileItem)
            item.originalFilename.ifBlank { ContentTypeDetector.typeLabel(item.type) }
        else
            item.title.ifBlank { item.content.take(80) }

        // Treat a title equal to the filename as "no comment" — that's how pre-comment file items
        // (and freshly received ones) look, so we don't echo the filename on both lines.
        val comment = if (isFileItem && item.title.isNotBlank() && item.title != item.originalFilename)
            item.title else ""
        holder.tvSubtitle.text = when {
            comment.isNotBlank() -> comment
            item.sourceDomain.isNotBlank() -> item.sourceDomain
            isFileItem -> ContentTypeDetector.typeLabel(item.type)
            item.originalFilename.isNotBlank() -> item.originalFilename
            else -> ContentTypeDetector.typeLabel(item.type)
        }

        if (item.fileSize > 0) {
            holder.tvSize.visibility = View.VISIBLE
            holder.tvSize.text = FileStorage.formatSize(item.fileSize)
        } else {
            holder.tvSize.visibility = View.GONE
        }

        // Cloud badge: shown for cloud items without local file
        holder.tvCloudBadge.visibility =
            if (item.source == "cloud" && !item.fileLocal) View.VISIBLE else View.GONE

        // Progress bar: upload or download in progress
        bindProgressBar(holder, item)

        // Selection UI
        val isSelected = selectedIds.contains(item.id)
        holder.checkbox.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.checkbox.isChecked = isSelected
        holder.card.setCardBackgroundColor(
            ContextCompat.getColor(holder.itemView.context,
                if (isSelected) R.color.colorSelected else R.color.colorSurface)
        )

        // Async thumbnail
        holder.ivThumbnail.setImageBitmap(null)
        holder.ivThumbnail.visibility = View.GONE
        holder.tvEmoji.visibility = View.VISIBLE

        // Ghost appearance: item is ghost while source=cloud and no local file.
        // Clear pendingGhostIds entry once DB confirms the item is no longer ghost.
        val isDbGhost = item.source == "cloud" && !item.fileLocal
        if (!isDbGhost) pendingGhostIds.remove(item.id)
        holder.card.alpha = if (isDbGhost || pendingGhostIds.contains(item.id)) 0.5f else 1.0f

        if (item.type in listOf("image", "video") && item.thumbnailPath.isNotBlank()) {
            val path = item.thumbnailPath; val itemId = item.id
            ioExecutor.execute {
                // Bounded decode: never hand an oversized bitmap to the ImageView, even if an
                // older build left a full-resolution file in the thumbnail slot.
                val bmp = com.stashapp.utils.BitmapUtils.decodeBounded(path, 512)
                mainHandler.post {
                    if (holder.boundItemId == itemId && bmp != null) {
                        holder.ivThumbnail.setImageBitmap(bmp)
                        holder.ivThumbnail.visibility = View.VISIBLE
                        holder.tvEmoji.visibility = View.GONE
                    }
                }
            }
        }

        // Single tap opens the item; double tap fires its primary action (download / open
        // externally). onSingleTapConfirmed waits out the double-tap window so a double tap doesn't
        // also open the detail screen. Long-press still enters selection mode.
        val gesture = android.view.GestureDetector(
            holder.card.context,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    if (selectionMode) toggleSelection(item.id)
                    onClick(item)
                    return true
                }
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    if (!selectionMode) onDoubleClick?.invoke(item)
                    return true
                }
                override fun onLongPress(e: android.view.MotionEvent) {
                    if (!selectionMode) enterSelectionMode(item.id) else toggleSelection(item.id)
                    onLongClick(item)
                }
            }
        )
        holder.card.setOnClickListener(null)
        holder.card.setOnLongClickListener(null)
        @Suppress("ClickableViewAccessibility")
        holder.card.setOnTouchListener { v, ev ->
            val handled = gesture.onTouchEvent(ev)
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
            handled
        }
    }

    class Diff : DiffUtil.ItemCallback<StashListItem>() {
        override fun areItemsTheSame(a: StashListItem, b: StashListItem) = when {
            a is StashListItem.Header && b is StashListItem.Header -> a.label == b.label
            a is StashListItem.Entry && b is StashListItem.Entry -> a.item.id == b.item.id
            else -> false
        }
        override fun areContentsTheSame(a: StashListItem, b: StashListItem) = a == b
    }
}
