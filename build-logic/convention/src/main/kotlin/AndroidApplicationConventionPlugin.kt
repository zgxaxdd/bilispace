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
                compileSdk = 36

                defaultConfig {
                    minSdk = 24
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
