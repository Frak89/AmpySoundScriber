# Ponte JavaScript del motore di notazione (WebView)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# ONNX Runtime usa JNI
-keep class ai.onnxruntime.** { *; }
