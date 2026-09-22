import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * app 模块的公共配置。
 *
 * 同样只搬移既有配置，不新增：
 * - `namespace` 由路径推导（`:app` → `com.naaammme.bbspace`），与原显式值一致；
 * - `applicationId` / `versionCode` / `versionName` / `splits` / `buildTypes` / 签名
 *   **刻意留在 app/build.gradle.kts** —— 它们是产物身份与发布策略，不是可共享的样板，
 *   搬进来只会让"改版本号要翻约定插件"这种更糟的体验。
 * - `jvmTarget` 由根 `subprojects { }` 统一设置，此处不重复。
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")

            extensions.configure<ApplicationExtension> {
                namespace = "com.naaammme.bbspace"
                // compileSdk 37 + minor 2：AGP 9 的 `compileSdk { }` 块支持 minor 版本。
                // haze 2.0.0-beta03 的 AAR 元数据声明 `minCompileSdk=37, minCompileMinorSdk=2`，
                // 只写 `compileSdk = 37` 会被 AAR 元数据检查判为不满足。
                compileSdk {
                    version = release(37) {
                        minorApiLevel = 2
                    }
                }

                defaultConfig {
                    // minSdk 33：与 library 约定插件一致（Liquid Glass 下限，门控清零）
                    minSdk = 33
                    targetSdk = 36
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        }
    }
}
