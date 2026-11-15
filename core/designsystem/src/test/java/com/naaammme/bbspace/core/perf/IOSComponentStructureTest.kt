package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * iOS 阶段 C 新组件的结构守卫（对照 compose-cupertino 提炼的规格常量）。
 *
 * ## 为什么用源码扫描而不是 UI 测试
 * 与本仓 [GlassDrawChainStructureTest]/[DesignTokenLintTest] 同一惯例：这些规格值
 * （270dp 卡宽、56dp 行高、发丝分隔线）是"写法层面的不变式"，JVM 断言取不到
 * Compose 语义树，而改掉任何一个数字都会让 iOS 观感走样且没有编译期检查拦截。
 *
 * ## 防假绿
 * 每条断言先确认目标源码真的被读到（读不到会 AssertionError），并跳过注释行。
 */
class IOSComponentStructureTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun sourceOf(vararg path: String): List<String> {
        val f = File(repoRoot, path.joinToString(File.separator))
        assertTrue("找不到源码 ${f.path}", f.isFile)
        return f.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
    }

    // ── 弹层 grabber ─────────────────────────────────────────────────────────

    /** iOS grabber 规格：36×5dp、2.5dp 全圆角；MATERIAL 分支必须回落 M3 默认。 */
    @Test
    fun sheetGrabberSpec() {
        val src = sourceOf("core", "designsystem", "src", "main", "java",
            "com", "naaammme", "bbspace", "core", "designsystem", "component", "BiliSheetGrabber.kt")
        val body = src.joinToString("\n")
        assertTrue("grabber 缺少 36.dp 宽度规格", body.contains("36.dp"))
        assertTrue("grabber 缺少 5.dp 高度规格", body.contains("5.dp"))
        assertTrue("grabber 缺少 2.5.dp 全圆角", body.contains("RoundedCornerShape(2.5.dp)"))
        assertTrue("grabber 的 MATERIAL 分支必须回落 BottomSheetDefaults.DragHandle（零回归）",
            body.contains("BottomSheetDefaults.DragHandle"))
        assertTrue("grabber 应消费 tertiaryLabel 角色色", body.contains("roles.tertiaryLabel"))
    }

    /**
     * 全部 ModalBottomSheet 都必须接入风格自适应 grabber。
     *
     * **2026-10-18（W6）**：名单从 7 处扩到 8 处 —— 外观设置页新增的「色彩风格」选择弹层
     * （22 档，`AppearanceSettingsScreen.kt:564` 已按约定写 `dragHandle = { BiliSheetGrabber() }`）。
     * 这条守卫是**写死名单**的：新加 sheet 不进名单就"不会被保护"，所以在同一批次里补进来，
     * 否则下一个 sheet 又会静默漏掉（W63 的同类病：没有编译期检查会拦住它）。
     */
    @Test
    fun allSheetsUseAdaptiveGrabber() {
        val sheetFiles = listOf(
            "feature/comment/src/main/java/com/naaammme/bbspace/feature/comment/editor/CommentEditorSheet.kt",
            "feature/download/src/main/java/com/naaammme/bbspace/feature/download/player/DownloadPlayerPane.kt",
            "feature/dynamic/src/main/java/com/naaammme/bbspace/feature/dynamic/DynamicScreen.kt",
            "feature/home/src/main/java/com/naaammme/bbspace/feature/home/interest/InterestDialog.kt",
            "feature/search/src/main/java/com/naaammme/bbspace/feature/search/filter/SearchFiltersSheet.kt",
            "feature/settings/src/main/java/com/naaammme/bbspace/feature/settings/appearance/AppearanceSettingsScreen.kt",
            "feature/video/src/main/java/com/naaammme/bbspace/feature/video/VideoScreen.kt",
            "feature/video/src/main/java/com/naaammme/bbspace/feature/video/detail/VideoDetailPage.kt"
        )
        for (rel in sheetFiles) {
            val f = File(repoRoot, rel.replace("/", File.separator))
            assertTrue("找不到弹层文件 $rel", f.isFile)
            val body = f.readLines()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString("\n")
            assertTrue(
                "$rel 未接入 dragHandle = { BiliSheetGrabber() }（风格自适应 grabber 收口）",
                body.contains("dragHandle = { BiliSheetGrabber() }")
            )
        }
    }

    // ── 动作面板 ─────────────────────────────────────────────────────────────

    /** ActionSheet 规格：行高 56dp、侧距 8dp、取消独立块、破坏性走角色色。 */
    @Test
    fun actionSheetSpec() {
        val src = sourceOf("core", "designsystem", "src", "main", "java",
            "com", "naaammme", "bbspace", "core", "designsystem", "component", "IOSActionSheet.kt")
        val body = src.joinToString("\n")
        assertTrue("动作行高应为 56dp（compose-cupertino ActionSheetButtonHeight）",
            body.contains("ActionRowHeight = 56.dp"))
        assertTrue("侧距应为 8dp（compose-cupertino ActionSheetSidePadding）",
            body.contains("SidePadding = 8.dp"))
        assertTrue("取消必须独立成块（与动作组之间有 8dp 间隙）",
            Regex("""Spacer\(Modifier\.height\(SidePadding\)\)""").containsMatchIn(body))
        assertTrue("破坏性动作必须走角色色 roles.destructive", body.contains("roles.destructive"))
        assertTrue("必须支持 reduceMotion 退化（纯 fade）", body.contains("reduceMotion"))
        assertTrue("必须支持二级子面板（children）", body.contains("subStack"))
    }

    // ── 警告框 ───────────────────────────────────────────────────────────────

    /** Alert 规格：270dp 卡宽、14dp 圆角、44dp 按钮行、0.5dp 发丝线、破坏性红。 */
    @Test
    fun alertSpec() {
        val src = sourceOf("core", "designsystem", "src", "main", "java",
            "com", "naaammme", "bbspace", "core", "designsystem", "component", "IOSAlertDialog.kt")
        val body = src.joinToString("\n")
        assertTrue("卡片宽度应为 270dp（CupertinoDialogsTokens.AlertDialogWidth）",
            body.contains("width(270.dp)"))
        assertTrue("卡片圆角应为 14dp", body.contains("RoundedCornerShape(14.dp)"))
        assertTrue("按钮行高应为 44dp", body.contains("height(44.dp)"))
        assertTrue("必须有 0.5dp 发丝分隔线", body.contains("0.5.dp"))
        assertTrue("破坏性动作必须走角色色 roles.destructive", body.contains("roles.destructive"))
        assertTrue("必须支持 reduceMotion 退化（纯 fade）", body.contains("reduceMotion"))
    }

    // ── 分组列表 ─────────────────────────────────────────────────────────────

    /** CapsuleGroup 必须提供 header/footer 槽位（Grouped Inset List 收口）。 */
    @Test
    fun capsuleGroupHasSectionSlots() {
        val src = sourceOf("core", "designsystem", "src", "main", "java",
            "com", "naaammme", "bbspace", "core", "designsystem", "component", "CapsuleGroup.kt")
        val body = src.joinToString("\n")
        assertTrue("CapsuleGroup 缺 header 槽", body.contains("header: (@Composable () -> Unit)? = null"))
        assertTrue("CapsuleGroup 缺 footer 槽", body.contains("footer: (@Composable () -> Unit)? = null"))
        assertTrue("缺 BiliGroupHeader 组件", body.contains("fun BiliGroupHeader("))
        assertTrue("缺 BiliGroupFooter 组件", body.contains("fun BiliGroupFooter("))
        assertTrue("分组标题必须消费角色色", body.contains("biliColorRoles().groupHeader"))
    }
}
