package com.netdisk.player4kid.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.netdisk.player4kid.baidu.BaiduApi
import java.io.IOException

/**
 * 处理 bdpan:// 地址的数据源：
 *  - bdpan://dlink/<fs_id>   → 取下载地址（带 UA: pan.baidu.com），交给 HTTP 数据源（支持 Range）
 *  - bdpan://stream/<fs_id>  → 调转码接口拿到 m3u8 文本，直接从内存返回；
 *                              getUri() 返回真实地址，m3u8 中的相对分片地址能正确解析
 *  - 其它地址（m3u8 分片、视频服务器上的文件）→ 带上播放器 UA 交给 HTTP 数据源
 */
@OptIn(UnstableApi::class)
class PanDataSource(
    private val upstream: DataSource,
    private val api: BaiduApi,
) : DataSource {

    private var memory: ByteArray? = null
    private var memoryPos = 0
    private var memoryRemaining = 0
    private var currentUri: Uri? = null
    private var upstreamOpened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme == PanMedia.SCHEME) {
            val fsId = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: 0L
            when (uri.authority) {
                "stream" -> return openPlaylist(dataSpec)
                "dlink" -> return openDlink(dataSpec, fsId)
            }
            throw IOException("未知地址 $uri")
        }
        return openUpstream(withUa(dataSpec, uri, api.streamUserAgent()))
    }

    private fun openPlaylist(spec: DataSpec): Long {
        val uri = spec.uri
        val path = uri.getQueryParameter("path").orEmpty()
        val type = uri.getQueryParameter("type") ?: BaiduApi.streamVideoType("")
        val (url, text) = api.fetchStreamPlaylist(path, type)
        val bytes = text.toByteArray(Charsets.UTF_8)
        currentUri = Uri.parse(url)
        memory = bytes
        memoryPos = spec.position.coerceIn(0, bytes.size.toLong()).toInt()
        val avail = bytes.size - memoryPos
        memoryRemaining = if (spec.length == C.LENGTH_UNSET.toLong()) avail else minOf(spec.length.toInt(), avail)
        return memoryRemaining.toLong()
    }

    private fun openDlink(spec: DataSpec, fsId: Long): Long {
        val url = api.downloadUrl(fsId)
        return try {
            openUpstream(withUa(spec, Uri.parse(url), BaiduApi.UA_DEFAULT))
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            // 下载地址过期（31360）等：重新获取一次
            Log.w(TAG, "dlink http ${e.responseCode}, retry with fresh link")
            closeQuietly()
            api.invalidateDlink(fsId)
            openUpstream(withUa(spec, Uri.parse(api.downloadUrl(fsId)), BaiduApi.UA_DEFAULT))
        }
    }

    private fun openUpstream(spec: DataSpec): Long {
        upstreamOpened = true
        val len = upstream.open(spec)
        currentUri = upstream.uri ?: spec.uri
        return len
    }

    private fun withUa(spec: DataSpec, uri: Uri, ua: String): DataSpec {
        val headers = HashMap(spec.httpRequestHeaders)
        headers["User-Agent"] = ua
        return spec.buildUpon().setUri(uri).setHttpRequestHeaders(headers).build()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val mem = memory
        if (mem != null) {
            if (memoryRemaining <= 0) return C.RESULT_END_OF_INPUT
            val n = minOf(length, memoryRemaining)
            System.arraycopy(mem, memoryPos, buffer, offset, n)
            memoryPos += n
            memoryRemaining -= n
            return n
        }
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (upstreamOpened) upstream.responseHeaders else emptyMap()

    override fun close() {
        memory = null
        currentUri = null
        closeQuietly()
    }

    private fun closeQuietly() {
        if (upstreamOpened) {
            upstreamOpened = false
            upstream.close()
        }
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val api: BaiduApi,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = PanDataSource(upstream.createDataSource(), api)
    }

    companion object {
        private const val TAG = "PanDataSource"
    }
}
