package com.watchapplock

import android.content.Context
import android.content.SharedPreferences
import android.util.ArraySet
import java.security.SecureRandom

/**
 * SharedPreferences 单一封装。常驻 [LockGuardService] 与瞬态 UI 共享同一实例。
 *
 * 内存策略（贴合 2+32 手表）：
 * - 锁名单用 StringSet 持久化，不维护内存大列表
 * - [unlockedMap] 用 HashMap 限定容量，LRU 清理超过 50 条
 */
object Prefs {

    private const val FILE_NAME = "watch_app_lock"
    private const val MAX_UNLOCK_CACHE = 50

    private lateinit var sp: SharedPreferences

    // ---- keys ----
    private const val K_LOCKED_SET = "locked_set"
    private const val K_LOCK_MODE = "lock_mode"          // "pin" | "pattern"
    private const val K_PIN_HASH = "pin_hash"
    private const val K_PIN_SALT = "pin_salt"
    private const val K_KEEP_ALIVE = "keep_alive"
    private const val K_AUTO_START = "auto_start"
    private const val K_DEV_MODE = "dev_mode"
    private const val K_ANTI_UNINSTALL = "anti_uninstall"
    private const val K_FAKE_CRASH = "fake_crash"
    private const val K_TTL_SECONDS = "ttl_seconds"
    private const val K_FAIL_COUNT = "fail_count"
    private const val K_FAIL_LOCK_UNTIL = "fail_lock_until"

    /** 解锁缓存：pkg -> 放行截止时间戳(ms)。内存态，进程重启即失效（符合预期）。 */
    private val unlockedMap: HashMap<String, Long> = HashMap()
    private val failCountMap: HashMap<String, Int> = HashMap()

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        ensureDefaults()
    }

    private fun ensureDefaults() {
        if (!sp.contains(K_LOCK_MODE)) edit { putString(K_LOCK_MODE, "pin") }
        if (!sp.contains(K_KEEP_ALIVE)) edit { putBoolean(K_KEEP_ALIVE, true) }
        if (!sp.contains(K_AUTO_START)) edit { putBoolean(K_AUTO_START, true) }
        if (!sp.contains(K_TTL_SECONDS)) edit { putInt(K_TTL_SECONDS, 30) }
        if (!sp.contains(K_DEV_MODE)) edit { putBoolean(K_DEV_MODE, false) }
    }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val e = sp.edit()
        e.block()
        e.apply()
    }

    // ===================== 锁名单 =====================

    /** 被锁应用包名集合（持久化）。返回新 Set，避免外部修改。 */
    @Synchronized
    fun getLockedSet(): Set<String> =
        sp.getStringSet(K_LOCKED_SET, emptySet()) ?: emptySet()

    @Synchronized
    fun isLocked(pkg: String): Boolean = getLockedSet().contains(pkg)

    @Synchronized
    fun toggleLock(pkg: String, on: Boolean) {
        val cur = ArraySet<String>(getLockedSet())
        if (on) cur.add(pkg) else cur.remove(pkg)
        edit { putStringSet(K_LOCKED_SET, cur) }
    }

    @Synchronized
    fun setLockedSet(pkgs: Set<String>) {
        edit { putStringSet(K_LOCKED_SET, pkgs) }
    }

    // ===================== 解锁 TTL 缓存 =====================

    /** 该包是否处于 TTL 放行窗口内。 */
    @Synchronized
    fun isUnlocked(pkg: String): Boolean {
        val until = unlockedMap[pkg] ?: return false
        if (System.currentTimeMillis() >= until) {
            unlockedMap.remove(pkg)
            return false
        }
        return true
    }

    /** 写入放行：now + ttl 秒。 */
    @Synchronized
    fun markUnlocked(pkg: String) {
        val ttlSecs = sp.getInt(K_TTL_SECONDS, 30)
        unlockedMap[pkg] = System.currentTimeMillis() + ttlSecs * 1000L
        if (unlockedMap.size > MAX_UNLOCK_CACHE) evictOldest()
    }

    @Synchronized
    fun clearUnlocked(pkg: String) {
        unlockedMap.remove(pkg)
    }

    @Synchronized
    fun clearAllUnlocked() {
        unlockedMap.clear()
    }

    private fun evictOldest() {
        // LRU：移除过期或最早到期的条目
        val now = System.currentTimeMillis()
        val it = unlockedMap.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value <= now) it.remove()
        }
        if (unlockedMap.size <= MAX_UNLOCK_CACHE) return
        unlockedMap.entries.sortedBy { it.value }.take(unlockedMap.size - MAX_UNLOCK_CACHE)
            .forEach { unlockedMap.remove(it.key) }
    }

    // ===================== 锁定方式 / PIN =====================

    fun getLockMode(): String = sp.getString(K_LOCK_MODE, "pin") ?: "pin"
    fun setLockMode(mode: String) = edit { putString(K_LOCK_MODE, mode) }

    fun hasPin(): Boolean = sp.getString(K_PIN_HASH, null) != null

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = PinHasher.hash(pin, salt)
        edit {
            putString(K_PIN_SALT, PinHasher.toB64(salt))
            putString(K_PIN_HASH, hash)
        }
    }

    /** 校验 PIN，返回是否匹配。 */
    fun verifyPin(pin: String): Boolean {
        val saltB64 = sp.getString(K_PIN_SALT, null) ?: return false
        val stored = sp.getString(K_PIN_HASH, null) ?: return false
        val salt = PinHasher.fromB64(saltB64) ?: return false
        val candidate = PinHasher.hash(pin, salt)
        return constantTimeEq(candidate, stored)
    }

    private fun constantTimeEq(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    // ===================== 失败计数 / 延时 =====================

    /**
     * 记录一次失败。返回 true 表示已触发延时锁定（30s）。
     * 规格第 4 节：错误 5 次锁屏延时 30s 再可输。
     */
    @Synchronized
    fun recordFail(pkg: String): Boolean {
        val n = (failCountMap[pkg] ?: 0) + 1
        failCountMap[pkg] = n
        if (n >= 5) {
            failCountMap[pkg] = 0
            val until = System.currentTimeMillis() + 30_000L
            edit { putLong(K_FAIL_LOCK_UNTIL, until) }
            return true
        }
        return false
    }

    @Synchronized
    fun resetFails(pkg: String) {
        failCountMap.remove(pkg)
    }

    /** 是否处于失败延时窗口（全局，覆盖所有包的锁屏输入）。 */
    fun isFailLocked(): Boolean {
        val until = sp.getLong(K_FAIL_LOCK_UNTIL, 0)
        return System.currentTimeMillis() < until
    }

    fun failLockRemainingMs(): Long {
        val until = sp.getLong(K_FAIL_LOCK_UNTIL, 0)
        return (until - System.currentTimeMillis()).coerceAtLeast(0)
    }

    // ===================== 保活 / 自启 / 安全 =====================

    fun getKeepAlive(): Boolean = sp.getBoolean(K_KEEP_ALIVE, true)
    fun setKeepAlive(on: Boolean) = edit { putBoolean(K_KEEP_ALIVE, on) }

    fun getAutoStart(): Boolean = sp.getBoolean(K_AUTO_START, true)
    fun setAutoStart(on: Boolean) = edit { putBoolean(K_AUTO_START, on) }

    fun getDevMode(): Boolean = sp.getBoolean(K_DEV_MODE, false)
    fun setDevMode(on: Boolean) = edit { putBoolean(K_DEV_MODE, on) }

    fun getAntiUninstall(): Boolean = sp.getBoolean(K_ANTI_UNINSTALL, false)
    fun setAntiUninstall(on: Boolean) = edit { putBoolean(K_ANTI_UNINSTALL, on) }

    fun getFakeCrash(): Boolean = sp.getBoolean(K_FAKE_CRASH, false)
    fun setFakeCrash(on: Boolean) = edit { putBoolean(K_FAKE_CRASH, on) }

    fun getTtlSeconds(): Int = sp.getInt(K_TTL_SECONDS, 30)
}
