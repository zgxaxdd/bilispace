plugins {
    id("bbspace.android.library")
    id("bbspace.android.compose")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    implementation(project(":feature:comment"))
    implementation(project(":core:video"))
    implementation(project(":core:auth"))
    implementation(project(":core:favorite"))
    implementation(project(":core:playback"))
    implementation(project(":core:feed"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    // P2-6：`:core:navigation` 是**空壳模块**（只有 build.gradle.kts，没有任何源码），
    // 已删除。它唯一的作用是 `api(libs.navigation.compose)` 的传递边，
    // 所以这条依赖要在这里显式补上（其余 5 个原依赖方本来就有自己的一份）。
    implementation(libs.navigation.compose)
    implementation(project(":core:settings"))
    implementation(project(":core:sponsorblock"))
    implementation(project(":core:swipe"))
    implementation(project(":infra:player"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
    // 真机 instrumented 测试：CDN 档位对话框（`VideoCdnModeDialog`）。
    // 存在的理由：adb 合成点按**驱动不了本 app 的列表/对话框**（实测十几次全落空），
    // 语义查找（onNodeWithText）才是可靠路径 —— 这也是唯一能自动验 CDN 档位的办法。
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.coil.compose)
}

// Compose compiler metrics: the only objective way to quantify recomposition.
// Output goes to <module>/build/compose-metrics and build/compose-reports.
// Diagnostic artifacts only; safe to delete with clean.
composeCompiler {
    metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
    reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
}
