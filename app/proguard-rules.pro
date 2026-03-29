# Add project specific ProGuard rules here.
-keep class com.browser.app.devtools.DevToolsBridge { *; }
-keep class com.browser.app.extensions.ExtensionManager { *; }
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
