package com.stashapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.stashapp.StashApp
import com.stashapp.data.StashDatabase
import com.stashapp.data.StashItem
import com.stashapp.data.StashRepository
import com.stashapp.utils.ContentTypeDetector
import kotlinx.coroutines.launch
import java.util.Calendar

class StashViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = StashDatabase.getDatabase(application).let { StashRepository(it.stashDao(), it.pendingOpDao()) }

    private val _filterType = MutableLiveData<String?>(null)
    private val _searchQuery = MutableLiveData<String?>(null)
    val sortOrder = MutableLiveData(SortOrder.DATE_DESC)
    val localOnly = MutableLiveData(false)

    /** Set to true before any filter/sort change; cleared by MainActivity after scrolling. */
    var pendingScrollToTop = false

    val filterType: LiveData<String?> = _filterType
    val searchQuery: LiveData<String?> = _searchQuery

    // Single trigger that fires whenever any filter input changes
    private val _trigger = MediatorLiveData<Unit>().also { t ->
        t.addSource(_filterType) { t.value = Unit }
        t.addSource(_searchQuery) { t.value = Unit }
        t.addSource(localOnly) { t.value = Unit }
        t.value = Unit
    }

    // Raw filtered items from Room — one switchMap off a single trigger
    private val rawItems: LiveData<List<StashItem>> = _trigger.switchMap {
        val type = _filterType.value
        val query = _searchQuery.value
        val lo = localOnly.value ?: false
        when {
            !query.isNullOrBlank() -> if (lo) repo.searchLocalOnly(query) else repo.search(query)
            type != null -> if (lo) repo.getLocalOnlyByType(type) else repo.getByType(type)
            else -> if (lo) repo.getLocalOnly() else repo.getAll()
        }
    }

    // Sorted + date-grouped list for the adapter
    val displayItems: LiveData<List<StashListItem>> = MediatorLiveData<List<StashListItem>>().apply {
        var currentItems: List<StashItem> = emptyList()
        var currentSort = SortOrder.DATE_DESC

        fun rebuild() { value = buildGroupedList(currentItems, currentSort) }

        addSource(rawItems) { currentItems = it ?: emptyList(); rebuild() }
        addSource(sortOrder) { currentSort = it; rebuild() }
    }

    fun setFilter(type: String?) { _filterType.value = type; _searchQuery.value = null }
    fun setSearch(query: String?) { _searchQuery.value = query }
    fun clearFilter() { _filterType.value = null; _searchQuery.value = null }

    fun insert(item: StashItem) = viewModelScope.launch { repo.insert(item) }
    /** Insert and return the stored row (with its generated id), so the caller can push it to sync. */
    suspend fun insertReturning(item: StashItem): StashItem? = repo.getById(repo.insert(item))
    fun deleteById(id: Long) = viewModelScope.launch { repo.deleteById(id) }
    /** Awaitable delete so a caller can sequence work (e.g. an orphan sweep) after the row is gone. */
    suspend fun deleteByIdNow(id: Long) = repo.deleteById(id)
    fun setCloudOnly(id: Long) = viewModelScope.launch { repo.setCloudOnly(id) }
    fun togglePin(item: StashItem) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        repo.setPinned(item.id, !item.isPinned, now)
        // Propagate the pin change to other devices (metadata-only, no file re-upload).
        repo.getById(item.id)?.let { (getApplication() as StashApp).syncClient.pushItem(it, withFile = false) }
    }
    suspend fun findByUrl(url: String) = repo.findByUrl(url)
    suspend fun getById(id: Long) = repo.getById(id)

    private fun buildGroupedList(items: List<StashItem>, sort: SortOrder): List<StashListItem> {
        val result = mutableListOf<StashListItem>()

        val pinned = items.filter { it.isPinned }.sortedWith(sort.comparator())
        if (pinned.isNotEmpty()) {
            result += StashListItem.Header("📌  Pinned")
            pinned.forEach { result += StashListItem.Entry(it) }
        }

        val unpinned = items.filter { !it.isPinned }.sortedWith(sort.comparator())

        when (sort) {
            SortOrder.DATE_DESC, SortOrder.DATE_ASC -> {
                val now = Calendar.getInstance()
                fun startOfDay(cal: Calendar) = (cal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                val todayStart = startOfDay(now)
                val yesterdayStart = startOfDay((now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })
                val weekStart = startOfDay((now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) })
                val monthStart = startOfDay((now.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -30) })

                var lastGroup = ""
                unpinned.forEach { item ->
                    val group = when {
                        item.createdAt >= todayStart -> "Today"
                        item.createdAt >= yesterdayStart -> "Yesterday"
                        item.createdAt >= weekStart -> "This Week"
                        item.createdAt >= monthStart -> "This Month"
                        else -> "Older"
                    }
                    if (group != lastGroup) { result += StashListItem.Header(group); lastGroup = group }
                    result += StashListItem.Entry(item)
                }
            }
            SortOrder.NAME_ASC -> {
                var lastGroup = ""
                unpinned.forEach { item ->
                    val name = item.title.ifBlank { item.originalFilename }
                    val first = name.firstOrNull { it.isLetter() }
                    val group = first?.uppercaseChar()?.toString() ?: "#"
                    if (group != lastGroup) { result += StashListItem.Header(group); lastGroup = group }
                    result += StashListItem.Entry(item)
                }
            }
            SortOrder.SIZE_DESC -> {
                var lastGroup = ""
                unpinned.forEach { item ->
                    val group = when {
                        item.fileSize >= 10L * 1024 * 1024 -> "Large  (> 10 MB)"
                        item.fileSize >= 1024 * 1024 -> "Medium  (1–10 MB)"
                        item.fileSize > 0 -> "Small  (< 1 MB)"
                        else -> "No size"
                    }
                    if (group != lastGroup) { result += StashListItem.Header(group); lastGroup = group }
                    result += StashListItem.Entry(item)
                }
            }
            SortOrder.TYPE -> {
                var lastGroup = ""
                unpinned.forEach { item ->
                    val group = "${ContentTypeDetector.typeEmoji(item.type)}  ${ContentTypeDetector.typeLabel(item.type)}"
                    if (group != lastGroup) { result += StashListItem.Header(group); lastGroup = group }
                    result += StashListItem.Entry(item)
                }
            }
        }

        return result
    }
}
