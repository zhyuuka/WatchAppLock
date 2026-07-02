package com.watchapplock

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log

/**
 * JobScheduler 兜底任务：检查 [LockGuardService] 是否存活，死了则重启。
 * 15min 周期，持久化（重启设备后仍有效）。
 */
class GuardJobService : JobService() {

    companion object { private const val TAG = "GuardJobService" }

    override fun onStartJob(params: JobParameters?): Boolean {
        val running = LockTrigger.isServiceRunning(this, LockGuardService::class.java)
        Log.d(TAG, "check: LockGuardService running=$running")
        if (!running && Prefs.getKeepAlive()) {
            ServiceGuard.start(this)
        }
        // 再排一次闹钟兜底
        ServiceGuard.scheduleAlarm(this)
        return false  // 任务已同步完成
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
