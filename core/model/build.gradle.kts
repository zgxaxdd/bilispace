plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
}


dependencies {
    // Compose Runtime for @Immutable annotation (lightweight, no UI dependencies)
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.runtime)

    testImplementation(libs.junit)
}
