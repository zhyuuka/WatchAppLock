package com.watchapplock

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 锁屏 Activity（规格 §4 解锁流 / §12 防绕过）。
 *
 * - 全屏 PIN / 图案输入，校验通过写 TTL 放行
 * - 错误 5 次延时 30s 再可输
 * - `EXCLUDE_FROM_RECENTS` + 尽量锁任务，近期任务清不掉锁屏
 * - 极简布局（纯色、无图），遵循 DeviceProfile 比例规则
 */
class LockScreenActivity : Activity() {

    companion object {
        const val EXTRA_TARGET_PKG = "target_pkg"
    }

    private var targetPkg: String? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var titleView: TextView
    private lateinit var pinDots: TextView
    private lateinit var errorView: TextView
    private lateinit var container: LinearLayout
    private var patternView: PatternView? = null

    private val pinBuf = StringBuilder()
    private var inputLocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
        DeviceProfile.applyImmersive(this)
        window.decorView.setBackgroundColor(Color.BLACK)

        setContentView(buildUi())
        applyShapePadding()

        // 若未设置 PIN，无法校验——直接放行（防御性，正常流程应先设 PIN）
        if (!Prefs.hasPin()) {
            targetPkg?.let { Prefs.markUnlocked(it) }
            finish()
            return
        }

        if (Prefs.isFailLocked()) startLockoutCountdown()
        if (Prefs.getLockMode() == "pattern") buildPattern() else buildKeypad()
    }

    // ===================== UI 构建 =====================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val pad = dp(20)
        root.setPadding(pad, pad, pad, pad)

        titleView = TextView(this).apply {
            text = appLabel()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }
        pinDots = TextView(this).apply {
            text = "• • • •"
            setTextColor(0xAAFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            transformationMethod = PasswordTransformationMethod.getInstance()
            setPadding(0, dp(12), 0, dp(12))
            visibility = View.GONE
        }
        errorView = TextView(this).apply {
            setTextColor(0xFFFF6B6B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            minHeight = dp(24)
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        root.addView(titleView)
        root.addView(pinDots)
        root.addView(container)
        root.addView(errorView)
        return root
    }

    /** 圆屏内容靠中留白，方屏撑满（规格 §8）。 */
    private fun applyShapePadding() {
        val p = DeviceProfile.probe(this)
        if (p.shape == DeviceProfile.Shape.ROUND) {
            val horiz = (p.widthPx * 0.10f).toInt()
            val top = (p.heightPx * 0.08f).toInt()
            val bottom = (p.heightPx * 0.12f).toInt() + p.safeInsetBottom
            window.decorView.setPadding(horiz, top, horiz, bottom)
        } else {
            window.decorView.setPadding(
                p.safeInsetLeft, p.safeInsetTop, p.safeInsetRight, p.safeInsetBottom
            )
        }
    }

    private fun buildKeypad() {
        pinDots.visibility = View.VISIBLE
        patternView?.visibility = View.GONE
        container.removeAllViews()
        val grid = GridLayout(this).apply {
            columnCount = 3
            rowCount = 4
            useDefaultMargins = true
            alignmentMode = GridLayout.ALIGN_MARGINS
        }
        val keys = arrayOf("1","2","3","4","5","6","7","8","9","del","0","ok")
        for (k in keys) {
            val b = Button(this).apply {
                text = if (k == "del") "⌫" else if (k == "ok") "✓" else k
                setBackgroundColor(0x22FFFFFF)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                val size = dp(56)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = size; height = size
                    setGravity(Gravity.CENTER)
                }
                setOnClickListener { onKey(k) }
            }
            grid.addView(b)
        }
        container.addView(grid)
        updateDots()
    }

    private fun buildPattern() {
        pinDots.visibility = View.GONE
        container.removeAllViews()
        val pv = PatternView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).apply {
                gravity = Gravity.CENTER
            }
            onPatternComplete = { seq -> verifyPattern(seq) }
        }
        patternView = pv
        container.addView(pv)
    }

    // ===================== 输入处理 =====================

    private fun onKey(k: String) {
        if (inputLocked) return
        when (k) {
            "del" -> if (pinBuf.isNotEmpty()) { pinBuf.deleteAt(pinBuf.length - 1); updateDots() }
            "ok" -> verifyPin(pinBuf.toString())
            else -> {
                if (pinBuf.length < 8) { pinBuf.append(k); updateDots() }
                if (pinBuf.length >= 4) { /* 等用户点 ok，或自动校验 */ }
            }
        }
    }

    private fun updateDots() {
        val n = pinBuf.length.coerceAtMost(8)
        pinDots.text = buildString { repeat(n) { append("● ") }; repeat((8 - n).coerceAtLeast(0)) { append("○ ") } }
    }

    private fun verifyPin(pin: String) {
        if (Prefs.verifyPin(pin)) {
            onUnlockSuccess()
        } else {
            onUnlockFail()
        }
        pinBuf.clear(); updateDots()
    }

    private fun verifyPattern(seq: List<Int>) {
        // 图案转字符串序列做校验（与 PIN 哈希同一套）
        val key = seq.joinToString("-")
        if (Prefs.verifyPin(key)) onUnlockSuccess() else onUnlockFail()
    }

    private fun onUnlockSuccess() {
        errorView.text = ""
        targetPkg?.let {
            Prefs.markUnlocked(it)
            Prefs.resetFails(it)
        }
        finish()
    }

    private fun onUnlockFail() {
        val locked = targetPkg?.let { Prefs.recordFail(it) } ?: false
        if (locked) {
            errorView.text = getString(R.string.lock_fail_too_many)
            startLockoutCountdown()
        } else {
            errorView.text = getString(R.string.lock_fail_wrong)
        }
    }

    private fun startLockoutCountdown() {
        inputLocked = true
        val tick = object : Runnable {
            override fun run() {
                val remain = Prefs.failLockRemainingMs()
                if (remain <= 0) {
                    inputLocked = false
                    errorView.text = ""
                    return
                }
                errorView.text = getString(R.string.lock_fail_countdown, (remain / 1000 + 1).toInt())
                handler.postDelayed(this, 500)
            }
        }
        handler.post(tick)
    }

    // ===================== 防绕过 =====================

    /** 拦截返回键，不允许直接退出锁屏。 */
    override fun onBackPressed() {
        // 不调用 super：留在锁屏
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 屏蔽 Home 之外的常用逃逸键不可行，但保证返回/任务键被吞
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    // ===================== 工具 =====================

    private fun appLabel(): String {
        val pkg = targetPkg ?: return getString(R.string.lock_screen_title)
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (_: Throwable) { getString(R.string.lock_screen_title) }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
