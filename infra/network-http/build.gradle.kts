plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.naaammme.bbspace.infra.http"
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    api(libs.retrofit)
    api(libs.retrofit.kotlin.serialization)
    api(libs.okhttp)
    api(libs.okhttp.brotli)
    api(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(project(":infra:crypto"))
    implementation(project(":infra:protobuf"))
}
