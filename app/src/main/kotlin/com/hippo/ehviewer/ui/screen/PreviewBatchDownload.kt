package com.hippo.ehviewer.ui.screen

import android.Manifest
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.util.isAtLeastQ
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.useEhPageLoader
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.SpiderQueen.Companion.obtainSpiderQueen
import com.hippo.ehviewer.spider.SpiderQueen.Companion.releaseSpiderQueen
import com.hippo.ehviewer.ui.reader.SavePageToGalleryResult
import com.hippo.ehviewer.ui.reader.pageImageExistsInGallery
import com.hippo.ehviewer.ui.reader.savePageToGalleryIfMissing
import com.hippo.ehviewer.util.requestPermission
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import moe.tarsin.snackbar
import moe.tarsin.string

data class PreviewDownloadResult(
    val saved: Int,
    val skipped: Int,
    val failed: Int,
    val total: Int,
) {
    val completed get() = failed == 0 && saved + skipped == total
}

private suspend fun PageLoader.awaitHdReady(index: Int) {
    retryPage(index, useDownloadOriginSetting = false)
    pages[index].statusFlow.first { status ->
        when (status) {
            is PageStatus.Ready, is PageStatus.Blocked -> true
            // Cancelled decode jobs report Error(null); ignore and wait for the HD retry.
            is PageStatus.Error -> status.message != null
            else -> false
        }
    }
}

context(ctx: Context, snackbar: SnackbarHostState)
suspend fun downloadSelectedPreviewPagesHd(
    detail: GalleryDetail,
    pageIndices: List<Int>,
    knownPreviews: List<GalleryPreview> = detail.previewList,
): PreviewDownloadResult {
    val indices = pageIndices.distinct().sorted()
    if (indices.isEmpty()) {
        snackbar(string(R.string.preview_download_hd_none))
        return PreviewDownloadResult(saved = 0, skipped = 0, failed = 0, total = 0)
    }
    val granted = isAtLeastQ || requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    if (!granted) {
        snackbar(string(R.string.permission_denied))
        return PreviewDownloadResult(saved = 0, skipped = 0, failed = indices.size, total = indices.size)
    }

    var saved = 0
    var skipped = 0
    var failed = 0
    val queen = obtainSpiderQueen(detail.galleryInfo, SpiderQueen.MODE_READ)
    queen.awaitReady()
    knownPreviews.forEach { preview ->
        if (preview.pToken.isNotEmpty()) {
            queen.spiderInfo.pTokenMap[preview.position] = preview.pToken
        }
    }
    try {
        useEhPageLoader(detail.galleryInfo, indices.first()) { loader ->
            indices.forEachIndexed { done, index ->
                coroutineContext.ensureActive()
                snackbar(string(R.string.preview_download_hd_progress, done + 1, indices.size))
                if (with(ctx) { with(loader) { pageImageExistsInGallery(index) } }) {
                    skipped++
                    return@forEachIndexed
                }
                loader.awaitHdReady(index)
                coroutineContext.ensureActive()
                when (loader.pages[index].statusFlow.value) {
                    is PageStatus.Ready, is PageStatus.Blocked -> {
                        when (with(ctx) {
                            with(loader) {
                                savePageToGalleryIfMissing(index)
                            }
                        }) {
                            SavePageToGalleryResult.Saved -> saved++
                            SavePageToGalleryResult.Skipped -> skipped++
                            SavePageToGalleryResult.Failed -> failed++
                        }
                    }
                    else -> failed++
                }
            }
        }
    } finally {
        releaseSpiderQueen(queen, SpiderQueen.MODE_READ)
    }
    snackbar(string(R.string.preview_download_hd_done, saved, skipped, failed))
    return PreviewDownloadResult(saved = saved, skipped = skipped, failed = failed, total = indices.size)
}
