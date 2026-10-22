package com.moror.browser.engine

import android.webkit.JavascriptInterface
import android.webkit.WebView

class MiniClipJSBridge(
    private val onImageFound: (url: String, width: Int, height: Int) -> Unit
) {

    @JavascriptInterface
    fun onNewLargeImage(url: String, width: Int, height: Int) {
        if (url.isNotBlank() && width > 0 && height > 0) {
            onImageFound(url, width, height)
        }
    }

    companion object {
        const val BRIDGE_NAME = "MororMiniClipBridge"

        fun injectObserverScript(webView: WebView, minDimension: Int) {
            injectObserverScript(webView, minDimension, minDimension, false)
        }

        fun injectObserverScript(webView: WebView, targetWidth: Int, targetHeight: Int, exactMatch: Boolean) {
            val script = """
                (function() {
                    window._mororTargetW = $targetWidth;
                    window._mororTargetH = $targetHeight;
                    window._mororExactMatch = $exactMatch;
                    
                    if (!window._mororReportedUrls) {
                        window._mororReportedUrls = new Set();
                    }
                    
                    function matchesDimension(nw, nh) {
                        if (!nw || !nh || nw < 180 || nh < 180) {
                            return false; // Skip avatars, icons, badges, emojis
                        }
                        if (window._mororExactMatch) {
                            // Exact lock: neither larger nor smaller
                            return Math.abs(nw - window._mororTargetW) <= 20 && Math.abs(nh - window._mororTargetH) <= 20;
                        }
                        
                        // Larger than or equal to: strictly larger than or equal to the selected dimensions
                        const tW = window._mororTargetW || 512;
                        const tH = window._mororTargetH || 512;
                        const maxTarget = Math.max(tW, tH);
                        const minTarget = Math.min(tW, tH);
                        const maxDim = Math.max(nw, nh);
                        const minDim = Math.min(nw, nh);
                        
                        return (nw >= tW && nh >= tH) || (maxDim >= maxTarget && minDim >= minTarget);
                    }
                    
                    function cleanImageUrl(rawUrl) {
                        if (!rawUrl) return null;
                        let url = rawUrl.trim();
                        if (!url || url.startsWith('data:image/svg') || url.startsWith('blob:')) return null;
                        return url;
                    }
                    
                    function extractBestUrl(img) {
                        if (!img) return null;
                        let src = img.currentSrc || img.src || '';
                        if (!src || src.startsWith('data:') || src.startsWith('blob:')) {
                            src = img.getAttribute('data-src') ||
                                  img.getAttribute('data-original') ||
                                  img.getAttribute('data-zoom-src') ||
                                  img.getAttribute('data-url') ||
                                  img.getAttribute('data-highres') ||
                                  src;
                        }
                        if ((!src || src.startsWith('data:')) && img.srcset) {
                            const parts = img.srcset.split(',');
                            const last = parts[parts.length - 1].trim().split(' ')[0];
                            if (last) src = last;
                        }
                        return cleanImageUrl(src);
                    }
                    
                    function reportImage(url, w, h) {
                        if (!url || window._mororReportedUrls.has(url)) return;
                        window._mororReportedUrls.add(url);
                        if (window.$BRIDGE_NAME) {
                            window.$BRIDGE_NAME.onNewLargeImage(url, w, h);
                        }
                    }
                    
                    function checkImageElement(img) {
                        if (!img) return;
                        const w = img.naturalWidth || img.width || (img.getBoundingClientRect ? img.getBoundingClientRect().width : 0);
                        const h = img.naturalHeight || img.height || (img.getBoundingClientRect ? img.getBoundingClientRect().height : 0);
                        
                        if (matchesDimension(w, h)) {
                            const url = extractBestUrl(img);
                            if (url) {
                                reportImage(url, Math.round(w), Math.round(h));
                            }
                        }
                    }
                    
                    function scanAllImages() {
                        // 1. Scan <img> elements
                        const images = document.getElementsByTagName('img');
                        for (let i = 0; i < images.length; i++) {
                            checkImageElement(images[i]);
                        }
                        
                        // 2. Scan background images on containers
                        try {
                            const bgElements = document.querySelectorAll('[style*="background-image"], [style*="background:"]');
                            for (let i = 0; i < bgElements.length; i++) {
                                const el = bgElements[i];
                                const bg = el.style.backgroundImage || (window.getComputedStyle ? window.getComputedStyle(el).backgroundImage : '');
                                if (bg && bg.indexOf('url(') !== -1) {
                                    const match = bg.match(/url\(['"]?([^'"]+)['"]?\)/);
                                    if (match && match[1]) {
                                        const bgUrl = cleanImageUrl(match[1]);
                                        if (bgUrl && !window._mororReportedUrls.has(bgUrl)) {
                                            const rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
                                            if (rect && matchesDimension(rect.width, rect.height)) {
                                                reportImage(bgUrl, Math.round(rect.width), Math.round(rect.height));
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e) {}
                    }
                    
                    // Immediate scan
                    scanAllImages();
                    
                    // Hook into DOM events if not already hooked
                    if (!window._mororObserverInjected) {
                        window._mororObserverInjected = true;
                        
                        // 1. Capture load event instantly for any newly loaded image in 0ms!
                        document.addEventListener('load', function(e) {
                            if (e.target && e.target.tagName === 'IMG') {
                                checkImageElement(e.target);
                            }
                        }, true);
                        
                        // 2. MutationObserver for dynamic React/Vue element additions
                        try {
                            const observer = new MutationObserver(function(mutations) {
                                for (let i = 0; i < mutations.length; i++) {
                                    const m = mutations[i];
                                    if (m.type === 'childList') {
                                        for (let j = 0; j < m.addedNodes.length; j++) {
                                            const node = m.addedNodes[j];
                                            if (node.nodeType === 1) {
                                                if (node.tagName === 'IMG') {
                                                    checkImageElement(node);
                                                } else if (node.getElementsByTagName) {
                                                    const subImgs = node.getElementsByTagName('img');
                                                    for (let k = 0; k < subImgs.length; k++) {
                                                        checkImageElement(subImgs[k]);
                                                    }
                                                }
                                            }
                                        }
                                    } else if (m.type === 'attributes' && m.target && m.target.tagName === 'IMG') {
                                        checkImageElement(m.target);
                                    }
                                }
                            });
                            
                            observer.observe(document.documentElement || document.body, {
                                childList: true,
                                subtree: true,
                                attributes: true,
                                attributeFilter: ['src', 'currentSrc', 'style']
                            });
                        } catch (e) {}
                        
                        // 3. Fallback periodic scan every 2 seconds
                        setInterval(scanAllImages, 2000);
                    }
                })();
            """.trimIndent()

            webView.evaluateJavascript(script, null)
        }
    }
}
