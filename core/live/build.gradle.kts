plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    api(project(":core:model"))
    implementation(project(":core:auth"))
    implementation(project(":core:common"))
    implementation(project(":core:published"))
    implementation(project(":infra:network-grpc"))
    implementation(project(":infra:network-http"))
    implementation(project(":infra:crypto"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // B7（2026-10-13，上游 v0.3.6 同步）：`LiveDanmakuProtocol` 的字节级健壮性测试
    // （恶意嵌套包 / 损坏压缩包 / 每帧包数上限）。协议解析是纯逻辑，不需要 Robolectric。
    testImplementation(libs.junit)
    // ⚠️ 必需：`org.json` 在 Android 上由系统提供，JVM 单测里是**未实现的桩**
    // （调用即 `RuntimeException: Method optString in org.json.JSONObject not mocked`）。
    // 加真实实现才能断言"解出来的 JSON 内容对不对"，而不只是"解出了几条"。
    // 版本与 `android-37` 的 `org.json` 语义一致（同为 org.json 官方实现）。
    testImplementation(libs.org.json)
}
