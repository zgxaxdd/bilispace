import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.kotlin.dsl.configure

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // AGP 9 内置 Kotlin 支持：org.jetbrains.kotlin.android 已移除（Kotlin 2.3.0+ 下应用它会直接报错）
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.room) apply false
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // P1-7：Compose 重组指标开关——**唯一不需要设备就能量化重组行为**的手段。
    //
    // 挂在这里而不是各模块 build.gradle.kts：本项目 50+ 模块，漏挂一个就等于
    // 那个模块无法度量，而且**不会报错**。判据就是「模块有没有应用 compose 插件」。
    //
    // 用法：
    //   ./gradlew :feature:home:compileDebugKotlin --rerun -Pbbspace.compose.metrics=true
    // 产出 <module>/build/compose-reports/*-composables.txt（含 skippable 列）
    // 与 <module>/build/compose-metrics/*-classes.txt。
    //
    // ⚠️ **必须带 `--rerun`**：只加 -P 参数时增量编译会把 compileDebugKotlin 判成
    // UP-TO-DATE，报告根本不会重新生成（本轮实测踩过，见执行报告 §2.4）。
    //
    // ⚠️ `stabilityConfigurationFiles`（稳定性声明）本轮**实测无收益，故未启用**：
    // 同一份代码、同一台机器，加/不加声明两个模块的可跳过数完全一致
    // （core:designsystem 68/68、feature:home 22/22 全 skippable，0 个不可跳过），
    // 且 FeedItem 虽在 core/model 内、`List<FeedItem>` 仍被判 unstable。
    // 详见 docs/port-analysis/01-执行报告 §2.4 与 §3.5。
    pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
        extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
            if (providers.gradleProperty("bbspace.compose.metrics").orNull == "true") {
                reportsDestination = layout.buildDirectory.dir("compose-reports")
                metricsDestination = layout.buildDirectory.dir("compose-metrics")
            }
        }
    }

    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            lint {
                disable += "NullSafeMutableLiveData"
            }
        }
    }

    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            lint {
                disable += "NullSafeMutableLiveData"
            }
        }
    }
}