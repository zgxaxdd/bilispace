import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Compose 编译：应用 Kotlin Compose 编译器插件并打开 `buildFeatures.compose = true`。
 *
 * **它刻意不应用 [AndroidLibraryConventionPlugin]，也不再等待 Android 扩展** ——
 * 两者都试过并失败，原因值得记下来，避免后人"简化"回去：
 *
 * 1. 合并成一个插件（compose 顺带补 library）在 `app` 上会冲突：app 是
 *    application + compose，两个 Android 插件不能共存。用 `afterEvaluate` 按类型判定
 *    也不行 —— `afterEvaluate` 晚于 `dependencies { }`，`api`/`implementation`
 *    会直接变成 `Unresolved reference`。
 *
 * 2. 等待 Android 扩展（`pluginManager.withPlugin`）同样不成立：`plugins { }` 块里
 *    如果 compose 写在 library 前面，扩展此刻还不存在，抛
 *    "Extension of type 'CommonExtension' does not exist"。
 *
 * 因此职责切分是：**本插件只负责 Compose，library 相关一律交给
 * [AndroidLibraryConventionPlugin]**；模块的 `plugins { }` 里
 * `id("bbspace.android.library")` 必须排在本插件之前（构建脚本由脚本自动生成，
 * 顺序是稳定的）。这样 `configure<CommonExtension>` 在应用时扩展必然已就绪，
 * 不需要任何等待或延迟。
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<CommonExtension> {
                buildFeatures.compose = true
            }
        }
    }
}
