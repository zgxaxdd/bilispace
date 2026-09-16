plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    api(project(":core:common"))
    api(project(":core:model"))

    implementation(project(":infra:crypto"))
    implementation(project(":infra:network-http"))
    implementation(project(":infra:coldstart"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.coil.core)
}
