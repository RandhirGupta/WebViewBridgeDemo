package com.example.webviewbridge

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * MainActivity demonstrates secure WebView-to-Native communication using
 * AndroidX WebKit's WebMessageListener API.
 *
 * Architecture:
 * - NativeBridge: Low-level bridge injected by addWebMessageListener
 * - Native: High-level JavaScript wrapper providing Promise-based API
 *
 * Usage from JavaScript:
 * ```javascript
 * const result = await Native.postMessage({ method: 'ping' });
 * ```
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        setupWebView()
        setupBackNavigation()
    }

    private fun setupWebView() {
        configureWebViewSettings()
        setupWebViewClient()
        setupBridge()
        loadContent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewSettings() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Note: These settings are for local testing only.
            // In production, use specific origin whitelisting instead.
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
        }
    }

    private var documentStartScriptSupported = false

    private fun setupWebViewClient() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: $url")

                // Fallback: inject bridge wrapper if DOCUMENT_START_SCRIPT wasn't supported
                if (!documentStartScriptSupported && view != null) {
                    Log.d(TAG, "Injecting bridge wrapper via evaluateJavascript (fallback)")
                    view.evaluateJavascript(BRIDGE_WRAPPER_SCRIPT, null)
                }
            }
        }
    }

    private fun setupBridge() {
        injectBridgeWrapper()
        setupWebMessageListener()
    }

    private fun loadContent() {
        webView.loadUrl(ASSET_URL)
    }

    /**
     * Injects the JavaScript bridge wrapper at document start.
     * This ensures the Native object is available before any page scripts run.
     */
    private fun injectBridgeWrapper() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(webView, BRIDGE_WRAPPER_SCRIPT, ALLOWED_ORIGINS)
                documentStartScriptSupported = true
                Log.d(TAG, "Bridge wrapper script injected")
            }.onFailure { e ->
                Log.e(TAG, "Failed to inject bridge wrapper: ${e.message}")
            }
        } else {
            Log.e(TAG, "Document start script injection not supported on this device")
        }
    }

    /**
     * Sets up the WebMessageListener to handle incoming messages from JavaScript.
     */
    private fun setupWebMessageListener() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching {
                WebViewCompat.addWebMessageListener(
                    webView,
                    BRIDGE_NAME,
                    ALLOWED_ORIGINS,
                    this::onMessageReceived
                )
                Log.d(TAG, "WebMessageListener setup complete")
            }.onFailure { e ->
                Log.e(TAG, "Failed to setup WebMessageListener: ${e.message}")
            }
        } else {
            Log.e(TAG, "WebMessageListener not supported on this device")
        }
    }

    /**
     * Handles incoming messages from JavaScript.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun onMessageReceived(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        val messageData = message.data ?: return

        Log.d(TAG, "Message received: $messageData (origin: $sourceOrigin, mainFrame: $isMainFrame)")

        runCatching {
            val request = JSONObject(messageData)
            val callbackId = request.getString("callbackId")
            val method = request.getString("method")
            val args = request.optJSONObject("args") ?: JSONObject()

            routeMethod(method, args, callbackId, replyProxy)
        }.onFailure { e ->
            Log.e(TAG, "Error parsing message: ${e.message}")
            sendError(replyProxy, "", "Invalid message format: ${e.message}")
        }
    }

    // ============================================================
    // Method Routing
    // ============================================================

    private fun routeMethod(
        method: String,
        args: JSONObject,
        callbackId: String,
        replyProxy: JavaScriptReplyProxy
    ) {
        when (method) {
            METHOD_PING -> handlePing(callbackId, replyProxy)
            METHOD_GET_DEVICE_INFO -> handleGetDeviceInfo(callbackId, replyProxy)
            METHOD_GET_APP_INFO -> handleGetAppInfo(callbackId, replyProxy)
            METHOD_SHOW_TOAST -> handleShowToast(args, callbackId, replyProxy)
            METHOD_GET_PREFERENCE -> handleGetPreference(args, callbackId, replyProxy)
            METHOD_SET_PREFERENCE -> handleSetPreference(args, callbackId, replyProxy)
            METHOD_COPY_TO_CLIPBOARD -> handleCopyToClipboard(args, callbackId, replyProxy)
            METHOD_SHARE -> handleShare(args, callbackId, replyProxy)
            else -> sendError(replyProxy, callbackId, "Unknown method: $method")
        }
    }

    // ============================================================
    // Method Handlers
    // ============================================================

    private fun handlePing(callbackId: String, replyProxy: JavaScriptReplyProxy) {
        sendSuccess(replyProxy, callbackId, JSONObject().apply {
            put("message", "pong")
            put("timestamp", System.currentTimeMillis())
        })
    }

    private fun handleGetDeviceInfo(callbackId: String, replyProxy: JavaScriptReplyProxy) {
        sendSuccess(replyProxy, callbackId, JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("brand", Build.BRAND)
            put("osVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("device", Build.DEVICE)
        })
    }

    private fun handleGetAppInfo(callbackId: String, replyProxy: JavaScriptReplyProxy) {
        runCatching {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            sendSuccess(replyProxy, callbackId, JSONObject().apply {
                put("packageName", packageName)
                put("versionName", packageInfo.versionName)
                put("versionCode", PackageInfoCompat.getLongVersionCode(packageInfo))
                put("appName", applicationInfo.loadLabel(packageManager).toString())
            })
        }.onFailure { e ->
            sendError(replyProxy, callbackId, "Failed to get app info: ${e.message}")
        }
    }

    private fun handleShowToast(args: JSONObject, callbackId: String, replyProxy: JavaScriptReplyProxy) {
        val message = args.optString("message", "")
        val duration = if (args.optString("duration") == "long") {
            Toast.LENGTH_LONG
        } else {
            Toast.LENGTH_SHORT
        }

        runOnUiThread {
            Toast.makeText(this, message, duration).show()
        }

        sendSuccess(replyProxy, callbackId, JSONObject().apply {
            put("shown", true)
        })
    }

    private fun handleGetPreference(args: JSONObject, callbackId: String, replyProxy: JavaScriptReplyProxy) {
        val key = args.optString("key", "")
        if (key.isEmpty()) {
            sendError(replyProxy, callbackId, "key is required")
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val value = prefs.getString(key, args.optString("defaultValue", ""))

        sendSuccess(replyProxy, callbackId, JSONObject().apply {
            put("key", key)
            put("value", value)
        })
    }

    private fun handleSetPreference(args: JSONObject, callbackId: String, replyProxy: JavaScriptReplyProxy) {
        val key = args.optString("key", "")
        if (key.isEmpty()) {
            sendError(replyProxy, callbackId, "key is required")
            return
        }

        val value = args.optString("value", "")
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(key, value)
        }

        sendSuccess(replyProxy, callbackId, JSONObject().apply {
            put("key", key)
            put("value", value)
            put("saved", true)
        })
    }

    private fun handleCopyToClipboard(args: JSONObject, callbackId: String, replyProxy: JavaScriptReplyProxy) {
        val text = args.optString("text", "")
        if (text.isEmpty()) {
            sendError(replyProxy, callbackId, "text is required")
            return
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", text))

        sendSuccess(replyProxy, callbackId, JSONObject().apply {
            put("copied", true)
        })
    }

    private fun handleShare(args: JSONObject, callbackId: String, replyProxy: JavaScriptReplyProxy) {
        val title = args.optString("title", "Share")
        val text = args.optString("text", "")
        val url = args.optString("url", "")
        val shareText = buildString {
            append(text)
            if (url.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(url)
            }
        }

        val shareIntent = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            },
            title
        )
        startActivity(shareIntent)

        sendSuccess(replyProxy, callbackId, JSONObject().apply {
            put("shared", true)
        })
    }

    // ============================================================
    // Response Helpers
    // ============================================================

    private fun sendSuccess(replyProxy: JavaScriptReplyProxy, callbackId: String, result: JSONObject) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val response = JSONObject().apply {
                put("callbackId", callbackId)
                put("result", result)
            }
            replyProxy.postMessage(response.toString())
        }
    }

    private fun sendError(replyProxy: JavaScriptReplyProxy, callbackId: String, errorMessage: String) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val response = JSONObject().apply {
                put("callbackId", callbackId)
                put("error", JSONObject().apply {
                    put("message", errorMessage)
                    put("code", "BRIDGE_ERROR")
                })
            }
            replyProxy.postMessage(response.toString())
        }
    }

    // ============================================================
    // Navigation
    // ============================================================

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    override fun onDestroy() {
        webView.apply {
            stopLoading()
            destroy()
        }
        super.onDestroy()
    }

    // ============================================================
    // Constants
    // ============================================================

    companion object {
        private const val TAG = "WebViewBridge"
        private const val BRIDGE_NAME = "NativeBridge"
        private const val PREFS_NAME = "webview_bridge_prefs"
        private const val ASSET_URL = "file:///android_asset/test_bridge.html"

        // Allowed origins - use specific domains in production
        private val ALLOWED_ORIGINS = setOf("*")

        // Method names
        private const val METHOD_PING = "ping"
        private const val METHOD_GET_DEVICE_INFO = "getDeviceInfo"
        private const val METHOD_GET_APP_INFO = "getAppInfo"
        private const val METHOD_SHOW_TOAST = "showToast"
        private const val METHOD_GET_PREFERENCE = "getPreference"
        private const val METHOD_SET_PREFERENCE = "setPreference"
        private const val METHOD_COPY_TO_CLIPBOARD = "copyToClipboard"
        private const val METHOD_SHARE = "share"

        /**
         * JavaScript bridge wrapper that provides a Promise-based API.
         * Injected at document start to ensure availability before page scripts.
         */
        private const val BRIDGE_WRAPPER_SCRIPT = """
(function() {
    'use strict';

    console.log('[NativeBridge] Bridge wrapper script executing...');

    if (window.Native) {
        console.log('[NativeBridge] Native already exists, skipping initialization');
        return;
    }

    var callbacks = {};
    var counter = 0;
    var initialized = false;

    function generateId() {
        return 'cb_' + (++counter) + '_' + Date.now();
    }

    function setupHandler() {
        if (initialized) return;
        initialized = true;
        console.log('[NativeBridge] Setting up message handler');
        NativeBridge.onmessage = function(event) {
            console.log('[NativeBridge] Response received:', event.data);
            try {
                var response = JSON.parse(event.data);
                var cb = callbacks[response.callbackId];
                if (cb) {
                    delete callbacks[response.callbackId];
                    if (response.error) {
                        console.log('[NativeBridge] Error for callbackId ' + response.callbackId + ':', response.error);
                        cb.reject(response.error);
                    } else {
                        console.log('[NativeBridge] Success for callbackId ' + response.callbackId + ':', JSON.stringify(response.result));
                        cb.resolve(response.result);
                    }
                } else {
                    console.warn('[NativeBridge] No callback found for callbackId:', response.callbackId);
                }
            } catch (e) {
                console.error('[NativeBridge] Parse error:', e);
            }
        };
    }

    window.Native = {
        isReady: function() {
            return initialized && typeof NativeBridge !== 'undefined';
        },

        initializeAsync: function() {
            console.log('[NativeBridge] initializeAsync called');
            return new Promise(function(resolve) {
                function check() {
                    if (typeof NativeBridge !== 'undefined') {
                        console.log('[NativeBridge] NativeBridge found, initializing...');
                        setupHandler();
                        console.log('[NativeBridge] Initialization complete');
                        resolve();
                    } else {
                        setTimeout(check, 10);
                    }
                }
                check();
            });
        },

        postMessage: function(message) {
            console.log('[NativeBridge] postMessage called with:', JSON.stringify(message));
            return new Promise(function(resolve, reject) {
                function sendMessage() {
                    if (typeof NativeBridge === 'undefined') {
                        console.error('[NativeBridge] Bridge not available');
                        return reject(new Error('Bridge not available - WebMessageListener may not be supported on this device'));
                    }

                    setupHandler();

                    var id = generateId();
                    var payload = {
                        callbackId: id,
                        method: message.method || 'unknown',
                        args: message.args || {}
                    };

                    console.log('[NativeBridge] Sending message with callbackId ' + id + ':', JSON.stringify(payload));

                    setTimeout(function() {
                        if (callbacks[id]) {
                            console.error('[NativeBridge] Request timeout for callbackId:', id);
                            delete callbacks[id];
                            reject(new Error('Request timeout'));
                        }
                    }, 30000);

                    callbacks[id] = { resolve: resolve, reject: reject };

                    NativeBridge.postMessage(JSON.stringify(payload));
                    console.log('[NativeBridge] Message posted, waiting for response...');
                }

                // Wait for bridge with timeout
                var attempts = 0;
                function waitForBridge() {
                    if (typeof NativeBridge !== 'undefined') {
                        sendMessage();
                    } else if (attempts < 100) {
                        attempts++;
                        if (attempts % 20 === 0) {
                            console.log('[NativeBridge] Waiting for bridge... attempt ' + attempts);
                        }
                        setTimeout(waitForBridge, 50);
                    } else {
                        console.error('[NativeBridge] Timeout waiting for NativeBridge after ' + attempts + ' attempts');
                        reject(new Error('Bridge not available - timeout waiting for NativeBridge'));
                    }
                }
                waitForBridge();
            });
        }
    };

    if (typeof NativeBridge !== 'undefined') {
        console.log('[NativeBridge] NativeBridge already available at script load');
        setupHandler();
    } else {
        console.log('[NativeBridge] NativeBridge not yet available, will wait on first call');
    }

    Object.freeze(window.Native);
    console.log('[NativeBridge] window.Native created and frozen');
})();
"""
    }
}
