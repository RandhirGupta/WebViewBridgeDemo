# WebView Bridge Demo

A sample Android project demonstrating secure communication between WebView and native code using `WebViewCompat.addWebMessageListener`.

## Features

- Secure WebView-to-Native communication
- Origin whitelisting
- Promise-based JavaScript API
- Request/Response pattern with unique IDs
- Three demo endpoints: ping, getDeviceInfo, echo

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
├── ARTICLE_CORRECTED.md                  # Corrected article
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

## Key Corrections from Original Article

1. **No need for addDocumentStartJavaScript** - The `addWebMessageListener` automatically injects the bridge
2. **Use NativeBridge.onmessage** - Not `window.addEventListener("message", ...)`
3. **NativeBridge.postMessage** - Not `window.postMessage()`

See [ARTICLE_CORRECTED.md](ARTICLE_CORRECTED.md) for the full corrected article.

## Requirements

- Android Studio Hedgehog or later
- minSdk 24
- compileSdk 34
- AndroidX WebKit 1.9.0+
