plugins {
    id("bbspace.android.library")
    id("bbspace.android.compose")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val appVersionName: String = (project.findProperty("versionName") as String?) ?: "1.0"

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:history"))
    implementation(project(":core:published"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:comment"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
