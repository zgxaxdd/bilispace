import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * 所有 Android library 模块的公共配置。
 *
 * 这些值此前在 50 个模块的 `android { }` 里逐份内联，且经盘点**完全一致**
 * （compileSdk 36 / minSdk 24 / AndroidJUnitRunner / Java 17 ×50），
 * 因此搬到一处。`namespace` 默认由路径推导（见 [derivedNamespace]），
 * 与源码包结构不一致的少数模块仍可显式覆盖。
 *
 * 注意：`jvmTarget` 不在这里设 —— 根 build.gradle.kts 的 `subprojects { }`
 * 已对全部 KotlinCompile 统一设为 JVM_17，重复设置不会更正确、只会掩盖不一致。
 *
 * `buildConfig` 也刻意**不**默认开启：50 个模块里只有 app 与 feature/bbspace
 * 真的用到 BuildConfig。默认开启会给其余 48 个模块凭空生成一个 BuildConfig 类，
 * 属行为变更而非搬迁，需要时由模块自己声明。
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                namespace = derivedNamespace()
                compileSdk = 37

                defaultConfig {
                    // minSdk 33：Liquid Glass 全量生效的下限（AGSL = API 33），
                    // 全仓 12 处 API 功能性门控随之清零，无降级分支
                    minSdk = 33
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        }
    }

    /**
     * 由 Gradle 路径推导 namespace：`:core:model` → `com.naaammme.bbspace.core.model`。
     *
     * 这是**默认值**，不是唯一真相：模块若在 `android { }` 里显式写 namespace，
     * 会覆盖它（约定插件在 `plugins { }` 阶段先执行，模块 `android { }` 后执行，后者胜出）。
     *
     * 已用脚本对 50 个模块逐一核对，45 个与推导结果一致，4 个例外且都**必须**保留原值：
     *   - `app` → `com.naaammme.bbspace`（application 插件里写死）
     *   - `infra:network-http` → `...infra.http`、`infra:network-grpc` → `...infra.grpc`、
     *     `infra:network-web` → `...infra.web`
     *     ——这三个的历史 namespace 不含 `network` 段，与源码目录结构一致。
     *     强行改成推导值等于重命名 Java 包，需连带移动源码目录与全部 import，
     *     属于与本次目标无关的高风险改动，因此保留显式声明。
     */
    private fun Project.derivedNamespace(): String {
        val segments = path.removePrefix(":").split(":").filter { it.isNotBlank() }
        require(segments.isNotEmpty()) { "无法为根项目推导 namespace" }
        return (listOf("com", "naaammme", "bbspace") + segments.map { it.replace('-', '.') })
            .joinToString(".")
    }
}
