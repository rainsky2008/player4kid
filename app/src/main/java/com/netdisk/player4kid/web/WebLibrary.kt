package com.netdisk.player4kid.web

import android.content.Context
import android.util.Log
import com.netdisk.player4kid.BuildConfig
import com.netdisk.player4kid.baidu.PanFile
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 家长添加的视频服务器 */
data class WebSource(val name: String, val url: String)

/**
 * 自建视频服务器（可选）。
 *
 *   {地址}seriesList.json   → [ {"id": 1, "name": "小猪佩奇 第一季", "imageUrl": "http://…/xx.jpg"}, … ]
 *   {地址}{id}/list.json    → {"season": 1, "title": "1",
 *                               "episodes": [ {"episodeNumber": 1, "title": "S101 …", "url": "http://…/xx.mp4"}, … ]}
 *
 * 视频是普通 HTTP 文件（支持 Range），直接播放，不经过百度网盘接口。
 *
 * 程序里直接用 URL 当作“路径”，转成 [PanFile] 后浏览、连播、进度记忆、计时都和网盘共用：
 *   http://host/                 → 节目列表（目录）
 *   http://host/1/list.json      → 某个节目的剧集（目录）
 *   http://host/1/xxx.mp4        → 视频文件
 * 文件的 fsId 用地址的哈希生成，取负数，不会和网盘的 fs_id 冲突。
 */
class WebLibrary(context: Context, private val http: OkHttpClient) {

    private val cacheDir = File(context.cacheDir, "web_list").apply { mkdirs() }
    private val memCache = ConcurrentHashMap<String, Pair<Long, List<PanFile>>>()

    /** 列出节目或剧集。优先用缓存；服务器连不上时退回旧缓存。阻塞调用，请在 IO 线程中使用。 */
    fun listDir(dir: String, useCache: Boolean = true): List<PanFile> {
        val cached = memCache[dir] ?: readDiskCache(dir)?.also { memCache[dir] = it }
        if (useCache && cached != null && System.currentTimeMillis() - cached.first < CACHE_MS) {
            return cached.second
        }
        val fresh = try {
            fetch(dir)
        } catch (e: IOException) {
            if (cached != null) {
                Log.w(TAG, "list $dir failed, use stale cache: ${e.message}")
                return cached.second
            }
            throw e
        }
        putCache(dir, fresh)
        return fresh
    }

    fun clearCache() {
        memCache.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun fetch(dir: String): List<PanFile> {
        val url = jsonUrlOf(dir)
        val text = get(url).trim()
        return try {
            when {
                text.startsWith("[") -> parseSeries(url, JSONArray(text))
                text.startsWith("{") -> parseEpisodes(url, JSONObject(text))
                else -> throw IOException("服务器返回的不是节目列表")
            }
        } catch (e: JSONException) {
            throw IOException("节目列表格式不正确", e)
        }
    }

    /** 节目列表 → 目录（封面图作为缩略图） */
    private fun parseSeries(listUrl: String, arr: JSONArray): List<PanFile> {
        val base = listUrl.substringBefore('?').substringBeforeLast('/') + "/"
        val out = ArrayList<PanFile>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            val dirPath = "$base$id/list.json"
            out.add(
                PanFile(
                    fsId = idOf(dirPath),
                    path = dirPath,
                    name = o.optString("name").trim().ifEmpty { "节目 $id" },
                    isDir = true,
                    category = 0,
                    size = 0,
                    mtime = 0,
                    thumb = o.optString("imageUrl").trim().takeIf { it.isNotEmpty() }?.let { encodeUrl(it) },
                )
            )
        }
        return out
    }

    /** 剧集列表 → 视频（或音频）文件，保持服务器给出的顺序 */
    private fun parseEpisodes(listUrl: String, o: JSONObject): List<PanFile> {
        val arr = o.optJSONArray("episodes") ?: JSONArray()
        val listHttpUrl = listUrl.toHttpUrlOrNull()
        val out = ArrayList<PanFile>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val raw = e.optString("url").trim()
            if (raw.isEmpty()) continue
            // 允许写相对地址（相对于 list.json）
            val url = if (isWebPath(raw)) raw else listHttpUrl?.resolve(raw)?.toString() ?: continue
            val fileName = url.substringBefore('?').substringAfterLast('/')
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val title = e.optString("title").trim()
            val display = when {
                title.isEmpty() -> fileName.substringBeforeLast('.')
                title.all { it.isDigit() } -> "第${title.trimStart('0').ifEmpty { "0" }}集"
                else -> title
            }
            out.add(
                PanFile(
                    fsId = idOf(url),
                    path = url,
                    // 带上扩展名：用来识别视频 / 音频，显示时会去掉
                    name = if (ext.isNotEmpty()) "$display.$ext" else display,
                    isDir = false,
                    category = if (ext in PanFile.AUDIO_EXT) PanFile.CATEGORY_AUDIO else PanFile.CATEGORY_VIDEO,
                    size = 0,
                    mtime = 0,
                    thumb = null,
                )
            )
        }
        return out
    }

    private fun get(url: String): String {
        val httpUrl = encodeUrl(url).toHttpUrlOrNull() ?: throw IOException("地址不正确：$url")
        val req = Request.Builder().url(httpUrl).header("User-Agent", USER_AGENT).build()
        val resp = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw IOException("连不上视频服务器（${e.javaClass.simpleName}）", e)
        }
        resp.use {
            if (!it.isSuccessful) {
                throw IOException(if (it.code == 404) "服务器上没有这个节目（HTTP 404）" else "服务器返回错误（HTTP ${it.code}）")
            }
            return it.body?.string().orEmpty()
        }
    }

    private fun putCache(key: String, list: List<PanFile>) {
        val now = System.currentTimeMillis()
        memCache[key] = now to list
        try {
            File(cacheDir, md5(key)).writeText(
                JSONObject().put("time", now).put("list", JSONArray(PanFile.listToJson(list))).toString()
            )
        } catch (e: Exception) {
            Log.w(TAG, "write cache", e)
        }
    }

    private fun readDiskCache(key: String): Pair<Long, List<PanFile>>? = try {
        val f = File(cacheDir, md5(key))
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            o.getLong("time") to PanFile.listFromJson(o.getJSONArray("list").toString())
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "WebLibrary"
        /** 自己的服务器没有调用次数限制，缓存短一点，新加的节目能更快看到 */
        private const val CACHE_MS = 3600 * 1000L
        private const val USER_AGENT = "player4kid/${BuildConfig.VERSION_NAME}"

        /**
         * 默认的视频服务器：在 local.properties 里配置 web.defaultUrl / web.defaultName（见 app/build.gradle.kts），
         * 代码里不放任何服务器地址；没配置时没有默认服务器，家长可以在家长设置里添加。
         */
        val DEFAULT_SOURCES: List<WebSource> =
            if (BuildConfig.DEFAULT_WEB_URL.isBlank()) emptyList()
            else listOf(WebSource(BuildConfig.DEFAULT_WEB_NAME.ifBlank { "视频" }, BuildConfig.DEFAULT_WEB_URL))

        /** 是否是服务器上的地址（而不是网盘路径） */
        fun isWebPath(path: String): Boolean =
            path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)

        /** 目录路径 → 要请求的 json 地址；服务器地址本身对应 seriesList.json */
        fun jsonUrlOf(dir: String): String =
            if (dir.substringBefore('?').endsWith(".json", ignoreCase = true)) dir
            else dir.trimEnd('/') + "/seriesList.json"

        /** 把空格、中文、弯引号等字符转成 %xx（已经编码过的不会重复编码） */
        fun encodeUrl(url: String): String =
            url.trim().toHttpUrlOrNull()?.toString() ?: url.trim().replace(" ", "%20")

        /** 规范化家长输入的服务器地址；不正确时返回 null */
        fun normalizeSource(input: String): String? {
            var s = input.trim()
            if (s.isEmpty()) return null
            if (!isWebPath(s)) s = "http://$s"
            val url = s.toHttpUrlOrNull() ?: return null
            val str = url.toString()
            return if (str.substringBefore('?').endsWith(".json", ignoreCase = true)) str
            else str.trimEnd('/') + "/"
        }

        /** 由地址生成稳定的负数 id（用于记忆播放进度） */
        fun idOf(key: String): Long {
            val d = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xff)
            return -((v and Long.MAX_VALUE).coerceAtLeast(1))
        }

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
