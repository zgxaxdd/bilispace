plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
}


dependencies {
    // 应用常量（BiliConstants）
    implementation(project(":core:common"))

    // Protobuf 支持
    implementation(project(":infra:protobuf"))

    // Android 核心库
    implementation(libs.androidx.core.ktx)

    // 网络请求
    implementation(libs.okhttp)

    // T-1（2026-10-04 审查报告）：鉴权原语此前**零单测**（TicketGenerator 301 行 /
    // GuestIdGenerator 278 行 / DeviceInfoCollector 486 行 / RSA+AES 全是裸奔）。
    // 先补最纯、最要命的两块：AppSigner / WbiSigner 的签名串与 locale protobuf
    // （真值向量独立算得，见测试注释）。
    testImplementation(libs.junit)
}
