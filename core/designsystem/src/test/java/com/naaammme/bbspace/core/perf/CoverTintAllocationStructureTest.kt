package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 封面**动态取色**的分配与降级结构守卫。
 *
 * ## 它守的东西（含一次真机回归的教训）
 * 推荐页/排行页卡片按封面主色做动态染色。取色要读像素，而 Coil3 默认解出的是
 * **HARDWARE 位图**（不能读像素、也不能画进软件 Canvas），唯一受支持的转换是
 * `copy(ARGB_8888)` —— 那是一份与封面同尺寸的大块（640×480≈1.2MB、960×540≈2.1MB）。
 * 于是这里有三条不变式：
 * 1. 转换必须走 `copy`，**不许**改用软件 Canvas 画（平台会抛，外层 `runCatching`
 *    会把异常吞成“没有颜色”）；
 * 2. 大块必须**立刻 recycle**、palette 只吃小样 —— 真正伤滚动的是这些大块的**存活时长**，
 *    不是峰值（旧写法只能等 GC 什么时候心情好）；
 * 3. 取色失败必须**留日志**：它的症状是“卡片没染色”这种静默降级，不崩、不进日志、
 *    JVM 单测也照绿。
 *
 * ## 为什么用扫源码而不是跑行为
 * JVM 单测里 `android.graphics.Bitmap` 被 stub（`isReturnDefaultValues = true`），
 * 上面三条在 JVM 上**跑不出行为差异**（`resolveRepresentativeSwatch` 把 framework 的
 * `colorToHSV` 换成纯 Kotlin 版本，正是同一个理由）。所以钉写法。
 * ⚠️ 但**结构守卫钉不住“平台到底允不允许”这类事实**：2026-10-01 本文件第一条就写反过
 * （写成“永远不许出现 copy”），把实现推向一个只在真机上炸的方案，380+ 单测全绿。
 * 改完取色必须真机看一眼染色。
 *
 * ## 防假绿
 * 每个断言前都先确认目标函数体真的取到了（取不到直接 AssertionError），
 * "必须不存在 X"那类断言否则会因为扫到空列表而假绿。扫描跳过注释与 import。
 */
class CoverTintAllocationStructureTest {

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

    private fun sourceOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val file = mainSources.firstOrNull { it.path.endsWith(normalized) }
            ?: throw AssertionError("找不到 `$suffix`，此时断言无意义。")
        return file.readLines().map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                trimmed.startsWith("/*") || trimmed.startsWith("import ")
            ) "" else line
        }
    }

    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        // 判据是 `trimStart().startsWith(...)`，所以**签名不许带缩进**
        // （带上缩进永远匹配不到，正是"守卫静默失配"那一类假绿）。
        require(!signaturePrefix.startsWith(" ")) { "签名前缀不许带缩进：$signaturePrefix" }
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue("找不到 `$signaturePrefix`，无法验证其内部结构。", start >= 0)
        var depth = 0
        var opened = false
        val body = mutableListOf<String>()
        for (i in start until lines.size) {
            body += lines[i]
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> { depth++; opened = true }
                    '}' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        assertTrue(
            "`$signaturePrefix` 的函数体只截到 ${body.size} 行，判据无意义。",
            body.size > 3
        )
        return body
    }

    private val store = "color${File.separator}VideoCardCoverColorStore.kt"

    /**
     * ① HARDWARE 位图只能 `copy()` 转换，不许画进软件 Canvas。
     *
     * 这条断言在 2026-10-01 写反过一次（当时写成“永远不许出现 copy”），
     * 于是实现被推去用 `Canvas.drawBitmap(硬件位图)` —— 平台上不允许，
     * 真机上整条取色静默失败、卡片染色全丢。规则以现在的形态为准。
     */
    @Test
    fun hardwareBitmapIsConvertedByCopyNotByDrawingToASoftwareCanvas() {
        val body = blockBody(sourceOf(store), "private fun sampleIntoSoftwareBitmap(")
        assertTrue(
            "HARDWARE 位图必须用 copy(ARGB_8888) 转换（唯一受支持的路径）。",
            body.count { Regex("""\.copy\(\s*Bitmap\.Config""").containsMatchIn(it) } >= 1
        )
        assertTrue(
            "取色路径里不许出现把位图画进软件 Canvas 的写法：" +
                "Canvas.drawBitmap 对 HARDWARE 位图会抛 “Software rendering doesn't support " +
                "hardware bitmaps”，而外层 runCatching 会把它吞成“没有颜色”。",
            body.none { it.contains("Canvas(") } &&
                sourceOf(store).none { it.contains("drawBitmap(") }
        )
    }

    /** ② 整尺寸复制要立刻归还（真正的问题是生命周期，不是峰值），palette 只吃小样。 */
    @Test
    fun fullSizedCopyIsRecycledImmediatelyAndPaletteGetsTheSmallSample() {
        val body = blockBody(sourceOf(store), "private fun sampleIntoSoftwareBitmap(")
        assertTrue(
            "复制出的整尺寸 ARGB_8888（1.2~2.1MB）必须在拿到小样后立刻 recycle，" +
                "并且 palette 用的是 createScaledBitmap 的小样。",
            body.any { Regex("""full\.recycle\(\)""").containsMatchIn(it) } &&
                body.any {
                    Regex("""createScaledBitmap\(\s*full\s*,\s*PALETTE_SAMPLE_PX""").containsMatchIn(it)
                }
        )
        assertTrue(
            "缩放失败时不许把整图也回收掉（palette 会拿到已回收的位图）——" +
                "必须走“返回整图、交给调用方回收”的分支。",
            body.any { Regex("""small\s*!==\s*full""").containsMatchIn(it) }
        )
        val extract = blockBody(sourceOf(store), "internal fun extractRepresentativeColor(")
        val finallyAt = extract.indexOfFirst { it.contains("} finally {") }
        val recycleAt = extract.indexOfFirst { it.contains(".recycle()") }
        assertTrue(
            "小样在 finally 里回收（palette.generate() 抛异常也要收），" +
                "且只回收自己造的那张（软件位图路径返回的是 Coil 缓存里的位图，误回收会让封面变空白）。",
            finallyAt in 0 until recycleAt &&
                extract.any { Regex("""sampled\s*!==\s*bitmap""").containsMatchIn(it) }
        )
    }

    /** ③ 取色失败必须留痕 —— 否则“丢染色”只能靠人眼发现。 */
    @Test
    fun extractionFailureIsLoggedNotSwallowed() {
        val body = blockBody(sourceOf(store), "internal fun extractRepresentativeColor(")
        assertTrue(
            "取色失败不许被 runCatching 静默吞掉：它的症状是“卡片没有颜色”，" +
                "不崩、不进日志、JVM 单测也照绿（2026-10-01 的真机回归就是这么漏过去的）。",
            body.any { it.contains(".onFailure") } && body.any { it.contains("Log.w(") }
        )
    }

    /** ③ 采样尺寸是"两个数量级"这件事的量化上界。 */
    @Test
    fun paletteSampleSideStaysWithinBudget() {
        val side = sourceOf(store).firstNotNullOfOrNull {
            Regex("""PALETTE_SAMPLE_PX\s*=\s*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
        } ?: throw AssertionError("找不到 PALETTE_SAMPLE_PX 常量定义，断言无意义。")
        assertTrue(
            "PALETTE_SAMPLE_PX=$side：palette 只用 48×48 面积，超过 128 就说明又在按封面尺寸取样" +
                "（128²≈64KB vs 整图 1.2~2.1MB）。",
            side in 48..128
        )
    }

    /** ④ 缓存命中路径不许再排一次主线程队列（染色晚一帧 = 滚动里"先白后有色"）。 */
    @Test
    fun cachedColorHitDispatchesImmediatelyOnMain() {
        val body = blockBody(sourceOf(store), "fun requestExtraction(")
        assertTrue(
            "缓存命中回调必须走 `Dispatchers.Main.immediate`：调用点本来就在主线程，" +
                "用 Dispatchers.Main 会白排一次分发。",
            body.count { it.contains("Dispatchers.Main.immediate") } == 1 &&
                body.none { Regex("""launch\(Dispatchers\.Main\)\s*\{""").containsMatchIn(it) }
        )
    }

    /** ⑤ 取色请求不许再显式指定缓存键（双下载双解码的历史坑，见其 KDoc）。 */
    @Test
    fun tintRequestDerivesItsCacheKeyFromData() {
        val body = blockBody(sourceOf(store), "fun requestExtraction(")
        assertTrue(
            "取色的 ImageRequest 不许设 memoryCacheKey/diskCacheKey：键一旦是**未解析的封面 URL**，" +
                "与卡片显示请求（data = 变体 URL）永远对不上 → 同一封面下载两次、解码两次。",
            body.none { Regex("""\.memoryCacheKey\(|\.diskCacheKey\(""").containsMatchIn(it) }
        )
    }
}
