plugins {
    alias(libs.plugins.android.library)
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.naaammme.bbspace.core.designsystem"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
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

    implementation(libs.coil.compose)
    implementation(libs.google.material)
    // ForegroundEnterBox 的回前台判据依赖进程级生命周期
    implementation(libs.androidx.lifecycle.process)

    // OPPO View 无缝动画：仅参与编译，运行时类由 ColorOS 16.1+ 系统提供，禁止打入 APK
    compileOnly(libs.oplus.viewseamless)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
