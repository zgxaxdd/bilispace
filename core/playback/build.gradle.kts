plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    api(project(":core:model"))
    api(libs.media3.common)

    implementation(project(":core:common"))
    implementation(project(":core:settings"))
    implementation(project(":core:auth"))
    implementation(project(":core:danmaku"))
    implementation(project(":core:download"))
    implementation(project(":core:history"))
    implementation(project(":core:live"))
    implementation(project(":core:video"))
    implementation(project(":infra:crypto"))
    implementation(project(":infra:network-http"))
    implementation(project(":infra:player"))
    implementation(project(":infra:network-grpc"))
    implementation(project(":infra:network-web"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    // 弹幕分段会话（VodDanmakuSession）用假取段器做确定性调度测试
    testImplementation(libs.kotlinx.coroutines.test)
}
