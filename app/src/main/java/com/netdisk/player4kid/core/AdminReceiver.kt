package com.netdisk.player4kid.core

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/** 设备管理器：用于 lockNow() 息屏；若被设置为设备所有者，还用于锁定任务（kiosk）模式。 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Kiosk.applyDeviceOwnerPolicies(context)
    }
}
