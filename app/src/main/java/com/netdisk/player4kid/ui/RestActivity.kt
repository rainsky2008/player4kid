package com.netdisk.player4kid.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import com.netdisk.player4kid.App
import com.netdisk.player4kid.core.PlayTimeManager
import com.netdisk.player4kid.core.TimeTexts
import com.netdisk.player4kid.databinding.ActivityRestBinding
import com.netdisk.player4kid.player.VideoQueue
import com.netdisk.player4kid.ui.parent.ParentActivity
import com.netdisk.player4kid.ui.parent.ParentGate

/**
 * 强制休息界面：倒计时结束前无法离开（返回键无效；按 Home 回到桌面也会被送回这里）。
 */
class RestActivity : BaseKidActivity() {


    private lateinit var b: ActivityRestBinding
    private var tipIndex = 0
    private var tipTicks = 0

    private val ticker = object : Runnable {
        override fun run() {
            update()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRestBinding.inflate(layoutInflater)
        setContentView(b.root)

        tipIndex = (System.currentTimeMillis() / 1000 % TIPS.size).toInt()
        b.txtTip.text = TIPS[tipIndex]

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (App.playTime.restRemainingMs() <= 0) finish()
            }
        })

        b.btnContinue.setOnClickListener {
            startActivity(Intent(this, VideoPlayerActivity::class.java))
            finish()
        }
        b.btnHome.setOnClickListener {
            startActivity(
                Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
        }
        // 家长入口（长按）：可以提前结束休息
        b.txtParent.setOnLongClickListener {
            ParentGate.request(this) { startActivity(Intent(this, ParentActivity::class.java)) }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(ticker)
    }

    private fun update() {
        val pt = App.playTime
        val rest = pt.restRemainingMs()
        val daily = pt.dailyRemainingMs()
        b.txtToday.text = TimeTexts.afterRest()

        if (rest > 0) {
            b.txtCountdown.text = PlayTimeManager.formatMinSec(rest)
            b.doneBox.visibility = View.GONE
            if (++tipTicks >= 15) {
                tipTicks = 0
                tipIndex = (tipIndex + 1) % TIPS.size
                b.txtTip.text = TIPS[tipIndex]
            }
        } else {
            b.txtEmoji.text = "😊"
            b.txtTitle.text = "休息好啦！"
            b.txtCountdown.text = "00:00"
            b.txtTip.text = if (daily > 0) "眼睛休息好了，可以继续看啦" else "明天再来看吧，现在可以听故事哦"
            b.doneBox.visibility = View.VISIBLE
            b.btnContinue.visibility =
                if (daily > 0 && VideoQueue.hasQueue()) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private val TIPS = listOf(
            "看看窗外远处的绿色植物吧 🌿",
            "闭上眼睛，慢慢数到 20 👀",
            "站起来伸个懒腰，转转脖子 🙆",
            "去喝一杯水吧 🥛",
            "眼珠上下左右转一转 👁️",
            "做一做眼保健操 ✋",
            "跳一跳，活动一下身体 🐰",
        )
    }
}
