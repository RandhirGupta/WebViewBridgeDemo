# WebView Bridge Demo

A sample Android project demonstrating secure communication between WebView and native code using `WebViewCompat.addWebMessageListener`.

## Features

- Secure WebView-to-Native communication
- Origin whitelisting
- Promise-based JavaScript API
- Request/Response pattern with unique callback IDs
- Early script injection via `addDocumentStartJavaScript`
- Eight demo endpoints: ping, getDeviceInfo, getAppInfo, showToast, getPreference, setPreference, copyToClipboard, share

## Project Structure

```
WebViewBridgeDemo/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/webviewbridge/
│   │   │   └── MainActivity.kt          # Native bridge implementation
│   │   ├── assets/
│   │   │   └── test_bridge.html         # Demo web page
│   │   └── res/
│   │       └── layout/activity_main.xml
│   └── build.gradle.kts
├── ARTICLE.md                            # Full technical article
└── README.md
```

## How It Works

### Native Side (Kotlin)

```kotlin
WebViewCompat.addWebMessageListener(
    webView,
    "NativeBridge",                    // JS object name
    setOf("https://trusted.com")       // Allowed origins
) { _, message, _, _, replyProxy ->
    // Handle message and reply
    replyProxy.postMessage(response)
}
```

### JavaScript Side

```javascript
// The bridge is auto-injected
NativeBridge.onmessage = (event) => {
    console.log(event.data);  // Response from native
};

NativeBridge.postMessage('{"action":"ping"}');
```

## Building

1. Open the project in Android Studio
2. Sync Gradle files
3. Run on device/emulator (API 24+)

## Key Points

1. **Use `addDocumentStartJavaScript`** to inject the bridge wrapper early — avoids race conditions with page scripts
2. **Use `NativeBridge.onmessage`** — Not `window.addEventListener("message", ...)`
3. **Use `NativeBridge.postMessage`** — Not `window.postMessage()`

See [ARTICLE.md](ARTICLE.md) for the full technical article.

## Requirements

- Android Studio Hedgehog or later
- minSdk 24
- compileSdk 34
- AndroidX WebKit 1.9.0+
