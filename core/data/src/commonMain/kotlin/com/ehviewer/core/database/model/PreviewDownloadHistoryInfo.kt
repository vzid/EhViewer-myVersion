package com.ehviewer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Clock

@Entity(tableName = "PREVIEW_DOWNLOAD_HISTORY")
class PreviewDownloadHistoryInfo(
    @PrimaryKey
    @ColumnInfo(name = "GID")
    val gid: Long,

    @ColumnInfo(name = "TITLE")
    val title: String,

    @ColumnInfo(name = "PAGES")
    val pages: String,

    @ColumnInfo(name = "TIME")
    val time: Long = Clock.System.now().toEpochMilliseconds(),
)
