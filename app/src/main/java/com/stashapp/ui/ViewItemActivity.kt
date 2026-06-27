package com.stashapp.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.stashapp.StashApp
import com.stashapp.R
import com.stashapp.data.StashItem
import com.stashapp.databinding.ActivityViewItemBinding
import com.stashapp.sync.SyncState
import com.stashapp.utils.ContentTypeDetector
import com.stashapp.utils.FileOpener
import com.stashapp.utils.FileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ViewItemActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "extra_id"
        /** Open straight into the in-place editor (used by the "new note" FAB). */
        const val EXTRA_START_EDIT = "extra_start_edit"
        /** Marks a freshly-created blank note: discarding or saving-empty deletes it. */
        const val EXTRA_IS_NEW = "extra_is_new"
        private const val MENU_PIN = 0
        private const val MENU_EDIT = 1
        private const val MENU_DELETE = 2
        private val LINK_TEXT = listOf("link", "text")
    }

    private var isNewNote = false

    private lateinit var binding: ActivityViewItemBinding
    private lateinit var viewModel: ViewItemViewModel
    private val dateFmt = SimpleDateFormat("EEEE, MMMM d, yyyy  ·  h:mm a", Locale.getDefault())

    private var currentItem: StashItem? = null
    private var isEditing = false
    private var originalContent = ""

    private var mediaPlayer: MediaPlayer? = null
    private val seekHandler = Handler(Looper.getMainLooper())
    private var seekRunnable: Runnable? = null

    private val editingBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { discardEdit() }
    }

    private val fullscreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { closeFullscreen() }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val item = currentItem ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                File(item.content).inputStream().use { input ->
                    contentResolver.openOutputStream(uri)?.use { output -> input.copyTo(output) }
                }
            }.fold(
                onSuccess = { withContext(Dispatchers.Main) { Toast.makeText(this@ViewItemActivity, "Saved", Toast.LENGTH_SHORT).show() } },
                onFailure = { withContext(Dispatchers.Main) { Toast.makeText(this@ViewItemActivity, "Save failed", Toast.LENGTH_SHORT).show() } }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewItemBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = "" }
        onBackPressedDispatcher.addCallback(this, editingBackCallback)
        onBackPressedDispatcher.addCallback(this, fullscreenBackCallback)
        binding.btnCloseFullscreen.setOnClickListener { closeFullscreen() }

        viewModel = ViewModelProvider(this)[ViewItemViewModel::class.java]

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id == -1L) { finish(); return }
        isNewNote = intent.getBooleanExtra(EXTRA_IS_NEW, false)

        lifecycleScope.launch {
            val item = viewModel.getById(id) ?: run { finish(); return@launch }
            currentItem = item
            bindItem(item)
            invalidateOptionsMenu()
            if (intent.getBooleanExtra(EXTRA_START_EDIT, false)) startEdit()
            // Watch for changes by another client while this view is open. A deletion shows the
            // "unavailable" dialog; any other update refreshes the comment field + placeholder
            // shortcut so that, once a comment is set, tap-to-edit stops firing. We deliberately
            // avoid a full bindItem() here: that re-creates media players and re-decodes previews,
            // which would leak the running MediaPlayer and reset playback on a metadata-only change.
            viewModel.getLiveById(id).observe(this@ViewItemActivity) { updated ->
                when {
                    updated == null && !isFinishing -> showItemUnavailable()
                    updated != null && !isEditing -> {
                        currentItem = updated
                        refreshComment(updated)
                        invalidateOptionsMenu()
                    }
                }
            }
        }
    }

    // ── Menu (edit icon for text items) ──────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val item = currentItem ?: return true
        menu.add(0, MENU_PIN, 0, if (item.isPinned) "Unpin" else "Pin").apply {
            setIcon(R.drawable.ic_pin)
            icon?.mutate()?.setTint(if (item.isPinned) android.graphics.Color.RED else android.graphics.Color.WHITE)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        // Edit is offered for text/link (their comment) and local file items.
        val canEdit = when (item.type) {
            "text", "link" -> true
            else -> item.fileLocal
        }
        if (canEdit) {
            menu.add(0, MENU_EDIT, 1, "Edit").apply {
                setIcon(R.drawable.ic_edit)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
        menu.add(0, MENU_DELETE, 2, "Delete").apply {
            setIcon(R.drawable.ic_delete)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_PIN -> { togglePin(); true }
            MENU_EDIT -> { startEdit(); true }
            MENU_DELETE -> { confirmDelete(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Inline text editing ───────────────────────────────────────────────────

    private fun startEdit() {
        val item = currentItem ?: return
        // Text edits content; links + files edit the comment stored in `title`.
        originalContent = if (item.type == "text") item.content else item.title
        binding.tvContent.setText(originalContent)
        isEditing = true
        editingBackCallback.isEnabled = true

        binding.tvContent.apply {
            isFocusableInTouchMode = true
            isCursorVisible = true
            requestFocus()
            setSelection(text.length)
        }
        getSystemService(InputMethodManager::class.java)
            .showSoftInput(binding.tvContent, InputMethodManager.SHOW_IMPLICIT)

        binding.editActions.visibility = View.VISIBLE
        binding.btnAction.visibility = View.GONE
        binding.btnShare.visibility = View.GONE
        // Hide "Save to Files" while editing the comment — it should only reappear once the
        // user commits or discards the edit (see exitEditMode).
        binding.btnSaveToFiles.visibility = View.GONE

        binding.btnSaveEdit.setOnClickListener { saveEdit() }
        binding.btnDiscardEdit.setOnClickListener { discardEdit() }
    }

    private fun saveEdit() {
        val item = currentItem ?: return
        val text = binding.tvContent.text.toString()
        val now = System.currentTimeMillis()
        // Text: the box is the content (title = first line). File: the box is the comment, which
        // lives in `title`; the file path in `content` is untouched.
        // A brand-new note saved empty is just discarded (delete the placeholder row).
        if (isNewNote && item.type == "text" && text.isBlank()) { deleteNewNoteAndFinish(); return }

        val updatedItem = if (item.type == "text") {
            val newTitle = text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: ""
            item.copy(content = text, title = newTitle, updatedAt = now)
        } else {
            item.copy(title = text.trim(), updatedAt = now)
        }
        isNewNote = false   // it's a real, saved item now
        exitEditMode()
        lifecycleScope.launch {
            viewModel.updateEdited(item.id, updatedItem.content, updatedItem.title, now)
            currentItem = updatedItem
            if (item.type == "text") binding.btnAction.setOnClickListener { copyToClipboard(updatedItem.content) }
        }
        // Metadata-only edit — don't re-upload the file.
        (application as StashApp).syncClient.pushItem(updatedItem, withFile = false)
    }

    private fun discardEdit() {
        // Discarding a never-saved note removes the placeholder row entirely.
        if (isNewNote) { deleteNewNoteAndFinish(); return }
        binding.tvContent.setText(originalContent)
        exitEditMode()
    }

    /** Delete the blank placeholder created by the "new note" FAB (never pushed, so local-only). */
    private fun deleteNewNoteAndFinish() {
        val item = currentItem
        lifecycleScope.launch {
            if (item != null) viewModel.deleteById(item.id)
            finish()
        }
    }

    private fun exitEditMode() {
        isEditing = false
        editingBackCallback.isEnabled = false
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.tvContent.windowToken, 0)
        binding.tvContent.apply {
            isFocusableInTouchMode = false
            isCursorVisible = false
            clearFocus()
        }
        binding.editActions.visibility = View.GONE
        binding.btnAction.visibility = View.VISIBLE
        binding.btnShare.visibility = View.VISIBLE
        // Restore "Save to Files" (re-derives the local-file-only visibility rule).
        currentItem?.let { bindSaveToFiles(it) }
    }

    // ── Item binding ──────────────────────────────────────────────────────────

    private fun togglePin() {
        val item = currentItem ?: return
        val now = System.currentTimeMillis()
        val updated = item.copy(isPinned = !item.isPinned, updatedAt = now)
        currentItem = updated
        invalidateOptionsMenu()
        lifecycleScope.launch {
            viewModel.setPinned(item.id, updated.isPinned, now)
        }
        (application as StashApp).syncClient.pushItem(updated, withFile = false)
    }

    private fun showItemUnavailable() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Item no longer available")
            .setMessage("This item was deleted from another device.")
            .setPositiveButton("OK") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    /** The user's comment for a file item, treating a title that still equals the filename as blank. */
    private fun fileComment(item: StashItem): String =
        if (item.title == item.originalFilename) "" else item.title

    /**
     * Make the comment field's "Add a comment…" placeholder act as a shortcut into edit mode.
     * Only wired for items that store an editable comment (links + local files): when the comment
     * is empty (so the hint is showing) and we're not already editing, a tap reuses the existing
     * [startEdit] flow. When a comment is already set the field just shows it and tapping is inert.
     */
    /**
     * Re-apply the comment-bearing portion of the view after a metadata change (e.g. the user
     * just set a comment, or another device did). Updates the displayed comment/title and
     * re-evaluates the tap-to-edit placeholder shortcut. Cheap — no media/preview re-init.
     */
    private fun refreshComment(item: StashItem) {
        when (item.type) {
            "text" -> {
                binding.tvContent.setText(item.content)
                binding.btnAction.setOnClickListener { copyToClipboard(item.content) }
            }
            "link" -> binding.tvContent.setText(item.title)
            else -> binding.tvContent.setText(fileComment(item))
        }
        enableCommentPlaceholderEdit(item)
    }

    private fun enableCommentPlaceholderEdit(item: StashItem) {
        val canEdit = when (item.type) {
            "link" -> true
            else -> item.fileLocal
        }
        // Only wire the tap shortcut while the comment is blank (placeholder showing). Once a
        // comment is set the field just displays it, so clear any previously-installed listener —
        // otherwise tapping the now-populated field would still drop into edit mode.
        if (canEdit && fileComment(item).isBlank()) {
            binding.tvContent.setOnClickListener {
                if (!isEditing && fileComment(item).isBlank()) startEdit()
            }
        } else {
            binding.tvContent.setOnClickListener(null)
            binding.tvContent.isClickable = false
        }
    }

    private fun bindItem(item: StashItem) {
        binding.tvEmoji.text = ContentTypeDetector.typeEmoji(item.type)
        binding.tvTypeLabel.text = ContentTypeDetector.typeLabel(item.type)
        // Files + links: immutable name/URL in tvTitle. Text items: title shown inline with content.
        binding.tvTitle.text = when (item.type) {
            "link" -> item.content.take(80)
            "text" -> item.title.ifBlank { item.content.take(80) }
            else -> item.originalFilename.ifBlank { ContentTypeDetector.typeLabel(item.type) }
        }
        binding.tvDate.text = dateFmt.format(Date(item.createdAt))

        if (item.fileSize > 0) {
            binding.tvMeta.text = "${item.mimeType}  ·  ${FileStorage.formatSize(item.fileSize)}"
        } else if (item.sourceDomain.isNotBlank()) {
            binding.tvMeta.text = item.sourceDomain
        } else {
            binding.tvMeta.visibility = View.GONE
        }

        if (item.source == "cloud" && !item.fileLocal && item.type !in listOf("link", "text")) {
            bindCloudItem(item)
            return
        }

        if (item.type == "image" && item.fileLocal) {
            // Bound the decode so a high-resolution photo can't exceed the Canvas draw limit.
            val bmp = com.stashapp.utils.BitmapUtils.decodeBounded(item.content, 2048)
            if (bmp != null) {
                binding.ivPreview.setImageBitmap(bmp)
                binding.ivPreview.visibility = View.VISIBLE
                binding.ivPreview.setOnClickListener { openFullscreen() }
            }
        }

        when (item.type) {
            "link" -> {
                binding.tvContent.hint = "Add a comment…"
                binding.tvContent.setText(item.title)
                binding.btnAction.text = "Open Link"
                binding.btnAction.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.content)))
                }
            }
            "text" -> {
                binding.tvTitle.visibility = View.GONE
                binding.vDivider.visibility = View.GONE
                binding.tvContent.setText(item.content)
                binding.btnAction.text = "Copy Text"
                binding.btnAction.setOnClickListener { copyToClipboard(item.content) }
            }
            "video" -> {
                binding.tvContent.hint = "Add a comment…"
                binding.tvContent.setText(fileComment(item))
                binding.btnAction.text = "Open Externally"
                bindVideo(item)
                binding.btnAction.setOnClickListener { openFile(item) }
            }
            "audio" -> {
                binding.tvContent.hint = "Add a comment…"
                binding.tvContent.setText(fileComment(item))
                binding.btnAction.text = "Open Externally"
                bindAudio(item)
                binding.btnAction.setOnClickListener { openFile(item) }
            }
            else -> {
                binding.tvContent.hint = "Add a comment…"
                binding.tvContent.setText(fileComment(item))
                binding.btnAction.text = "Open File"
                binding.btnAction.setOnClickListener { openFile(item) }
            }
        }

        enableCommentPlaceholderEdit(item)
        bindShare(item)
        bindSaveToFiles(item)
    }

    // ── Video ─────────────────────────────────────────────────────────────────

    private fun bindVideo(item: StashItem) {
        binding.videoContainer.visibility = View.VISIBLE

        if (item.thumbnailPath.isNotBlank()) {
            com.stashapp.utils.BitmapUtils.decodeBounded(item.thumbnailPath, 2048)?.let {
                binding.ivVideoPoster.setImageBitmap(it)
            }
        }

        val mc = MediaController(this)
        mc.setAnchorView(binding.videoContainer)
        binding.videoView.setMediaController(mc)
        binding.videoView.setVideoPath(item.content)

        val startPlayback = {
            binding.ivVideoPoster.visibility = View.GONE
            binding.ivVideoPlay.visibility = View.GONE
            binding.videoView.visibility = View.VISIBLE
            binding.videoView.start()
        }
        binding.ivVideoPoster.setOnClickListener { startPlayback() }
        binding.ivVideoPlay.setOnClickListener { startPlayback() }

        binding.videoView.setOnPreparedListener {
            // Video ready; user taps poster to start
        }
        binding.videoView.setOnCompletionListener {
            binding.videoView.visibility = View.GONE
            binding.ivVideoPoster.visibility = View.VISIBLE
            binding.ivVideoPlay.visibility = View.VISIBLE
            binding.videoView.seekTo(0)
        }
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    private fun bindAudio(item: StashItem) {
        binding.audioPlayerCard.visibility = View.VISIBLE
        val player = MediaPlayer()
        runCatching {
            player.setDataSource(item.content)
            player.setOnPreparedListener { mp ->
                binding.seekBar.max = mp.duration
                binding.tvPosition.text = "0:00 / ${formatDuration(mp.duration)}"
            }
            player.setOnCompletionListener {
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                seekRunnable?.let { seekHandler.removeCallbacks(it) }
            }
            player.prepareAsync()
        }.onFailure { binding.audioPlayerCard.visibility = View.GONE }
        mediaPlayer = player

        binding.btnPlayPause.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                seekRunnable?.let { seekHandler.removeCallbacks(it) }
            } else {
                player.start()
                binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                startSeekUpdate()
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player.seekTo(progress)
                    binding.tvPosition.text = "${formatDuration(progress)} / ${formatDuration(player.duration)}"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun startSeekUpdate() {
        seekRunnable?.let { seekHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val mp = mediaPlayer ?: return
                if (mp.isPlaying) {
                    binding.seekBar.progress = mp.currentPosition
                    binding.tvPosition.text = "${formatDuration(mp.currentPosition)} / ${formatDuration(mp.duration)}"
                    seekHandler.postDelayed(this, 500)
                }
            }
        }
        seekRunnable = r
        seekHandler.post(r)
    }

    private fun formatDuration(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ── Fullscreen image preview ──────────────────────────────────────────────

    private fun openFullscreen() {
        binding.ivFullscreen.setImageDrawable(binding.ivPreview.drawable)
        binding.fullscreenOverlay.visibility = View.VISIBLE
        fullscreenBackCallback.isEnabled = true
        // Go edge-to-edge once; don't repeat on every bar-hide call (causes layout thrashing).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Keep the close button below the status bar at all times — when bars appear transiently
        // the inset listener fires and the button nudges down so it stays visible and tappable.
        ViewCompat.setOnApplyWindowInsetsListener(binding.btnCloseFullscreen) { view, insets ->
            val topBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val margin8dp = (8 * resources.displayMetrics.density + 0.5f).toInt()
            val lp = view.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.topMargin = topBar + margin8dp
            view.layoutParams = lp
            insets
        }
        hideSystemBars()
        // Tapping the image re-hides the bars if the user pulled them down.
        binding.ivFullscreen.setOnClickListener { hideSystemBars() }
    }

    private fun closeFullscreen() {
        binding.ivFullscreen.setOnClickListener(null)
        ViewCompat.setOnApplyWindowInsetsListener(binding.btnCloseFullscreen, null)
        (binding.btnCloseFullscreen.layoutParams as android.widget.FrameLayout.LayoutParams)
            .topMargin = (8 * resources.displayMetrics.density + 0.5f).toInt()
        binding.fullscreenOverlay.visibility = View.GONE
        fullscreenBackCallback.isEnabled = false
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, binding.fullscreenOverlay)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, binding.fullscreenOverlay).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun openFile(item: StashItem) =
        com.stashapp.utils.FileOpener.openExternally(this, item, lifecycleScope)

    private fun sharableUri(item: StashItem): Uri =
        com.stashapp.utils.FileOpener.sharableUri(this, item)

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("stash", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun bindShare(item: StashItem) {
        if (!item.fileLocal && item.type !in listOf("link", "text")) {
            binding.btnShare.visibility = View.GONE
            return
        }
        binding.btnShare.setOnClickListener {
            if (item.type == "link" || item.type == "text") {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, item.content)
                }
                startActivity(Intent.createChooser(intent, "Share via"))
            } else {
                // Build the (correctly-named) URI off the main thread, then show the chooser.
                lifecycleScope.launch {
                    val uri = runCatching { withContext(Dispatchers.IO) { sharableUri(item) } }.getOrNull()
                    if (uri == null) {
                        Toast.makeText(this@ViewItemActivity, "Cannot share this file", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = item.mimeType.ifBlank { "*/*" }
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share via"))
                }
            }
        }
    }

    private fun bindSaveToFiles(item: StashItem) {
        if (item.type in listOf("link", "text") || !item.fileLocal) {
            binding.btnSaveToFiles.visibility = View.GONE
            return
        }
        binding.btnSaveToFiles.visibility = View.VISIBLE
        binding.btnSaveToFiles.setOnClickListener {
            val name = FileOpener.sanitizeFilename(
                item.originalFilename.ifBlank { item.title }.ifBlank { File(item.content).name }
            )
            createDocumentLauncher.launch(name)
        }
    }

    // ── Cloud download ────────────────────────────────────────────────────────

    private fun bindCloudItem(item: StashItem) {
        binding.tvContent.setText("This file is in cloud storage. Download it to open.")
        binding.btnAction.text = "☁  Download File"
        binding.btnShare.visibility = View.GONE
        binding.btnSaveToFiles.visibility = View.GONE

        val syncClient = (application as StashApp).syncClient

        syncClient.downloadProgress.observe(this) { progressMap ->
            val pct = progressMap[item.syncId]
            when {
                pct == null -> {
                    lifecycleScope.launch {
                        val updated = viewModel.getBySyncId(item.syncId)
                        if (updated != null && updated.fileLocal) {
                            finish()
                            startActivity(intent.putExtra(EXTRA_ID, updated.id))
                        } else {
                            binding.progressBar.visibility = View.GONE
                            binding.btnAction.text = "☁  Download File"
                            binding.btnAction.isEnabled = true
                        }
                    }
                }
                pct < 0 -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnAction.text = "⚠  Download failed — tap to retry"
                    binding.btnAction.isEnabled = true
                    Toast.makeText(this, "Download failed — file may not exist on server yet", Toast.LENGTH_LONG).show()
                }
                else -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = pct
                    binding.btnAction.text = if (pct > 0) "Downloading… $pct%" else "Downloading…"
                    binding.btnAction.isEnabled = false
                }
            }
        }

        // Show thumbnail poster for cloud images/videos
        if (item.thumbnailPath.isNotBlank()) {
            val bmp = com.stashapp.utils.BitmapUtils.decodeBounded(item.thumbnailPath, 2048)
            if (bmp != null) {
                if (item.type == "video") {
                    binding.videoContainer.visibility = View.VISIBLE
                    binding.ivVideoPoster.setImageBitmap(bmp)
                    binding.ivVideoPlay.visibility = View.GONE
                } else {
                    binding.ivPreview.setImageBitmap(bmp)
                    binding.ivPreview.visibility = View.VISIBLE
                    binding.ivPreview.setOnClickListener { openFullscreen() }
                }
            }
        }

        binding.btnAction.setOnClickListener {
            if (syncClient.state.value != SyncState.CONNECTED) {
                Toast.makeText(this, "Not connected to sync server", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Download file?")
                .setMessage("This will download the file from the sync server.")
                .setPositiveButton("Download") { _, _ ->
                    syncClient.requestFile(item.syncId)
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = 0
                    binding.btnAction.text = "Downloading…"
                    binding.btnAction.isEnabled = false
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Delete ──────────────────────────────────────────────────────────────────

    private fun confirmDelete() {
        val item = currentItem ?: return
        val syncClient = (application as StashApp).syncClient
        val connected = syncClient.state.value == SyncState.CONNECTED && item.syncId.isNotBlank()
        val isFileItem = item.type !in LINK_TEXT
        val cloudOnly = isFileItem && !item.fileLocal

        val builder = AlertDialog.Builder(this).setTitle("Remove from Stash?")
        if (connected && isFileItem && !cloudOnly) {
            builder
                .setPositiveButton("Remove everywhere") { _, _ -> performFullDelete(item) }
                .setNeutralButton("Remove locally only") { _, _ -> ghostItem(item) }
                .setNegativeButton("Cancel", null)
        } else {
            builder
                .setPositiveButton("Remove") { _, _ -> performFullDelete(item) }
                .setNegativeButton("Cancel", null)
        }
        builder.show()
    }

    private fun performFullDelete(item: StashItem) {
        deleteLocalFilesFor(item)
        // Record a durable delete tombstone for synced items, even offline, so the deletion is
        // flushed on reconnect and a stale sync_meta can't resurrect the item.
        if (item.syncId.isNotBlank()) (application as StashApp).syncClient.deleteItem(item.syncId)
        lifecycleScope.launch {
            viewModel.deleteById(item.id)
            finish()
        }
    }

    /** "Remove locally only": drop the local file but keep the cloud copy (item becomes cloud-only). */
    private fun ghostItem(item: StashItem) {
        deleteLocalFilesFor(item, dropThumbnail = false)
        lifecycleScope.launch {
            viewModel.setCloudOnly(item.id)
            finish()
        }
    }

    private fun deleteLocalFilesFor(item: StashItem, dropThumbnail: Boolean = true) {
        val syncClient = (application as StashApp).syncClient
        if (item.type !in LINK_TEXT) {
            if (item.syncId.isNotBlank()) syncClient.cancelDownload(item.syncId)
            if (item.content.isNotBlank()) runCatching { File(item.content).delete() }
            com.stashapp.utils.FileOpener.deleteShareCache(this, item)
        }
        if (dropThumbnail && item.thumbnailPath.isNotBlank()) runCatching { File(item.thumbnailPath).delete() }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        binding.videoView.pause()
        mediaPlayer?.pause()
        binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        seekRunnable?.let { seekHandler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        seekRunnable?.let { seekHandler.removeCallbacks(it) }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
