plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    id("bbspace.android.room")
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:settings"))
    implementation(project(":infra:network-grpc"))
    implementation(project(":infra:player"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.okhttp)
}
