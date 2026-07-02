package com.watchapplock

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager

/**
 * 物理屏幕探测。判定圆 / 方 / 长条 / 宽屏，并把 CSS 自适应变量注入 WebView。
 *
 * 规格 §8：
 * - [Display.getRealSize] 取真实像素
 * - [DisplayCutout]（API 27 部分支持）/ 圆屏 chin
 * - [Configuration.screenLayout] 判断 round
 * - 比例 w/h：square(0.9–1.1) / tall(>1.1) / wide(<0.9) / round(isRound)
 */
object DeviceProfile {

    enum class Shape { SQUARE, TALL, WIDE, ROUND }

    data class Profile(
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
        val shape: Shape,
        val isRound: Boolean,
        val safeInsetTop: Int,
        val safeInsetBottom: Int,   // 圆屏 chin 高度承载于此
        val safeInsetLeft: Int,
        val safeInsetRight: Int
    ) {
        /** 拼成 JS 可直接执行的 updateViewport(...) 调用。 */
        fun toJsCall(): String {
            val s = when (shape) {
                Shape.SQUARE -> "square"
                Shape.TALL -> "tall"
                Shape.WIDE -> "wide"
                Shape.ROUND -> "round"
            }
            return """
                (function(){
                  if(typeof updateViewport==='function'){
                    updateViewport({
                    shape:'$s',
                    w:$widthPx, h:$heightPx,
                    density:$density,
                    round:$isRound,
                    top:$safeInsetTop, bottom:$safeInsetBottom,
                    left:$safeInsetLeft, right:$safeInsetRight
                  });
                  }
                })();
            """.trimIndent()
        }
    }

    fun probe(context: Context): Profile {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val real = Point()
        display.getRealSize(real)
        val w = real.x.coerceAtLeast(1)
        val h = real.y.coerceAtLeast(1)

        val dm = DisplayMetrics()
        display.getRealMetrics(dm)

        val isRound = context.resources.configuration.screenLayout and
            Configuration.SCREENLAYOUT_ROUND_MASK == Configuration.SCREENLAYOUT_ROUND_YES

        val ratio = w.toFloat() / h.toFloat()
        val shape = when {
            isRound -> Shape.ROUND
            ratio in 0.9f..1.1f -> Shape.SQUARE
            ratio > 1.1f -> Shape.TALL
            else -> Shape.WIDE
        }

        // chin / 安全区：API 27 部分支持 DisplayCutout，否则圆屏按经验留底部 12%
        var insetTop = 0
        var insetBottom = 0
        var insetLeft = 0
        var insetRight = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val cutout = display.cutout
                if (cutout != null) {
                    insetTop = cutout.safeInsetTop
                    insetBottom = cutout.safeInsetBottom
                    insetLeft = cutout.safeInsetLeft
                    insetRight = cutout.safeInsetRight
                }
            }
        }
        // 圆屏兜底：底部留出 chin
        if (isRound && insetBottom == 0) {
            insetBottom = (h * 0.12f).toInt()
        }

        return Profile(w, h, dm.density, shape, isRound, insetTop, insetBottom, insetLeft, insetRight)
    }

    /** 给 [Activity] 应用沉浸式撑满（规格 §8 Activity 层）。 */
    fun applyImmersive(activity: Activity) {
        val win = activity.window
        win.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        // 防翻转抖动
        @Suppress("DEPRECATION")
        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
