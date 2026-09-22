plugins {
    id("bbspace.android.library")
    id("bbspace.android.compose")
    // AGP 9 built-in Kotlin: kotlin.android removed
}


dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))

    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.material.icons.core)
    api(libs.androidx.compose.material3.adaptive)
    api(libs.androidx.compose.material3.adaptive.layout)
    api(libs.androidx.compose.material3.adaptive.navigation)
    api(libs.androidx.compose.material3.navigation.suite)
    api(libs.androidx.window)

    // 玻璃体系（方案 1.4）：haze 核心 + 模糊效果 + 材质；api 因 GlassEffect 签名暴露 HazeState
    api(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.materials)
    // 液态玻璃（LIQUID 档）：miuix-blur 背板系统（layerBackdrop 录制 + drawBackdrop 采样），
    // api 因 GlassEffect/LiquidBackdrop 签名暴露 LayerBackdrop。BiliPai 同款实现。
    api(libs.miuix.blur)
    // 帧率守卫（GlassGuard）
    implementation(libs.androidx.metrics.performance)

    implementation(libs.coil.compose)
    // 封面代表色提取（VideoCardCoverColorStore）：androidx.palette 聚类取面积最大色。
    // api 因 VideoCardCoverColorStore 的 public 签名暴露 Palette.Swatch（可单测的纯策略）。
    api(libs.androidx.palette)
    implementation(libs.google.material)
    // ForegroundEnterBox 的回前台判据依赖进程级生命周期
    implementation(libs.androidx.lifecycle.process)

    // OPPO View 无缝动画：仅参与编译，运行时类由 ColorOS 16.1+ 系统提供，禁止打入 APK
    compileOnly(libs.oplus.viewseamless)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}

android {
    testOptions {
        // iOS 色板经 Color.tone()（ColorUtils.kt）依赖 android.graphics 的 HSV 换算，
        // 本地 JVM 单测里 framework 桩默认直接抛异常；改为返回默认值，使固定色板
        // 断言（surface == background 等，均为常量，不经过桩）可以运行。
        unitTests.isReturnDefaultValues = true
    }
}

// Compose compiler metrics: the only objective way to quantify recomposition.
// Output goes to <module>/build/compose-metrics and build/compose-reports.
// Diagnostic artifacts only; safe to delete with clean.
composeCompiler {
    metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
    reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
}
