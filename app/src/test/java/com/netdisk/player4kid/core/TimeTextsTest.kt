package com.netdisk.player4kid.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 给孩子看的时间：能看的向下取整（不多说），要等的向上取整（不少说） */
class TimeTextsTest {

    private fun ms(min: Int, sec: Int) = (min * 60L + sec) * 1000

    @Test
    fun allowanceRoundsDown() {
        assertEquals("18 分钟", TimeTexts.allowance(ms(18, 39)))
        assertEquals("18 分钟", TimeTexts.allowance(ms(18, 59) + 999))
        assertEquals("19 分钟", TimeTexts.allowance(ms(19, 0)))
        assertEquals("1 分钟", TimeTexts.allowance(ms(1, 0)))
        assertEquals("59 秒", TimeTexts.allowance(ms(0, 59) + 999))
        assertEquals("0 秒", TimeTexts.allowance(0))
        assertEquals("0 秒", TimeTexts.allowance(-5000))
    }

    @Test
    fun allowanceHours() {
        assertEquals("1 小时", TimeTexts.allowance(ms(60, 30)))
        assertEquals("1 小时 30 分钟", TimeTexts.allowance(ms(90, 59)))
        assertEquals("59 分钟", TimeTexts.allowance(ms(59, 59)))
    }

    @Test
    fun waitingRoundsUp() {
        assertEquals("8 分钟", TimeTexts.waiting(ms(7, 30)))
        assertEquals("8 分钟", TimeTexts.waiting(ms(7, 0) + 1))
        assertEquals("7 分钟", TimeTexts.waiting(ms(7, 0)))
        assertEquals("1 分钟", TimeTexts.waiting(ms(0, 60)))
        assertEquals("30 秒", TimeTexts.waiting(ms(0, 29) + 1))
    }

    @Test
    fun precise() {
        assertEquals("18 分 39 秒", TimeTexts.allowancePrecise(ms(18, 39) + 900))
        assertEquals("1 分 0 秒", TimeTexts.allowancePrecise(ms(1, 0)))
        assertEquals("45 秒", TimeTexts.allowancePrecise(ms(0, 45)))
    }

    @Test
    fun countdownRoundsDown() {
        assertEquals("18:39", TimeTexts.countdown(ms(18, 39) + 999))
        assertEquals("00:59", TimeTexts.countdown(ms(0, 59) + 999))
        assertEquals("00:00", TimeTexts.countdown(-1))
    }

    @Test
    fun countdownAgreesWithAllowance() {
        // 首页 / 目录说的“X 分钟”，和播放页“还能看 XX:YY”的分钟数永远相同
        for (s in 60L..3599L step 7) {
            val t = s * 1000 + 999
            assertEquals(TimeTexts.allowance(t).removeSuffix(" 分钟").toInt(), TimeTexts.countdown(t).substringBefore(':').toInt())
        }
    }

    @Test
    fun preciseAgreesWithAllowance() {
        // 首页 / 目录说的“X 分钟”，和播放页“X 分 Y 秒”的 X 永远相同
        for (s in 0L..7200L step 7) {
            val t = s * 1000 + 500
            val minutes = TimeTexts.allowance(t)
            val precise = TimeTexts.allowancePrecise(t)
            if (s >= 60 && s < 3600) {
                assertEquals(minutes.removeSuffix(" 分钟"), precise.substringBefore(" 分 "))
            }
        }
    }
}
