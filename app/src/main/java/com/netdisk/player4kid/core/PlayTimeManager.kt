package com.netdisk.player4kid.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 视频观看时长管理：
 *  - 每日视频总时长（家长可配置，可临时加时）
 *  - 单次连续观看时长，到点强制休息
 *  - 休息结束后可继续观看，直到当日时长用完
 *
 * 只统计“视频真正在播放”的时间，音频不计时。
 * 停下来不计时，但也不算休息：连续观看时长只有强制休息后才重新计算。
 * 所有数据都保存在磁盘上，重启、重装、被系统杀掉都不会清零（看视频时每 5 秒保存一次）。
 */
class PlayTimeManager(context: Context, private val prefs: Prefs) {

    enum class Block { NONE, RESTING, EXHAUSTED }

    private val sp = context.getSharedPreferences("playtime", Context.MODE_PRIVATE)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var day: String = sp.getString("day", "") ?: ""
    private var usedTodayMs: Long = sp.getLong("used_today", 0L)
    private var bonusTodayMs: Long = sp.getLong("bonus_today", 0L)
    private var sessionMs: Long = sp.getLong("session", 0L)
    private var lastVideoAt: Long = sp.getLong("last_video_at", 0L)
    private var restEndAt: Long = sp.getLong("rest_end_at", 0L)

    init {
        // 每次程序启动（重启、重装、被系统杀掉后）都会从磁盘读回，方便在 logcat 里核对
        Log.i(TAG, "loaded: day=$day used=${usedTodayMs / 1000}s bonus=${bonusTodayMs / 1000}s " +
            "session=${sessionMs / 1000}s lastVideoAt=$lastVideoAt restEndAt=$restEndAt")
    }

    private var lastPersist = 0L

    private val restMs get() = prefs.restMin * 60_000L
    private val sessionLimitMs get() = prefs.sessionLimitMin * 60_000L
    val dailyLimitMs: Long get() = prefs.dailyLimitMin * 60_000L + bonusTodayMs

    @Synchronized
    private fun refresh() {
        val now = System.currentTimeMillis()
        val today = dayFormat.format(Date(now))
        if (day.isEmpty() || today > day) {
            // 新的一天：清零（时钟往回拨则不清零，防止改时间刷时长）
            day = today
            usedTodayMs = 0L
            bonusTodayMs = 0L
            sessionMs = 0L
            restEndAt = 0L
            persist(force = true)
        }
        // 时钟被往回拨：休息结束时间不能比“现在 + 休息时长”更晚
        if (restEndAt > now + restMs) {
            restEndAt = now + restMs
            persist(force = true)
        }
        // 注意：停下来（暂停、退出、关机、重启）不计时，但也不算休息——
        // 连续观看时长接着累计，只有强制休息结束、家长“结束休息 / 清零今日”或新的一天才重新计算。
    }

    @Synchronized
    fun usedTodayMs(): Long { refresh(); return usedTodayMs }

    @Synchronized
    fun dailyRemainingMs(): Long { refresh(); return (dailyLimitMs - usedTodayMs).coerceAtLeast(0L) }

    @Synchronized
    fun sessionRemainingMs(): Long {
        refresh()
        return (sessionLimitMs - sessionMs).coerceAtLeast(0L)
    }

    /** 本次还能看多久 = min(本次剩余, 今日剩余) */
    @Synchronized
    fun watchableNowMs(): Long = minOf(sessionRemainingMs(), dailyRemainingMs())

    /** 这次看完时，是今天的时间先用完（而不是要休息） */
    @Synchronized
    fun dayEndsFirst(): Boolean = dailyRemainingMs() <= sessionRemainingMs()

    @Synchronized
    fun restRemainingMs(): Long {
        refresh()
        return (restEndAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    @Synchronized
    fun videoBlock(): Block {
        if (dailyRemainingMs() <= 0) return Block.EXHAUSTED
        if (restRemainingMs() > 0) return Block.RESTING
        return Block.NONE
    }

    /**
     * 视频播放中每隔一段时间调用一次，累计观看时长。
     * @return 累加之后的状态；返回 RESTING 时已自动开始休息。
     */
    @Synchronized
    fun addVideoTime(deltaMs: Long): Block {
        refresh()
        if (deltaMs > 0) {
            usedTodayMs += deltaMs
            sessionMs += deltaMs
            lastVideoAt = System.currentTimeMillis()
            persist()
        }
        if (usedTodayMs >= dailyLimitMs) {
            persist(force = true)
            return Block.EXHAUSTED
        }
        if (sessionMs >= sessionLimitMs) {
            startRest()
            return Block.RESTING
        }
        return Block.NONE
    }

    @Synchronized
    fun startRest() {
        restEndAt = System.currentTimeMillis() + restMs
        sessionMs = 0L
        persist(force = true)
    }

    // ---------------- 家长操作 ----------------
    @Synchronized
    fun endRestNow() {
        restEndAt = 0L
        sessionMs = 0L
        persist(force = true)
    }

    @Synchronized
    fun addBonusMinutes(min: Int) {
        refresh()
        bonusTodayMs += min * 60_000L
        persist(force = true)
    }

    @Synchronized
    fun resetToday() {
        refresh()
        usedTodayMs = 0L
        bonusTodayMs = 0L
        sessionMs = 0L
        restEndAt = 0L
        persist(force = true)
    }

    @Synchronized
    fun flush() = persist(force = true)

    /**
     * 保存到磁盘。看视频时每 5 秒保存一次：重启 / 重装 / 断电 / 被系统杀掉，最多少记最后几秒。
     * @param force 关键时刻（开始休息、新的一天、家长操作、离开播放页）：立即同步写盘，返回时已经写好
     */
    private fun persist(force: Boolean = false) {
        val t = SystemClock.elapsedRealtime()
        if (!force && t - lastPersist < 5_000) return
        lastPersist = t
        val e = sp.edit()
            .putString("day", day)
            .putLong("used_today", usedTodayMs)
            .putLong("bonus_today", bonusTodayMs)
            .putLong("session", sessionMs)
            .putLong("last_video_at", lastVideoAt)
            .putLong("rest_end_at", restEndAt)
        if (force) e.commit() else e.apply()
    }

    companion object {
        private const val TAG = "PlayTime"

        /** mm:ss，只用于视频进度（“看到 12:30”）和休息页的大倒计时；给孩子的剩余时间提示用 [TimeTexts] */
        fun formatMinSec(ms: Long): String {
            val totalSec = (ms + 999) / 1000
            return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
        }
    }
}
