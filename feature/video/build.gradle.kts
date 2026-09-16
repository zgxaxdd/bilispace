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
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:navigation"))
    implementation(project(":core:settings"))
    implementation(project(":core:sponsorblock"))
    implementation(project(":infra:player"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    testImplementation(libs.junit)
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
