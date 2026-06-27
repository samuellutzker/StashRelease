package com.stashapp.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.stashapp.StashApp
import com.stashapp.data.StashDatabase
import com.stashapp.data.StashItem
import com.stashapp.data.StashRepository
import com.stashapp.sync.SyncWorker
import com.stashapp.sync.computeFileHash
import com.stashapp.utils.ContentTypeDetector
import com.stashapp.utils.FileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ShareReceiverActivity : AppCompatActivity() {

    private lateinit var repo: StashRepository
    private var progressDialog: AlertDialog? = null

    private fun showProgress() {
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            val p = (24 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        progressDialog = AlertDialog.Builder(this)
            .setMessage("Saving to Stash…")
            .setView(spinner)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun hideProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = StashDatabase.getDatabase(this).let { StashRepository(it.stashDao(), it.pendingOpDao()) }
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> processSingle(intent)
            Intent.ACTION_SEND_MULTIPLE -> processMultiple(intent)
            // "Open with…" from Gmail / file managers: the attachment URI is in intent.data.
            Intent.ACTION_VIEW -> processView(intent)
            else -> finish()
        }
    }

    /** Handle an ACTION_VIEW "Open with…" — e.g. opening an email attachment into Stash. */
    private fun processView(intent: Intent) {
        val uri = intent.data
        if (uri == null) { finish(); return }
        // Prefer the resolver's MIME (content URIs are authoritative); fall back to intent.type.
        val mimeType = contentResolver.getType(uri) ?: intent.type ?: "application/octet-stream"

        showProgress()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { saveFile(uri, mimeType) } }
            hideProgress()
            val item = result.getOrNull()
            if (item != null) {
                showSaved(1)
                (application as StashApp).syncClient.pushItem(item)
                SyncWorker.enqueue(applicationContext)
            } else if (result.isFailure) {
                Toast.makeText(this@ShareReceiverActivity, "Couldn't save this file", Toast.LENGTH_SHORT).show()
            }
            // null return = duplicate; toast already shown inside saveFile
            finish()
        }
    }

    private fun processSingle(intent: Intent) {
        val mimeType = intent.type ?: "application/octet-stream"
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)

        if (uri != null) showProgress()
        lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) {
                when {
                    uri != null -> saveFile(uri, mimeType)
                    text != null -> saveText(text, mimeType)
                    else -> null
                }
            }
            hideProgress()
            if (item != null) {
                showSaved(1)
                (application as StashApp).syncClient.pushItem(item)
                // Guarantee the upload finishes even if we're backgrounded/killed before it does.
                SyncWorker.enqueue(applicationContext)
            }
            finish()
        }
    }

    private fun processMultiple(intent: Intent) {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: run { finish(); return }
        val mimeType = intent.type ?: "application/octet-stream"

        showProgress()
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> runCatching { saveFile(uri, mimeType) }.getOrNull() }
            }
            hideProgress()
            if (items.isNotEmpty()) {
                showSaved(items.size)
                val syncClient = (application as StashApp).syncClient
                items.forEach { syncClient.pushItem(it) }
                // Guarantee the uploads finish even if we're backgrounded/killed before they do.
                SyncWorker.enqueue(applicationContext)
            }
            finish()
        }
    }

    private suspend fun saveFile(uri: Uri, mimeType: String): StashItem? {
        val actualMime = contentResolver.getType(uri)?.takeIf { it.isNotBlank() && it != "*/*" } ?: mimeType
        val type = ContentTypeDetector.detect(actualMime)
        val (file, filename) = FileStorage.copyToStash(this, uri, type)
        val fileSize = file.length()

        val hash = computeFileHash(file.absolutePath)
        if (hash.isNotEmpty() && repo.getByFileHash(hash) != null) {
            runCatching { file.delete() }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ShareReceiverActivity, "Already in Stash", Toast.LENGTH_SHORT).show()
            }
            return null
        }

        var thumbPath = ""
        if (type == "image") {
            runCatching {
                // Bounded decode avoids allocating a full-resolution photo just to shrink it.
                val bmp = com.stashapp.utils.BitmapUtils.decodeBounded(file.absolutePath, 1024)
                if (bmp != null) {
                    val thumb = android.graphics.Bitmap.createScaledBitmap(bmp, 200, 200, true)
                    thumbPath = FileStorage.saveThumbnail(this, thumb, UUID.randomUUID().leastSignificantBits).absolutePath
                    bmp.recycle()
                }
            }
        } else if (type == "video") {
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                if (frame != null) {
                    val thumb = android.graphics.Bitmap.createScaledBitmap(frame, 200, 200, true)
                    thumbPath = FileStorage.saveThumbnail(this, thumb, UUID.randomUUID().leastSignificantBits).absolutePath
                    frame.recycle()
                }
            }
        }

        val syncId = UUID.randomUUID().toString()
        val id = repo.insert(StashItem(
            // Files carry their name in `originalFilename`; `title` is the user's optional, editable
            // comment (blank until they add one) and is what syncs as the item title.
            title = "",
            content = file.absolutePath,
            type = type,
            mimeType = actualMime,
            originalFilename = filename,
            fileSize = fileSize,
            sourceDomain = "",
            thumbnailPath = thumbPath,
            syncId = syncId,
            fileHash = hash
        ))
        return repo.getById(id)!!
    }

    private suspend fun saveText(text: String, mimeType: String): StashItem? {
        val isUrl = ContentTypeDetector.isUrl(text)

        if (isUrl) {
            val existing = repo.findByUrl(text.trim())
            if (existing != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ShareReceiverActivity, "Already in Stash", Toast.LENGTH_SHORT).show()
                }
                return null
            }
        }

        val type = if (isUrl) "link" else "text"
        val domain = if (isUrl) ContentTypeDetector.linkSubtype(text) else ""
        val title = if (isUrl) "" else text.take(60)
        val syncId = UUID.randomUUID().toString()
        val id = repo.insert(StashItem(
            title = title,
            content = text.trim(),
            type = type,
            mimeType = mimeType,
            originalFilename = "",
            sourceDomain = domain,
            syncId = syncId
        ))
        return repo.getById(id)
    }

    private fun showSaved(count: Int) {
        val msg = if (count == 1) "Saved to Stash" else "$count items saved to Stash"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
