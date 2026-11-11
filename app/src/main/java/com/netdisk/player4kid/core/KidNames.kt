package com.netdisk.player4kid.core

/** 给孩子看的名字 */
object KidNames {

    /**
     * 给孩子看的文件夹名：去掉家长用来排序的编号前缀（排序仍按原名）。
     *   “02.Journey to the West” → “Journey to the West”，“009 小鬼当家” → “小鬼当家”，
     *   “11、动画视频” → “动画视频”，“01小猪佩奇” → “小猪佩奇”；
     *   “50部经典动画”“5 Little Monkeys”“2021.7.29 …” 这种数字是名字的一部分，保留。
     * 规则：1~3 位数字，后面跟着 . 、 - _ 等分隔符，或者数字以 0 开头（补零编号）。
     */
    fun folder(name: String): String {
        val m = Regex("""^(\d{1,3})([\s._、，,\-]*)""").find(name) ?: return name
        val digits = m.groupValues[1]
        val sep = m.groupValues[2]
        val rest = name.substring(m.value.length).trim()
        if (rest.isEmpty() || rest.first().isDigit()) return name
        val numbered = sep.any { !it.isWhitespace() } || (digits.length > 1 && digits.startsWith("0"))
        return if (numbered) rest else name
    }
}
