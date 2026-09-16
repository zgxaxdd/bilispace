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
}
