package com.stashapp.ui

import com.stashapp.data.StashItem

sealed class StashListItem {
    data class Header(val label: String) : StashListItem()
    data class Entry(val item: StashItem) : StashListItem()
}
