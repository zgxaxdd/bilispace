plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    implementation(project(":core:settings"))
    implementation(project(":core:common"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
}
