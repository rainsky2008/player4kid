package com.netdisk.player4kid.baidu

import android.content.Context
import android.os.Build
import android.util.Log
import com.netdisk.player4kid.BuildConfig
import com.netdisk.player4kid.core.Prefs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 百度网盘开放平台（xpan）接口。所有方法均为阻塞调用，请在 IO 线程中使用。
 *
 * 文档：https://pan.baidu.com/union/doc/
 *
 * 注意：
 *  - 未审核的应用调用次数很少，所以目录列表会缓存到磁盘（默认 6 小时），接口出错时也会退回到旧缓存；
 *  - 新建的应用可能只能访问 /apps/{应用名}（网盘里显示为“我的应用数据/应用名”）。
 */
class BaiduApi(
    context: Context,
    private val prefs: Prefs,
    private val http: OkHttpClient,
) {
    class ApiException(val errno: Int, message: String) : IOException(message)

    private val cacheDir = File(context.cacheDir, "pan_list").apply { mkdirs() }

    // ======================================================================== 授权（设备码模式）

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val qrcodeUrl: String,
        val expiresIn: Int,
        val interval: Int,
    )

    sealed class PollResult {
        object Success : PollResult()
        object Pending : PollResult()
        object SlowDown : PollResult()
        data class Fatal(val message: String) : PollResult()
        data class Error(val message: String) : PollResult()
    }

    fun requestDeviceCode(): DeviceCode {
        checkAppKey()
        val url = "$OAUTH/device/code".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "device_code")
            .addQueryParameter("client_id", prefs.appKey)
            .addQueryParameter("scope", "basic,netdisk")
            .build()
        val o = getRaw(url)
        if (o.has("error")) throw ApiException(-1, oauthError(o))
        return DeviceCode(
            deviceCode = o.getString("device_code"),
            userCode = o.optString("user_code"),
            verificationUrl = o.optString("verification_url", "https://openapi.baidu.com/device"),
            qrcodeUrl = o.optString("qrcode_url"),
            expiresIn = o.optInt("expires_in", 300),
            interval = o.optInt("interval", 5).coerceAtLeast(5),
        )
    }

    fun pollDeviceToken(deviceCode: String): PollResult {
        val url = "$OAUTH/token".toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", "device_token")
            .addQueryParameter("code", deviceCode)
            .addQueryParameter("client_id", prefs.appKey)
            .addQueryParameter("client_secret", prefs.secretKey)
            .build()
        val o = try {
            getRaw(url)
        } catch (e: IOException) {
            return PollResult.Error(e.message ?: "网络错误")
        }
        if (o.has("access_token")) {
            saveToken(o)
            return PollResult.Success
        }
        return when (o.optString("error")) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "authorization_declined" -> PollResult.Fatal("已拒绝授权")
            "expired_token", "invalid_grant" -> PollResult.Fatal("二维码已失效")
            "invalid_client" -> PollResult.Fatal("AppKey 或 SecretKey 不正确")
            else -> PollResult.Error(oauthError(o))
        }
    }

    /**
     * 刷新 access_token。refresh_token 只能用一次，所以：
     *  - 同一时间只允许一个刷新（synchronized）
     *  - 如果别的线程已经刷新过（token 已经变了），直接返回
     */
    @Synchronized
    private fun refreshToken(staleToken: String?): Boolean {
        if (staleToken != null && staleToken != prefs.accessToken && prefs.accessToken.isNotEmpty()) {
            return true // 已经被别的请求刷新过
        }
        val rt = prefs.refreshToken
        if (rt.isEmpty()) return false
        val url = "$OAUTH/token".toHttpUrl().newBuilder()
            .addQueryParameter("grant_type", "refresh_token")
            .addQueryParameter("refresh_token", rt)
            .addQueryParameter("client_id", prefs.appKey)
            .addQueryParameter("client_secret", prefs.secretKey)
            .build()
        val o = getRaw(url)
        if (!o.has("access_token")) {
            Log.w(TAG, "refresh token failed: $o")
            val err = o.optString("error")
            if (err == "invalid_grant" || err == "expired_token") {
                // refresh_token 已失效，只能重新扫码
                prefs.accessToken = ""
                prefs.refreshToken = ""
            }
            return false
        }
        saveToken(o)
        return true
    }

    private fun saveToken(o: JSONObject) {
        val expiresIn = o.optLong("expires_in", 30L * 24 * 3600)
        prefs.saveTokens(
            access = o.getString("access_token"),
            refresh = o.optString("refresh_token").ifEmpty { prefs.refreshToken },
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
        )
    }

    /** 获取有效 access_token，快过期（< 1 天）时自动刷新 */
    @Synchronized
    fun token(): String {
        if (!prefs.isLoggedIn) throw ApiException(ERR_NOT_LOGIN, "尚未登录百度网盘，请家长扫码登录")
        val leftMs = prefs.tokenExpiresAt - System.currentTimeMillis()
        if (prefs.accessToken.isEmpty() || leftMs < 24L * 3600 * 1000) {
            try {
                refreshToken(null)
            } catch (e: IOException) {
                Log.w(TAG, "refresh token error", e)
            }
        }
        if (prefs.accessToken.isEmpty()) throw ApiException(ERR_NOT_LOGIN, "登录已失效，请家长重新扫码登录")
        return prefs.accessToken
    }

    // ======================================================================== 网盘

    /** 用户信息，同时写入 prefs */
    fun userInfo(): JSONObject {
        val o = getPan { t ->
            "$PAN/rest/2.0/xpan/nas".toHttpUrl().newBuilder()
                .addQueryParameter("method", "uinfo")
                .addQueryParameter("access_token", t)
                .build()
        }
        prefs.userName = o.optString("baidu_name").ifEmpty { o.optString("netdisk_name") }
        prefs.vipType = o.optInt("vip_type")
        return o
    }

    private val memCache = ConcurrentHashMap<String, Pair<Long, List<PanFile>>>()

    /**
     * 列出目录内容（自动翻页），目录在前，自然排序。
     * 优先使用缓存；接口失败（如超出调用次数）时使用旧缓存。
     */
    fun listDir(dir: String, foldersOnly: Boolean = false, useCache: Boolean = true): List<PanFile> {
        val key = "$foldersOnly|$dir"
        val cached = memCache[key] ?: readDiskCache(key)?.also { memCache[key] = it }
        if (useCache && cached != null && System.currentTimeMillis() - cached.first < LIST_CACHE_MS) {
            return cached.second
        }
        val fresh = try {
            fetchDir(dir, foldersOnly)
        } catch (e: IOException) {
            if (cached != null && (e !is ApiException || e.errno != ERR_NOT_LOGIN)) {
                Log.w(TAG, "listDir failed, use stale cache: ${e.message}")
                return cached.second
            }
            throw e
        }
        putCache(key, fresh)
        return fresh
    }

    /** 用补充了缩略图的列表更新缓存 */
    fun updateDirCache(dir: String, list: List<PanFile>) = putCache("false|$dir", list)

    private fun fetchDir(dir: String, foldersOnly: Boolean): List<PanFile> {
        val result = ArrayList<PanFile>()
        var start = 0
        while (true) {
            val o = getPan { t ->
                "$PAN/rest/2.0/xpan/file".toHttpUrl().newBuilder()
                    .addQueryParameter("method", "list")
                    .addQueryParameter("access_token", t)
                    .addQueryParameter("dir", dir)
                    .addQueryParameter("order", "name")
                    .addQueryParameter("start", start.toString())
                    .addQueryParameter("limit", PAGE.toString())
                    .addQueryParameter("web", "1")
                    .addQueryParameter("folder", if (foldersOnly) "1" else "0")
                    .build()
            }
            val arr = o.optJSONArray("list") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val f = PanFile.fromJson(arr.getJSONObject(i))
                // folder=1 时只返回 path，补上名字
                result.add(if (f.name.isEmpty()) f.copy(name = f.path.substringAfterLast('/')) else f)
            }
            if (arr.length() < PAGE) break
            start += arr.length()
        }
        return result.sortedWith(PanFile.NATURAL_ORDER)
    }

    private fun putCache(key: String, list: List<PanFile>) {
        val now = System.currentTimeMillis()
        memCache[key] = now to list
        try {
            File(cacheDir, hash(key)).writeText(
                JSONObject().put("time", now).put("list", JSONArray(PanFile.listToJson(list))).toString()
            )
        } catch (e: Exception) {
            Log.w(TAG, "write cache", e)
        }
    }

    private fun readDiskCache(key: String): Pair<Long, List<PanFile>>? = try {
        val f = File(cacheDir, hash(key))
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            // 旧版本写的缓存也按现在的规则重新排一次
            o.getLong("time") to PanFile.listFromJson(o.getJSONArray("list").toString())
                .sortedWith(PanFile.NATURAL_ORDER)
        }
    } catch (e: Exception) {
        null
    }

    fun clearListCache() {
        memCache.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private val dlinkCache = ConcurrentHashMap<Long, Pair<Long, String>>()

    /** 批量查询文件信息（含下载地址 dlink，有效期 8 小时；每次最多 100 个） */
    fun fileMetas(fsIds: List<Long>): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fsIds.chunked(100).forEach { chunk ->
            val o = getPan { t ->
                "$PAN/rest/2.0/xpan/multimedia".toHttpUrl().newBuilder()
                    .addQueryParameter("method", "filemetas")
                    .addQueryParameter("access_token", t)
                    .addQueryParameter("fsids", JSONArray(chunk).toString())
                    .addQueryParameter("dlink", "1")
                    .addQueryParameter("thumb", "1")
                    .build()
            }
            val arr = o.optJSONArray("list") ?: JSONArray()
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val link = item.optString("dlink")
                if (link.isNotEmpty()) dlinkCache[item.optLong("fs_id")] = now to link
                out.add(item)
            }
        }
        return out
    }

    fun invalidateDlink(fsId: Long) {
        dlinkCache.remove(fsId)
    }

    /** 可直接请求的下载地址（需要 UA: pan.baidu.com，会 302 跳转到 CDN，支持 Range） */
    fun downloadUrl(fsId: Long): String {
        val cached = dlinkCache[fsId]
        val link = if (cached != null && System.currentTimeMillis() - cached.first < DLINK_TTL_MS) {
            cached.second
        } else {
            fileMetas(listOf(fsId))
            dlinkCache[fsId]?.second ?: throw ApiException(-9, "获取播放地址失败")
        }
        val sep = if (link.contains('?')) "&" else "?"
        return "$link${sep}access_token=${token()}"
    }

    /**
     * 获取转码后的 m3u8 播放列表。
     *
     * 非会员首次请求返回 errno=133 和 adToken/ltime，需要等待 ltime 秒（广告时间）后
     * 带上 adToken 再请求；会员直接返回 m3u8。失败时也是 HTTP 200 + JSON。
     *
     * @return Pair(请求地址, m3u8 文本)
     */
    fun fetchStreamPlaylist(path: String, type: String): Pair<String, String> {
        val ua = streamUserAgent()
        var adToken: String? = null
        var refreshed = false
        var transcodingRetries = 0
        while (true) {
            val usedToken = token()
            val url = buildStreamUrl(usedToken, path, type, adToken)
            val body = fetchText(url, ua)
            if (body.trimStart().startsWith("#EXTM3U")) return url to body
            val o = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw ApiException(-1, "转码接口返回异常")
            }
            val errno = errnoOf(o)
            when {
                errno == 133 && adToken == null -> {
                    adToken = o.optString("adToken")
                    val waitSec = o.optInt("ltime", o.optInt("adTime", 0)).coerceIn(0, 60)
                    Log.i(TAG, "stream: wait ad ${waitSec}s")
                    if (waitSec > 0) Thread.sleep(waitSec * 1000L)
                }
                errno in TOKEN_ERRORS && !refreshed -> {
                    refreshed = true
                    if (!refreshToken(usedToken)) throw ApiException(ERR_NOT_LOGIN, "登录已失效，请家长重新扫码登录")
                }
                errno == 31341 && transcodingRetries < 5 -> { // 正在转码
                    transcodingRetries++
                    Thread.sleep(3000)
                }
                else -> throw ApiException(errno, "转码播放失败：" + errnoMessage(errno, o))
            }
        }
    }

    private fun buildStreamUrl(token: String, path: String, type: String, adToken: String?): String {
        val b = "$PAN/rest/2.0/xpan/file".toHttpUrl().newBuilder()
            .addQueryParameter("method", "streaming")
            .addQueryParameter("access_token", token)
            .addQueryParameter("path", path)
            .addQueryParameter("type", type)
        if (!adToken.isNullOrEmpty()) b.addQueryParameter("adToken", adToken)
        return b.build().toString()
    }

    // ======================================================================== HTTP

    private fun getPan(buildUrl: (token: String) -> HttpUrl): JSONObject {
        var usedToken = token()
        var o = getRaw(buildUrl(usedToken))
        var errno = errnoOf(o)
        if (errno in TOKEN_ERRORS) {
            if (refreshToken(usedToken)) {
                usedToken = token()
                o = getRaw(buildUrl(usedToken))
                errno = errnoOf(o)
            } else {
                throw ApiException(ERR_NOT_LOGIN, "登录已失效，请家长重新扫码登录")
            }
        }
        if (errno == 20017) {
            prefs.saveTokens("", "", 0)
            throw ApiException(ERR_NOT_LOGIN, "授权已被取消，请家长重新扫码登录")
        }
        if (errno != 0) throw ApiException(errno, errnoMessage(errno, o))
        return o
    }

    private fun errnoOf(o: JSONObject): Int = when {
        o.has("errno") -> o.optInt("errno", 0)
        o.has("error_code") -> o.optInt("error_code", 0)
        else -> 0
    }

    private fun getRaw(url: HttpUrl): JSONObject {
        val req = Request.Builder().url(url).header("User-Agent", UA_DEFAULT).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IOException("服务器返回异常(HTTP ${resp.code})")
            }
        }
    }

    private fun fetchText(url: String, ua: String): String {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        http.newCall(req).execute().use { resp -> return resp.body?.string().orEmpty() }
    }

    private fun checkAppKey() {
        if (prefs.appKey.isBlank() || prefs.secretKey.isBlank()) {
            throw ApiException(-1, "请先在家长设置中填写百度开放平台的 AppKey 和 SecretKey")
        }
    }

    private fun oauthError(o: JSONObject): String {
        val e = o.optString("error")
        val d = o.optString("error_description")
        return when (e) {
            "invalid_client" -> "AppKey 或 SecretKey 不正确"
            "expired_token" -> "二维码已过期"
            else -> if (d.isNotEmpty()) "$e: $d" else e.ifEmpty { "授权失败" }
        }
    }

    private fun errnoMessage(errno: Int, o: JSONObject): String {
        val m = when (errno) {
            -7, 20020 -> "没有访问这个目录的权限（应用可能只能访问“我的应用数据”下的应用目录）"
            -9, 31066 -> "文件或目录不存在"
            2, 31023 -> "参数错误"
            6 -> "不允许接入用户数据"
            31034, 20012 -> "调用太频繁或超出接口次数限制，请稍后再试"
            20011 -> "应用未审核，授权用户数已满"
            20013 -> "应用没有该接口权限"
            31326 -> "下载被拒绝（UA 校验失败）"
            31360 -> "播放地址已过期"
            31304 -> "不支持的视频格式"
            31338 -> "视频码率太高，无法转码"
            31346 -> "视频转码失败"
            31347 -> "视频太长，无法转码"
            31301 -> "不是音视频文件"
            else -> o.optString("errmsg").ifEmpty { o.optString("error_msg").ifEmpty { "接口错误" } }
        }
        return "$m(errno=$errno)"
    }

    fun streamUserAgent(): String =
        "xpanvideo;$APP_NAME;${BuildConfig.VERSION_NAME};android-android;${Build.VERSION.RELEASE};ts"

    private fun hash(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "BaiduApi"
        private const val OAUTH = "https://openapi.baidu.com/oauth/2.0"
        private const val PAN = "https://pan.baidu.com"
        private const val PAGE = 1000
        private const val LIST_CACHE_MS = 6 * 3600 * 1000L
        private const val DLINK_TTL_MS = 7 * 3600 * 1000L
        private const val APP_NAME = "netdisk-player4kid"

        const val UA_DEFAULT = "pan.baidu.com"
        const val ERR_NOT_LOGIN = -100

        /** 需要刷新 access_token 的错误码 */
        private val TOKEN_ERRORS = setOf(-6, 111, 110, 20016, 31045)

        fun streamVideoType(mode: String) = when (mode) {
            Prefs.MODE_STREAM_480 -> "M3U8_AUTO_480"
            Prefs.MODE_STREAM_1080 -> "M3U8_AUTO_1080"
            else -> "M3U8_AUTO_720"
        }
    }
}
