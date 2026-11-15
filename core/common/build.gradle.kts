plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // B12（2026-10-13，上游 v0.3.6 同步）：`OnDemandRequestCache` 的并发/取消语义
    // 必须用虚拟时间与可控调度器验证（数"加载实际发生了几次"是合并成立与否的硬证据）。
    testImplementation(libs.kotlinx.coroutines.test)
}
