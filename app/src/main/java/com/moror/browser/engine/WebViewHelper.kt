package com.moror.browser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.moror.browser.data.MiniClipPreferences
import java.net.URLEncoder

object WebViewHelper {

    // Clean Chrome Mobile UA that does NOT contain "Version/4.0" or "; wv", bypassing Google's 403 disallowed_useragent block
    fun getCleanMobileUserAgent(context: Context): String {
        return try {
            val defaultUa = WebSettings.getDefaultUserAgent(context)
            defaultUa
                .replace(Regex(";\\s*wv\\b"), "")
                .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
        } catch (e: Exception) {
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
        }
    }

    // Clean Chrome Desktop UA
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    fun setupWebView(
        webView: WebView,
        context: Context,
        isDesktopMode: Boolean = false,
        onPageStarted: (url: String) -> Unit,
        onPageFinished: (url: String, title: String) -> Unit,
        onProgressChanged: (progress: Int) -> Unit,
        onCreateWindowRequest: ((resultMsg: Message) -> Boolean)? = null,
        onCloseWindowRequest: ((window: WebView?) -> Unit)? = null
    ) {
        val settings = webView.settings

        // Enable JavaScript and Modern Web Storage (Mandatory for Gmail and Google Account Login)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // Enable Multiple Windows support for tabs / popups
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true

        // Viewport and Rendering optimizations
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // Handle Clean User-Agent to avoid Google's "disallowed_useragent" (Error 403)
        if (isDesktopMode) {
            settings.userAgentString = DESKTOP_USER_AGENT
        } else {
            settings.userAgentString = getCleanMobileUserAgent(context)
        }

        // Enable Cookies and Third-Party Cookies (Required for Google multi-domain OAuth)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // WebChromeClient for page title, progress updates and multiple window support
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                onProgressChanged(newProgress)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val currentUrl = view?.url ?: ""
                onPageFinished(currentUrl, title ?: "")
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (resultMsg != null && onCreateWindowRequest != null) {
                    return onCreateWindowRequest(resultMsg)
                }
                return super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
            }

            override fun onCloseWindow(window: WebView?) {
                if (onCloseWindowRequest != null) {
                    onCloseWindowRequest(window)
                } else {
                    super.onCloseWindow(window)
                }
            }
        }

        // WebViewClient to keep browsing within the WebView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { onPageStarted(it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val currentUrl = url ?: ""
                val currentTitle = view?.title ?: ""
                onPageFinished(currentUrl, currentTitle)
                CookieManager.getInstance().flush()

                if (MiniClipPreferences.isBlockVideosEnabled(context)) {
                    injectVideoBlockerScript(view)
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request != null) {
                    val isVideoBlocked = MiniClipPreferences.isBlockVideosEnabled(context)

                    // Instantly block and drop video requests to save mobile data and cache space
                    if (isVideoBlocked && FastCacheEngine.isVideoRequest(request)) {
                        return FastCacheEngine.createEmptyVideoResponse()
                    }

                    if (FastCacheEngine.isCacheableResource(request, isVideoBlocked)) {
                        val cachedResponse = FastCacheEngine.interceptAndCache(context, request)
                        if (cachedResponse != null) {
                            return cachedResponse
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                // Keep web navigation inside our isolated WebView
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return false
            }
        }
    }

    /**
     * Injects a script and CSS to pause existing video elements and hide them from DOM.
     */
    fun injectVideoBlockerScript(view: WebView?) {
        val script = """
            (function() {
                try {
                    if (!document.getElementById('moror-video-blocker-style')) {
                        const style = document.createElement('style');
                        style.id = 'moror-video-blocker-style';
                        style.textContent = 'video, [data-video], .video-player, .video-container { display: none !important; }';
                        (document.head || document.documentElement).appendChild(style);
                    }
                    document.querySelectorAll('video').forEach(function(v) {
                        try {
                            v.pause();
                            v.removeAttribute('src');
                            v.preload = 'none';
                            v.load();
                        } catch(e){}
                    });
                } catch(e) {}
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    /**
     * Resolves user input: if it's a valid URL, load it; otherwise perform a web search.
     */
    fun resolveUrl(input: String, context: Context? = null): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "https://www.google.com"

        // If it starts with standard web protocols
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }

        // If it looks like a domain name (e.g. example.com, accounts.google.com)
        val domainPattern = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(:\\d+)?(/.*)?$")
        if (domainPattern.matches(trimmed) && !trimmed.contains(" ")) {
            return "https://$trimmed"
        }

        // Otherwise, perform search based on configured engine
        val query = try {
            URLEncoder.encode(trimmed, "UTF-8")
        } catch (e: Exception) {
            trimmed
        }

        val engine = if (context != null) MiniClipPreferences.getSearchEngine(context) else "Google"
        return when {
            engine.contains("DuckDuckGo", ignoreCase = true) -> "https://duckduckgo.com/?q=$query"
            engine.contains("Bing", ignoreCase = true) -> "https://www.bing.com/search?q=$query"
            else -> "https://www.google.com/search?q=$query"
        }
    }

    fun clearSessionAndCookies(webView: WebView) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
        webView.clearCache(true)
        webView.clearFormData()
        webView.clearHistory()
        webView.clearSslPreferences()
    }
}
