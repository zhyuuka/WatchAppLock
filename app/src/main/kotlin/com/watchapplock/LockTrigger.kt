package com.watchapplock

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 锁触发共用逻辑。[UsagePoller] 与 [LockAccessibilityService] 共用，
 * 避免重复实现「命中 + TTL + 防抖 + 拉起锁屏」。
 */
object LockTrigger {

    private const val TAG = "LockTrigger"
    private const val DEBOUNCE_MS = 3_000L

    // 每包名最近一次弹锁时间，防抖（规格 §4：同一包名 3 秒内不重复弹）
    private val lastShown: HashMap<String, Long> = HashMap()

    /**
     * @return true 表示已拉起锁屏。
     */
    @Synchronized
    fun maybeLock(context: Context, pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        // 不锁自己
        if (pkg == context.packageName) return false

        if (!Prefs.isLocked(pkg)) return false
        if (Prefs.isUnlocked(pkg)) return false

        val now = System.currentTimeMillis()
        val last = lastShown[pkg] ?: 0L
        if (now - last < DEBOUNCE_MS) return false
        lastShown[pkg] = now

        return launchLock(context, pkg)
    }

    /** 直接拉起锁屏覆盖目标应用。 */
    fun launchLock(context: Context, pkg: String): Boolean {
        return try {
            val intent = Intent(context, LockScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(LockScreenActivity.EXTRA_TARGET_PKG, pkg)
            }
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "launchLock failed: ${t.message}")
            false
        }
    }

    /** 判断 Service 是否在运行（供 [ServiceGuard] 兜底检查）。 */
    fun isServiceRunning(context: Context, cls: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val services = am.getRunningServices(Int.MAX_VALUE) ?: return false
        return services.any { it.service.className == cls.name }
    }
}
