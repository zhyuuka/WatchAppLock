package com.watchapplock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 锁核心：每 1500ms 轮询 [UsageStatsManager.queryEvents]，取最近一条
 * `MOVE_TO_FOREGROUND` 的包名，命中黑名单且未在 TTL 放行内则拉起锁屏。
 *
 * 不依赖无障碍（规格 §2）。无障碍仅作为可选增强。
 */
class UsagePoller(
    private val context: Context,
    private val handler: Handler
) {
    companion object {
        private const val TAG = "UsagePoller"
        private const val INTERVAL_MS = 1500L
        private const val WINDOW_MS = 5_000L
    }

    private val usm: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    @Volatile private var running = false
    private var lastEventTime: Long = 0L  // 上次处理到的事件时间戳，避免重复

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            poll()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        // 立即跑一轮再进入周期
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun poll() {
        if (usm == null) return
        val now = System.currentTimeMillis()
        val from = (now - WINDOW_MS).coerceAtLeast(0)
        val events: UsageEvents = try {
            usm.queryEvents(from, now)
        } catch (t: Throwable) {
            // 查询返回异常：跳过本轮，不误锁（规格 §11）
            Log.w(TAG, "queryEvents failed: ${t.message}")
            return
        }

        val e = UsageEvents.Event()
        var lastFgPkg: String? = null
        var lastFgTime: Long = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastFgPkg = e.packageName
                lastFgTime = e.timeStamp
            }
        }

        if (lastFgPkg.isNullOrEmpty()) return           // 空结果跳过，不误锁
        if (lastFgTime != 0L && lastFgTime <= lastEventTime) {
            // 没有比上次更新的前台事件
            return
        }
        lastEventTime = lastFgTime

        LockTrigger.maybeLock(context, lastFgPkg)
    }
}
