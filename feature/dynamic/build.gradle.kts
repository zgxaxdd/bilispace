plugins {
    id("bbspace.android.library")
    id("bbspace.android.compose")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}


dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:dynamic"))
    implementation(project(":core:comment"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:comment"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.navigation.compose)
    // P2-6：空壳模块 `:core:navigation` 删除后，补齐它原本经 `api` 传递的两个 artifact。
    // 本模块**真的在用**它们（`androidx.navigation.*` 的 NavController/NavGraphBuilder、
    // 以及 `androidx.activity.result.PickVisualMediaRequest`），不是占位依赖。
    implementation(libs.navigation.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.coil.compose)
}
