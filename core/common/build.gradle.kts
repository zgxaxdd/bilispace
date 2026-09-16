plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
