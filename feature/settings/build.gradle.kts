plugins {
    id("bbspace.android.library")
    id("bbspace.android.compose")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:settings"))
    implementation(project(":core:sponsorblock"))
    implementation(project(":core:designsystem"))
    // 刷视频诊断页（SW-06）读 SwipeQoeHub/SwipeQoeMath（core:swipe，无 UI 依赖）
    implementation(project(":core:swipe"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // PT-10 V9：推荐来源设置页文案守卫（纯 JVM 源码结构断言）
    testImplementation(libs.junit)
}
