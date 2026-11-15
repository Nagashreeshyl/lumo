package com.nagashreeshyl.nive.data

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.nagashreeshyl.nive.NiveApplication
import com.nagashreeshyl.nive.R
import com.nagashreeshyl.nive.audio.Media
import com.nagashreeshyl.nive.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** Offline downloads into their own cache (never evicted). The player reads it before the network. */
@OptIn(UnstableApi::class)
class Downloads(private val context: Context, media: Media, private val library: Library) {
    val manager = DownloadManager(context, StandaloneDatabaseProvider(context), media.downloads, media.upstream, Executors.newFixedThreadPool(2))
    private val _active = MutableStateFlow<Set<String>>(emptySet()); val active = _active.asStateFlow()

    init {
        manager.maxParallelDownloads = 2
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(m: DownloadManager, download: Download, finalException: Exception?) {
                val id = download.request.id
                val song = library.existing(id)?.song
                when (download.state) {
                    Download.STATE_COMPLETED -> { song?.let { library.setDownloaded(it, true) }; _active.value -= id }
                    Download.STATE_FAILED -> _active.value -= id
                    else -> _active.value += id
                }
            }
            override fun onDownloadRemoved(m: DownloadManager, download: Download) {
                library.existing(download.request.id)?.song?.let { library.setDownloaded(it, false) }
                _active.value -= download.request.id
            }
        })
    }

    fun download(song: Song) {
        library.track(song)
        val request = DownloadRequest.Builder(song.id, Uri.parse("nive://${song.id}")).setCustomCacheKey(song.id).setData(song.title.toByteArray()).build()
        DownloadService.sendAddDownload(context, NiveDownloadService::class.java, request, false)
    }

    fun delete(id: String) = DownloadService.sendRemoveDownload(context, NiveDownloadService::class.java, id, false)
}

@OptIn(UnstableApi::class)
class NiveDownloadService : DownloadService(2, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, "downloads", R.string.downloads, 0) {
    private val helper by lazy { DownloadNotificationHelper(this, "downloads") }
    override fun getDownloadManager() = (application as NiveApplication).downloads.manager
    override fun getScheduler(): Scheduler? = null
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        helper.buildProgressNotification(this, android.R.drawable.stat_sys_download, null,
            downloads.firstOrNull()?.request?.data?.let { String(it) }, downloads, notMetRequirements)
}
