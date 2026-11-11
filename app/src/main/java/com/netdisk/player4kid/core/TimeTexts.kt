package com.netdisk.player4kid.core

import com.netdisk.player4kid.App

/**
 * 给孩子看的时间提示。首页、目录、播放页、休息页都用这里的文字，保证数字和说法一致，
 * 让孩子有合理的预期：
 *  - 能看的时间向下取整（18 分 39 秒 → “18 分钟”），不会多说；不到 1 分钟显示秒；
 *  - 要等的时间（休息）向上取整（7 分 30 秒 → “8 分钟”），不会少说；
 *  - 说清楚看完以后会怎样：要休息，还是今天的视频就看完了。
 */
object TimeTexts {

    private val pt get() = App.playTime
    private val prefs get() = App.prefs

    /** 还能看的时长：整分钟向下取整；不到 1 分钟显示秒 */
    fun allowance(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return if (sec >= 60) minutesText(sec / 60) else "$sec 秒"
    }

    /** 还要等的时长：整分钟向上取整；不到 1 分钟显示秒 */
    fun waiting(ms: Long): String {
        val sec = ((ms + 999) / 1000).coerceAtLeast(0)
        return if (sec >= 60) minutesText((sec + 59) / 60) else "$sec 秒"
    }

    /** 90 → “1 小时 30 分钟”，60 → “1 小时”，35 → “35 分钟” */
    private fun minutesText(min: Long): String = when {
        min < 60 -> "$min 分钟"
        min % 60 == 0L -> "${min / 60} 小时"
        else -> "${min / 60} 小时 ${min % 60} 分钟"
    }

    /** 播放页的精确倒计时：18 分 39 秒（不用 18:39，免得看成钟点） */
    fun allowancePrecise(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return if (sec >= 60) "${sec / 60} 分 ${sec % 60} 秒" else "$sec 秒"
    }

    /** 这次看完以后会怎样 */
    fun afterThis(): String =
        if (pt.dayEndsFirst()) "然后今天的视频就看完了" else "然后要休息 ${prefs.restMin} 分钟"

    /** 第一行：今天还能看多少 */
    fun today(): String {
        val daily = pt.dailyRemainingMs()
        return if (daily <= 0) "今天的视频时间用完啦 🌙" else "今天还能看 ${allowance(daily)}视频"
    }

    /** 第二行：这次还能看多少、之后会怎样（休息中 / 用完时说明原因） */
    fun thisTime(): String {
        val daily = pt.dailyRemainingMs()
        val rest = pt.restRemainingMs()
        return when {
            daily <= 0 -> "明天再来看吧，现在可以听故事和儿歌哦"
            rest > 0 -> "正在休息，还要 ${waiting(rest)}才能看视频 · 可以听音频"
            else -> "这次还能看 ${allowance(pt.watchableNowMs())}，${afterThis()}"
        }
    }

    /** 目录页的一行状态 */
    fun browseLine(): String {
        val daily = pt.dailyRemainingMs()
        if (daily <= 0) return "今天的视频时间用完啦，可以听音频哦"
        return "今天还能看 ${allowance(daily)} · ${thisTime()}"
    }

    /** 播放页右上角：还能看 18:39（秒向下取整，分钟数和首页 / 目录页说的“18 分钟”一致） */
    fun playerLine(): String = "还能看 ${countdown(pt.watchableNowMs())}"

    /** mm:ss，秒向下取整 */
    fun countdown(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(sec / 60, sec % 60)
    }

    /** 播放页最后一分钟的提醒 */
    fun playerWarning(): String {
        val sec = (pt.watchableNowMs() + 999) / 1000
        return if (pt.dayEndsFirst()) "今天的视频时间还剩 $sec 秒" else "还有 $sec 秒就要休息啦 👀"
    }

    /** 休息页：休息好以后能看多久 */
    fun afterRest(): String {
        val daily = pt.dailyRemainingMs()
        if (daily <= 0) return "今天的视频时间已经用完了"
        val next = minOf(prefs.sessionLimitMin * 60_000L, daily)
        return "今天还能看 ${allowance(daily)}，休息好以后这次可以看 ${allowance(next)}"
    }
}
