package com.naaammme.bbspace.perf

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Baseline Profile 的**自有 Compose 覆盖率**棘轮（报告 P0-1）。
 *
 * ## 为什么需要这条测试
 * 修这个缺陷之前，`app/src/main/baseline-prof.txt` 里 40 条自有类规则全是
 * `HSPL<类>;->*()V`。实测展开产物（`expandReleaseArtProfileWildcards`）：
 * **属于 bbspace 自有类的规则 79 条，带 `Composer` 签名的 0 条** ——
 * `HomeScreenKt` / `ThemeKt` / `AppNavHostKt` 各自只剩一条 `-><clinit>()V`。
 * 也就是说：**三方库进了 profile，自家首屏 Compose 一条没进**。
 *
 * 这个缺陷**不会报错、不会红、APK 里也确实有 `baseline.prof`**，唯一的现象是
 * "profile 收益不如预期"。所以只能靠一条显式断言把它钉住：
 * 自有类里带 `Composer` 签名的规则数**只许多不许少**。
 *
 * ## 为什么是源码扫描而不是读构建产物
 * 展开产物要先跑 `:app:expandReleaseArtProfileWildcards`（release 变体）才有，
 * 单测里读不到。源码扫描能挡住"有人把这段规则删了/又退回 `->*()V`"这一主要回归路径；
 * 构建产物的验收仍以本文件顶部的命令为准（见报告 P0-1「可验证预期效果」）。
 *
 * ## 为什么不能退回通配
 * 实测：把自有 Compose 类换成包级通配写法（类名部分用 `**`、方法部分仍是 `*()V`，
 * 即 `HSPLcom/naaammme/bbspace/feature/home/[star][star];->[star]()V`）后，
 * 展开产物里带 `Composer` 的自有规则**仍然是 0**。所以只能写显式签名。
 */
class BaselineProfileComposeCoverageTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun rules(): List<String> {
        val file = File(repoRoot, "app/src/main/baseline-prof.txt")
        assertTrue("找不到 baseline-prof.txt（repoRoot=${repoRoot.absolutePath}）", file.isFile)
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun ownComposeRules(): List<String> =
        rules().filter { it.startsWith("HSPLcom/naaammme/") && it.contains("Composer;") }

    /**
     * 棘轮：自有类里带 `Composer` 签名的显式规则数。
     *
     * 首次落地时实测 **94** 条。这个数字只许增：新增首屏 Compose 组件时应当补规则，
     * 删规则（或退回 `->*()V`）会让它掉下来 → 红。
     */
    @Test
    fun ownComposeMethodsHaveExplicitProfileRules() {
        val count = ownComposeRules().size
        assertTrue(
            "自有类的 Composer 显式规则只有 $count 条（下限 $MIN_OWN_COMPOSE_RULES）。" +
                "这些规则是 javap 从 release 类里取的**真实签名**，不要手写、也不要删——" +
                "退回 `HSPL<类>;->*()V` 会让展开产物里的自有 Composer 规则数归零（实测过）。",
            count >= MIN_OWN_COMPOSE_RULES
        )
    }

    /** 首屏路径上那几支必须各有规则——它们正是当初被漏掉的那批。 */
    @Test
    fun coldStartComposeEntryPointsAreCovered() {
        val own = ownComposeRules().joinToString("\n")
        val required = listOf(
            "com/naaammme/bbspace/feature/home/HomeScreenKt;",
            "com/naaammme/bbspace/feature/home/video/HomeVideoPageKt;",
            "com/naaammme/bbspace/navigation/AppNavHostKt;",
            "com/naaammme/bbspace/core/designsystem/theme/ThemeKt;",
            "com/naaammme/bbspace/core/designsystem/component/AdaptiveMediaGridKt;",
            "com/naaammme/bbspace/core/designsystem/component/CoverImageKt;",
            "com/naaammme/bbspace/playback/PlaybackHostKt;"
        )
        required.forEach { klass ->
            assertTrue(
                "$klass 一条带 `Composer` 签名的规则都没有——它又退回只剩 `<clinit>()V` 了",
                own.contains(klass)
            )
        }
    }

    /** 防假绿：不许用"看起来像规则"的通配把数量凑出来。 */
    @Test
    fun composeCoverageIsNotFakedByWildcards() {
        val faked = ownComposeRules().filter { it.contains("**") || it.contains("*()V") }
        assertTrue(
            "自有 Compose 规则里出现了通配写法（实测对自有类无效，只是看起来覆盖了）：$faked",
            faked.isEmpty()
        )
    }

    /** 非 Compose 类的 `*()V` 规则必须保留：它们命中 `<clinit>`/无参方法，是正确用法。 */
    @Test
    fun nonComposeStarRulesAreKept() {
        val star = rules().filter { it.startsWith("HSPLcom/naaammme/") && it.endsWith(";->*()V") }
        assertTrue(
            "自有类的 `->*()V` 规则被整体删掉了（$star）。它们对 AppInitializer/BiliApplication " +
                "这类非 Compose 类是正确用法，报告 §3.7.4 明确要求保留。",
            star.isNotEmpty()
        )
    }

    private companion object {
        /** 落地实测值 94；允许未来只增不减。 */
        const val MIN_OWN_COMPOSE_RULES = 94
    }
}
