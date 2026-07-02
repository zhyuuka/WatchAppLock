package com.watchapplock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 解锁自启动（规格 §5）。
 *
 * - BOOT_COMPLETED + LOCKED_BOOT_COMPLETED 双广播（LOCKED 在用户解锁后触发，更可靠）
 * - 兼容 QUICKBOOT_POWERON（MTK/展讯 ROM）
 * - 接收 [ACTION_HEARTBEAT] 心跳：AlarmManager 唤醒时自检并拉起 Service
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val ACTION_HEARTBEAT = "com.watchapplock.HEARTBEAT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "onReceive action=$action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            ACTION_HEARTBEAT -> {
                if (Prefs.getAutoStart() || action == ACTION_HEARTBEAT) {
                    if (!LockTrigger.isServiceRunning(context, LockGuardService::class.java)) {
                        val svc = Intent(context, LockGuardService::class.java)
                            .putExtra(LockGuardService.START_REASON, if (action == ACTION_HEARTBEAT) "guard" else "boot")
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(svc)
                            } else {
                                context.startService(svc)
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "startForegroundService failed: ${t.message}")
                            // 降级：仅排兜底
                            ServiceGuard.scheduleAlarm(context)
                        }
                    }
                    // 始终保持兜底调度
                    ServiceGuard.scheduleJob(context)
                    ServiceGuard.scheduleAlarm(context)
                }
            }
        }
    }
}
