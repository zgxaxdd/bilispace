plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    api(project(":core:model"))
    api(project(":core:designsystem"))

    implementation(project(":core:common"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    api(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
