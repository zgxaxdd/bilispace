plugins {
    `java-library`
}

java {
    // 与 AGP 9.4.0 对齐的 lint-api；这个 jar 只被 Lint 消费，**不进 APK**。
    // 上游 BiliPai 写的是 9.3.2 对应的 32.3.2，本仓 AGP 9.4.0 ⇒ 必须取 32.4.1，
    // 否则 IssueRegistry.getApi() 与 CURRENT_API 不匹配，检测器会被静默跳过。
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly("com.android.tools.lint:lint-api:32.4.1")
}

tasks.jar {
    manifest.attributes["Lint-Registry-v2"] = "com.naaammme.bbspace.lint.CiIssueRegistry"
}
