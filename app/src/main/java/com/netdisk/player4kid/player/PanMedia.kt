package com.netdisk.player4kid.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.netdisk.player4kid.App
import com.netdisk.player4kid.baidu.BaiduApi
import com.netdisk.player4kid.baidu.PanFile
import com.netdisk.player4kid.core.Prefs
import com.netdisk.player4kid.web.WebLibrary

/**
 * 把网盘文件变成播放器可播放的 MediaItem。
 *
 * MediaItem 里只放自定义地址 `bdpan://...`，真正的播放地址在播放器加载时
 * 才由 [PanDataSource] 去取（dlink 有 8 小时有效期，转码流可能需要等待广告时间）。
 *
 *  - bdpan://dlink/<fs_id>                       原画：下载地址直接播放
 *  - bdpan://stream/<fs_id>?path=..&type=..      转码：m3u8 流
 *
 * 视频服务器（[WebLibrary]）上的文件直接使用它的 http 地址。
 */
@OptIn(UnstableApi::class)
object PanMedia {
    const val SCHEME = "bdpan"
    const val EXTRA_FS_ID = "fs_id"
    const val EXTRA_PATH = "path"

    fun videoItem(f: PanFile, mode: String = App.prefs.videoMode): MediaItem = when {
        WebLibrary.isWebPath(f.path) -> webItem(f)
        mode == Prefs.MODE_ORIGINAL -> dlinkItem(f)
        else -> streamItem(f, BaiduApi.streamVideoType(mode))
    }

    /** @param dir 所在目录的完整标题（如“RAZ全套音频 / Level C”），显示在首页音频条和通知里 */
    fun audioItem(f: PanFile, dir: String? = null): MediaItem =
        if (WebLibrary.isWebPath(f.path)) webItem(f, dir) else dlinkItem(f, dir)

    /** 视频服务器上的文件：普通 HTTP 地址，直接播放 */
    fun webItem(f: PanFile, dir: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId("${f.fsId}")
        .setUri(Uri.parse(WebLibrary.encodeUrl(f.path)))
        .setMediaMetadata(metadata(f, dir))
        .build()

    fun dlinkItem(f: PanFile, dir: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId("${f.fsId}")
        .setUri(Uri.parse("$SCHEME://dlink/${f.fsId}"))
        .setMediaMetadata(metadata(f, dir))
        .build()

    fun streamItem(f: PanFile, type: String): MediaItem = MediaItem.Builder()
        .setMediaId("${f.fsId}")
        .setUri(
            Uri.Builder().scheme(SCHEME).authority("stream").appendPath("${f.fsId}")
                .appendQueryParameter("path", f.path)
                .appendQueryParameter("type", type)
                .build()
        )
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .setMediaMetadata(metadata(f))
        .build()

    private fun metadata(f: PanFile, dir: String? = null) = MediaMetadata.Builder()
        .setTitle(f.displayName)
        .setDisplayTitle(f.displayName)
        // 目录标题：albumTitle 给界面用；artist 让系统通知第二行显示目录
        .setAlbumTitle(dir)
        .setArtist(dir)
        .setExtras(Bundle().apply {
            putLong(EXTRA_FS_ID, f.fsId)
            putString(EXTRA_PATH, f.path)
        })
        .build()

    fun fsIdOf(item: MediaItem?): Long =
        item?.mediaMetadata?.extras?.getLong(EXTRA_FS_ID) ?: item?.mediaId?.toLongOrNull() ?: 0L

    fun isStream(item: MediaItem?): Boolean = item?.localConfiguration?.uri?.authority == "stream"

    /** 播放器使用的媒体源工厂：带 bdpan:// 地址解析，见 [PanDataSource] */
    fun mediaSourceFactory(context: Context): DefaultMediaSourceFactory {
        val http = OkHttpDataSource.Factory(App.http) // 不设置 UA，由 PanDataSource 按请求设置
        val upstream = DefaultDataSource.Factory(context, http)
        return DefaultMediaSourceFactory(PanDataSource.Factory(upstream, App.api))
            .setLoadErrorHandlingPolicy(NoRetryOnApiError)
    }

    /** 网盘接口报错（未登录、超出次数、转码失败等）不要重试，避免浪费接口调用次数 */
    private object NoRetryOnApiError : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (apiErrorOf(loadErrorInfo.exception) != null) return C.TIME_UNSET
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    /** 从异常链中找出网盘接口错误 */
    fun apiErrorOf(t: Throwable?): BaiduApi.ApiException? {
        var e = t
        var depth = 0
        while (e != null && depth++ < 8) {
            if (e is BaiduApi.ApiException) return e
            e = e.cause
        }
        return null
    }

    /** 这些错误换下一个文件也没用，直接停下 */
    fun isFatalApiError(e: BaiduApi.ApiException?): Boolean =
        e != null && e.errno in setOf(BaiduApi.ERR_NOT_LOGIN, 31034, 20012, 20011, 20013, 6)
}
