plugins {
    id("bbspace.android.library")
    id("bbspace.android.compose")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    implementation(project(":core:article"))
    implementation(project(":core:feed"))
    implementation(project(":core:listen"))
    implementation(project(":core:live"))
    implementation(project(":core:settings"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:rank"))

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

// Compose compiler metrics: the only objective way to quantify recomposition.
// Output goes to <module>/build/compose-metrics and build/compose-reports.
// Diagnostic artifacts only; safe to delete with clean.
composeCompiler {
    metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
    reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
}
