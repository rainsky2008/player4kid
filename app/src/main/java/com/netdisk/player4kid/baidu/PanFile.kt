package com.netdisk.player4kid.baidu

import org.json.JSONArray
import org.json.JSONObject

/** 网盘中的一个文件 / 目录 */
data class PanFile(
    val fsId: Long,
    val path: String,
    val name: String,
    val isDir: Boolean,
    val category: Int,
    val size: Long,
    val mtime: Long,
    val thumb: String?,
) {
    enum class Kind { DIR, VIDEO, AUDIO, OTHER }

    val ext: String get() = name.substringAfterLast('.', "").lowercase()

    val kind: Kind
        get() = when {
            isDir -> Kind.DIR
            ext in VIDEO_EXT -> Kind.VIDEO
            ext in AUDIO_EXT -> Kind.AUDIO
            category == CATEGORY_VIDEO -> Kind.VIDEO
            category == CATEGORY_AUDIO -> Kind.AUDIO
            else -> Kind.OTHER
        }

    /** 去掉扩展名的显示名 */
    val displayName: String
        get() = if (isDir || !name.contains('.')) name else name.substringBeforeLast('.')

    fun toJson(): JSONObject = JSONObject()
        .put("fs_id", fsId)
        .put("path", path)
        .put("server_filename", name)
        .put("isdir", if (isDir) 1 else 0)
        .put("category", category)
        .put("size", size)
        .put("server_mtime", mtime)
        .put("thumb", thumb ?: "")

    companion object {
        const val CATEGORY_VIDEO = 1
        const val CATEGORY_AUDIO = 2

        val VIDEO_EXT = setOf(
            "mp4", "m4v", "mkv", "avi", "mov", "flv", "f4v", "wmv", "rmvb", "rm", "ts", "m2ts",
            "mts", "3gp", "webm", "mpg", "mpeg", "vob", "asf"
        )
        val AUDIO_EXT = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "ape", "amr", "m4b"
        )

        /** 解析接口返回的条目（list / filemetas 字段基本一致） */
        fun fromJson(o: JSONObject): PanFile {
            val path = o.optString("path")
            val name = o.optString("server_filename").ifEmpty {
                o.optString("filename").ifEmpty { path.substringAfterLast('/') }
            }
            var thumb: String? = o.optString("thumb").takeIf { it.isNotEmpty() }
            o.optJSONObject("thumbs")?.let { t ->
                thumb = listOf("url3", "url2", "url1", "icon")
                    .map { t.optString(it) }.firstOrNull { it.isNotEmpty() } ?: thumb
            }
            return PanFile(
                fsId = o.optLong("fs_id"),
                path = path,
                name = name,
                isDir = o.optInt("isdir") == 1,
                category = o.optInt("category"),
                size = o.optLong("size"),
                mtime = o.optLong("server_mtime"),
                thumb = thumb,
            )
        }

        fun listToJson(list: List<PanFile>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): List<PanFile> {
            if (s.isNullOrEmpty()) return emptyList()
            return try {
                val arr = JSONArray(s)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }

        /** 自然排序：目录在前；第2集 排在 第10集 前面 */
        val NATURAL_ORDER: Comparator<PanFile> = Comparator { a, b ->
            if (a.isDir != b.isDir) return@Comparator if (a.isDir) -1 else 1
            naturalCompare(a.name, b.name)
        }

        /**
         * 自然排序比较：连续的数字按数值比较，补零和不补零可以混用，
         *   001 < 002 < 009 < 010，  1 < 2 < 4 < 05 < 06 < 09 < 10 < 12
         * 全角数字（１２３）也按数值比较；字母不区分大小写。
         * 数值相同只是补零不同时（5 / 05 / 005），少补零的在前，保证顺序固定。
         */
        fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            var paddingDiff = 0 // 第一处“数值相同、补零不同”的差别，最后才用来决定先后
            while (i < a.length && j < b.length) {
                if (a[i].isDigit() && b[j].isDigit()) {
                    val si = i
                    val sj = j
                    while (i < a.length && a[i].isDigit()) i++
                    while (j < b.length && b[j].isDigit()) j++
                    val na = digitsOf(a, si, i)
                    val nb = digitsOf(b, sj, j)
                    if (na.length != nb.length) return na.length - nb.length
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                    if (paddingDiff == 0) paddingDiff = (i - si) - (j - sj)
                } else {
                    val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            val rest = (a.length - i) - (b.length - j)
            if (rest != 0) return rest
            if (paddingDiff != 0) return paddingDiff
            return a.compareTo(b)
        }

        /** s[from, to) 这一段数字 → 去掉前导零的 ASCII 数字串（全角数字也转换） */
        private fun digitsOf(s: String, from: Int, to: Int): String {
            val sb = StringBuilder(to - from)
            for (k in from until to) {
                val d = Character.digit(s[k], 10)
                if (sb.isEmpty() && d == 0) continue
                sb.append('0' + d)
            }
            return sb.toString()
        }
    }
}
