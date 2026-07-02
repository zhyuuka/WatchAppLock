package com.watchapplock

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log

/**
 * 可选增强分支（规格 §3）。
 *
 * - 监听 [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED]，近实时拦截目标应用
 * - 默认不启用；启用时 +约 10MB（用户选择）
 * - 不作为锁核心：关闭它，[UsagePoller] 仍正常工作
 *
 * 配置见 res/xml/accessibility_service_config.xml
 */
class LockAccessibilityService : AccessibilityService() {

    companion object { private const val TAG = "LockA11y" }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        // 近实时触发
        LockTrigger.maybeLock(this, pkg)
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "accessibility connected")
    }
}
