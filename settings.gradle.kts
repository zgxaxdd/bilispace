pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

//includeBuild("../DanmakuFlameMaster") {
//    dependencySubstitution {
//        substitute(module("com.github.naaammme:DanmakuFlameMaster"))
//            .using(project(":DanmakuFlameMaster"))
//    }
//}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.naaammme")
            }
        }
    }
}

rootProject.name = "bbspace"
include(":app")

// 源码级 Lint 检测器（A2）：把「扫源码 + 钉行号/钉调用次数」的 JVM 守卫逐步换成
// 有语义解析的 Lint 诊断。当前只放 1 条规则（NoPrintln，零基线）作为**机制证明**，
// 既有守卫一条都没动 —— 见 docs/2026-10-13-上游同步落地-执行报告.md §1.10。
include(":ci-lint")

// Infra 层
include(":infra:protobuf")
include(":infra:crypto")
include(":infra:network-http")
include(":infra:network-web")
include(":infra:network-grpc")
include(":infra:coldstart")
include(":infra:player")

// Core 层
include(":core:model")
include(":core:common")
include(":core:auth")
include(":core:article")
include(":core:comment")
include(":core:danmaku")
include(":core:download")
include(":core:dynamic")
include(":core:feed")
include(":core:favorite")
include(":core:history")
include(":core:im")
include(":core:rank")
include(":core:listen")
include(":core:live")
include(":core:playback")
include(":core:published")
include(":core:search")
include(":core:settings")
include(":core:sponsorblock")
include(":core:space")
include(":core:swipe")
include(":core:video")
include(":core:designsystem")
// P2-6：`:core:navigation` 已删除 —— 它只有 build.gradle.kts、**没有任何源码**，
// 唯一作用是 `api(libs.navigation.compose)` 的传递边（已在 feature/video 显式补上）。
// 空模块会让人读结构图时误判分层，也白拉长配置阶段。

// Feature 层
include(":feature:home")
include(":feature:video")
include(":feature:comment")
include(":feature:dynamic")
include(":feature:search")
include(":feature:space")
include(":feature:user")
include(":feature:auth")
include(":feature:settings")
include(":feature:bbspace")
include(":feature:live")
include(":feature:history")
include(":feature:favorite")
include(":feature:download")
include(":feature:webview")
include(":feature:listen")
include(":feature:im")
include(":feature:rank")

