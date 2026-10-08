package com.naaammme.bbspace.core.designsystem.motion

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动效时长 **token 化棘轮**（报告 P1-3）。
 *
 * ## 这条门禁补的是什么
 * `Animation.kt` 里定义好了 `short/medium/long/extraLong/morph` 与曲线，用户也能在设置里
 * 调"动效速度"——但**没有任何东西约束动画必须走 token**。于是 23 处 `tween(150)` /
 * `tween(350)` 这类字面量散在 9 个文件里，后果有两层：
 * 1. 观感：调档位只对一部分动画生效，「设置没反应」；
 * 2. **合规**：`reduceMotion`（系统"移除动画"）在这些动画上失效——`createAnimations`
 *    把各档压到 ~1ms 是**读侧**行为，只有调用方去读才生效。这是无障碍问题，不只是观感。
 *
 * ## 计数口径（**必须先定死，否则棘轮基线是错的**）
 * 统计的是**出现次数**：`src/main` 下（排除 `core/designsystem/.../theme/` 这个 token 定义处）
 * 每一行里 `tween(` / `spring(` 后面紧跟数字字面量的次数（`durationMillis = 150`、
 * `tween(160)`、`tween(400)` 都算）。
 *
 * 报告原文写「**13 处**」，实测为 **23 处**（同一行里出现两次算两次，例如
 * `slideOutVertically(tween(150)) { it } + fadeOut(tween(150))`）；
 * 按"含字面量的行"数是 20 行。三个数都能自洽，但**门禁只能钉一个**——这里钉出现次数。
 *
 * ## 为什么不用"有没有出现 LocalAnimations"这种判据
 * 那只证明文件里某处用了 token，挡不住同一个文件里新写一个 `tween(300)`。
 */
class AnimationTokenRatchetTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    /** token 的定义处本身要允许字面量（`createAnimations` 就是按档位算数字的地方）。 */
    private val themeDirSuffix =
        "${File.separator}core${File.separator}designsystem${File.separator}src${File.separator}main" +
            "${File.separator}java${File.separator}com${File.separator}naaammme${File.separator}bbspace" +
            "${File.separator}core${File.separator}designsystem${File.separator}theme${File.separator}"

    private val mainSources: List<File> by lazy {
        File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .filterNot { it.path.contains(themeDirSuffix) }
            .toList()
    }

    /** 防假绿：扫不到源码时，"0 处违规"毫无意义。 */
    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 300 个说明路径推导失效，本次棘轮在假绿。",
            mainSources.size > 300
        )
    }

    @Test
    fun hardcodedDurationsAreFrozen() {
        val hits = hardcodedDurationSites()
        assertTrue(
            buildString {
                appendLine(
                    "以下位置直接写了动画时长字面量（共 ${hits.size} 处，冻结上限 $FROZEN_LIMIT 处）："
                )
                hits.forEach { appendLine("  $it") }
                appendLine()
                appendLine(
                    "改用 LocalAnimations.current 的 short/medium/long/extraLong/morph。" +
                        "字面量会让「动效速度」档位与 reduceMotion 在这些动画上失效" +
                        "——这是无障碍问题，不只是观感。"
                )
                appendLine()
                appendLine(PORTED_EXEMPTION_NOTE)
            },
            hits.size <= FROZEN_LIMIT && hits.all { it.startsWith(PORTED_FILE) }
        )
    }

    /**
     * 只放行**一个方向**：数字必须只减不增。
     *
     * 落地时（2026-10-05）已把这 23 处全部改为 token，所以上限钉在 0。
     * 若将来确有必须硬编码的场景，请连同理由一起把上限调大——
     * 但不要在没写理由的情况下直接抬数字。
     *
     * ## 唯一的例外：`DissolvableSurface.kt`（1:1 移植件）
     *
     * 2026-10-05 起，粒子消散**直接移植 BiliPai 的 GL 实现**（用户明确要求
     * "尽量不要自己写代码，最好移植过来"）。该文件里的 240ms / 135ms 卡片塌缩时长、
     * 以及邻卡抖动的 80ms / 60ms，都是**源实现时序的一部分**，改成 token 就不再是移植。
     * 所以上限定为 4，**但断言里额外要求"命中项必须全部来自这一个文件"** ——
     * 别处新增一个字面量仍然会红（棘轮的严格性没有被削弱，只是给这个移植件挂了账）。
     */
    private fun hardcodedDurationSites(): List<String> {
        val pattern = Regex("""(tween|spring)\(\s*(durationMillis\s*=\s*)?\d""")
        return mainSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, raw ->
                val trimmed = raw.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                    trimmed.startsWith("/*") || trimmed.startsWith("import ")
                ) {
                    return@mapIndexedNotNull null
                }
                val n = pattern.findAll(raw).count()
                if (n == 0) null else "${file.name}:${index + 1}  ×$n  ${raw.trim()}"
            }
        }
    }

    private companion object {
        /** 4 = `DissolvableSurface.kt` 里移植过来的 240/135/80/60 四处。 */
        const val FROZEN_LIMIT = 4

        const val PORTED_FILE = "DissolvableSurface.kt"

        const val PORTED_EXEMPTION_NOTE =
            "注：允许的 4 处全部来自 $PORTED_FILE —— 它是 BiliPai 粒子消散的 1:1 移植件，" +
                "时长是其效果时序的一部分。别处新增字面量仍会失败。"
    }
}
