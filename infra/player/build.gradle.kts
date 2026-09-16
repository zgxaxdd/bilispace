plugins {
    id("bbspace.android.library")
    id("bbspace.android.compose")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":infra:crypto"))
    implementation(project(":infra:network-http"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.runtime)
    api(libs.media3.exoplayer)
    api(libs.media3.exoplayer.dash)
    api(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    api(libs.media3.ui)
    implementation(libs.danmakuFlameMaster)

    testImplementation(libs.junit)
}

