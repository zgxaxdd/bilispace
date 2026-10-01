package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 封面**动态取色**的分配结构守卫（2026-10-01，对照 BiliPai `8cfc4246b`）。
 *
 * ## 它守的缺陷
 * `VideoCardCoverColorStore.extractRepresentativeColor` 原先为了读 HARDWARE 位图的像素，
 * 按**封面原尺寸** `copy(ARGB_8888)` 一份软件位图并且不回收：
 * 640×480 ≈1.2MB、960×540 ≈2.1MB，每张染色卡片一次。它不是泄漏（GC 会收），
 * 但滚动时持续制造大对象 → GC 停顿正好落在滚动帧上，症状是"滚动偶发掉帧"，
 * 而代码看上去完全正常。改法是降采样到 96² 小样并在 finally 里 recycle。
 *
 * ## 为什么用扫源码而不是跑行为
 * JVM 单测里 `android.graphics.Bitmap` 被 stub（`isReturnDefaultValues = true`），
 * 这条路**跑不出行为差异**（这也是本仓 `resolveRepresentativeSwatch` 为什么把
 * framework 的 `colorToHSV` 换成纯 Kotlin 版本的同一个理由）。所以钉写法。
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

    /** ① 旧缺陷形态：按封面尺寸整张复制，且无人回收。 */
    @Test
    fun coverTintNeverCopiesAFullSizedSoftwareBitmap() {
        val lines = sourceOf(store)
        val copyCount = lines.count { Regex("""\.copy\(\s*Bitmap\.Config""").containsMatchIn(it) }
        assertTrue(
            "取色路径里不得出现 `bitmap.copy(Bitmap.Config…)`：那是**按封面原尺寸**复制" +
                "软件位图（640×480≈1.2MB、960×540≈2.1MB）且不回收，滚动时持续制造大对象。" +
                "正确做法见 sampleIntoSoftwareBitmap（降采样小样 + finally recycle）。当前 $copyCount 处。",
            copyCount == 0
        )
    }

    /** ② 我们造的小图必须被回收，而且是在 finally 里（palette 抛异常也要回收）。 */
    @Test
    fun paletteSampleIsRecycledInFinallyAndNeverTheCaller() {
        val body = blockBody(sourceOf(store), "internal fun extractRepresentativeColor(")
        val finallyAt = body.indexOfFirst { it.contains("} finally {") }
        val recycleAt = body.indexOfFirst { it.contains(".recycle()") }
        assertTrue(
            "小样必须在 finally 里 recycle（palette.generate() 抛异常时也要收）。" +
                "finally@$finallyAt recycle@$recycleAt",
            finallyAt in 0 until recycleAt
        )
        assertTrue(
            "只许回收自己造的那张：软件位图路径返回的是**调用方的**位图（Coil 缓存里的），" +
                "误回收会让后续封面显示成空白。",
            body.any { Regex("""sampled\s*!==\s*bitmap""").containsMatchIn(it) }
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
