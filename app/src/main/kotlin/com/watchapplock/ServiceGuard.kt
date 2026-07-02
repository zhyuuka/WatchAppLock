package com.watchapplock

import android.app.AlarmManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log

/**
 * 保活兜底（规格 §5）：
 * - JobScheduler 15min 检查 Service 存活，死了重启
 * - AlarmManager `setAndAllowWhileIdle` Doze 下心跳
 *
 * 不采用：1px 像素 Activity、双进程互拉（耗电/内存吃不消）。
 */
object ServiceGuard {

    private const val TAG = "ServiceGuard"
    private const val JOB_ID = 9001
    private const val ALARM_INTERVAL_MS = 5 * 60 * 1000L  // 心跳 5min

    /** 拉起常驻前台 Service。兼容 8.1 startForegroundService。 */
    fun start(context: Context) {
        val intent = Intent(context, LockGuardService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startService failed: ${t.message}")
        }
        scheduleJob(context)
        scheduleAlarm(context)
    }

    /** JobScheduler 兜底：15min 检查存活。 */
    fun scheduleJob(context: Context) {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val info = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, GuardJobService::class.java)
        )
            .setPeriodic(15 * 60 * 1000L)
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .build()
        try {
            js.schedule(info)
        } catch (t: Throwable) {
            Log.w(TAG, "scheduleJob failed: ${t.message}")
        }
    }

    /** AlarmManager 心跳：Doze 下也能触发。 */
    fun scheduleAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + ALARM_INTERVAL_MS
        val pi = android.app.PendingIntent.getBroadcast(
            context, 0,
            Intent(context, BootReceiver::class.java).apply {
                action = BootReceiver.ACTION_HEARTBEAT
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    android.app.PendingIntent.FLAG_IMMUTABLE else 0
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (t: Throwable) {
            // 部分手表 ROM 砍掉精确闹钟权限，降级忽略
            Log.w(TAG, "scheduleAlarm failed: ${t.message}")
        }
    }
}
