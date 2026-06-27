package com.stashapp.ui

import com.stashapp.data.StashItem

enum class SortOrder(val label: String) {
    DATE_DESC("Newest first"),
    DATE_ASC("Oldest first"),
    NAME_ASC("Name A–Z"),
    SIZE_DESC("Largest first"),
    TYPE("By type");

    fun comparator(): Comparator<StashItem> = when (this) {
        DATE_DESC -> compareByDescending { it.createdAt }
        DATE_ASC  -> compareBy { it.createdAt }
        NAME_ASC  -> compareBy { it.title.lowercase() }
        SIZE_DESC -> compareByDescending { it.fileSize }
        TYPE      -> compareBy({ it.type }, { -it.createdAt })
    }
}
