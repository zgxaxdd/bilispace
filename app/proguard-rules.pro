# Protobuf-lite: only keep field names, not entire classes.
# GeneratedMessageLite.dynamicMethod accesses fields by name,
# so renaming them (qn_ -> a) breaks deserialization.
# Let R8 still remove unused proto classes via normal reachability.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Keep only runtime entry points used by generated Lite messages and the app.
-keep class com.google.protobuf.GeneratedMessageLite { *; }
-keep class com.google.protobuf.ByteString { *; }
-keep class com.google.protobuf.Parser { *; }
-keep class com.google.protobuf.MessageLite { *; }
-keep class com.google.protobuf.Any { *; }
-keep class com.google.protobuf.Empty { *; }
-dontwarn com.google.protobuf.**

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# OPPO View 无缝动画（compileOnly，实现类由 ColorOS 系统在运行时提供，不打入 APK）。
# 系统在窗口动画过程中反射回调 AnimationCallback 的方法，R8 无法看见这条调用链，
# 需保留实现类的回调方法；兼容层对 LinkageError 的捕获保证非 OPPO 机型安全降级。
-keepclassmembers class * extends com.oplus.animation.OplusViewSeamless$AnimationCallback {
    public void animationProgress(float);
    public void onAnimationStart(boolean);
    public void onAnimationEnd(boolean);
}
# 桩类只存在于编译期（compileOnly），运行时由 ColorOS 系统注入；无缝路径被真实调用后
# 这些引用对 R8 可达，必须显式忽略缺失，否则 release 混淆直接失败。
-dontwarn com.oplus.animation.OplusViewSeamless
-dontwarn com.oplus.animation.OplusViewSeamless$AnimationCallback