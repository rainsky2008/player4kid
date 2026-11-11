package com.netdisk.player4kid.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 首页卡片上的文件夹名：去掉排序用的编号，但不能误伤名字里本来就有的数字 */
class KidNamesTest {

    private fun check(input: String, expected: String) = assertEquals(input, expected, KidNames.folder(input))

    @Test
    fun stripsOrderingPrefix() {
        // 平板上真实的文件夹名
        check("01小猪佩奇1-9", "小猪佩奇1-9")
        check("02.Journey to the West（108篇完结）", "Journey to the West（108篇完结）")
        check("003 视频", "视频")
        check("003-Black cat 音频", "Black cat 音频")
        check("009 小鬼当家 1~10", "小鬼当家 1~10")
        check("10.植物的秘密（给孩子的第一堂植物课）完结", "植物的秘密（给孩子的第一堂植物课）完结")
        check("11、动画视频", "动画视频")
        check("13、科普孩子喜欢的宇宙运行课（共计12节课）", "科普孩子喜欢的宇宙运行课（共计12节课）")
        check("1_Peppa", "Peppa")
    }

    @Test
    fun keepsNumbersThatArePartOfTheName() {
        check("50部经典英文动画电影", "50部经典英文动画电影")
        check("2021.7.29 风靡30多年的《神奇校车》", "2021.7.29 风靡30多年的《神奇校车》")
        check("5 Little Monkeys", "5 Little Monkeys")
        check("3只小猪", "3只小猪")
        check("1.5岁绘本", "1.5岁绘本")
        check("BBC I can cook", "BBC I can cook")
        check("CURIOUS GEORGE S07", "CURIOUS GEORGE S07")
        check("E171 X小毛人", "E171 X小毛人")
        check("四季中国", "四季中国")
        check("007", "007")
        check("", "")
    }
}
