package com.netdisk.player4kid.baidu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 文件名排序：数字按数值比较，补零（001、05）和不补零（1、5）混在一起也要排对 */
class NaturalOrderTest {

    private val cmp = Comparator<String> { a, b -> PanFile.naturalCompare(a, b) }

    /** 打乱很多次再排序，结果都应该等于 expected */
    private fun assertSorts(expected: List<String>) {
        assertEquals(expected, expected.reversed().sortedWith(cmp))
        repeat(50) { seed ->
            assertEquals(expected, expected.shuffled(Random(seed)).sortedWith(cmp))
        }
    }

    @Test
    fun zeroPadded() = assertSorts(listOf("001", "002", "003", "009", "010", "011", "099", "100"))

    @Test
    fun mixedPadding() = assertSorts(listOf("1", "2", "3", "4", "05", "06", "09", "10", "11", "12"))

    @Test
    fun withExtension() = assertSorts(
        listOf("1.mp3", "2.mp3", "3.mp3", "4.mp3", "05.mp3", "06.mp3", "09.mp3", "10.mp3", "11.mp3", "12.mp3")
    )

    @Test
    fun withPrefixAndSuffix() {
        assertSorts(listOf("第1集.mp4", "第2集.mp4", "第05集.mp4", "第09集.mp4", "第10集.mp4", "第11集.mp4"))
        assertSorts(listOf("Book 1 Level C.mp3", "Book 2 Level B.mp3", "Book 09 Level A.mp3", "Book 10 Level A.mp3"))
        assertSorts(listOf("S01E01.mkv", "S01E02.mkv", "S01E10.mkv", "S02E01.mkv", "S10E01.mkv"))
        assertSorts(listOf("001 The Monkey.mp4", "002 The Cave.mp4", "009 Fire.mp4", "010 Pigsy.mp4", "108 Rewards.mp4"))
    }

    @Test
    fun caseInsensitive() = assertSorts(listOf("apple 1", "Apple 2", "banana 1", "BANANA 3"))

    @Test
    fun fullWidthDigits() = assertSorts(listOf("第１集", "第2集", "第０３集", "第10集"))

    @Test
    fun samePaddingTie() {
        // 数值相同、补零不同：顺序固定（少补零的在前），并且比较结果前后一致
        assertSorts(listOf("5.mp3", "05.mp3", "005.mp3", "6.mp3"))
        assertTrue(PanFile.naturalCompare("05", "5") > 0)
        assertTrue(PanFile.naturalCompare("5", "05") < 0)
    }

    @Test
    fun foldersFirst() {
        fun f(name: String, dir: Boolean) = PanFile(0, "/$name", name, dir, 0, 0, 0, null)
        val list = listOf(f("10.mp3", false), f("2", true), f("1.mp3", false), f("10", true), f("05.mp3", false))
        assertEquals(
            listOf("2", "10", "1.mp3", "05.mp3", "10.mp3"),
            list.sortedWith(PanFile.NATURAL_ORDER).map { it.name },
        )
    }
}
