package com.watchapplock

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log

/**
 * 权限授予链检测与跳转（规格 §9）。
 *
 * 1. 使用情况访问权限（必须）
 * 2. 通知渠道（8.1 必须）
 * 3. 电池优化白名单
 * 4. ROM 自启动白名单（文案引导，无法统一跳转）
 * 5. 无障碍（可选）
 */
object PermissionHelper {

    private const val TAG = "PermissionHelper"

    /** 使用情况访问权限。 */
    fun isUsageAccessGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "open usage settings failed: ${it.message}") }
    }

    /** 电池优化白名单。 */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestBatteryOptimizationExemption(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "request battery exemption failed: ${it.message}") }
    }

    /** 无障碍是否启用（针对本应用的服务）。 */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val target = "${context.packageName}/${LockAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        while (splitter.hasNext()) {
            if (splitter.next().equals(target, ignoreCase = true)) return true
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "open a11y settings failed: ${it.message}") }
    }

    /** 通知渠道是否启用（前台 Service 8.1 必须）。 */
    fun isNotificationEnabled(context: Context): Boolean {
        return Settings.Secure.getInt(
            context.contentResolver,
            "notification_enabled", 1
        ) == 1 && isChannelEnabled(context)
    }

    private fun isChannelEnabled(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val ch = nm.getNotificationChannel(App.CHANNEL_ID_FOREGROUND) ?: return true
        return ch.importance != android.app.NotificationManager.IMPORTANCE_NONE
    }

    fun openNotificationSettings(context: Context) {
        runCatching {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "open notif settings failed: ${it.message}") }
    }

    /** 伪装崩溃：返回后让 Activity 直接 finish 弹系统崩溃对话框（仅演示）。 */
    fun triggerFakeCrash() {
        throw RuntimeException("Unfortunately, System UI has stopped.")
    }

    // ===================== 设备管理员（防卸载） =====================

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as android.app.admin.DevicePolicyManager
        return dpm.isAdminActive(WalDeviceAdminReceiver.componentName(context))
    }

    fun requestDeviceAdmin(context: Context) {
        runCatching {
            val intent = android.app.admin.DevicePolicyManager
                .ACTION_ADD_DEVICE_ADMIN
            val i = Intent(intent).apply {
                putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    WalDeviceAdminReceiver.componentName(context)
                )
                putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    context.getString(R.string.device_admin_desc)
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        }.onFailure { Log.w(TAG, "request device admin failed: ${it.message}") }
    }
}
