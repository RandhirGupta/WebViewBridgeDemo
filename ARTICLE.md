# Beyond addJavascriptInterface: Building a Secure WebView Bridge in Android

When developing hybrid Android applications, establishing a secure communication channel between the WebView and the native layer is essential. This bridge allows web pages to access native capabilities such as device info, storage, or sensors — while maintaining security.

The key question is: how can we expose native functionality without compromising security?

## Why Not Just Use addJavascriptInterface?

For years, Android developers used `addJavascriptInterface` to bridge WebView and native code. However, this method has significant security risks — especially on Android versions before 4.2 — where malicious JavaScript could exploit injected objects to invoke arbitrary methods via reflection.

Even on newer versions, `addJavascriptInterface` exposes your native methods directly to any script running in the WebView, making it difficult to control access.

## The Modern Solution: WebViewCompat.addWebMessageListener

AndroidX WebKit provides `WebViewCompat.addWebMessageListener()` — a secure alternative that uses message-passing instead of direct method exposure.

### Method Signature

```kotlin
WebViewCompat.addWebMessageListener(
    webView: WebView,
    jsObjectName: String,
    allowedOriginRules: Set<String>,
    listener: WebViewCompat.WebMessageListener
)
```

### Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| webView | WebView | The WebView instance to attach the listener to |
| jsObjectName | String | Name of the JavaScript object to inject (e.g., "NativeBridge") |
| allowedOriginRules | Set\<String\> | Origins permitted to use the bridge |
| listener | WebMessageListener | Callback that handles incoming messages |

### What This Method Does

When you call `addWebMessageListener()`:

1. **Injects a JavaScript object** named `jsObjectName` into the global scope of the web page
2. **Makes it available immediately** when the page begins to load
3. **Injects into every navigation** that matches the allowed origin rules
4. **Provides two-way communication** via `postMessage()` and `onmessage`

The injected JavaScript object provides:
- `postMessage(String)` — Send messages from JavaScript to native
- `onmessage` — Handler to receive messages from native

## WebMessageListener Interface

The listener callback receives messages sent by JavaScript:

```kotlin
interface WebMessageListener {
    fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    )
}
```

### Callback Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| view | WebView | The WebView that received the message |
| message | WebMessageCompat | The message content (access via `message.data`) |
| sourceOrigin | Uri | The origin of the page that sent the message |
| isMainFrame | Boolean | True if message came from main frame, false if from iframe |
| replyProxy | JavaScriptReplyProxy | Used to send responses back to JavaScript |

### JavaScriptReplyProxy

The `replyProxy` parameter is your channel to respond to JavaScript. Call `replyProxy.postMessage(String)` to send data back. The response triggers the `onmessage` handler on the JavaScript side.

## Origin Rules Format

The `allowedOriginRules` parameter controls which pages can access the bridge. Each rule must follow this format:

```
SCHEME "://" [ HOSTNAME_PATTERN [ ":" PORT ] ]
```

### Valid Origin Rule Examples

| Rule | Matches |
|------|---------|
| `https://example.com` | Only exact match: `https://example.com` |
| `https://*.example.com` | All subdomains: `www.example.com`, `api.example.com` (but not `example.com` itself) |
| `https://example.com:8080` | Only `example.com` on port 8080 |
| `http://192.168.1.1` | Specific IP address |
| `http://[::1]` | IPv6 localhost |
| `my-app-scheme://` | Custom URL schemes |
| `*` | All origins (not recommended for production) |

## Comparison: addJavascriptInterface vs addWebMessageListener

| Aspect | addJavascriptInterface | addWebMessageListener |
|--------|----------------------|----------------------|
| Communication | Direct method calls | String message passing |
| Security | Reflection vulnerabilities | No reflection exposure |
| Origin Control | None | Built-in whitelisting |
| Data Format | Any Java object | Strings only (JSON recommended) |
| Response Pattern | Return values | Async via replyProxy |
| API Level | API 1+ | Requires AndroidX WebKit |

## Advantages of addWebMessageListener

### 1. Origin-Based Security

Only pages from whitelisted domains can access the bridge. Even if a malicious page is loaded in your WebView, it cannot communicate with native code unless you explicitly allow its origin.

### 2. No Reflection Vulnerabilities

Unlike `addJavascriptInterface`, there's no Java object exposed to JavaScript. Attackers cannot use reflection to access methods beyond what you explicitly handle in your listener.

### 3. String-Only Communication

All data passes as strings. You control exactly what actions are available by parsing message content. This creates a clear, auditable API surface.

### 4. Frame Awareness

The `isMainFrame` parameter lets you restrict communication to the main frame only, preventing potentially malicious iframes from accessing native functionality.

### 5. Immediate Availability

The JavaScript object is available immediately when the page begins to load — no need to wait for page load events or worry about race conditions.

## Putting It All Together: A Practical Implementation

The API reference above covers the building blocks. Now let's see how to combine them into a real, usable bridge with a structured request/response protocol and a Promise-based JavaScript API.

### Step 1: Define a Request/Response Protocol

Raw string messages aren't enough for real apps. Define a JSON protocol so JavaScript can make named method calls and receive structured responses:

**Request** (JavaScript → Native)

```json
{
  "callbackId": "cb_1_1708345123456",
  "method": "getDeviceInfo",
  "args": {}
}
```

**Success Response** (Native → JavaScript)

```json
{
  "callbackId": "cb_1_1708345123456",
  "result": { "manufacturer": "Google", "model": "Pixel 8" }
}
```

**Error Response** (Native → JavaScript)

```json
{
  "callbackId": "cb_1_1708345123456",
  "error": { "message": "Unknown method", "code": "BRIDGE_ERROR" }
}
```

The `callbackId` ties each response back to the original request, enabling concurrent operations without mixups.

### Step 2: Handle Messages and Route Methods on the Native Side

```kotlin
private fun onMessageReceived(
    view: WebView,
    message: WebMessageCompat,
    sourceOrigin: Uri,
    isMainFrame: Boolean,
    replyProxy: JavaScriptReplyProxy
) {
    val messageData = message.data ?: return

    runCatching {
        val request = JSONObject(messageData)
        val callbackId = request.getString("callbackId")
        val method = request.getString("method")
        val args = request.optJSONObject("args") ?: JSONObject()

        when (method) {
            "ping" -> handlePing(callbackId, replyProxy)
            "getDeviceInfo" -> handleGetDeviceInfo(callbackId, replyProxy)
            "showToast" -> handleShowToast(args, callbackId, replyProxy)
            else -> sendError(replyProxy, callbackId, "Unknown method: $method")
        }
    }
}

private fun sendSuccess(
    replyProxy: JavaScriptReplyProxy,
    callbackId: String,
    result: JSONObject
) {
    val response = JSONObject().apply {
        put("callbackId", callbackId)
        put("result", result)
    }
    replyProxy.postMessage(response.toString())
}

private fun sendError(
    replyProxy: JavaScriptReplyProxy,
    callbackId: String,
    errorMessage: String
) {
    val response = JSONObject().apply {
        put("callbackId", callbackId)
        put("error", JSONObject().apply {
            put("message", errorMessage)
            put("code", "BRIDGE_ERROR")
        })
    }
    replyProxy.postMessage(response.toString())
}
```

### Step 3: Build a Promise-Based JavaScript Wrapper

The raw `NativeBridge.postMessage()` API only sends strings. Wrapping it with Promises gives a clean, async/await-friendly API for the web layer:

```javascript
(function() {
    'use strict';

    var callbacks = {};
    var counter = 0;

    function generateId() {
        return 'cb_' + (++counter) + '_' + Date.now();
    }

    // Listen for native responses
    NativeBridge.onmessage = function(event) {
        var response = JSON.parse(event.data);
        var cb = callbacks[response.callbackId];
        if (cb) {
            delete callbacks[response.callbackId];
            if (response.error) {
                cb.reject(response.error);
            } else {
                cb.resolve(response.result);
            }
        }
    };

    // Public API
    window.Native = {
        postMessage: function(message) {
            return new Promise(function(resolve, reject) {
                var id = generateId();

                // Timeout after 30 seconds
                setTimeout(function() {
                    if (callbacks[id]) {
                        delete callbacks[id];
                        reject(new Error('Request timeout'));
                    }
                }, 30000);

                callbacks[id] = { resolve: resolve, reject: reject };

                NativeBridge.postMessage(JSON.stringify({
                    callbackId: id,
                    method: message.method,
                    args: message.args || {}
                }));
            });
        }
    };

    Object.freeze(window.Native);
})();
```

Now calling native methods from JavaScript is clean and intuitive:

```javascript
// Simple call
const info = await window.Native.postMessage({ method: 'getDeviceInfo' });
console.log(info.manufacturer, info.model);

// Call with arguments
await window.Native.postMessage({
    method: 'showToast',
    args: { message: 'Hello from WebView!', duration: 'short' }
});
```

### Step 4: Inject the Wrapper Early with addDocumentStartJavaScript

There's a timing problem: the web page may try to call `window.Native` before the bridge wrapper has been injected. AndroidX WebKit solves this with `addDocumentStartJavaScript()`, which injects scripts at document start — before any page scripts execute:

```kotlin
private fun injectBridgeWrapper() {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            BRIDGE_WRAPPER_SCRIPT,  // The JS wrapper from Step 3
            ALLOWED_ORIGINS
        )
    }
}
```

This ensures `window.Native` is available the moment any page script runs — no race conditions, no `DOMContentLoaded` waiting.

> **Note:** `addDocumentStartJavaScript` requires AndroidX WebKit 1.6.0+ and a compatible Android System WebView.

## Feature Support

This API requires checking feature availability before use:

```kotlin
if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
    // Safe to use addWebMessageListener
}
```

## Important: NativeBridge.postMessage vs window.postMessage

A common source of confusion: the injected `NativeBridge.postMessage()` is completely different from the browser's `window.postMessage()`.

| API | Purpose |
|-----|---------|
| `window.postMessage()` | Browser's cross-origin messaging between frames/windows |
| `NativeBridge.postMessage()` | WebView bridge for JavaScript to native Android communication |

They are unrelated APIs. Don't mix them up!

## Requirements

| Requirement | Version |
|-------------|---------|
| AndroidX WebKit | 1.6.0+ (for `addDocumentStartJavaScript`) |
| Android System WebView | 74+ |
| Minimum SDK | 24 |

Add the dependency:

```kotlin
implementation("androidx.webkit:webkit:1.9.0")
```

## Best Practices

1. **Whitelist specific origins** — Never use `"*"` in production
2. **Validate incoming messages** — Don't trust data from JavaScript
3. **Use request identifiers** — Match responses to requests for concurrent operations
4. **Check isMainFrame** — Consider restricting to main frame only
5. **Handle errors gracefully** — Send structured error responses
6. **Inject scripts early** — Use `addDocumentStartJavaScript` to avoid race conditions between your bridge wrapper and page scripts

## Conclusion

`WebViewCompat.addWebMessageListener` provides a secure, modern alternative to `addJavascriptInterface`. By using message-passing with origin restrictions, it eliminates reflection vulnerabilities while giving you fine-grained control over which pages can communicate with native code.

Combined with a structured JSON protocol, a Promise-based JavaScript wrapper, and early script injection via `addDocumentStartJavaScript`, you get a production-ready bridge that is secure, easy to extend, and pleasant to work with on both sides.

If you're still using `addJavascriptInterface`, it's time to migrate.

## Sample Project

A complete working implementation demonstrating all concepts in this article is available on GitHub:

[WebViewBridgeDemo](https://github.com/RandhirGupta/WebViewBridgeDemo) — Sample Android project showing secure WebView-to-native communication using `addWebMessageListener`.

## References

- [WebViewCompat.WebMessageListener API Reference](https://developer.android.com/reference/androidx/webkit/WebViewCompat.WebMessageListener)
- [WebViewCompat API Reference](https://developer.android.com/reference/kotlin/androidx/webkit/WebViewCompat)
- [AndroidX WebKit Releases](https://developer.android.com/jetpack/androidx/releases/webkit)
