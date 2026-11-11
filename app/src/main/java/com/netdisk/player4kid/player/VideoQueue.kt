package com.netdisk.player4kid.player

import android.content.Context
import com.netdisk.player4kid.App
import com.netdisk.player4kid.baidu.PanFile

/**
 * 当前视频播放列表。保存到磁盘，休息结束后可以“继续看”。
 */
object VideoQueue {
    private val sp by lazy { App.instance.getSharedPreferences("video_queue", Context.MODE_PRIVATE) }

    var files: List<PanFile> = emptyList()
        private set
    var index: Int = 0

    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        files = PanFile.listFromJson(sp.getString("files", null))
        index = sp.getInt("index", 0).coerceIn(0, (files.size - 1).coerceAtLeast(0))
    }

    fun set(list: List<PanFile>, startIndex: Int) {
        loaded = true
        files = list
        index = startIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        sp.edit().putString("files", PanFile.listToJson(list)).putInt("index", index).apply()
    }

    fun saveIndex(i: Int) {
        index = i
        sp.edit().putInt("index", i).apply()
    }

    fun hasQueue(): Boolean {
        ensureLoaded()
        return files.isNotEmpty()
    }

    fun current(): PanFile? {
        ensureLoaded()
        return files.getOrNull(index)
    }
}
