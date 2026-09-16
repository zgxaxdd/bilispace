plugins {
    id("bbspace.android.library")
    id("bbspace.android.compose")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    implementation(project(":core:live"))
    implementation(project(":core:playback"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:settings"))
    implementation(project(":infra:player"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
}
