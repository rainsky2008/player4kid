package com.netdisk.player4kid.ui

import android.app.Activity
import android.app.Dialog
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 显示对话框；如果是在孩子的全屏页面上（[BaseKidActivity]），对话框也保持全屏。
 *
 * 对话框是单独的窗口，不处理的话一弹出来，状态栏就会闪一下并出现。
 * 做法：先让对话框窗口不抢焦点 → show() → 在对话框窗口上隐藏系统栏 → 再恢复焦点（可以正常打字），
 * 这样状态栏没有机会出现。家长设置页本来就显示状态栏，直接 show()。
 */
fun Dialog.showOn(activity: Activity) {
    val w = window
    if (activity !is BaseKidActivity || w == null) {
        show()
        return
    }
    w.setFlags(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
    )
    show()
    WindowCompat.getInsetsController(w, w.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.systemBars())
    }
    w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
}
