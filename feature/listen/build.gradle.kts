plugins {
    id("bbspace.android.library")
    id("bbspace.android.compose")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:listen"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":infra:player"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.navigation.compose)

    implementation(libs.coil.compose)
}
