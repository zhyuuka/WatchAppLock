package com.watchapplock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * 设置界面宿主（规格 §4 设置流 / §8 WebView 层）。
 *
 * - WebView match_parent，加载 `file:///android_asset/ui/settings.html`
 * - 注入 [JsBridge] 与 [DeviceProfile] CSS 变量
 * - 用完即毁：onDestroy 销毁 WebView 并 System.gc()
 * - 加载失败降级显示原生极简设置页
 *
 * 继承原生 [android.app.Activity]，不引入 AppCompat/Fragment，符合最小依赖。
 */
class SettingsActivity : android.app.Activity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val HOME_URL = "file:///android_asset/ui/settings.html"
    }

    private var webView: LockWebView? = null
    private var fallback: View? = null
    private lateinit var bridge: JsBridge
    private var profile: DeviceProfile.Profile? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceProfile.applyImmersive(this)
        window.decorView.setBackgroundColor(Color.BLACK)

        bridge = JsBridge(WeakReference(this))

        webView = LockWebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(false)
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            }
            // 适配圆屏/方屏：撑满不缩放
            setInitialScale(100)
            webViewClient = LockClient(this@SettingsActivity)
            webChromeClient = WebChromeClient()
            addJavascriptInterface(bridge, "Bridge")
        }
        setContentView(webView)
        webView?.loadUrl(HOME_URL)

        // 拉起常驻守护服务（用户主动打开 App，属前台启动，合法）
        if (Prefs.getKeepAlive()) ServiceGuard.start(applicationContext)
    }

    override fun onResume() {
        super.onResume()
        // 重新探测并注入视口（旋转/尺寸变化后）
        pushViewport()
        // 通知前端刷新权限态
        evaluateJs("window.__wal && __wal.onResume && __wal.onResume();")
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }
        webView = null
        System.gc()  // 规格 §7：用完即毁 + gc
    }

    /** 供 [JsBridge] 回调。 */
    fun evaluateJs(js: String) {
        webView?.post { webView?.evaluateJavascript(js, null) }
    }

    /** 探测屏幕并注入 CSS 变量 / 调 updateViewport。 */
    fun pushViewport() {
        val p = DeviceProfile.probe(this)
        profile = p
        val inject = buildString {
            append("(function(){")
            append("var r=document.documentElement.style;")
            append("r.setProperty('--screen-shape','${shapeName(p.shape)}');")
            append("r.setProperty('--viewport-w','${p.widthPx}');")
            append("r.setProperty('--viewport-h','${p.heightPx}');")
            append("r.setProperty('--safe-inset-top','${p.safeInsetTop}px');")
            append("r.setProperty('--safe-inset-bottom','${p.safeInsetBottom}px');")
            append("r.setProperty('--safe-inset-left','${p.safeInsetLeft}px');")
            append("r.setProperty('--safe-inset-right','${p.safeInsetRight}px');")
            append("document.documentElement.setAttribute('data-shape','${shapeName(p.shape)}');")
            append("})();")
        }
        evaluateJs(inject)
        evaluateJs(p.toJsCall())
    }

    private fun shapeName(s: DeviceProfile.Shape) = when (s) {
        DeviceProfile.Shape.SQUARE -> "square"
        DeviceProfile.Shape.TALL -> "tall"
        DeviceProfile.Shape.WIDE -> "wide"
        DeviceProfile.Shape.ROUND -> "round"
    }

    /** 加载失败降级原生极简页（规格 §11）。 */
    fun showFallback() {
        runOnUiThread {
            webView?.visibility = View.GONE
            if (fallback == null) {
                fallback = TextView(this).apply {
                    text = getString(R.string.fallback_settings)
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setPadding(32, 32, 32, 32)
                    setBackgroundColor(Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                (fallback as View).also { setContentView(it) }
            }
        }
    }

    /** 捕获尺寸变化，把像素值传给页面（规格 §8 onSizeChanged）。 */
    private class LockWebView(context: Context) : WebView(context) {
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            (context as? SettingsActivity)?.evaluateJs(
                "if(typeof updateViewport==='function'){updateViewport({w:$w,h:$h});}"
            )
        }
    }

    private class LockClient(private val activity: SettingsActivity) : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            activity.pushViewport()
            activity.evaluateJs("window.__wal && __wal.onReady && __wal.onReady();")
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            Log.w(TAG, "webview error: ${error?.description}")
            activity.showFallback()
        }
    }
}
