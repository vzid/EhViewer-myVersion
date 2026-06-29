package com.hippo.ehviewer.ui

object PreviewReaderReturnPosition {
    private val pages = mutableMapOf<Long, Int>()

    @Synchronized
    fun start(gid: Long, page: Int) {
        pages[gid] = page
    }

    @Synchronized
    fun update(gid: Long, page: Int) {
        if (gid in pages) {
            pages[gid] = page
        }
    }

    @Synchronized
    fun consume(gid: Long): Int? = pages.remove(gid)
}
