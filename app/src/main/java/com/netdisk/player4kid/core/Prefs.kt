package com.netdisk.player4kid.core

import android.content.Context
import android.content.SharedPreferences
import com.netdisk.player4kid.BuildConfig
import com.netdisk.player4kid.web.WebLibrary
import com.netdisk.player4kid.web.WebSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * 所有家长设置、账号令牌都保存在这里。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---------------- 百度开放平台应用凭证 ----------------
    var appKey: String
        get() = sp.getString("app_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.BAIDU_APP_KEY
        set(v) = sp.edit().putString("app_key", v.trim()).apply()

    var secretKey: String
        get() = sp.getString("secret_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.BAIDU_SECRET_KEY
        set(v) = sp.edit().putString("secret_key", v.trim()).apply()

    // ---------------- 令牌 ----------------
    var accessToken: String
        get() = sp.getString("access_token", "") ?: ""
        set(v) = sp.edit().putString("access_token", v).apply()

    var refreshToken: String
        get() = sp.getString("refresh_token", "") ?: ""
        set(v) = sp.edit().putString("refresh_token", v).apply()

    /** access_token 过期的时间点（墙钟毫秒） */
    var tokenExpiresAt: Long
        get() = sp.getLong("token_expires_at", 0L)
        set(v) = sp.edit().putLong("token_expires_at", v).apply()

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v).apply()

    var vipType: Int
        get() = sp.getInt("vip_type", 0)
        set(v) = sp.edit().putInt("vip_type", v).apply()

    /** refresh_token 只能用一次，必须立即同步写入磁盘 */
    fun saveTokens(access: String, refresh: String, expiresAt: Long) {
        sp.edit()
            .putString("access_token", access)
            .putString("refresh_token", refresh)
            .putLong("token_expires_at", expiresAt)
            .commit()
    }

    val isLoggedIn: Boolean get() = refreshToken.isNotEmpty() || accessToken.isNotEmpty()

    fun clearAccount() {
        sp.edit().remove("access_token").remove("refresh_token").remove("token_expires_at")
            .remove("user_name").remove("vip_type").apply()
    }

    // ---------------- 资源目录 ----------------
    /**
     * 网盘资源目录：里面的每个文件夹都单独显示在桌面上（平铺），以后新建的文件夹也会自动出现。
     * 空字符串表示不使用。
     */
    var panHome: String
        get() = sp.getString("pan_home", null) ?: DEFAULT_PAN_HOME
        set(v) = sp.edit().putString("pan_home", v.trim().trimEnd('/').let { if (it.isEmpty()) "" else if (it.startsWith("/")) it else "/$it" }).apply()

    /** 单独添加的网盘目录（每个目录显示为桌面上的一张卡片） */
    var rootDirs: List<String>
        get() {
            val raw = sp.getString("root_dirs", null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(v) = sp.edit().putString("root_dirs", JSONArray(v.distinct()).toString()).apply()

    /** 视频服务器（旧版播放器的服务器格式）；没有设置过时用 local.properties 里配置的默认服务器（没配置就没有） */
    var webSources: List<WebSource>
        get() {
            val raw = sp.getString("web_sources", null) ?: return WebLibrary.DEFAULT_SOURCES
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    WebSource(o.optString("name"), o.optString("url"))
                }.filter { it.url.isNotBlank() }
            } catch (e: Exception) {
                WebLibrary.DEFAULT_SOURCES
            }
        }
        set(v) {
            val arr = JSONArray()
            v.distinctBy { it.url }.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
            sp.edit().putString("web_sources", arr.toString()).apply()
        }

    // ---------------- 时长限制（分钟/秒） ----------------
    /** 每天可看视频的总时长（分钟） */
    var dailyLimitMin: Int
        get() = sp.getInt("daily_limit_min", 60)
        set(v) = sp.edit().putInt("daily_limit_min", v.coerceIn(1, 24 * 60)).apply()

    /** 单次连续观看时长（分钟），到点强制休息 */
    var sessionLimitMin: Int
        get() = sp.getInt("session_limit_min", 20)
        set(v) = sp.edit().putInt("session_limit_min", v.coerceIn(1, 24 * 60)).apply()

    /** 休息时长（分钟） */
    var restMin: Int
        get() = sp.getInt("rest_min", 10)
        set(v) = sp.edit().putInt("rest_min", v.coerceIn(1, 24 * 60)).apply()

    /** 播放音频时，无操作多少秒后息屏 */
    var audioScreenOffSec: Int
        get() = sp.getInt("audio_screen_off_sec", 15)
        set(v) = sp.edit().putInt("audio_screen_off_sec", v.coerceIn(5, 600)).apply()

    // ---------------- 播放 ----------------
    /** 视频播放方式：stream_480 / stream_720 / stream_1080 / original */
    var videoMode: String
        get() = sp.getString("video_mode", MODE_STREAM_720) ?: MODE_STREAM_720
        set(v) = sp.edit().putString("video_mode", v).apply()

    /** 音频播放页的播放列表是否展开（记住上次的选择） */
    var audioPlaylistExpanded: Boolean
        get() = sp.getBoolean("audio_playlist_expanded", true)
        set(v) = sp.edit().putBoolean("audio_playlist_expanded", v).apply()

    // ---------------- 家长 / 桌面 ----------------
    var parentPin: String
        get() = sp.getString("parent_pin", "") ?: ""
        set(v) = sp.edit().putString("parent_pin", v).apply()

    /** 是否启用防退出（锁定任务 / 拉回前台） */
    var kioskEnabled: Boolean
        get() = sp.getBoolean("kiosk_enabled", true)
        set(v) = sp.edit().putBoolean("kiosk_enabled", v).apply()

    // ---------------- 播放进度记忆 ----------------
    private val posSp: SharedPreferences =
        context.getSharedPreferences("positions", Context.MODE_PRIVATE)

    fun savePosition(fsId: Long, positionMs: Long, durationMs: Long) {
        val e = posSp.edit()
        if (durationMs > 0 && positionMs > durationMs - 15_000) {
            e.remove(fsId.toString()) // 已看完，下次从头开始
        } else if (positionMs > 5_000) {
            e.putLong(fsId.toString(), positionMs)
        }
        e.apply()
        if (posSp.all.size > 500) posSp.edit().clear().apply()
    }

    fun getPosition(fsId: Long): Long = posSp.getLong(fsId.toString(), 0L)

    fun clearPosition(fsId: Long) = posSp.edit().remove(fsId.toString()).apply()

    companion object {
        /** 百度开放平台应用只能访问 /apps/应用名，资源都放在这里 */
        const val DEFAULT_PAN_HOME = "/apps/player4kid"

        const val MODE_STREAM_480 = "stream_480"
        const val MODE_STREAM_720 = "stream_720"
        const val MODE_STREAM_1080 = "stream_1080"
        const val MODE_ORIGINAL = "original"
    }
}
