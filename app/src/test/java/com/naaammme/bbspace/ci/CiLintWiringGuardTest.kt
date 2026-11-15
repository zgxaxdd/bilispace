package com.naaammme.bbspace.ci

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A2 接线的结构守卫：`ci-lint` 模块必须真的挂在 Lint 上。
 *
 * ## 为什么需要它
 *
 * A2 这条链路有**四个**断点，任何一处失效都**不报错、只是不跑**：
 * ① `settings.gradle.kts` 少 `include(":ci-lint")` ⇒ 模块根本不进构建；
 * ② `build.gradle.kts` 的 `subprojects` 少 `add("lintChecks", ...)` ⇒ jar 打出来但
 *    Lint 不知道它；
 * ③ `tasks.jar` 少 `Lint-Registry-v2` manifest 属性 ⇒ Lint 找不到登记表类；
 * ④ `CiIssueRegistry.getApi()` 与 `CURRENT_API` 不匹配 ⇒ 检测器被**静默跳过**
 *    （这条最毒：编译通过、lint 跑完、报告干净，但一条规则都没生效）。
 *
 * JVM 单测跑不出这些（它们全在 Gradle/打包层），而 `lint-tests` 又不在本地缓存
 * （见执行报告 §1.10）⇒ 按本仓既有惯例走源码结构断言。
 *
 * ## 判据是**存在性 + 关键字**，不是行号
 *
 * 行号钉不得（改一行注释全线飘）。这里只钉"这段配置必须出现"与"必须引用哪个符号"。
 */
class CiLintWiringGuardTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun fileOf(relative: String): List<String> {
        val f = File(repoRoot, relative)
        assertTrue("找不到 `$relative`（repoRoot=${repoRoot.absolutePath}）—— 此时断言无意义。", f.isFile)
        return f.readLines()
    }

    @Test
    fun ciLintModuleIsIncludedInSettings() {
        val settings = fileOf("settings.gradle.kts")
        assertTrue(
            "settings.gradle.kts 必须 include(\":ci-lint\") —— 少了它模块根本不进构建，" +
                "而 lintChecks 那条接线会因为找不到项目直接报错（属于会叫的那种，但仍要钉住）。",
            settings.any { it.contains("include(\":ci-lint\")") }
        )
    }

    /**
     * 两个 Android 插件都要挂。只挂 application 的话，50+ 个 library 模块全不被检查 ——
     * 而**不会报错**。
     *
     * 判据按**区间**而不是"出现在两个块之后"：application 块内一处、library 块内一处，
     * 共 2 处。写成"wiring 必须在 libAt 之后"是错的（第一处本来就在 app 块里）。
     */
    @Test
    fun lintChecksIsWiredForBothAndroidPlugins() {
        val build = fileOf("build.gradle.kts")
        val wiring = "add(\"lintChecks\", rootProject.project(\":ci-lint\"))"
        val appAt = build.indexOfFirst { it.contains("pluginManager.withPlugin(\"com.android.application\")") }
        val libAt = build.indexOfFirst { it.contains("pluginManager.withPlugin(\"com.android.library\")") }
        val wiringAt = build.withIndex().filter { it.value.contains(wiring) }.map { it.index }

        assertTrue("build.gradle.kts 的 subprojects 里必须给 Android 模块挂 lintChecks —— 接线串是：$wiring", wiringAt.isNotEmpty())
        assertTrue("找不到 com.android.application 插件块", appAt >= 0)
        assertTrue("找不到 com.android.library 插件块", libAt >= 0)
        assertTrue(
            "lintChecks 必须挂 2 处（application 块内 + library 块内），实测 ${wiringAt.size} 处 —— " +
                "只挂一个，另一类模块全不被检查且不报错。",
            wiringAt.size >= 2
        )
        assertTrue(
            "application 块内（行 $appAt 起）必须有一处 lintChecks 接线；实测接线行 $wiringAt。",
            wiringAt.any { it > appAt && it < libAt }
        )
        assertTrue(
            "library 块内（行 $libAt 起）必须有一处 lintChecks 接线；实测接线行 $wiringAt。",
            wiringAt.any { it > libAt }
        )
    }

    @Test
    fun jarCarriesLintRegistryManifestAttribute() {
        val build = fileOf("ci-lint/build.gradle.kts")
        assertTrue(
            "ci-lint/build.gradle.kts 必须写 Lint-Registry-v2 manifest 属性 —— " +
                "少了它 Lint 找不到登记表类，模块白打。",
            build.any { it.contains("Lint-Registry-v2") }
        )
        assertTrue(
            "Lint-Registry-v2 必须指向 com.naaammme.bbspace.lint.CiIssueRegistry。",
            build.any { it.contains("com.naaammme.bbspace.lint.CiIssueRegistry") }
        )
    }

    /**
     * 最毒的一条：`getApi()` / `getMinApi()` 与 `CURRENT_API` 不匹配时，检测器被
     * **静默跳过** —— 编译通过、lint 跑完、报告干净，但一条规则都没生效。
     */
    @Test
    fun registryDeclaresCurrentApiAndRegistersTheDetector() {
        val registry = fileOf(
            "ci-lint/src/main/java/com/naaammme/bbspace/lint/CiIssueRegistry.java"
        )
        assertTrue(
            "CiIssueRegistry 必须 import ApiKt（CURRENT_API 的来源）。",
            registry.any { it.contains("import com.android.tools.lint.detector.api.ApiKt;") }
        )
        assertTrue(
            "getApi() 与 getMinApi() 必须返回 ApiKt.CURRENT_API —— 不匹配会被静默跳过。",
            registry.count { it.contains("ApiKt.CURRENT_API") } >= 2
        )
        assertTrue(
            "CiIssueRegistry 必须登记 NoPrintlnDetector.ISSUE —— 登记表里没有它，" +
                "检测器写好了也不会跑。",
            registry.any { it.contains("NoPrintlnDetector.ISSUE") }
        )
        val detector = fileOf(
            "ci-lint/src/main/java/com/naaammme/bbspace/lint/NoPrintlnDetector.java"
        )
        assertTrue(
            "NoPrintlnDetector 必须是 Detector.UastScanner（否则 visitMethodCall 不会被调）。",
            detector.any { it.contains("implements Detector.UastScanner") }
        )
        assertTrue(
            "必须覆盖 visitMethodCall（lint-api 32.x 里 visitMethod 已 deprecated）。",
            detector.any { it.contains("public void visitMethodCall(") }
        )
    }

    /**
     * 6 条规则必须**全部**登记 —— 上面那条只钉了 `NoPrintlnDetector`，
     * 于是另外 5 条曾经处于「文件在、检测器写好了、但没人断言过它被登记」的状态。
     *
     * ## 为什么这不能靠运行时自检
     *
     * 试过：给 `:ci-lint` 加 JUnit 测试，实例化/读 `CiIssueRegistry.ISSUES`。
     * **在离线依赖集下不可行**，而且失败原因很有代表性 —— 逐层暴露：
     * ① `testImplementation("…lint-api…")` ⇒ POM 的传递依赖（sdklib/dvlib/ddmlib/
     *    sdk-common）本仓没有缓存，Gradle 直接拒绝配置测试类路径；
     * ② 改成只喂 lint-api 本体 ⇒ `IssueRegistry.<clinit>` 要 Guava 的 `Maps`；
     * ③ 补 Guava ⇒ 还要 kotlin-stdlib 的 `Intrinsics`；
     * ④ 补 kotlin-stdlib ⇒ 还要 `org.jetbrains.uast.UElement`（6 个检测器都
     *    `implements Detector.UastScanner`，`ISSUES` 的静态初始化会加载它们）。
     * 那已经等于把整个 lint 运行时搬进依赖集。**结论：这条守卫只能走源码扫描。**
     *
     * ## 判据
     *
     * 登记表 {@code ISSUES} 里必须出现 6 个 {@code XxxDetector.ISSUE} 符号，
     * 且每个符号对应的 Detector 类文件必须存在 —— 双向闭合（漏登记 / 登记了但类被删
     * 都会红）。
     */
    @Test
    fun allSixDetectorsAreRegisteredAndExist() {
        val registry = fileOf(
            "ci-lint/src/main/java/com/naaammme/bbspace/lint/CiIssueRegistry.java"
        )
        // 预期清单：新增规则时这里和 CiIssueRegistry 一起改，漏一处就红。
        val expectedDetectors = listOf(
            "NoPrintlnDetector",
            "AnimateAsStateDetector",
            "HardcodedAnimationDurationDetector",
            "PathOutlineAllocationDetector",
            "RunBlockingNeedsBoundDetector",
            "SpLiteralDetector"
        )
        expectedDetectors.forEach { name ->
            assertTrue(
                "CiIssueRegistry 的 ISSUES 必须登记 `$name.ISSUE` —— 检测器写好了但没登记，" +
                    "规则永远不会跑（且不会报错）。",
                registry.any { it.contains("$name.ISSUE") }
            )
            val src = fileOf("ci-lint/src/main/java/com/naaammme/bbspace/lint/$name.java")
            assertTrue(
                "$name.java 必须定义 `public static final Issue ISSUE`，否则登记表引用不到。",
                src.any { it.contains("public static final Issue ISSUE") }
            )
            assertTrue(
                "$name 必须是 Severity.ERROR —— 这些规则取代的是原先会让单测变红的计数棘轮，" +
                    "降成 WARNING 等于悄悄放宽守卫。",
                src.any { it.contains("Severity.ERROR") }
            )
        }
        // 反向：登记表里不许出现预期之外的其他 Detector.ISSUE（防止悄悄加了规则却没人管）。
        val registeredSymbols = registry.filter { it.contains("Detector.ISSUE") }
            .mapNotNull { Regex("""(\w+Detector)\.ISSUE""").find(it)?.groupValues?.get(1) }
            .toSet()
        assertTrue(
            "CiIssueRegistry 登记了预期之外的检测器：${registeredSymbols - expectedDetectors.toSet()}。" +
                "新增规则请同时更新本测试的 expectedDetectors 清单。",
            registeredSymbols - expectedDetectors.toSet() == emptySet<String>()
        )
    }

    /**
     * 每条规则都必须声明 `Category` 与一段**可执行的修复指引**。
     *
     * 判据是 `Issue.create(...)` 的实参里出现 `Category.X` 与 `Severity.ERROR`，
     * 且文件里存在 `context.report(` —— 没有 report 的检测器等于空规则。
     */
    @Test
    fun everyDetectorActuallyReports() {
        val names = listOf(
            "NoPrintlnDetector",
            "AnimateAsStateDetector",
            "HardcodedAnimationDurationDetector",
            "PathOutlineAllocationDetector",
            "RunBlockingNeedsBoundDetector",
            "SpLiteralDetector"
        )
        names.forEach { name ->
            val src = fileOf("ci-lint/src/main/java/com/naaammme/bbspace/lint/$name.java")
            val text = src.joinToString("\n")
            assertTrue(
                "$name 里找不到 `context.report(` —— 检测器不报告任何东西，等于空规则。",
                text.contains("context.report(")
            )
            assertTrue(
                "$name 的 Issue.create 必须指定 Category —— 报告要能按类别阅读。",
                Regex("""Category\.[A-Z_]+""").containsMatchIn(text)
            )
            assertTrue(
                "$name 必须声明 JAVA_FILE_SCOPE（UAST 检测器只对源码文件派发）。",
                text.contains("Scope.JAVA_FILE_SCOPE")
            )
        }
    }
}
