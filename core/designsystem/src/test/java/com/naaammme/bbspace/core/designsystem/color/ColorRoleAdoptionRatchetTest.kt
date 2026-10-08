package com.naaammme.bbspace.core.designsystem.color

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语义角色色的**采用率棘轮**（报告 P1-5）。
 *
 * ## 这条门禁补的是什么
 * `BiliColorRoles.kt:124-131` 声明了唯一入口 `biliColorRoles()`，但组件层仍在直接读
 * `MaterialTheme.colorScheme.*`。后果：① 「角色层」抽象只部分生效，改一次角色值不能全站生效；
 * ② `ColorContrastTest` 只保护走角色层的那几处，直取 Material 槽位的组件**保护不到**。
 *
 * ## 本轮实测：报告说的「22 个文件」能迁的只有一部分
 * 报告 P1-5 的落点是「22 个文件改为经 `biliColorRoles()` 取色」。实测 `core/designsystem`
 * 下确实是 **22 个文件 / 72 处**直取 Material 槽位，**但角色层只有 8 个角色**
 * （primaryLabel / secondaryLabel / tertiaryLabel / separator / groupHeader / groupFooter /
 * fill / destructive），而 72 处用到的槽位是 16 种：
 *
 * | 槽位 | 次数 | 有对应角色吗 |
 * |---|---|---|
 * | `onSurfaceVariant` | 12 | ✅ secondaryLabel |
 * | `onSurface` | 5 | ✅ primaryLabel |
 * | `outlineVariant` | 2 | ✅ separator |
 * | `error` | 2 | ✅ destructive |
 * | `surfaceContainerLow` | 14 | ❌ |
 * | `primary` | 11 | ❌（groupHeader 只在 iOS 档取它，不是"主色"角色） |
 * | `background` / `onBackground` | 5 / 4 | ❌ |
 * | `surfaceVariant`、`surfaceContainer*`、`outline`、`secondaryContainer`… | 余额 | ❌ |
 *
 * 即：**72 处里只有 21 处（≈29%）有角色可迁**，其余 51 处是角色层**根本没有**的槽位。
 * 所以「22 个文件全部改成角色取色」这句话在当前角色集下做不到 —— 要么先扩角色集
 * （设计决策，会动到 iOS/MATERIAL 两档的取值），要么承认大部分组件继续直取 Material。
 *
 * ## 本条门禁冻的是什么
 * - 文件数只许减（当前 22）；
 * - **直取 Material 槽位的出现次数**只许减（当前 72）——这一条更重要：
 *   它挡住"新写一个组件顺手再取一次 colorScheme"。
 */
class ColorRoleAdoptionRatchetTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val designSystemSources: List<File> by lazy {
        File(repoRoot, "core/designsystem/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${designSystemSources.size} 个 designsystem 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 40 个说明路径推导失效，本次棘轮在假绿。",
            designSystemSources.size > 40
        )
    }

    @Test
    fun directMaterialColorSchemeUsageOnlyShrinks() {
        val files = designSystemSources.filter { directUsages(it) > 0 }
        assertTrue(
            buildString {
                appendLine(
                    "core/designsystem 下直取 MaterialTheme.colorScheme 的文件数为 ${files.size}" +
                        "，超过冻结上限 $FROZEN_FILES："
                )
                files.forEach { appendLine("  ${it.name}  ×${directUsages(it)}") }
                appendLine()
                appendLine(
                    "新组件请走 biliColorRoles()（BiliColorRoles.kt:124-131 的唯一入口）；" +
                        "角色层没有对应槽位时，先在角色层加角色，不要在组件里直取。"
                )
            },
            files.size <= FROZEN_FILES
        )
    }

    @Test
    fun directMaterialColorSchemeOccurrencesOnlyShrink() {
        val total = designSystemSources.sumOf { directUsages(it) }
        assertTrue(
            "core/designsystem 下直取 MaterialTheme.colorScheme 共 $total 处，超过冻结上限 " +
                "$FROZEN_OCCURRENCES。注意当前 72 处里只有 21 处有角色可迁" +
                "（其余槽位角色层没有）——所以这条棘轮主要用来挡**新增**，不是用来逼迁移。",
            total <= FROZEN_OCCURRENCES
        )
    }

    /** `BiliColorRoles.kt` 自身就是 Material → 角色的换算处，不计入。 */
    private fun directUsages(file: File): Int {
        if (file.name == "BiliColorRoles.kt") return 0
        return file.readLines().count { line ->
            val t = line.trimStart()
            !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") &&
                Regex("""MaterialTheme\.colorScheme\.\w+""").containsMatchIn(line)
        }
    }

    private companion object {
        /** 落地实测（2026-10-05）：22 个文件 / 72 处。只许减。 */
        const val FROZEN_FILES = 22
        const val FROZEN_OCCURRENCES = 72
    }
}
