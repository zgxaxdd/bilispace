plugins {
    // 公共 application 配置（namespace / compileSdk / minSdk / targetSdk / Java 17）
    // 由约定插件提供。顺序重要：compose 约定要配置 Android 扩展，
    // 因此必须排在 application 约定之后。
    id("bbspace.android.application")
    id("bbspace.android.compose")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 签名凭据：Gradle 属性优先，其次**环境变量**。
//
// ⚠️ 必须走 `providers.environmentVariable(...)` 而**不能**用 `System.getenv(...)`：
// 后者在配置阶段读值、不会被配置缓存跟踪 —— 开了配置缓存之后环境变量就传不进构建，
// 症状是打包时报 `KeytoolException: keystore password was incorrect`
// （密码实际为 null）。真机打包时踩过这个坑（见 report-2026-09-28-C10.md §8.3）。
val signingStoreFile: String? = providers.gradleProperty("SIGNING_STORE_FILE").orNull
    ?: providers.environmentVariable("SIGNING_STORE_FILE").orNull
val signingStorePassword: String? = providers.gradleProperty("SIGNING_STORE_PASSWORD").orNull
    ?: providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
val signingKeyAlias: String? = providers.gradleProperty("SIGNING_KEY_ALIAS").orNull
    ?: providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
val signingKeyPassword: String? = providers.gradleProperty("SIGNING_KEY_PASSWORD").orNull
    ?: providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
    ?: signingStorePassword
val hasReleaseSigning: Boolean =
    !signingStoreFile.isNullOrBlank() &&
        !signingStorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()

android {
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    defaultConfig {
        // 只留约定插件无法提供、且属于发布身份的项；
        // minSdk / targetSdk / testInstrumentationRunner 已由
        // bbspace.android.application 提供，不再重复。
        applicationId = "com.naaammme.bbspace"
        // 版本号唯一真相：默认值与 versionName 平铺在这里，
        // 发布时可用 -PversionCode= / -PversionName= 覆盖（CI 由 tag 推导）。
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 20000
        versionName = (project.findProperty("versionName") as String?) ?: "2.0.0"

        vectorDrawables.useSupportLibrary = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            // AGP 9.3+ 的新 optimization DSL：同时开启代码优化与优化资源缩减，
            // 并自动提供默认 Android keep 规则（含 @JavascriptInterface / @Keep），
            // 因此不再需要 getDefaultProguardFile("proguard-android-optimize.txt")。
            // 注意：绝不能与 isMinifyEnabled / isShrinkResources 同时使用（语义重叠）。
            optimization {
                enable = true
            }
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles("proguard-rules.pro")
        }
    }
    buildFeatures {
        // compose = true 由 bbspace.android.compose 设置；这里只留 BuildConfig
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":feature:home"))
    implementation(project(":feature:dynamic"))
    implementation(project(":feature:search"))
    implementation(project(":feature:space"))
    implementation(project(":feature:user"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:bbspace"))
    implementation(project(":feature:history"))
    implementation(project(":feature:favorite"))
    implementation(project(":feature:comment"))
    implementation(project(":feature:video"))
    implementation(project(":feature:live"))
    implementation(project(":feature:download"))
    implementation(project(":feature:webview"))
    implementation(project(":feature:listen"))
    implementation(project(":feature:im"))

    implementation(project(":core:auth"))
    implementation(project(":core:comment"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":core:download"))
    implementation(project(":core:feed"))
    implementation(project(":core:history"))
    implementation(project(":core:im"))
    implementation(project(":core:live"))
    implementation(project(":core:playback"))
    implementation(project(":core:settings"))
    implementation(project(":core:video"))

    implementation(project(":infra:coldstart"))
    implementation(project(":infra:crypto"))
    implementation(project(":infra:network-http"))
    implementation(project(":infra:network-grpc"))
    implementation(project(":infra:player"))

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    // P2-6：空壳模块 `:core:navigation` 删除后补齐它原本经 `api` 传递的 navigation-compose。
    // AppNavHost 直接用到 NavHost / rememberNavController / NavBackStackEntry。
    implementation(libs.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    implementation(libs.media3.session)
    // 手工 baseline profile（src/main/baseline-prof.txt）在低版本 ART 上的安装入口
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary.android)
}

ksp {
    arg("dagger.hilt.disableModulesHaveInstallInCheck", "true")
    arg("dagger.fastInit", "enabled")
}

hilt {
    enableAggregatingTask = true
}
