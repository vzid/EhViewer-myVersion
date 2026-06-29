package com.ehviewer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ehviewer.core.database.model.PreviewDownloadHistoryInfo

@Dao
interface PreviewDownloadHistoryDao {
    @Query("SELECT * FROM PREVIEW_DOWNLOAD_HISTORY WHERE GID = :gid")
    suspend fun load(gid: Long): PreviewDownloadHistoryInfo?

    @Query("SELECT * FROM PREVIEW_DOWNLOAD_HISTORY ORDER BY TIME DESC")
    suspend fun list(): List<PreviewDownloadHistoryInfo>

    @Upsert
    suspend fun upsert(info: PreviewDownloadHistoryInfo)
}
