package com.stashapp.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.stashapp.R
import com.stashapp.StashApp
import androidx.lifecycle.lifecycleScope
import com.stashapp.databinding.ActivityMainBinding
import com.stashapp.utils.FileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.stashapp.sync.SyncState
import com.stashapp.utils.ContentTypeDetector
import android.graphics.drawable.Drawable
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: StashViewModel by viewModels()
    private lateinit var adapter: StashAdapter
    private var actionMode: ActionMode? = null

    private val allTypes = listOf(
        "link", "image", "video", "audio", "document",
        "text", "archive", "apk", "contact", "other"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""

        checkAndShareCrashLog()
        setupChips()
        setupRecyclerView()
        setupSwipeActions()
        binding.fabNewNote.setOnClickListener { createAndEditNewNote() }
        observeItems()
        observeFilter()
        observeSyncState()
    }

    private fun checkAndShareCrashLog() {
        val logFile = java.io.File(filesDir, "crash_log.txt")
        if (!logFile.exists()) return
        val text = logFile.readText()
        logFile.delete()
        AlertDialog.Builder(this)
            .setTitle("Previous crash detected")
            .setMessage(text.take(2000))
            .setPositiveButton("Copy to clipboard") { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun setupChips() {
        addTypeChip("All", null, checked = true)
        allTypes.forEach { type ->
            addTypeChip("${ContentTypeDetector.typeEmoji(type)} ${ContentTypeDetector.typeLabel(type)}", type)
        }
        addLocalOnlyChip()
    }

    private fun addTypeChip(label: String, type: String?, checked: Boolean = false) {
        val chip = Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = checked
            // No check icon on selection — it changes the chip width and shifts the row.
            isCheckedIconVisible = false
            chipBackgroundColor = resources.getColorStateList(R.color.chip_selector, theme)
            setTextColor(resources.getColorStateList(R.color.chip_text_selector, theme))
            chipStrokeWidth = 1.5f
            chipStrokeColor = resources.getColorStateList(R.color.chip_text_selector, theme)
            setOnClickListener {
                // Tapping the already-selected type clears the filter back to "All".
                val newType = if (viewModel.filterType.value == type) null else type
                viewModel.pendingScrollToTop = true
                viewModel.setFilter(newType)
                updateChipSelection(newType)
                actionMode?.finish()
            }
        }
        // Each type chip wears its type's accent colour: the colour outlines the chip and fills it
        // when selected, so the type is identifiable straight from the filter row. ("All" keeps the
        // neutral navy styling above.)
        if (type != null) applyTypeChipColors(chip, type)
        binding.chipGroup.addView(chip)
    }

    private fun applyTypeChipColors(chip: Chip, type: String) {
        val color = ContextCompat.getColor(this, ContentTypeDetector.typeColorRes(type))
        val surface = ContextCompat.getColor(this, R.color.colorSurface)
        val onAccent = ContextCompat.getColor(this, R.color.colorChipTextSelected)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        chip.chipStrokeWidth = resources.displayMetrics.density * 2f
        chip.chipStrokeColor = ColorStateList.valueOf(color)
        chip.chipBackgroundColor = ColorStateList(states, intArrayOf(color, surface))
        chip.setTextColor(ColorStateList(states, intArrayOf(onAccent, color)))
    }

    private fun addLocalOnlyChip() {
        val chip = Chip(this).apply {
            text = "📱 Local"
            isCheckable = true
            isChecked = false
            isCheckedIconVisible = false
            chipBackgroundColor = resources.getColorStateList(R.color.chip_selector, theme)
            setTextColor(resources.getColorStateList(R.color.chip_text_selector, theme))
            chipStrokeWidth = 1.5f
            chipStrokeColor = resources.getColorStateList(R.color.chip_text_selector, theme)
            setOnClickListener {
                val newVal = !(viewModel.localOnly.value ?: false)
                viewModel.pendingScrollToTop = true
                viewModel.localOnly.value = newVal
                isChecked = newVal
                actionMode?.finish()
            }
        }
        binding.chipGroup.addView(chip)
        // Extra start margin to visually separate Local from type chips
        (chip.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = (resources.displayMetrics.density * 12).toInt()
            chip.layoutParams = it
        }
        viewModel.localOnly.observe(this) { lo ->
            chip.isChecked = lo
        }
    }

    private fun updateChipSelection(selectedType: String?) {
        for (i in 0 until binding.chipGroup.childCount - 1) { // skip last (local-only) chip
            val chip = binding.chipGroup.getChildAt(i) as? Chip ?: continue
            val chipType = if (i == 0) null else allTypes.getOrNull(i - 1)
            chip.isChecked = chipType == selectedType
        }
    }

    private fun setupRecyclerView() {
        adapter = StashAdapter(
            onClick = { item ->
                if (adapter.selectionMode) {
                    updateActionModeTitle()
                } else {
                    startActivity(Intent(this, ViewItemActivity::class.java).apply {
                        putExtra(ViewItemActivity.EXTRA_ID, item.id)
                    })
                }
            },
            onLongClick = { _ ->
                if (actionMode == null) {
                    startActionMode()
                }
                true
            },
            onRetryTransfer = { item -> retryTransfer(item) },
            onDoubleClick = { item -> firePrimaryAction(item) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    /**
     * Double-tap action: fire the item's primary button without opening the detail screen —
     * download a cloud-only file, open a local file externally, open a link, copy text. Does
     * nothing while a download for the item is in progress.
     */
    private fun firePrimaryAction(item: com.stashapp.data.StashItem) {
        val syncClient = (application as StashApp).syncClient
        if (syncClient.downloadProgress.value?.containsKey(item.syncId) == true) return
        when {
            item.type == "link" -> runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(item.content)))
            }.onFailure { toast("Can't open link") }
            item.type == "text" -> {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Stash", item.content))
                toast("Copied")
            }
            !item.fileLocal -> {  // cloud-only file → download
                if (syncClient.state.value != SyncState.CONNECTED) { toast("Not connected to sync server"); return }
                if (item.syncId.isNotBlank()) syncClient.requestFile(item.syncId)
            }
            else -> com.stashapp.utils.FileOpener.openExternally(this, item, lifecycleScope)
        }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** Re-trigger a failed transfer: re-upload local file items, re-request cloud-only ones. */
    private fun retryTransfer(item: com.stashapp.data.StashItem) {
        val syncClient = (application as StashApp).syncClient
        if (syncClient.state.value != SyncState.CONNECTED) {
            android.widget.Toast.makeText(this, "Not connected to sync server", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val isCloudOnly = item.type !in listOf("link", "text") && !item.fileLocal
        if (isCloudOnly) syncClient.requestFile(item.syncId) else syncClient.pushItem(item)
    }

    private fun setupSwipeActions() {
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            private val deletePaint = Paint().apply { color = Color.parseColor("#D32F2F") }
            private val sharePaint = Paint().apply { color = Color.parseColor("#388E3C") }
            private val trashIcon: Drawable by lazy {
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_delete)!!
            }
            private val shareIcon: Drawable by lazy {
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_share)!!
            }

            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                // Only allow swipe on Entry items (not headers), and not in selection mode
                if (viewHolder is StashAdapter.HeaderVH || adapter.selectionMode) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                val item = adapter.entryAt(pos) ?: run { adapter.notifyItemChanged(pos); return }
                when (direction) {
                    ItemTouchHelper.RIGHT ->
                        confirmSingleDelete(item, onCancel = { adapter.notifyItemChanged(pos) })
                    ItemTouchHelper.LEFT -> {
                        // Share is a one-shot action, not a dismissal — restore the row, then share.
                        adapter.notifyItemChanged(pos)
                        shareSwiped(item)
                    }
                }
            }

            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val iconSize = (itemView.height * 0.4f).toInt()
                val margin = (itemView.height - iconSize) / 2
                val iconTop = itemView.top + margin
                when {
                    // Swipe right → delete: red background + trash, anchored to the left edge.
                    dX > 0 -> {
                        c.drawRect(
                            itemView.left.toFloat(), itemView.top.toFloat(),
                            itemView.left + dX, itemView.bottom.toFloat(), deletePaint
                        )
                        val iconLeft = itemView.left + margin
                        if (iconLeft + iconSize <= itemView.left + dX) {
                            trashIcon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                            trashIcon.draw(c)
                        }
                    }
                    // Swipe left → share: green background + share icon, anchored to the right edge.
                    dX < 0 -> {
                        c.drawRect(
                            itemView.right + dX, itemView.top.toFloat(),
                            itemView.right.toFloat(), itemView.bottom.toFloat(), sharePaint
                        )
                        val iconRight = itemView.right - margin
                        val iconLeft = iconRight - iconSize
                        if (iconLeft >= itemView.right + dX) {
                            shareIcon.setBounds(iconLeft, iconTop, iconRight, iconTop + iconSize)
                            shareIcon.draw(c)
                        }
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.recyclerView)
    }

    // ── Background swipe to step the type-filter chips ──────────────────────────────────────
    // Detected at the Activity level (dispatchTouchEvent) so it works regardless of which view
    // is under the finger: empty list space, type-header rows, AND the empty-state view (which
    // is a separate view drawn over the RecyclerView). We only skip actual entry rows (so the
    // row swipe-to-delete/share still works) and the chip bar (so it can scroll horizontally).
    private var swDownX = 0f
    private var swDownY = 0f
    private var swEligible = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swDownX = ev.rawX; swDownY = ev.rawY
                swEligible = isChipSwipeArea(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_UP -> {
                if (swEligible) {
                    val dx = ev.rawX - swDownX
                    val dy = ev.rawY - swDownY
                    val minDistance = resources.displayMetrics.density * 48
                    if (kotlin.math.abs(dx) >= minDistance && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        cycleFilterChip(forward = dx < 0)
                    }
                }
                swEligible = false
            }
            MotionEvent.ACTION_CANCEL -> swEligible = false
        }
        return super.dispatchTouchEvent(ev)
    }

    /** True where a horizontal swipe should cycle chips — anywhere except an entry row or the chip bar. */
    private fun isChipSwipeArea(rawX: Float, rawY: Float): Boolean {
        (binding.chipGroup.parent as? View)?.let { chipBar ->
            if (pointInView(chipBar, rawX, rawY)) return false
        }
        val rv = binding.recyclerView
        if (pointInView(rv, rawX, rawY)) {
            val loc = IntArray(2); rv.getLocationOnScreen(loc)
            val child = rv.findChildViewUnder(rawX - loc[0], rawY - loc[1])
            if (child != null && rv.getChildViewHolder(child) is StashAdapter.EntryVH) return false
        }
        return true
    }

    private fun pointInView(v: View, rawX: Float, rawY: Float): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + v.width && rawY >= loc[1] && rawY <= loc[1] + v.height
    }

    /** Step the type filter one chip in either direction (clamped, no wrap). 0 = All … last = Other. */
    private fun cycleFilterChip(forward: Boolean) {
        val current = viewModel.filterType.value?.let { allTypes.indexOf(it) + 1 } ?: 0
        val next = (current + if (forward) 1 else -1).coerceIn(0, allTypes.size)
        if (next == current) return
        val newType = if (next == 0) null else allTypes[next - 1]
        viewModel.pendingScrollToTop = true
        viewModel.setFilter(newType)
        updateChipSelection(newType)
        scrollChipIntoView(next)
        actionMode?.finish()
    }

    private fun scrollChipIntoView(index: Int) {
        val chip = binding.chipGroup.getChildAt(index) ?: return
        (binding.chipGroup.parent as? HorizontalScrollView)?.let { hsv ->
            hsv.post {
                val pad = (resources.displayMetrics.density * 16).toInt()
                hsv.smoothScrollTo((chip.left - pad).coerceAtLeast(0), 0)
            }
        }
    }

    /** Swipe-right share. Cloud-only files have no local bytes to send, so prompt a download first. */
    private fun shareSwiped(item: com.stashapp.data.StashItem) {
        if (item.type !in listOf("link", "text") && !item.fileLocal) {
            toast("Download the file first to share it")
            return
        }
        shareItem(item)
    }

    private fun startActionMode() {
        actionMode = startActionMode(object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menuInflater.inflate(R.menu.context_menu, menu)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return when (item.itemId) {
                    R.id.action_select_all -> { adapter.selectAll(); updateActionModeTitle(); true }
                    R.id.action_delete_selected -> { confirmBulkDelete(); true }
                    else -> false
                }
            }
            override fun onDestroyActionMode(mode: ActionMode) {
                adapter.clearSelection()
                actionMode = null
            }
        })
        updateActionModeTitle()
    }

    private fun updateActionModeTitle() {
        val count = adapter.selectedIds.size
        actionMode?.title = if (count == 0) "Select items" else "$count selected"
    }

    /** Remove an item's on-disk files (and cancel any in-flight download so it can't recreate them). */
    private fun deleteLocalFilesFor(item: com.stashapp.data.StashItem) {
        if (item.type !in listOf("link", "text")) {
            if (item.syncId.isNotBlank()) (application as StashApp).syncClient.cancelDownload(item.syncId)
            if (item.content.isNotBlank()) runCatching { File(item.content).delete() }
            // Also drop the cached copy made when the file was opened/shared externally.
            com.stashapp.utils.FileOpener.deleteShareCache(this, item)
        }
        if (item.thumbnailPath.isNotBlank()) runCatching { File(item.thumbnailPath).delete() }
    }

    private fun performFullDelete(item: com.stashapp.data.StashItem) {
        deleteLocalFilesFor(item)
        viewModel.deleteById(item.id)
        // Always record a durable delete tombstone for synced items, even when offline, so
        // the deletion is flushed on reconnect and a stale sync_meta can't resurrect the item.
        if (item.syncId.isNotBlank()) (application as StashApp).syncClient.deleteItem(item.syncId)
    }

    private fun ghostItem(item: com.stashapp.data.StashItem) {
        if (item.type !in listOf("link", "text")) {
            if (item.syncId.isNotBlank()) (application as StashApp).syncClient.cancelDownload(item.syncId)
            if (item.content.isNotBlank()) runCatching { File(item.content).delete() }
            com.stashapp.utils.FileOpener.deleteShareCache(this, item)
        }
        adapter.pendingGhostIds.add(item.id)
        adapter.notifyDataSetChanged()  // immediate visual feedback before Room propagates
        viewModel.setCloudOnly(item.id)
    }

    private fun confirmBulkDelete() {
        val count = adapter.selectedIds.size
        if (count == 0) return
        val syncClient = (application as StashApp).syncClient
        val connected = syncClient.state.value == SyncState.CONNECTED

        val selectedItems = adapter.selectedIds.mapNotNull { id ->
            adapter.currentList.filterIsInstance<StashListItem.Entry>().find { it.item.id == id }?.item
        }
        val hasFileItems = selectedItems.any { it.type !in listOf("link", "text") }
        val allCloudOnly = hasFileItems && selectedItems.filter { it.type !in listOf("link", "text") }.all { !it.fileLocal }

        fun doBulkFull() {
            // Delete sequentially and AWAIT each row removal, then sweep orphaned files so the
            // disk is actually reclaimed (stray .part files, anything a prior interruption left).
            lifecycleScope.launch {
                // Report the real disk freed across the whole operation (file deletes + orphan
                // sweep) as before − after, not just the sweep's own count.
                val before = syncClient.managedStorageBytes()
                selectedItems.forEach { item ->
                    deleteLocalFilesFor(item)
                    viewModel.deleteByIdNow(item.id)
                    if (item.syncId.isNotBlank()) syncClient.deleteItem(item.syncId)
                }
                syncClient.reclaimOrphanFiles()
                val freed = (before - syncClient.managedStorageBytes()).coerceAtLeast(0)
                if (freed > 0) toast("Freed ${com.stashapp.utils.FileStorage.formatSize(freed)}")
            }
            actionMode?.finish()
        }
        fun doBulkGhost() {
            selectedItems.forEach { item ->
                if (item.type !in listOf("link", "text")) {
                    ghostItem(item)
                } else {
                    performFullDelete(item)
                }
            }
            actionMode?.finish()
        }

        // Items with no local copy (links, text, cloud-only files) cannot be kept "locally only" —
        // they will be permanently deleted from the server if the user picks "Remove locally only".
        val noLocalCount = selectedItems.count { it.type in listOf("link", "text") || !it.fileLocal }

        val baseMessage = "This cannot be undone."
        val mixedWarning = if (noLocalCount > 0)
            "\n\n$noLocalCount item${if (noLocalCount > 1) "s" else ""} " +
            "${if (noLocalCount > 1) "have" else "has"} no local copy and " +
            "will be permanently deleted."
        else ""

        val builder = AlertDialog.Builder(this)
            .setTitle("Delete $count item${if (count > 1) "s" else ""}?")
        if (connected && allCloudOnly) {
            builder
                .setMessage(baseMessage)
                .setPositiveButton("Delete from cloud") { _, _ -> doBulkFull() }
                .setNegativeButton("Cancel", null)
        } else if (connected && hasFileItems) {
            builder
                .setMessage(baseMessage + mixedWarning)
                .setPositiveButton("Remove everywhere") { _, _ -> doBulkFull() }
                .setNeutralButton("Remove locally only") { _, _ -> doBulkGhost() }
                .setNegativeButton("Cancel", null)
        } else if (connected) {
            builder
                .setMessage(baseMessage)
                .setPositiveButton("Remove everywhere") { _, _ -> doBulkFull() }
                .setNegativeButton("Cancel", null)
        } else {
            builder
                .setMessage(baseMessage)
                .setPositiveButton("Delete") { _, _ -> doBulkFull() }
                .setNegativeButton("Cancel", null)
        }
        builder.show()
    }

    private fun observeItems() {
        viewModel.displayItems.observe(this) { items ->
            val shouldScroll = viewModel.pendingScrollToTop
            if (shouldScroll) viewModel.pendingScrollToTop = false
            adapter.submitList(items) {
                if (shouldScroll) binding.recyclerView.scrollToPosition(0)
            }
            val hasEntries = items.any { it is StashListItem.Entry }
            binding.emptyState.visibility = if (hasEntries) View.GONE else View.VISIBLE
            binding.recyclerView.visibility = if (hasEntries) View.VISIBLE else View.GONE
            if (!hasEntries) updateEmptyMessage()
        }
    }

    /** Tailor the empty-state to why the list is empty. Title/subtitle: a search with no
     *  hits, an empty category filter ("No Videos yet"), or the welcoming empty-stash copy —
     *  which "Other" also uses, since "No Other yet" reads clunky. The medallion glyph swaps
     *  to the active category's line-art icon (including "Other"); search and "All" keep the
     *  inbox. The share-hint subtitle stays visible throughout so the medallion doesn't shift. */
    private fun updateEmptyMessage() {
        val query = viewModel.searchQuery.value
        val type = viewModel.filterType.value

        when {
            !query.isNullOrBlank() -> {
                binding.tvEmptyTitle.setText(R.string.empty_search)
                binding.tvEmptySub.setText(R.string.empty_search_sub)
            }
            type != null && type != "other" -> {
                binding.tvEmptyTitle.text =
                    getString(R.string.empty_filter, ContentTypeDetector.typeLabel(type))
                binding.tvEmptySub.setText(R.string.empty_stash_sub)
            }
            else -> {
                binding.tvEmptyTitle.setText(R.string.empty_stash)
                binding.tvEmptySub.setText(R.string.empty_stash_sub)
            }
        }
        binding.tvEmptySub.visibility = View.VISIBLE

        val iconRes = if (type != null && query.isNullOrBlank()) {
            ContentTypeDetector.typeIconRes(type)
        } else {
            R.drawable.ic_empty_inbox
        }
        binding.ivEmptyIcon.setImageResource(iconRes)
    }

    private fun observeFilter() {
        viewModel.filterType.observe(this) { type ->
            binding.tvTitle.text = if (type == null) "Stash" else ContentTypeDetector.typeLabel(type)
        }
    }

    private fun openItem(content: String, type: String, mimeType: String) {
        val intent = when (type) {
            "link" -> Intent(Intent.ACTION_VIEW, Uri.parse(content))
            else -> {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(content))
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType.ifBlank { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        runCatching { startActivity(intent) }
    }

    private fun shareItem(item: com.stashapp.data.StashItem) {
        if (item.type == "link" || item.type == "text") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, item.content)
            }, "Share via"))
        } else {
            lifecycleScope.launch {
                val uri = runCatching { withContext(Dispatchers.IO) { FileOpener.sharableUri(this@MainActivity, item) } }.getOrNull()
                if (uri == null) { toast("Cannot share this file"); return@launch }
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = item.mimeType.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share via"))
            }
        }
    }

    private fun confirmSingleDelete(item: com.stashapp.data.StashItem, onCancel: (() -> Unit)? = null) {
        val syncClient = (application as StashApp).syncClient
        val connected = syncClient.state.value == SyncState.CONNECTED && item.syncId.isNotBlank()
        val isFileItem = item.type !in listOf("link", "text")

        val cloudOnly = isFileItem && !item.fileLocal
        val builder = AlertDialog.Builder(this).setTitle("Remove from Stash?")
        when {
            connected && cloudOnly -> builder
                .setPositiveButton("Delete from cloud") { _, _ -> performFullDelete(item) }
                .setNegativeButton("Cancel") { _, _ -> onCancel?.invoke() }
            connected && isFileItem -> builder
                .setPositiveButton("Remove everywhere") { _, _ -> performFullDelete(item) }
                .setNeutralButton("Remove locally only") { _, _ -> ghostItem(item) }
                .setNegativeButton("Cancel") { _, _ -> onCancel?.invoke() }
            connected && !isFileItem -> builder
                .setPositiveButton("Remove") { _, _ -> performFullDelete(item) }
                .setNegativeButton("Cancel") { _, _ -> onCancel?.invoke() }
            else -> builder
                .setPositiveButton("Remove") { _, _ -> performFullDelete(item) }
                .setNegativeButton("Cancel") { _, _ -> onCancel?.invoke() }
        }
        builder.setOnCancelListener { onCancel?.invoke() }.show()
    }

    private fun showSortDialog() {
        val orders = SortOrder.values()
        val labels = orders.map { it.label }.toTypedArray()
        val current = orders.indexOf(viewModel.sortOrder.value)
        AlertDialog.Builder(this)
            .setTitle("Sort by")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                viewModel.pendingScrollToTop = true
                viewModel.sortOrder.value = orders[which]
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeSyncState() {
        val syncClient = (application as StashApp).syncClient
        syncClient.state.observe(this) { state ->
            val enabled = state != SyncState.DISABLED
            if (adapter.syncEnabled != enabled) {
                adapter.syncEnabled = enabled
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
            invalidateOptionsMenu()
        }
        syncClient.uploadProgress.observe(this) { adapter.setUploadProgress(it) }
        syncClient.downloadProgress.observe(this) { adapter.setDownloadProgress(it) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView

        searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(160, 255, 255, 255))
        }
        searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)
            ?.setColorFilter(Color.WHITE)

        searchView.queryHint = "Search stash…"
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true.also { viewModel.setSearch(q) }
            override fun onQueryTextChange(q: String?) = true.also {
                if (q.isNullOrBlank()) viewModel.clearFilter() else viewModel.setSearch(q)
            }
        })
        searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_mag_icon)
            ?.setColorFilter(Color.WHITE)
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                binding.tvTitle.visibility = View.GONE
                binding.ivLogo.visibility = View.GONE
                return true
            }
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                binding.tvTitle.visibility = View.VISIBLE
                binding.ivLogo.visibility = View.VISIBLE
                viewModel.clearFilter()
                return true
            }
        })
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val syncItem = menu.findItem(R.id.action_sync_settings) ?: return super.onPrepareOptionsMenu(menu)
        val state = (application as StashApp).syncClient.state.value ?: SyncState.DISABLED
        val color = when (state) {
            SyncState.CONNECTED -> Color.parseColor("#4CAF50")   // green
            SyncState.CONNECTING -> Color.parseColor("#FFC107")  // amber
            SyncState.DISCONNECTED -> Color.parseColor("#F44336") // red
            SyncState.DISABLED -> Color.parseColor("#9E9E9E")    // grey
        }
        syncItem.icon?.let { icon ->
            val wrapped = DrawableCompat.wrap(icon.mutate())
            DrawableCompat.setTint(wrapped, color)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort -> { showSortDialog(); true }
            R.id.action_sync_settings -> {
                startActivity(Intent(this, SyncSettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * FAB: create a blank text note and open it straight in the detail view's in-place editor
     * (parity with the iOS "+"). The empty row isn't pushed yet — saving syncs it, discarding/
     * leaving an empty note deletes it (handled in ViewItemActivity).
     */
    private fun createAndEditNewNote() {
        lifecycleScope.launch {
            val item = com.stashapp.data.StashItem(
                title = "",
                content = "",
                type = "text",
                mimeType = "text/plain",
                originalFilename = "",
                sourceDomain = "",
                syncId = java.util.UUID.randomUUID().toString()
            )
            val stored = viewModel.insertReturning(item) ?: return@launch
            startActivity(Intent(this@MainActivity, ViewItemActivity::class.java).apply {
                putExtra(ViewItemActivity.EXTRA_ID, stored.id)
                putExtra(ViewItemActivity.EXTRA_START_EDIT, true)
                putExtra(ViewItemActivity.EXTRA_IS_NEW, true)
            })
        }
    }

    override fun onBackPressed() {
        if (actionMode != null) { actionMode?.finish(); return }
        super.onBackPressed()
    }
}
