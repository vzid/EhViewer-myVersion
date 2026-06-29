package com.ehviewer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlin.time.Clock

@Entity(tableName = "PREVIEW_SELECTIONS", primaryKeys = ["GID", "PAGE"])
class PreviewSelectionInfo(
    @ColumnInfo(name = "GID")
    val gid: Long,

    @ColumnInfo(name = "PAGE")
    val page: Int,

    @ColumnInfo(name = "TIME")
    val time: Long = Clock.System.now().toEpochMilliseconds(),
)
