package com.stashapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.stashapp.data.StashDatabase
import com.stashapp.data.StashRepository

class ViewItemViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = StashDatabase.getDatabase(application).let { StashRepository(it.stashDao(), it.pendingOpDao()) }

    suspend fun getById(id: Long) = repo.getById(id)
    fun getLiveById(id: Long) = repo.getLiveById(id)
    suspend fun getBySyncId(syncId: String) = repo.getBySyncId(syncId)
    suspend fun updateEdited(id: Long, content: String, title: String, updatedAt: Long) =
        repo.updateEdited(id, content, title, updatedAt)
    suspend fun deleteById(id: Long) = repo.deleteById(id)
    suspend fun setCloudOnly(id: Long) = repo.setCloudOnly(id)
    suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long) = repo.setPinned(id, pinned, updatedAt)
}
