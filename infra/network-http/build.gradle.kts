plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.naaammme.bbspace.infra.http"
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)

    // Retrofit 已移除：全仓 0 处使用（无 Retrofit.Builder / 无 @GET/@POST），
    // 实际网络层是手写 OkHttp + protobuf/gRPC。它此前以 `api` 声明，
    // 等于把一条完全用不到的依赖**传递给所有下游模块**。
    api(libs.okhttp)
    api(libs.okhttp.brotli)
    api(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(project(":infra:crypto"))
    implementation(project(":infra:protobuf"))
}
