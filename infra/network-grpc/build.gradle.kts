plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.naaammme.bbspace.infra.grpc"
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    api(project(":infra:protobuf"))

    implementation(project(":infra:network-http"))
    implementation(project(":infra:crypto"))
}
