plugins {
    id("bbspace.android.library")
    // AGP 9 built-in Kotlin: kotlin.android removed
}


dependencies {
    // 刷视频模式的纯逻辑层（队列/去重/预取判据/交接时序）。JVM 可测、零 UI 依赖。
    // api(core:model)：SwipeEntry 持有 VideoTarget/FeedItem 等模型类型，
    // 且沿用 core:model 的惯例经 api 传出 compose-runtime 注解（@Immutable 标注，
    // 仅编译期提示、无 UI 依赖——见 core/model/build.gradle.kts 同款注释）。
    api(project(":core:model"))
    // 会话状态机（SW-02）对外暴露 StateFlow，调用方编译需要 coroutines 类型。
    api(libs.kotlinx.coroutines.core)
    // 会话快照的 JSON 编解码（进程重建恢复，SW-02）：只用 JsonObject 构建器，
    // 不引入 @Serializable 插件与 core:model 注解扩散。
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
