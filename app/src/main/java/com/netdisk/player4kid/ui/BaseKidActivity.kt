package com.netdisk.player4kid.ui

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.netdisk.player4kid.App
import com.netdisk.player4kid.R
import com.netdisk.player4kid.core.Kiosk
import com.netdisk.player4kid.player.AudioEngine

/**
 * 孩子使用的界面的基类：
 *  - 进入锁定任务模式（设备所有者时）
 *  - 隐藏状态栏和导航栏（全屏）
 *  - 正在听音频时，无操作一段时间后自动息屏
 */
abstract class BaseKidActivity : AppCompatActivity() {

    /**
     * true：同时隐藏状态栏（全屏，页面直接顶到屏幕最上面）；false：只隐藏导航栏。
     * 孩子的页面默认全屏（首页自己有时钟）；从顶部往下滑，状态栏会临时出现。
     */
    protected open val fullScreen = true

    /** 是否在“听音频且无操作”时自动息屏 */
    protected open val autoScreenOffForAudio = true

    protected val uiHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable { onIdle() }

    /** 下一次自动息屏的时间点（elapsedRealtime），0 表示没有安排 */
    protected var idleDeadline = 0L
        private set

    /** 状态栏颜色：和页面顶部的背景同色，顶上不会出现一条色带（默认是首页 / 目录页的天空色） */
    protected open val statusBarColorRes: Int = R.color.sky_top

    /** 状态栏图标用深色（浅色背景时）还是浅色（深色背景时） */
    protected open val lightStatusBar = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 音量键始终调节媒体音量（否则视频还在加载、没有声音输出时，Android 9 会去调铃声音量）
        volumeControlStream = AudioManager.STREAM_MUSIC
        window.statusBarColor = ContextCompat.getColor(this, statusBarColorRes)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = lightStatusBar
    }

    override fun onResume() {
        super.onResume()
        Kiosk.mainTaskId = taskId
        Kiosk.enterLockTaskIfNeeded(this)
        hideSystemBars()
        resetIdleTimer()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(idleRunnable)
        idleDeadline = 0L
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetIdleTimer()
    }

    protected fun resetIdleTimer() {
        uiHandler.removeCallbacks(idleRunnable)
        idleDeadline = 0L
        if (!autoScreenOffForAudio) return
        val ms = App.prefs.audioScreenOffSec * 1000L
        idleDeadline = SystemClock.elapsedRealtime() + ms
        uiHandler.postDelayed(idleRunnable, ms)
    }

    /** 无操作超时 */
    protected open fun onIdle() {
        idleDeadline = 0L
        if (!AudioEngine.isActive) {
            resetIdleTimer()
            return
        }
        if (!Kiosk.screenOff(this)) {
            // 没有设备管理器权限：跳到音频界面用黑屏 + 最低亮度代替
            startActivity(
                Intent(this, AudioPlayerActivity::class.java)
                    .putExtra(AudioPlayerActivity.EXTRA_BLACKOUT, true)
            )
        }
    }

    protected fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullScreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
}
