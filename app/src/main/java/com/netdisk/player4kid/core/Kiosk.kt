package com.netdisk.player4kid.core

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.netdisk.player4kid.App
import com.netdisk.player4kid.ui.LauncherActivity

/**
 * 桌面接管 / 防退出 / 息屏。
 *
 * 两档防护：
 *  1. 普通：本程序设为默认桌面（HOME），程序被切到后台（最近任务、通知栏点进别的应用等）时自动拉回前台。
 *  2. 设备所有者（adb shell dpm set-device-owner ...）：启用系统“锁定任务模式”，
 *     Home/最近任务/通知栏全部失效，并禁止修改系统时间（防止改时间刷时长）。
 */
object Kiosk {
    private const val TAG = "Kiosk"
    private val handler = Handler(Looper.getMainLooper())

    /** 家长临时离开（打开系统设置等）时暂停防退出，elapsedRealtime */
    @Volatile
    private var guardSuspendedUntil = 0L

    /** 本程序主任务 id，用于拉回前台 */
    @Volatile
    var mainTaskId = -1

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isAdminActive(ctx: Context) = dpm(ctx).isAdminActive(admin(ctx))
    fun isDeviceOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    fun adminRequestIntent(ctx: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "用于播放音频时自动息屏（保护孩子眼睛）。"
            )

    /** 关闭屏幕。需要已激活设备管理器，成功返回 true。 */
    fun screenOff(ctx: Context): Boolean {
        if (!isAdminActive(ctx)) return false
        return try {
            dpm(ctx).lockNow()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "lockNow failed", e)
            false
        }
    }

    fun isScreenOn(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    // ---------------------------------------------------------------- 设备所有者
    fun applyDeviceOwnerPolicies(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        try {
            dpm.setLockTaskPackages(admin, arrayOf(ctx.packageName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 保留电源菜单和状态栏信息（时间/电量），其余（Home、最近任务、通知）全部禁用
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                )
            }
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(
                admin, home, ComponentName(ctx, LauncherActivity::class.java)
            )
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            @Suppress("DEPRECATION")
            dpm.setAutoTimeRequired(admin, true)
        } catch (e: Exception) {
            Log.w(TAG, "applyDeviceOwnerPolicies", e)
        }
    }

    /** 解除设备所有者（之后才能卸载本程序） */
    fun releaseDeviceOwner(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        val dpm = dpm(activity)
        val admin = admin(activity)
        try {
            exitLockTask(activity)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            @Suppress("DEPRECATION")
            dpm.setAutoTimeRequired(admin, false)
            dpm.clearPackagePersistentPreferredActivities(admin, activity.packageName)
            dpm.setLockTaskPackages(admin, arrayOf())
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(activity.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "releaseDeviceOwner", e)
        }
    }

    // ---------------------------------------------------------------- 锁定任务
    fun inLockTask(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    fun enterLockTaskIfNeeded(activity: Activity) {
        if (!App.prefs.kioskEnabled || isGuardSuspended()) return
        if (!dpm(activity).isLockTaskPermitted(activity.packageName)) return
        if (inLockTask(activity)) return
        try {
            activity.startLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask", e)
        }
    }

    private fun inLockTaskSafe(ctx: Context) = try {
        inLockTask(ctx)
    } catch (e: Exception) {
        false
    }

    fun exitLockTask(activity: Activity) {
        if (!inLockTask(activity)) return
        try {
            activity.stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask", e)
        }
    }

    // ---------------------------------------------------------------- 防退出（拉回前台）
    fun suspendGuard(minutes: Int) {
        guardSuspendedUntil = SystemClock.elapsedRealtime() + minutes * 60_000L
    }

    fun resumeGuard() {
        guardSuspendedUntil = 0L
    }

    fun isGuardSuspended() = SystemClock.elapsedRealtime() < guardSuspendedUntil

    /** 所有界面都进入后台时调用（见 App 的生命周期统计） */
    fun onAppBackgrounded(app: App) {
        if (!App.prefs.kioskEnabled || isGuardSuspended()) return
        if (!isScreenOn(app)) return // 息屏导致的后台，不处理
        if (!isDefaultLauncher(app) && !inLockTaskSafe(app)) return // 还不是默认桌面时不和系统抢
        handler.postDelayed({
            if (App.startedActivities > 0 || isGuardSuspended() || !isScreenOn(app)) return@postDelayed
            bringToFront(app)
        }, 800)
    }

    private fun bringToFront(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val task = mainTaskId
        if (task != -1) {
            try {
                am.moveTaskToFront(task, 0)
                return
            } catch (e: Exception) {
                Log.w(TAG, "moveTaskToFront", e)
            }
        }
        ctx.startActivity(
            Intent(ctx, LauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    // ---------------------------------------------------------------- 桌面相关
    fun isDefaultLauncher(ctx: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return res?.activityInfo?.packageName == ctx.packageName
    }

    /** 系统中除本程序以外的桌面 */
    fun otherLaunchers(ctx: Context): List<ComponentName> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return ctx.packageManager.queryIntentActivities(intent, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.packageName != ctx.packageName && it.packageName != "com.android.settings" }
    }

    fun appLabel(ctx: Context, cn: ComponentName): String = try {
        ctx.packageManager.getActivityInfo(cn, 0).loadLabel(ctx.packageManager).toString()
    } catch (e: Exception) {
        cn.packageName
    }

    fun openHomeSettings(activity: Activity) {
        suspendGuard(5)
        exitLockTask(activity)
        try {
            activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    fun openSystemSettings(activity: Activity) {
        suspendGuard(15)
        exitLockTask(activity)
        activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openLauncher(activity: Activity, cn: ComponentName) {
        suspendGuard(30)
        exitLockTask(activity)
        activity.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
