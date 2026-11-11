package com.netdisk.player4kid.ui.parent

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.netdisk.player4kid.App
import com.netdisk.player4kid.core.Kiosk
import com.netdisk.player4kid.ui.showOn

/** 家长密码验证 + 设置菜单 */
object ParentGate {

    /** 桌面右上角“设置”按钮：验证家长密码后，选择打开系统设置 / WLAN / 家长设置 */
    fun showSettingsMenu(activity: Activity) {
        request(activity) {
            val items = arrayOf("系统设置", "WLAN（无线网络）", "家长设置（时长、目录、账号）")
            AlertDialog.Builder(activity)
                .setTitle("设置")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> Kiosk.openSystemSettings(activity)
                        1 -> openWifi(activity)
                        2 -> activity.startActivity(Intent(activity, ParentActivity::class.java))
                    }
                }
                .setNegativeButton("关闭", null)
                .create()
                .showOn(activity)
        }
    }

    fun openWifi(activity: Activity) {
        Kiosk.suspendGuard(10)
        Kiosk.exitLockTask(activity)
        try {
            activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** 验证家长密码；第一次使用时先设置密码 */
    fun request(activity: Activity, onPass: () -> Unit) {
        val pin = App.prefs.parentPin
        if (pin.isEmpty()) {
            setPin(activity, "第一次使用，请设置家长密码", onPass)
            return
        }
        val edit = pinEdit(activity)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("请输入家长密码")
            .setView(wrap(activity, edit, null))
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (edit.text.toString() == App.prefs.parentPin) {
                    dialog.dismiss()
                    onPass()
                } else {
                    edit.text.clear()
                    edit.error = "密码不对"
                }
            }
        }
        showWithKeyboard(activity, dialog, edit)
    }

    fun setPin(activity: Activity, title: String = "设置家长密码", onDone: (() -> Unit)? = null) {
        val e1 = pinEdit(activity).apply { hint = "4~8 位数字" }
        val e2 = pinEdit(activity).apply { hint = "再输入一次" }
        val box = wrap(activity, e1, e2)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = e1.text.toString()
                val b = e2.text.toString()
                when {
                    a.length < 4 -> e1.error = "至少 4 位"
                    a != b -> e2.error = "两次输入不一致"
                    else -> {
                        App.prefs.parentPin = a
                        Toast.makeText(activity, "家长密码已设置，请牢记", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        onDone?.invoke()
                    }
                }
            }
        }
        showWithKeyboard(activity, dialog, e1)
    }

    private fun pinEdit(activity: Activity) = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(8))
        gravity = Gravity.CENTER
        textSize = 24f
    }

    private fun wrap(activity: Activity, e1: EditText, e2: EditText?): LinearLayout {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(e1)
            if (e2 != null) addView(e2)
            if (e2 == null) {
                addView(TextView(activity).apply {
                    text = "忘记密码可通过 adb 清除本应用数据"
                    textSize = 12f
                    alpha = 0.5f
                })
            }
        }
    }

    private fun showWithKeyboard(activity: Activity, dialog: AlertDialog, focus: EditText) {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.showOn(activity) // 在孩子的全屏页面上弹出时，状态栏不会闪出来
        focus.requestFocus()
    }
}
