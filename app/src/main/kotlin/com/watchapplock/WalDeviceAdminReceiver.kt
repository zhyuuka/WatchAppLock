package com.watchapplock

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 设备管理员接收器（可选防卸载，规格 §14）。
 *
 * 启用后系统阻止直接卸载本应用，需先在设备管理中停用。
 */
class WalDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "WalDevAdmin"
        fun componentName(context: Context): ComponentName =
            ComponentName(context, WalDeviceAdminReceiver::class.java)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "device admin disabled")
        Prefs.setAntiUninstall(false)
    }
}
