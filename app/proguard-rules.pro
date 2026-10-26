# Keep ONNX Runtime JNI bindings if minification is enabled later.
-keep class ai.onnxruntime.** { *; }

# The FLAC encoder references optional javax.sound classes that are absent on Android.
-dontwarn javax.sound.**
