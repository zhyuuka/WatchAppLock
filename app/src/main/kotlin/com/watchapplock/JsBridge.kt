package com.watchapplock

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebView ↔ SharedPreferences 双向桥（规格 §10）。
 *
 * - 所有 `@JavascriptInterface` 方法在 WebView 内部线程执行
 * - 涉及 UI（startActivity / evaluateJavascript）转发主线程
 * - 返回复杂结构用 JSON 字符串，前端 JSON.parse
 */
class JsBridge(private val hostRef: WeakReference<SettingsActivity>) {

    companion object { private const val TAG = "JsBridge" }

    private val main = Handler(Looper.getMainLooper())
    private val devCounter = AtomicInteger(0)

    private fun ctx(): Context? = hostRef.get()

    // ===================== 已锁应用 =====================

    /** @return 已锁应用包名 JSON 数组字符串。 */
    @android.webkit.JavascriptInterface
    fun getLockedApps(): String {
        val arr = StringBuilder("[")
        var first = true
        for (p in Prefs.getLockedSet()) {
            if (!first) arr.append(',')
            arr.append('"').append(escape(p)).append('"')
            first = false
        }
        arr.append(']')
        return arr.toString()
    }

    @android.webkit.JavascriptInterface
    fun toggleLock(pkg: String, on: Boolean) {
        Prefs.toggleLock(pkg, on)
        notifyChanged("lockedApps")
    }

    @android.webkit.JavascriptInterface
    fun setLockedApps(json: String) {
        val set = HashSet<String>()
        runCatching {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) set.add(arr.getString(i))
        }
        Prefs.setLockedSet(set)
        notifyChanged("lockedApps")
    }

    /** @return 可启动应用 JSON 数组：[{pkg,label,system}]。 */
    @android.webkit.JavascriptInterface
    fun getInstalledApps(): String {
        val c = ctx() ?: return "[]"
        return AppListProvider.toJson(AppListProvider.list(c))
    }

    /** @return 应用显示名，查不到则返回包名。 */
    @android.webkit.JavascriptInterface
    fun getAppLabel(pkg: String): String {
        val c = ctx() ?: return pkg
        return try {
            c.packageManager.getApplicationLabel(
                c.packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Throwable) { pkg }
    }

    // ===================== 锁定方式 / PIN =====================

    @android.webkit.JavascriptInterface
    fun getLockMode(): String = Prefs.getLockMode()

    @android.webkit.JavascriptInterface
    fun setLockMode(mode: String) {
        if (mode == "pin" || mode == "pattern") {
            Prefs.setLockMode(mode)
            notifyChanged("lockMode")
        }
    }

    @android.webkit.JavascriptInterface
    fun hasPin(): Boolean = Prefs.hasPin()

    @android.webkit.JavascriptInterface
    fun verifyPin(pin: String): Boolean = Prefs.verifyPin(pin)

    @android.webkit.JavascriptInterface
    fun setPin(pin: String) {
        Prefs.setPin(pin)
        notifyChanged("pin")
    }

    // ===================== 保活 / 自启 =====================

    @android.webkit.JavascriptInterface
    fun getKeepAlive(): Boolean = Prefs.getKeepAlive()

    @android.webkit.JavascriptInterface
    fun setKeepAlive(on: Boolean) {
        Prefs.setKeepAlive(on)
        val c = ctx() ?: return
        if (on) ServiceGuard.start(c) else {
            // 关闭保活：停止常驻（仍可手动从设置再开）
            runCatching { c.stopService(android.content.Intent(c, LockGuardService::class.java)) }
        }
        notifyChanged("keepAlive")
    }

    @android.webkit.JavascriptInterface
    fun getAutoStart(): Boolean = Prefs.getAutoStart()

    @android.webkit.JavascriptInterface
    fun setAutoStart(on: Boolean) {
        Prefs.setAutoStart(on)
        notifyChanged("autoStart")
    }

    // ===================== 权限状态 / 跳转 =====================

    @android.webkit.JavascriptInterface
    fun isUsageAccessGranted(): Boolean =
        ctx()?.let { PermissionHelper.isUsageAccessGranted(it) } ?: false

    @android.webkit.JavascriptInterface
    fun openUsageAccessSettings() {
        main.post { ctx()?.let { PermissionHelper.openUsageAccessSettings(it) } }
    }

    @android.webkit.JavascriptInterface
    fun isBatteryOptimized(): Boolean =
        ctx()?.let { !PermissionHelper.isBatteryOptimizationIgnored(it) } ?: true

    @android.webkit.JavascriptInterface
    fun requestBatteryOptimizationExemption() {
        main.post { ctx()?.let { PermissionHelper.requestBatteryOptimizationExemption(it) } }
    }

    @android.webkit.JavascriptInterface
    fun isNotificationEnabled(): Boolean =
        ctx()?.let { PermissionHelper.isNotificationEnabled(it) } ?: false

    @android.webkit.JavascriptInterface
    fun openNotificationSettings() {
        main.post { ctx()?.let { PermissionHelper.openNotificationSettings(it) } }
    }

    @android.webkit.JavascriptInterface
    fun isAccessibilityEnabled(): Boolean =
        ctx()?.let { PermissionHelper.isAccessibilityEnabled(it) } ?: false

    @android.webkit.JavascriptInterface
    fun openAccessibilitySettings() {
        main.post { ctx()?.let { PermissionHelper.openAccessibilitySettings(it) } }
    }

    @android.webkit.JavascriptInterface
    fun isServiceRunning(): Boolean =
        ctx()?.let { LockTrigger.isServiceRunning(it, LockGuardService::class.java) } ?: false

    @android.webkit.JavascriptInterface
    fun startServiceNow() {
        main.post { ctx()?.let { ServiceGuard.start(it) } }
    }

    // ===================== 安全选项 =====================

    @android.webkit.JavascriptInterface
    fun getAntiUninstall(): Boolean = Prefs.getAntiUninstall()

    /** 设备管理员实际生效状态（与开关可能短暂不一致，以系统为准）。 */
    @android.webkit.JavascriptInterface
    fun isAntiUninstallActive(): Boolean =
        ctx()?.let { PermissionHelper.isDeviceAdminActive(it) } ?: false

    @android.webkit.JavascriptInterface
    fun setAntiUninstall(on: Boolean) {
        Prefs.setAntiUninstall(on)
        if (on) main.post { ctx()?.let { PermissionHelper.requestDeviceAdmin(it) } }
        notifyChanged("antiUninstall")
    }

    @android.webkit.JavascriptInterface
    fun getFakeCrash(): Boolean = Prefs.getFakeCrash()

    @android.webkit.JavascriptInterface
    fun setFakeCrash(on: Boolean) {
        Prefs.setFakeCrash(on)
        notifyChanged("fakeCrash")
    }

    @android.webkit.JavascriptInterface
    fun applyFakeCrash() {
        main.post { PermissionHelper.triggerFakeCrash() }
    }

    // ===================== 开发者选项 / ADB / Root =====================

    @android.webkit.JavascriptInterface
    fun getDevMode(): Boolean = Prefs.getDevMode()

    /** 连点版本号调用。累计 7 次解锁开发者选项，返回是否刚解锁。 */
    @android.webkit.JavascriptInterface
    fun bumpDevCounter(): Boolean {
        val n = devCounter.incrementAndGet()
        if (n >= 7 && !Prefs.getDevMode()) {
            Prefs.setDevMode(true)
            notifyChanged("devMode")
            return true
        }
        return false
    }

    @android.webkit.JavascriptInterface
    fun getAdbStatus(): String {
        val c = ctx() ?: return "unavailable"
        val adbEnabled = try {
            android.provider.Settings.Global.getInt(
                c.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0
            ) == 1
        } catch (_: Throwable) { false }
        val whitelisted = PermissionHelper.isBatteryOptimizationIgnored(c)
        return when {
            adbEnabled && whitelisted -> "adb-ready"
            adbEnabled -> "adb-debug-on"
            else -> "adb-off"
        }
    }

    @android.webkit.JavascriptInterface
    fun getRootStatus(): String {
        val paths = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su")
        return if (paths.any { File(it).exists() }) "root-available" else "no-root"
    }

    /** 一键复制 ADB 命令（前端拿字符串写入剪贴板）。 */
    @android.webkit.JavascriptInterface
    fun getAdbCommands(): String {
        val pkg = "com.watchapplock"
        val cmd = "adb shell dumpsys deviceidle whitelist +$pkg\n" +
            "adb shell pm grant $pkg android.permission.PACKAGE_USAGE_STATS"
        return cmd
    }

    @android.webkit.JavascriptInterface
    fun getVersionName(): String = "1.0.0"

    // ===================== 回调前端 =====================

    private fun notifyChanged(what: String) {
        main.post {
            hostRef.get()?.evaluateJs("window.__wal && __wal.onPrefChanged && __wal.onPrefChanged('$what');")
        }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
