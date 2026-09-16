plugins {
    id("bbspace.android.library")
    id("bbspace.android.compose")
    // AGP 9 built-in Kotlin: kotlin.android removed
}


dependencies {
    implementation(platform(libs.androidx.compose.bom))
    api(libs.navigation.compose)
}
