package com.hippo.ehviewer.ui.screen

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.util.isAtLeastT
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.util.FileUtils
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import splitties.init.appCtx

data class PreviewDownloadState(
    val gid: Long? = null,
    val running: Boolean = false,
)

object PreviewDownloadManager {
    private const val NOTIFICATION_ID = 0x4550

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PreviewDownloadState())
    val state: StateFlow<PreviewDownloadState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private val notificationManager by lazy { NotificationManagerCompat.from(appCtx) }
    private val channelId by lazy { "${appCtx.packageName}.preview_selected_download" }

    @Synchronized
    fun start(
        detail: GalleryDetail,
        pageIndices: List<Int>,
        knownPreviews: List<GalleryPreview> = detail.previewList,
    ): Boolean {
        if (activeJob?.isActive == true) {
            return false
        }
        val indices = pageIndices.distinct().sorted()
        if (indices.isEmpty()) {
            return true
        }
        _state.value = PreviewDownloadState(gid = detail.gid, running = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runDownload(detail, indices, knownPreviews)
        }
        activeJob = job
        job.start()
        return true
    }

    @Synchronized
    fun stop(gid: Long) {
        if (_state.value.gid == gid) {
            activeJob?.cancel()
        }
    }

    private suspend fun runDownload(
        detail: GalleryDetail,
        pageIndices: List<Int>,
        knownPreviews: List<GalleryPreview>,
    ) {
        ensureNotificationChannel()
        try {
            showProgress(detail, PreviewDownloadProgress(current = 0, total = pageIndices.size, speedBytesPerSecond = 0))
            val result = with(appCtx) {
                downloadSelectedPreviewPagesHd(detail, pageIndices, knownPreviews) { progress ->
                    showProgress(detail, progress)
                }
            }
            if (result.completed) {
                EhDB.putPreviewDownloadHistory(detail.galleryInfo, pageIndices)
                pageIndices.forEach { EhDB.removePreviewSelectionPage(detail.gid, it) }
                showDone(detail, result)
            } else {
                showFailed(detail, result)
            }
        } catch (_: CancellationException) {
            showStopped(detail)
        } catch (t: Throwable) {
            logcat(t)
            showFailed(detail, null)
        } finally {
            val currentJob = coroutineContext[Job]
            synchronized(this) {
                if (activeJob == currentJob) {
                    activeJob = null
                    _state.value = PreviewDownloadState()
                }
            }
        }
    }

    private fun ensureNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannelCompat.Builder(channelId, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(appCtx.getString(R.string.preview_download_notification_channel))
                .build(),
        )
    }

    private fun canNotify() = !isAtLeastT ||
        ActivityCompat.checkSelfPermission(appCtx, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun title(detail: GalleryDetail) =
        EhUtils.getSuitableTitle(detail.galleryInfo).ifBlank { detail.gid.toString() }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(appCtx, MainActivity::class.java)
        return PendingIntent.getActivity(
            appCtx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @SuppressLint("MissingPermission")
    private fun showProgress(detail: GalleryDetail, progress: PreviewDownloadProgress) {
        if (!canNotify()) return
        val speedText = FileUtils.humanReadableByteCount(progress.speedBytesPerSecond)
        val builder = NotificationCompat.Builder(appCtx, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(appCtx.getString(R.string.preview_download_notification_title, title(detail)))
            .setContentText(
                appCtx.getString(
                    R.string.preview_download_notification_progress,
                    progress.current,
                    progress.total,
                    speedText,
                ),
            )
            .setSubText("${progress.current}/${progress.total}")
            .setProgress(progress.total, progress.current, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent())
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    @SuppressLint("MissingPermission")
    private fun showDone(detail: GalleryDetail, result: PreviewDownloadResult) {
        if (!canNotify()) return
        val builder = NotificationCompat.Builder(appCtx, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(appCtx.getString(R.string.preview_download_notification_title, title(detail)))
            .setContentText(
                appCtx.getString(
                    R.string.preview_download_notification_done,
                    result.saved,
                    result.skipped,
                    result.failed,
                ),
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    @SuppressLint("MissingPermission")
    private fun showStopped(detail: GalleryDetail) {
        if (!canNotify()) return
        val builder = NotificationCompat.Builder(appCtx, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(appCtx.getString(R.string.preview_download_notification_title, title(detail)))
            .setContentText(appCtx.getString(R.string.preview_download_notification_stopped))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    @SuppressLint("MissingPermission")
    private fun showFailed(detail: GalleryDetail, result: PreviewDownloadResult?) {
        if (!canNotify()) return
        val text = result?.let {
            appCtx.getString(R.string.preview_download_notification_failed, it.saved, it.skipped, it.failed)
        } ?: appCtx.getString(R.string.preview_download_notification_failed_unknown)
        val builder = NotificationCompat.Builder(appCtx, channelId)
            .setSmallIcon(com.hippo.ehviewer.R.drawable.ic_baseline_warning_24)
            .setContentTitle(appCtx.getString(R.string.preview_download_notification_title, title(detail)))
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }
}
