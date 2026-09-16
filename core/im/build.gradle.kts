plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":infra:network-grpc"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.core.ktx)
}
