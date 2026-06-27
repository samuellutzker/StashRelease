package com.stashapp.utils

import android.util.Patterns
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.stashapp.R

object ContentTypeDetector {

    fun detect(mimeType: String, text: String? = null): String = when {
        mimeType == "text/plain" && text != null && isUrl(text) -> "link"
        mimeType.startsWith("image/") -> "image"
        mimeType.startsWith("video/") -> "video"
        mimeType.startsWith("audio/") -> "audio"
        mimeType == "application/pdf"
            || mimeType.contains("msword")
            || mimeType.contains("officedocument")
            || mimeType == "text/csv"
            || mimeType == "application/rtf" -> "document"
        mimeType == "text/plain" -> "text"
        mimeType == "application/zip"
            || mimeType == "application/x-rar-compressed"
            || mimeType == "application/x-7z-compressed"
            || mimeType == "application/x-tar" -> "archive"
        mimeType == "application/vnd.android.package-archive" -> "apk"
        mimeType == "text/vcard" || mimeType == "text/x-vcard" -> "contact"
        else -> "other"
    }

    fun isUrl(text: String): Boolean {
        val trimmed = text.trim()
        return (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("www."))
            && Patterns.WEB_URL.matcher(trimmed).matches()
    }

    fun extractDomain(url: String): String = try {
        val host = java.net.URI(url.trim()).host ?: ""
        host.removePrefix("www.")
    } catch (_: Exception) { "" }

    fun linkSubtype(url: String): String {
        val domain = extractDomain(url)
        return when {
            domain.contains("youtube.com") || domain.contains("youtu.be") -> "YouTube"
            domain.contains("maps.google") || domain.contains("goo.gl") -> "Maps"
            domain.contains("twitter.com") || domain.contains("x.com") -> "Twitter/X"
            domain.contains("instagram.com") -> "Instagram"
            domain.contains("reddit.com") -> "Reddit"
            domain.contains("github.com") -> "GitHub"
            domain.contains("spotify.com") -> "Spotify"
            domain.contains("tiktok.com") -> "TikTok"
            else -> domain
        }
    }

    fun typeLabel(type: String): String = when (type) {
        "link" -> "Links"
        "image" -> "Images"
        "video" -> "Videos"
        "audio" -> "Audio"
        "document" -> "Documents"
        "text" -> "Text"
        "archive" -> "Archives"
        "apk" -> "Apps"
        "contact" -> "Contacts"
        else -> "Other"
    }

    fun typeEmoji(type: String): String = when (type) {
        "link" -> "🔗"
        "image" -> "🖼"
        "video" -> "🎥"
        "audio" -> "🎵"
        "document" -> "📄"
        "text" -> "📝"
        "archive" -> "📦"
        "apk" -> "📱"
        "contact" -> "📇"
        else -> "🗂"
    }

    // The per-type accent color resource — drives the list color tab and the type-filter chip outline.
    @ColorRes
    fun typeColorRes(type: String): Int = when (type) {
        "link" -> R.color.typeLink
        "image" -> R.color.typeImage
        "video" -> R.color.typeVideo
        "audio" -> R.color.typeAudio
        "document" -> R.color.typeDocument
        "text" -> R.color.typeText
        "archive" -> R.color.typeArchive
        "apk" -> R.color.typeApk
        "contact" -> R.color.typeContact
        else -> R.color.typeOther
    }

    // The per-type line-art glyph shown in the empty-state medallion when a filter is active.
    @DrawableRes
    fun typeIconRes(type: String): Int = when (type) {
        "link" -> R.drawable.ic_type_link
        "image" -> R.drawable.ic_type_image
        "video" -> R.drawable.ic_type_video
        "audio" -> R.drawable.ic_type_audio
        "document" -> R.drawable.ic_type_document
        "text" -> R.drawable.ic_type_text
        "archive" -> R.drawable.ic_type_archive
        "apk" -> R.drawable.ic_type_apk
        "contact" -> R.drawable.ic_type_contact
        else -> R.drawable.ic_type_other
    }
}
