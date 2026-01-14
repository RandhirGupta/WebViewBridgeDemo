# Secure Communication Between Android WebView and Native Code: A Modern Approach

When developing hybrid Android applications, establishing a secure communication channel between the WebView (which runs web content) and the native layer (Java/Kotlin) is essential. This bridge unlocks powerful integrations - allowing web pages to access native capabilities such as the camera, storage, or sensors - while still maintaining the sandboxed environment.

The key question is: **how can we expose native functionality without compromising security?** Let's explore.

## Why Not Just Use addJavascriptInterface?

In the past, Android developers commonly relied on `addJavascriptInterface` to bridge WebView and native code. However, this method came with significant security risks - especially on devices running versions prior to Android 4.2 - where malicious JavaScript could exploit injected objects to invoke arbitrary methods via reflection.

The modern and safer alternative is to use **WebViewCompat.addWebMessageListener** from the AndroidX WebKit library. This approach:

- Restricts communication to whitelisted origins only
- Uses string-based message passing (no direct method exposure)
- Provides a clean async communication pattern
- Automatically injects the JavaScript bridge object

## How addWebMessageListener Works

When you call `WebViewCompat.addWebMessageListener()`, the AndroidX WebKit library:

1. **Injects a JavaScript object** with the name you specify into the web page
2. **Provides a `postMessage()` method** on that object for web-to-native communication
3. **Provides an `onmessage` handler** on that object for native-to-web responses

This is fundamentally different from `window.postMessage()` (the browser's cross-origin messaging API). Don't confuse the two!

## 1. Setting Up the Native Side (Kotlin)

```kotlin
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    companion object {
        private const val BRIDGE_NAME = "NativeBridge"
        // Whitelist your trusted domains
        private val ALLOWED_ORIGINS = setOf("https://trusted.example.com")
        // For local testing with file:// URLs, use: setOf("*")
    }

    private fun setupWebMessageListener() {
        // Always check if the feature is supported
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.e("WebView", "WebMessageListener not supported")
            return
        }

        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_NAME,           // JavaScript object name
            ALLOWED_ORIGINS        // Whitelisted origins
        ) { view, message, sourceOrigin, isMainFrame, replyProxy ->

            val messageData = message.data ?: return@addWebMessageListener

            try {
                val request = JSONObject(messageData)
                val action = request.optString("action")
                val requestId = request.optString("requestId")

                when (action) {
                    "ping" -> {
                        val response = JSONObject().apply {
                            put("requestId", requestId)
                            put("success", true)
                            put("data", JSONObject().put("message", "pong"))
                        }
                        replyProxy.postMessage(response.toString())
                    }

                    "getDeviceInfo" -> {
                        val deviceInfo = JSONObject().apply {
                            put("manufacturer", Build.MANUFACTURER)
                            put("model", Build.MODEL)
                            put("osVersion", Build.VERSION.RELEASE)
                        }
                        val response = JSONObject().apply {
                            put("requestId", requestId)
                            put("success", true)
                            put("data", deviceInfo)
                        }
                        replyProxy.postMessage(response.toString())
                    }

                    else -> {
                        val response = JSONObject().apply {
                            put("requestId", requestId)
                            put("success", false)
                            put("error", "Unknown action: $action")
                        }
                        replyProxy.postMessage(response.toString())
                    }
                }
            } catch (e: Exception) {
                Log.e("WebView", "Error processing message", e)
            }
        }
    }
}
```

### Key Points:

- **Feature check is required**: Not all WebView versions support this API
- **Origin whitelisting**: Only pages from allowed origins can communicate
- **String-based messages**: All communication is via JSON strings, not direct method calls
- **Request/Response pattern**: Use `requestId` to match responses to requests

## 2. JavaScript Side (Correct Implementation)

```javascript
/**
 * The NativeBridge object is automatically injected by
 * WebViewCompat.addWebMessageListener(). You don't need to create it.
 *
 * It provides:
 * - NativeBridge.postMessage(string) - Send to native
 * - NativeBridge.onmessage = function(event) {} - Receive from native
 */

// Store pending request callbacks
const pendingRequests = new Map();
let requestCounter = 0;

// Setup the response handler
function setupBridge() {
    if (typeof NativeBridge === 'undefined') {
        console.error('NativeBridge not available');
        return false;
    }

    // THIS IS THE CORRECT WAY to receive messages from native
    // The replyProxy.postMessage() triggers this handler
    NativeBridge.onmessage = function(event) {
        try {
            const response = JSON.parse(event.data);
            const { requestId, success, data, error } = response;

            if (requestId && pendingRequests.has(requestId)) {
                const { resolve, reject } = pendingRequests.get(requestId);
                pendingRequests.delete(requestId);

                if (success) {
                    resolve(data);
                } else {
                    reject(new Error(error));
                }
            }
        } catch (e) {
            console.error('Error parsing response:', e);
        }
    };

    return true;
}

// Generic function to send messages to native
function sendToNative(action, data = {}) {
    return new Promise((resolve, reject) => {
        if (typeof NativeBridge === 'undefined') {
            reject(new Error('Bridge not available'));
            return;
        }

        const requestId = `req_${++requestCounter}_${Date.now()}`;

        // Store callbacks
        pendingRequests.set(requestId, { resolve, reject });

        // Timeout after 10 seconds
        setTimeout(() => {
            if (pendingRequests.has(requestId)) {
                pendingRequests.delete(requestId);
                reject(new Error('Request timeout'));
            }
        }, 10000);

        // Send the message
        NativeBridge.postMessage(JSON.stringify({
            action,
            requestId,
            ...data
        }));
    });
}

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    setupBridge();
});
```

### Usage Examples:

```javascript
// Ping test
async function ping() {
    const result = await sendToNative('ping');
    console.log(result); // { message: "pong" }
}

// Get device info
async function getDeviceInfo() {
    const info = await sendToNative('getDeviceInfo');
    console.log(info);
    // { manufacturer: "Google", model: "Pixel 7", osVersion: "14" }
}

// With error handling
try {
    const info = await sendToNative('getDeviceInfo');
    displayDeviceInfo(info);
} catch (error) {
    console.error('Failed to get device info:', error.message);
}
```

## Common Mistakes to Avoid

### Mistake 1: Confusing window.postMessage with NativeBridge.postMessage

```javascript
// WRONG - This is the browser's cross-origin messaging API
window.postMessage("hello", "*");
window.addEventListener("message", (e) => { ... });

// CORRECT - This uses the injected WebView bridge
NativeBridge.postMessage("hello");
NativeBridge.onmessage = (e) => { ... };
```

### Mistake 2: Manually creating the bridge object

```javascript
// WRONG - Don't do this
window.NativeBridge = {
    postMessage: function(msg) {
        window.postMessage(msg, "*");
    }
};

// CORRECT - The bridge is automatically injected by addWebMessageListener
// Just use it directly: NativeBridge.postMessage(...)
```

### Mistake 3: Using addDocumentStartJavaScript for the bridge

`addDocumentStartJavaScript` is useful for injecting analytics or polyfills, but not needed for the bridge itself since `addWebMessageListener` handles injection automatically.

## When to Use addDocumentStartJavaScript

While not needed for the bridge itself, `addDocumentStartJavaScript` is useful for:

- Injecting polyfills before any page script runs
- Setting up global configurations
- Blocking or modifying certain browser APIs for security

```kotlin
// Example: Freeze the bridge to prevent tampering
val securityScript = """
    Object.freeze(NativeBridge);
""".trimIndent()

WebViewCompat.addDocumentStartJavaScript(
    webView,
    securityScript,
    setOf("https://trusted.example.com")
)
```

## Conceptual Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                        INITIALIZATION                           │
├─────────────────────────────────────────────────────────────────┤
│  1. Native calls addWebMessageListener("NativeBridge", ...)     │
│  2. WebView injects NativeBridge object into JavaScript         │
│  3. JavaScript sets up NativeBridge.onmessage handler           │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                     REQUEST/RESPONSE FLOW                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   JavaScript                          Native (Kotlin)           │
│   ──────────                          ───────────────           │
│                                                                 │
│   NativeBridge.postMessage(           WebMessageListener        │
│     '{"action":"ping"}'     ──────►   receives message          │
│   )                                           │                 │
│                                               ▼                 │
│                                       Process request           │
│                                               │                 │
│   NativeBridge.onmessage    ◄──────   replyProxy.postMessage(   │
│   receives response                     '{"success":true}'      │
│                                       )                         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Security Considerations

| Security Feature | Description |
|-----------------|-------------|
| **Origin Whitelisting** | Only trusted domains can communicate with native code |
| **String-only Messages** | No direct method invocation - all data passes as strings |
| **No Reflection Risk** | Unlike `addJavascriptInterface`, no reflection attacks possible |
| **Frame Awareness** | The `isMainFrame` parameter lets you restrict to main frame only |
| **Async by Design** | Request/response pattern prevents blocking attacks |

## Gradle Dependencies

```kotlin
dependencies {
    implementation("androidx.webkit:webkit:1.9.0")
}
```

## Minimum Requirements

- **minSdk**: 21 (Android 5.0)
- **WebView**: Chrome 74+ (for full WebMessageListener support)
- **AndroidX WebKit**: 1.4.0+ (for addWebMessageListener)

## Summary

The modern approach using `WebViewCompat.addWebMessageListener`:

1. **Automatically injects** a JavaScript bridge object
2. **Restricts communication** to whitelisted origins
3. **Uses string messages** instead of exposing methods
4. **Provides async responses** via `replyProxy`
5. **Is safer** than the legacy `addJavascriptInterface`

If you're still using `addJavascriptInterface`, it's time to migrate to this secure, modern approach.

## Sample Project

A complete working sample project is available in this repository demonstrating all the concepts above.
