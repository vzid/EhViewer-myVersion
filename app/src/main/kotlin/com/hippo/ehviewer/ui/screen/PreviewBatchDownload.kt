package com.hippo.ehviewer.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.GalleryTagGroup
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.util.isAtLeastQ
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.useEhPageLoader
import com.hippo.ehviewer.spider.SpiderQueen
import com.hippo.ehviewer.spider.SpiderQueen.Companion.obtainSpiderQueen
import com.hippo.ehviewer.spider.SpiderQueen.Companion.releaseSpiderQueen
import com.hippo.ehviewer.ui.reader.GalleryImageSaveTarget
import com.hippo.ehviewer.ui.reader.SavePageToGalleryResult
import com.hippo.ehviewer.ui.reader.pageImageExistsInGallery
import com.hippo.ehviewer.ui.reader.savePageToGalleryIfMissing
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.FileUtils
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

data class PreviewDownloadResult(
    val saved: Int,
    val skipped: Int,
    val failed: Int,
    val total: Int,
) {
    val completed get() = failed == 0 && saved + skipped == total
}

data class PreviewDownloadProgress(
    val current: Int,
    val total: Int,
    val speedBytesPerSecond: Long,
)

private class PreviewSpeedMeter {
    private var bytesSinceLast = 0L
    private var lastAt = SystemClock.elapsedRealtime()
    var currentSpeed = 0L
        private set

    fun add(bytesRead: Int): Long {
        if (bytesRead > 0) {
            bytesSinceLast += bytesRead.toLong()
        }
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastAt
        if (elapsed >= 1_000L) {
            currentSpeed = bytesSinceLast * 1_000L / elapsed
            bytesSinceLast = 0L
            lastAt = now
        }
        return currentSpeed
    }
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

private fun List<GalleryTagGroup>.firstTagText(namespace: TagNamespace) =
    firstOrNull { it.namespace == namespace }?.tags?.firstOrNull()?.text?.trim()?.ifEmpty { null }

private fun GalleryDetail.previewDownloadOwnerName() =
    tagGroups.firstTagText(TagNamespace.Artist)
        ?: tagGroups.firstTagText(TagNamespace.Group)
        ?: "unknow"

private fun GalleryDetail.previewDownloadFolderName(): String {
    val title = EhUtils.getSuitableTitle(galleryInfo).ifBlank { gid.toString() }
    return FileUtils.sanitizeFilename("${previewDownloadOwnerName()}-$title")
}

private fun GalleryDetail.previewDownloadTarget(index: Int): GalleryImageSaveTarget {
    val width = pages.coerceAtLeast(index + 1).toString().length.coerceAtLeast(1)
    val filename = "${(index + 1).toString().padStart(width, '0')}.png"
    return GalleryImageSaveTarget(
        relativePath = Environment.DIRECTORY_PICTURES +
            File.separator + AppConfig.APP_DIRNAME +
            File.separator + previewDownloadFolderName(),
        filename = filename,
        mimeType = "image/png",
    )
}

context(ctx: Context)
suspend fun downloadSelectedPreviewPagesHd(
    detail: GalleryDetail,
    pageIndices: List<Int>,
    knownPreviews: List<GalleryPreview> = detail.previewList,
    onProgress: (PreviewDownloadProgress) -> Unit = {},
): PreviewDownloadResult {
    val indices = pageIndices.distinct().sorted()
    if (indices.isEmpty()) {
        return PreviewDownloadResult(saved = 0, skipped = 0, failed = 0, total = 0)
    }
    val granted = isAtLeastQ ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) {
        return PreviewDownloadResult(saved = 0, skipped = 0, failed = indices.size, total = indices.size)
    }

    var saved = 0
    var skipped = 0
    var failed = 0
    var currentIndex = -1
    var currentPosition = 0
    val speedMeter = PreviewSpeedMeter()
    val queen = obtainSpiderQueen(detail.galleryInfo, SpiderQueen.MODE_READ)
    val listener = object : SpiderQueen.OnSpiderListener {
        override fun onPageDownload(index: Int, contentLength: Long, receivedSize: Long, bytesRead: Int) {
            if (index == currentIndex) {
                onProgress(
                    PreviewDownloadProgress(
                        current = currentPosition,
                        total = indices.size,
                        speedBytesPerSecond = speedMeter.add(bytesRead),
                    ),
                )
            }
        }
    }
    try {
        queen.addOnSpiderListener(listener)
        queen.awaitReady()
        knownPreviews.forEach { preview ->
            if (preview.pToken.isNotEmpty()) {
                queen.spiderInfo.pTokenMap[preview.position] = preview.pToken
            }
        }
        useEhPageLoader(detail.galleryInfo, indices.first()) { loader ->
            indices.forEachIndexed { done, index ->
                coroutineContext.ensureActive()
                currentIndex = index
                currentPosition = done + 1
                val target = detail.previewDownloadTarget(index)
                onProgress(PreviewDownloadProgress(currentPosition, indices.size, speedMeter.currentSpeed))
                if (with(ctx) { pageImageExistsInGallery(target) }) {
                    skipped++
                    onProgress(PreviewDownloadProgress(currentPosition, indices.size, speedMeter.currentSpeed))
                    return@forEachIndexed
                }
                loader.awaitHdReady(index)
                coroutineContext.ensureActive()
                when (loader.pages[index].statusFlow.value) {
                    is PageStatus.Ready, is PageStatus.Blocked -> {
                        when (with(ctx) {
                            with(loader) {
                                savePageToGalleryIfMissing(index, target)
                            }
                        }) {
                            SavePageToGalleryResult.Saved -> saved++
                            SavePageToGalleryResult.Skipped -> skipped++
                            SavePageToGalleryResult.Failed -> failed++
                        }
                    }
                    else -> failed++
                }
                onProgress(PreviewDownloadProgress(currentPosition, indices.size, speedMeter.currentSpeed))
            }
        }
    } finally {
        queen.removeOnSpiderListener(listener)
        releaseSpiderQueen(queen, SpiderQueen.MODE_READ)
    }
    return PreviewDownloadResult(saved = saved, skipped = skipped, failed = failed, total = indices.size)
}
