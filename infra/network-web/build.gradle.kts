plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.naaammme.bbspace.infra.web"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":infra:crypto"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    api(libs.okhttp)
}
