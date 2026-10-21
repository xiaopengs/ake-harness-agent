package com.ake.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.ake.core.common.AkeLog

/**
 * WebView 的构造与配置。
 *
 * 集中在一处的理由：**每一条配置背后都是一个「少了它就会出现某种奇怪失败」的真实坑**，
 * 散落在各个 Activity 里必然有人忘配其中一条，而症状往往是「某个站点打不开」这种难归因问题。
 */
public object WebViewFactory {

    /**
     * UA：**不要用默认的 `; wv` 尾巴**。
     *
     * 很多站点见到 `Chrome/x ...; wv` 就判定「这是嵌入式浏览器」并给出降级页面 / 甚至直接拦。
     * 但本项目要用用户的**登录态**正常工作，拿不到正常页面等于整个任务没法做。
     * 因此 UA 伪装成同版本普通 Chrome —— 这是功能性需求，不是想绕过什么风控。
     */
    // ★ 外层 apply 必须显式命名：里面还有一个 `CookieManager.getInstance().apply`，
    //   裸 `this@apply` 会绑到**最近**的那个（CookieManager），
    //   而 `setAcceptThirdPartyCookies` 第一个参数要的是 WebView ⇒
    //   「actual type is 'CookieManager!', but 'WebView!' was expected」。
    public fun create(context: Context): WebView = WebView(context).apply webView@ {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // 大量 SPA 依赖 localStorage 维持会话
            databaseEnabled = true
            loadWithOverviewMode = false
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            allowContentAccess = true
            allowFileAccess = false           // 不让页面读 file:// —— 与 sito 隔离有关
            javaScriptCanOpenWindowsAutomatically = false // 弹窗一律自己拦，交给 UIFLOW 处理
            setSupportZoom(false)
            builtInZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            safeBrowsingEnabled = true        // Google Safe Browsing：对用户是净收益
            // 端侧 App 不需要把布局当 emerging markets 处理
            setGeolocationEnabled(false)
        }
        settings.userAgentString = mobileChromeUa(settings.userAgentString)

        // 第三方 Cookie：我们不希望被投放网络通过 iframe cookie 追踪，但第一方必须可用
        // ⚠ 这里必须是 android.webkit.CookieManager（不是 java.net.CookieManager）：
        //   后者是给 HttpURLConnection 用的，跟 WebView 的 cookie jar 完全不是同一个东西，
        //   混用会表现为「明明登录过，WebView 里还是未登录」。
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@webView, false)
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    /**
     * 去掉 WebView 标记，换成普通 Chrome UA。
     * 保留原始 UA 里的 Android 版本与设备信息，避免让站点拿到自相矛盾的 UA。
     */
    private fun mobileChromeUa(original: String): String {
        val cleaned = original.replace("; wv", "").replace("Version/4.0 ", "")
        return if (cleaned.contains("Chrome/")) cleaned else "$cleaned Chrome/120.0.0.0"
    }

    /**
     * ★ `app_webview/` —— **本 App 里最大一块不受我们掌控的痕迹**（数据库 F8）。
     *
     * 我们是以用户的**登录态**在 WebView 里访问站点的：Cookie / LocalStorage / IndexedDB /
     * FormData 全在这个目录里。只删自己的 DB 而留着它，「一键清除痕迹」就是假的 ——
     * 用户拿 ADB 看一下 `app_webview/` 就能还原访问过的站点。
     *
     * 顺序上必须「先删密钥再删文件」（§8.5 ①）：反过来做，万一中途崩了，
     * 文件还在而密钥没了 ⇒ 数据变成永久垃圾。
     */
    @SuppressLint("RequiresFeature")
    public fun wipe(context: Context): Boolean {
        var ok = true
        try {
            CookieManager.getInstance().apply {
                removeAllCookies { }
                flush()
            }
        } catch (t: Throwable) { ok = false; AkeLog.w(TAG, "cookie clear failed", t) }

        try {
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (t: Throwable) { ok = false; AkeLog.w(TAG, "webstorage clear failed", t) }

        try {
            WebViewDatabase.getInstance(context).clearFormData()
        } catch (t: Throwable) { /* 老 API，失败无所谓 */ }

        // 上面几步都异步，必须删目录本身兜住 IndexedDB / LocalStorage 文件
        try {
            java.io.File(context.dataDir, APP_WEBVIEW_DIR).deleteRecursively()
        } catch (t: Throwable) { ok = false; AkeLog.w(TAG, "app_webview delete failed", t) }

        return ok
    }

    private const val TAG = "WebViewFactory"
    private const val APP_WEBVIEW_DIR = "app_webview"
}
