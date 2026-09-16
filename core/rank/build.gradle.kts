plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":infra:network-http"))
    implementation(project(":infra:network-web"))
    implementation(project(":infra:crypto"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    // JVM 单测下 org.json 需真实实现（android.jar 是 stub）
    testImplementation("org.json:json:20240303")
}
