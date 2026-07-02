package com.watchapplock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * 应用入口。仅做轻量初始化：通知渠道。
 * 不在此启动 Service——由 [BootReceiver] / [SettingsActivity] 按需拉起，
 * 避免 Application 阻塞主线程。
 */
class App : Application() {

    companion object {
        const val CHANNEL_ID_FOREGROUND = "wal_foreground"
        const val CHANNEL_ID_ALERT = "wal_alert"
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        // 前台 Service 必需渠道（8.1 强制）
        NotificationChannel(
            CHANNEL_ID_FOREGROUND,
            getString(R.string.channel_foreground),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_foreground_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }.also { nm.createNotificationChannel(it) }

        // 告警渠道
        NotificationChannel(
            CHANNEL_ID_ALERT,
            getString(R.string.channel_alert),
            NotificationManager.IMPORTANCE_DEFAULT
        ).also { nm.createNotificationChannel(it) }
    }
}
