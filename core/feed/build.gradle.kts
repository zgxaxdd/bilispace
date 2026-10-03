plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:auth"))
    implementation(project(":core:settings"))

    implementation(project(":infra:crypto"))
    implementation(project(":infra:network-http"))
    // PT-10：WEB 档推荐流走 WBI 签名，需要 WbiKeyProvider（与 :core:rank 同一依赖口径）。
    // 这是本次移植**唯一新增的模块依赖边**，已在执行报告里登记为对任务书"不新增依赖"的偏离。
    implementation(project(":infra:network-web"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    // JVM 单测下 org.json 需真实实现（android.jar 是 stub）——同 :core:rank 先例
    testImplementation("org.json:json:20240303")
}
