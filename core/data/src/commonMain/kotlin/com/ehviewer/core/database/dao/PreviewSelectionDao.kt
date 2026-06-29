package com.ehviewer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ehviewer.core.database.model.PreviewSelectionInfo
import kotlinx.coroutines.flow.Flow

@Dao
interface PreviewSelectionDao {
    @Query("SELECT PAGE FROM PREVIEW_SELECTIONS WHERE GID = :gid ORDER BY PAGE")
    fun pagesFlow(gid: Long): Flow<List<Int>>

    @Query("SELECT PAGE FROM PREVIEW_SELECTIONS WHERE GID = :gid ORDER BY PAGE")
    suspend fun pages(gid: Long): List<Int>

    @Upsert
    suspend fun upsert(info: PreviewSelectionInfo)

    @Query("DELETE FROM PREVIEW_SELECTIONS WHERE GID = :gid AND PAGE = :page")
    suspend fun delete(gid: Long, page: Int)

    @Query("DELETE FROM PREVIEW_SELECTIONS WHERE GID = :gid")
    suspend fun deleteByGid(gid: Long)
}
