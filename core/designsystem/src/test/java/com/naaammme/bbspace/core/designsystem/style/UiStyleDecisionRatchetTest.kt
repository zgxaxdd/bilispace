package com.naaammme.bbspace.core.designsystem.style

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "读当前 UI 风格再分支"这件事的**散点登记棘轮**（BACKLOG UI-01）。
 *
 * ## 为什么不是一把禁掉
 * iOS / Material 两档并存是方案 §6 的现状：`UiStyle` 默认仍是 MATERIAL，
 * 四象限（两档 × 深浅）没走完真机走查之前不能翻转默认值，
 * 而把这些散点合并成"一套 token 两个实现"同样是**视觉**改动 —— 没有设备就只能
 * 看代码、看不到结果。所以这一批不合并，先把**扩散**按住：
 * 逐文件登记当前实测数量（2026-09-26，B21），只许减不许加。
 *
 * ## 为什么登记"文件 → 数量"而不是一个全局数字
 * 全局数字（"≤9"）允许"关掉一处、新开三处"仍然通过。逐文件登记才会：
 * 在新文件里加判定要先来这张表登记并写明理由；改老文件把两处并成一处，
 * 数字对不上就会红 —— 那时把表一起改小，就是它该有的方向。
 *
 * ## 收口的目标形态（下一轮真机走查时顺手做）
 * 组件内的材质判定改读 `GlassConfig`（`theme/GlassConfig` 那类"能力开关"），
 * 让"是不是 iOS"只决定**参数值**而不是决定**代码路径**；
 * 布局性分支（如 dock 的形态）保留判定，但每个都要在这张表上有理由。
 */
class UiStyleDecisionRatchetTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val mainSources: List<File> by lazy {
        repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    /** designsystem 内部是**实现这一档风格的地方**，判定出现在那里是本职，不计入散点。 */
    private val isDesignSystem = Regex("""(^|/)core/designsystem/""")

    private val sites: Map<String, Int> by lazy {
        mainSources
            .filterNot { isDesignSystem.containsMatchIn(it.relativeTo(repoRoot).path.replace('\\', '/')) }
            .map { file -> relOf(file) to decisionCount(file) }
            .filter { it.second > 0 }
            .toMap()
    }

    private fun relOf(file: File): String = file.relativeTo(repoRoot).path.replace('\\', '/')

    private fun decisionCount(file: File): Int = file.readLines().count { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) {
            false
        } else {
            DECISION.containsMatchIn(line)
        }
    }

    @Test
    fun scannerActuallySeesTheKnownSites() {
        val total = sites.values.sum()
        assertTrue(
            "designsystem 之外只找到 $total 处风格判定（预期 >= 9，repoRoot=${repoRoot.absolutePath}）。" +
                "少于登记基数说明扫描或正则失效，下面的棘轮会全部空转。",
            total >= REGISTERED.values.sum()
        )
    }

    /** 逐文件登记：数量对不上就是"多了新散点"或"收了旧散点"，两种都必须显式改表。 */
    @Test
    fun scatterIsNotGrowing() {
        val grown = sites.filter { (rel, count) -> (REGISTERED[rel] ?: 0) < count }
        assertTrue(
            "这些文件新增了对 UiStyle 的直接判定：\n" +
                grown.entries.joinToString("\n") { entry ->
                    "${entry.key} -> ${entry.value} 处（登记 ${(REGISTERED[entry.key] ?: 0)}）"
                } +
                "\n先问一句：这里需要的真是「两档不同的代码路径」，还是一个参数值？" +
                "材质/圆角/动效这类应当读 GlassConfig 或 token；确实要新判定，" +
                "就在 REGISTERED 里登记并写清为什么不能收进 token。",
            grown.isEmpty()
        )
        val stale = REGISTERED.keys.filter { it !in sites }
        assertTrue(
            "REGISTERED 里这些文件已经没有风格判定了（收口成功就把它删掉，棘轮才有方向性）：" +
                stale.joinToString(),
            stale.isEmpty()
        )
    }

    private companion object {
        val DECISION = Regex("""LocalUiStyle\.current\s*==\s*UiStyle\.IOS|config\.uiStyle\s*==\s*UiStyle\.IOS""")

        /** 2026-09-26（B21）实测。 */
        val REGISTERED = mapOf(
            // 底部 dock / 导航容器：两档的 dock 形态本身不同（iOS 浮动胶囊 vs M3 栏），
            // 是"布局性分支"，短期保留。
            "app/src/main/java/com/naaammme/bbspace/navigation/AppNavHost.kt" to 1,
            "app/src/main/java/com/naaammme/bbspace/playback/capsule/LinkedBottomDock.kt" to 1,
            // 账号页：iOS 档用 iOS 版返回键与行样式。
            "feature/auth/src/main/java/com/naaammme/bbspace/feature/auth/account/AccountScreen.kt" to 1,
            // 首页两处 + 视频页一处：顶栏搜索胶囊与卡片徽章材质，属"该读 GlassConfig"的候选。
            "feature/home/src/main/java/com/naaammme/bbspace/feature/home/HomeScreen.kt" to 1,
            // 2026-10-18（W11 Alt-1）：2 → **1**。删掉的是「角标玻璃材质」那一处判定
            // （`badgeGlassOn = iOS && LIQUID`）—— 它随每卡背板一起收口到非玻璃分支。
            // 剩下的是 `showMoreMenu` 的动作面板分支（iOS `IOSActionSheet` vs MATERIAL
            // `AlertDialog`），那是**真正的两套代码路径**，不能收进 token，保留。
            "feature/home/src/main/java/com/naaammme/bbspace/feature/home/video/HomeVideoPage.kt" to 1,
            // 设置项本身：读的是**待生效**的 config.uiStyle（不是环境值），这是风格选择器必需的。
            "feature/settings/src/main/java/com/naaammme/bbspace/feature/settings/appearance/AppearanceSettingsScreen.kt" to 1,
            "feature/settings/src/main/java/com/naaammme/bbspace/feature/settings/components/SettingItem.kt" to 2
        )
    }
}
