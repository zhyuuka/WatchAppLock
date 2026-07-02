package com.watchapplock

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 常驻前台 Service（规格 §3）。
 *
 * 职责：
 * - 前台通知（8.1 强制，startForegroundService 5s 内 startForeground）
 * - 启动 [UsagePoller] 轮询前台应用
 * - 被系统杀后由 START_STICKY + [ServiceGuard]（Job/Alarm）兜底重启
 *
 * 内存目标：8–12MB。仅本 Service 常驻。
 */
class LockGuardService : android.app.Service() {

    companion object {
        private const val TAG = "LockGuardService"
        private const val NOTIF_ID = 1001
        private const val START_REASON = "start_reason"
        const val REASON_BOOT = "boot"
        const val REASON_USER = "user"
        const val REASON_GUARD = "guard"
    }

    private var handlerThread: HandlerThread? = null
    private var workHandler: Handler? = null
    private var poller: UsagePoller? = null

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("wal-poller").apply { start() }
        workHandler = Handler(handlerThread!!.looper)
        poller = UsagePoller(this, workHandler!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        Log.d(TAG, "onStartCommand reason=${intent?.getStringExtra(START_REASON)}")

        // 仅在保活开启时轮询；关闭时仍保留前台壳但停止轮询可省电
        if (Prefs.getKeepAlive()) {
            poller?.start()
        } else {
            poller?.stop()
        }

        // 被杀后系统尽量重建
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务划掉：尽快自重启
        ServiceGuard.start(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        poller?.stop()
        handlerThread?.quitSafely()
        // 仍想存活：兜底重启
        if (Prefs.getKeepAlive()) {
            ServiceGuard.start(applicationContext)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID_FOREGROUND)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }
}
