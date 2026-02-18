# Beyond addJavascriptInterface: Building a Secure WebView Bridge in Android

If you've worked on hybrid Android apps, you've probably used `addJavascriptInterface` to let your WebView talk to native code. It works, but it has a well-known problem: on Android versions before 4.2, any JavaScript running in the WebView could exploit the injected object via reflection to call arbitrary methods. Even on newer versions, every `@JavascriptInterface` method you expose is directly callable by any script in the WebView — including scripts you didn't write.

AndroidX WebKit offers a better alternative: `WebViewCompat.addWebMessageListener()`. Instead of exposing Java objects directly, it uses message-passing. This article covers how it works and how to build a production-ready bridge on top of it.

## The API

```kotlin
WebViewCompat.addWebMessageListener(
    webView: WebView,
    jsObjectName: String,
    allowedOriginRules: Set<String>,
    listener: WebViewCompat.WebMessageListener
)
```

This injects a JavaScript object (named `jsObjectName`) into the global scope of every page that matches your origin rules. The object exposes two things:

- `postMessage(String)` — sends a string from JavaScript to your native listener
- `onmessage` — a handler that receives strings sent back from native

The native listener looks like this:

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

When JavaScript calls `NativeBridge.postMessage("something")`, your listener fires. You read the message from `message.data`, do whatever you need, and respond using `replyProxy.postMessage("response")`. The response arrives on the JavaScript side via the `onmessage` handler.

## Why this is better than addJavascriptInterface

**No reflection exposure.** There's no Java object in the WebView's JavaScript context. Attackers can't use reflection to reach beyond what you explicitly handle in your listener.

**Origin whitelisting.** You specify which origins can access the bridge. If a malicious page gets loaded in your WebView, it simply can't talk to native code unless its origin is in your allowlist.

**Frame awareness.** The `isMainFrame` parameter tells you whether the message came from the main frame or an iframe, so you can reject messages from iframes if you want.

**String-only communication.** All data passes as strings. You parse what you expect and ignore everything else. This gives you a narrow, auditable API surface.

## Origin rules

Each rule follows the format `SCHEME "://" HOSTNAME_PATTERN [ ":" PORT ]`:

- `https://example.com` — exact match only
- `https://*.example.com` — subdomains of example.com (not example.com itself)
- `https://example.com:8080` — specific port
- `http://192.168.1.1` — IP address
- `*` — matches everything (don't use this in production)

## Building an actual bridge

The raw `postMessage`/`onmessage` API only passes strings back and forth. For a real app, you need a protocol on top of it — something that lets you make named method calls, pass arguments, and match responses to requests.

Here's what I landed on. Each message is JSON with a `callbackId`, a `method` name, and an `args` object:

```json
{"callbackId": "cb_1_1708345123456", "method": "getDeviceInfo", "args": {}}
```

The native side parses this, routes to the right handler, and sends back a response with the same `callbackId`:

```json
{"callbackId": "cb_1_1708345123456", "result": {"manufacturer": "Google", "model": "Pixel 8"}}
```

Or on error:

```json
{"callbackId": "cb_1_1708345123456", "error": {"message": "Unknown method", "code": "BRIDGE_ERROR"}}
```

The `callbackId` is what makes concurrent calls work — without it, you can't tell which response belongs to which request.

### Native side

The message handler parses the JSON and routes based on the `method` field:

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
```

Sending responses:

```kotlin
private fun sendSuccess(replyProxy: JavaScriptReplyProxy, callbackId: String, result: JSONObject) {
    val response = JSONObject().apply {
        put("callbackId", callbackId)
        put("result", result)
    }
    replyProxy.postMessage(response.toString())
}

private fun sendError(replyProxy: JavaScriptReplyProxy, callbackId: String, errorMessage: String) {
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

### JavaScript side

On the web side, I wrapped the low-level `NativeBridge` object with a Promise-based API. JavaScript calls go through `window.Native.postMessage()`, which generates a unique callback ID, stashes the Promise's resolve/reject in a map, and sends the JSON to native. When the response comes back, the `onmessage` handler looks up the callback ID and resolves or rejects the corresponding Promise.

```javascript
(function() {
    'use strict';

    var callbacks = {};
    var counter = 0;

    function generateId() {
        return 'cb_' + (++counter) + '_' + Date.now();
    }

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

    window.Native = {
        postMessage: function(message) {
            return new Promise(function(resolve, reject) {
                var id = generateId();

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

With this wrapper, calling native code from JavaScript looks like any other async call:

```javascript
const info = await window.Native.postMessage({ method: 'getDeviceInfo' });
console.log(info.manufacturer, info.model);

await window.Native.postMessage({
    method: 'showToast',
    args: { message: 'Hello from WebView!', duration: 'short' }
});
```

## Injecting the wrapper early

There's a timing issue. The web page might try to use `window.Native` before the wrapper script has been injected. If you inject it in `onPageFinished`, you're too late — page scripts may have already run.

`addDocumentStartJavaScript()` solves this. It injects your script at document start, before any page scripts execute:

```kotlin
if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
    WebViewCompat.addDocumentStartJavaScript(webView, BRIDGE_WRAPPER_SCRIPT, ALLOWED_ORIGINS)
}
```

This requires AndroidX WebKit 1.6.0+ and a compatible System WebView.

## Watch out: NativeBridge.postMessage vs window.postMessage

These are completely unrelated APIs. `window.postMessage()` is the browser's cross-origin messaging between frames and windows. `NativeBridge.postMessage()` is the WebView bridge to native Android code. Don't mix them up — the event formats and behavior are different.

## Feature support

Both APIs require a runtime feature check before use:

```kotlin
if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
    // Safe to use addWebMessageListener
}
```

## Requirements

- AndroidX WebKit 1.6.0+
- Android System WebView 74+
- minSdk 24

```kotlin
implementation("androidx.webkit:webkit:1.9.0")
```

## Things to keep in mind

- Never use `"*"` as an origin rule in production. Whitelist specific domains.
- Don't trust incoming messages. Validate everything on the native side.
- Use `callbackId` (or some equivalent) to match responses to requests. Without it, concurrent calls break.
- Check `isMainFrame` if you want to block iframes from accessing the bridge.
- Use `addDocumentStartJavaScript` to avoid race conditions between your wrapper and page scripts.

## Sample project

The full working implementation is on GitHub: [WebViewBridgeDemo](https://github.com/RandhirGupta/WebViewBridgeDemo)

## References

- [WebViewCompat.WebMessageListener](https://developer.android.com/reference/androidx/webkit/WebViewCompat.WebMessageListener)
- [WebViewCompat](https://developer.android.com/reference/kotlin/androidx/webkit/WebViewCompat)
- [AndroidX WebKit releases](https://developer.android.com/jetpack/androidx/releases/webkit)
