package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 玻璃绘制链的结构守卫（本轮 B2 / B3 / B8）。
 *
 * ## 为什么需要它
 *
 * 这三条修复都是"写法层面的不变式"，JVM 上跑不出行为差异，但**改回去就会静默变慢**：
 * - **B3**：miuix `DrawBackdropElement.equals` 对 lambda 做**引用比较**（已反编译
 *   miuix-blur 0.9.4-rc01 核实），`update(node)` 末尾**无条件 `invalidateDrawCache()`**。
 *   玻璃链不在 `remember` 里 = 每次重组都作废 blur 缓存重建。播放进度 500ms 轮询会让
 *   控制条每秒重组 2 次，角标在取色渐变期每帧重组。
 * - **B2**：`LaunchedEffect(key)` 的语义是"key 变化 → 取消并**重启**协程"，所以
 *   `while (true)` 的 tick 循环必须在循环外按值早退，"key 翻转会取消它"是错觉。
 * - **B8**：Brush / outline 不许在 draw 块里构造（每帧分配）。
 *
 * ## 防假绿
 * 每个断言先确认目标函数体真的取到了（取不到会 AssertionError），否则"必须不存在 X"
 * 那类断言会因为扫到空列表而假绿。扫描跳过注释与 import —— 本仓库的注释里必然出现
 * `drawBackdrop`、`remember`、`while (true)` 这些词。
 */
class GlassDrawChainStructureTest {

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

    @Test
    fun scannerActuallyReadsSources() {
        assertTrue(
            "只扫到 ${mainSources.size} 个 main 源码文件（repoRoot=${repoRoot.absolutePath}），" +
                "少于 100 个说明路径推导失效。",
            mainSources.size > 100
        )
    }

    /**
     * B3：所有 `drawBackdrop` 玻璃链必须在 `remember { ... }` 里构造。
     *
     * 判据不是"有没有 remember"而是**顺序**：`remember(` 的出现必须早于 `drawBackdrop(`，
     * 即链被 remember 住而不是每次调用新建。改回内联写法会立刻变红。
     */
    @Test
    fun glassModifierChainsAreRemembered() {
        val targets = listOf(
            // 2026-09-29：playerControlGlass 改 internal（会话覆盖层重构中曾短暂暴露给
            // pager，现已收回 pane；可见性放宽本身不改变 remember 契约，守卫跟着改签名）。
            "player${File.separator}VideoPlayerPane.kt" to "internal fun Modifier.playerControlGlass(",
            "video${File.separator}HomeVideoPage.kt" to "private fun Modifier.glassBadgeSurface(",
            "glass${File.separator}LiquidBackdrop.kt" to "fun Modifier.liquidGlassSurface("
        )
        for ((suffix, signature) in targets) {
            val body = blockBody(sourceOf(suffix), signature)
            val rememberAt = body.indexOfFirst { it.contains("remember(") }
            val backdropAt = body.indexOfFirst { it.contains("drawBackdrop(") }
            assertTrue(
                "$signature 的玻璃链必须包在 remember { } 里（miuix 的 DrawBackdropElement.equals " +
                    "对 lambda 引用比较，update() 末尾无条件 invalidateDrawCache() —— 不 remember " +
                    "就是每次重组重建一次模糊缓存）。remember@$rememberAt drawBackdrop@$backdropAt",
                rememberAt in 0 until backdropAt
            )
        }
    }

    /**
     * B2：播放页的逐帧 tick 必须按值早退，不能"永远 while(true)"。
     *
     * `LaunchedEffect(key)` 在 key 翻转时是取消**并重启**，不是终止 —— 唯一可靠的停法是
     * 循环外按值早退。同名 LaunchedEffect 在文件里不止一个，这里按"包含 videoGlassTick++ 的那个"取体。
     *
     * 棘轮上调（2026-09-25）：原守卫只要求按 `!state.playWhenReady` 早退；现在 tick 的
     * key 换成 `videoGlassActive = playWhenReady && controlsBlur && material != OFF`，
     * 即「有消费者（控件模糊开）且画面在动」才逐帧重录 —— 模糊关闭/材质 OFF/暂停时
     * 不再白录。守卫同步钉住更严的门控：早退必须检查 `!videoGlassActive`，且
     * `videoGlassActive` 的定义必须同时含 playWhenReady、controlsBlur 与材质 OFF 三个条件。
     */
    @Test
    fun videoGlassTickStopsWhenNotPlaying() {
        val lines = sourceOf("player${File.separator}VideoPlayerPane.kt")
        val starts = lines.indices.filter {
            lines[it].trimStart().startsWith("LaunchedEffect(videoGlassActive)")
        }
        assertTrue("找不到 LaunchedEffect(videoGlassActive)（改名了？）", starts.isNotEmpty())
        val tickBody = starts.map { blockBodyAt(lines, it) }
            .firstOrNull { body -> body.any { it.contains("videoGlassTick++") } }
        assertTrue("找不到玻璃 tick 协程（videoGlassTick++ 被移走了？）", tickBody != null)
        val body = tickBody!!
        val guardAt = body.indexOfFirst {
            it.contains("return@LaunchedEffect") && it.contains("!videoGlassActive")
        }
        val loopAt = body.indexOfFirst { it.trimStart().startsWith("while (true) {") }
        assertTrue(
            "玻璃 tick 协程必须先按 videoGlassActive 早退（否则暂停/模糊关闭时也每帧重录视频层）；" +
                "当前 guard@$guardAt loop@$loopAt",
            guardAt in 0 until loopAt
        )
        // 钉住门控定义本身：三个条件缺一不可，防止后人悄悄删掉其中之一。
        // 定义可能跨多行（&& 结尾续行），取到赋值结束（不再以 && 结尾）为止。
        val defStart = lines.indexOfFirst { it.trimStart().startsWith("val videoGlassActive =") }
        assertTrue("找不到 videoGlassActive 定义（被改名/内联了？）", defStart >= 0)
        val def = buildString {
            var i = defStart
            while (i < lines.size) {
                append(lines[i])
                if (!lines[i].trimEnd().endsWith("&&")) break
                i++
            }
        }
        assertTrue(
            "videoGlassActive 必须同时包含 playWhenReady、controlsBlur 与材质 OFF 判断：$def",
            def.contains("state.playWhenReady") &&
                def.contains("controlsBlur") &&
                def.contains("GlassMaterial.OFF")
        )
    }

    /**
     * B8：`liquidRimStroke` 的 Brush 必须在 `drawWithCache` 的缓存块里构造，
     * 不许出现在 `onDrawWithContent` 之后（那就是每帧分配）。
     */
    @Test
    fun rimStrokeBrushIsBuiltInCacheBlockNotInDrawBlock() {
        val body = blockBody(
            sourceOf("glass${File.separator}GlassEffect.kt"),
            "private fun Modifier.liquidRimStroke("
        )
        assertTrue(
            "liquidRimStroke 必须用 drawWithCache（Brush/outline 只在尺寸变化时重建）",
            body.any { it.contains("drawWithCache") }
        )
        val brushAt = body.indexOfFirst { it.contains("Brush.linearGradient(") }
        val drawAt = body.indexOfFirst { it.contains("onDrawWithContent") }
        assertTrue(
            "Brush 必须在缓存块内构造（应早于 onDrawWithContent）：brush@$brushAt draw@$drawAt",
            brushAt in 0 until drawAt
        )
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private fun sourceOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val file = mainSources.firstOrNull { it.path.endsWith(normalized) }
            ?: throw AssertionError("找不到 `$suffix`（repoRoot=${repoRoot.absolutePath}）—— 此时断言无意义。")
        return file.readLines().map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") ||
                trimmed.startsWith("import ")
            ) {
                ""
            } else {
                line
            }
        }
    }

    /** 取以 [signaturePrefix] 开头的函数体（含签名），按花括号配平定界。 */
    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        // W23：内部按 trimStart() 比较，带缩进的前缀会静默失配、红得莫名其妙 —— 入口处直接拒绝。
        require(!signaturePrefix.startsWith(" ") && !signaturePrefix.startsWith("\t")) {
            "signaturePrefix 带了前导缩进（\"$signaturePrefix\"）：本 helper 对行做 trimStart() 比较，" +
                "缩进会导致永不匹配。请传去掉缩进的签名。"
        }
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue("找不到 `$signaturePrefix`，无法验证其内部结构。", start >= 0)
        return blockBodyAt(lines, start)
    }

    private fun blockBodyAt(lines: List<String>, start: Int): List<String> {
        var depth = 0
        var opened = false
        val body = mutableListOf<String>()
        for (i in start until lines.size) {
            body += lines[i]
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> {
                        depth++; opened = true
                    }

                    '}' -> depth--
                }
            }
            // 必须等见过 `{` 才判结束：多行签名（参数各占一行）在 depth 仍为 0 时要继续读，
            // 否则函数体只剩签名那几行，里面什么也找不到（VideoLogicGuardTest 同款坑）。
            if (opened && depth <= 0) break
        }
        return body
    }
}
