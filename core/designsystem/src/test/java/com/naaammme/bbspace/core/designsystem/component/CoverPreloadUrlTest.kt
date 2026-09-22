package com.naaammme.bbspace.core.designsystem.component

import com.naaammme.bbspace.core.common.media.BiliCoverSize
import com.naaammme.bbspace.core.common.media.coverUrlFor
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首页封面预加载与卡片请求的是不是**同一个 URL**（任务书 §2-T1 的二值问题）。
 *
 * ## 为什么这是个真问题
 *
 * Coil 的 memory cache key 与 disk cache key 都是从 `ImageRequest.data` 派生的。
 * 卡片请求的是 `.../abc.jpg@640w_480h_1e_1c_85q.webp`（缩略图档位后缀），
 * 而预加载上一轮直接把未解析的 `item.cover` 交给 Coil。两个字符串不同 →
 * 两个键不同 → 预加载解出来的位图卡片永远查不到，**预加载完全白做**，
 * 而且还额外下载了一张原图（比不预加载更贵）。
 *
 * 顺带纠正任务书里的一个假设：它怀疑"`.size(640,480)` 与卡片布局尺寸不同会让
 * memory key 对不上"。反编译 coil-core 3.6.2 的 `MemoryCacheService.newCacheKey` 后
 * 确认：**只有带 transformations 的请求才会把 size 写进 key 的 extras**
 * （字节码里那段 `if (transformations.isNotEmpty()) extras["coil#size"] = options.size`）。
 * 本仓两个请求都没有 transformations，所以 size 根本不进 key；
 * 尺寸差异是之后由 `isCacheValueValidForSize` 拿位图实际宽高比的 ——
 * 那仍然要求"预加载尺寸 ≥ 卡片尺寸"，但不是键不一致问题。
 * 真正的键不一致来自 **URL**。
 *
 * ## 这个文件能纯 JVM 跑的原因
 *
 * URL 解析是纯字符串变换（[biliImageUrlForVariant] 刻意抽成了非 Composable 的纯函数）。
 * 所以这里断言的是**行为**，不是文本形状 —— 比结构断言强一档。
 */
class CoverPreloadUrlTest {

    /** 真实形态的 bilibili 封面地址（feed 接口下发的样子）。 */
    private val rawCover = "https://i0.hdslb.com/bfs/archive/3f1c2a5b6d7e8f901234567890abcdef01234567.jpg"

    /** 双列卡片：预加载解析结果必须等于卡片实际请求的 URL。 */
    @Test
    fun doubleColumnPreloadMatchesCardUrl() {
        val card = biliImageUrlForVariant(rawCover, feedCoverVariant(singleColumn = false))
        val expected = coverUrlFor(rawCover, BiliCoverSize.Small43)
        assertTrue("卡片侧解析不应返回空", card != null)
        assertTrue(
            "预加载与双列卡片必须请求同一个 URL，否则缓存键对不上。" +
                "card=$card expected=$expected",
            card == expected
        )
        assertTrue("解析结果应带 Small43 后缀", card!!.endsWith(BiliCoverSize.Small43.suffix))
    }

    /** 单列大图：走 Large 档，且与双列不同档。 */
    @Test
    fun singleColumnPreloadUsesLargeVariant() {
        val single = biliImageUrlForVariant(rawCover, feedCoverVariant(singleColumn = true))
        val double = biliImageUrlForVariant(rawCover, feedCoverVariant(singleColumn = false))
        assertTrue("单列应请求 Large 档", single!!.endsWith(BiliCoverSize.Large.suffix))
        assertNotEquals(
            "单列与双列请求同一档的话，预加载尺寸就永远小于卡片需要 —— 必须分档",
            double, single
        )
    }

    /**
     * 关键回归防线：预加载解析出来的 URL **不得等于原始 cover**。
     *
     * 这就是上一轮那个 bug 的直接断言。把它写成"不等于原图"而不是"等于某个具体串"，
     * 是为了让将来调整档位后缀时这条测试不会假红，但仍然能抓住"忘了解析"。
     */
    @Test
    fun resolvedUrlIsNeverTheRawOriginal() {
        for (single in listOf(false, true)) {
            val resolved = biliImageUrlForVariant(rawCover, feedCoverVariant(single))
            assertNotEquals(
                "预加载请求原图等于白下载一张大图，且与卡片键不同、永远命中不了",
                rawCover, resolved
            )
        }
    }

    /**
     * 幂等：对已解析的 URL 再解析一次必须得到同一个串。
     *
     * 这条保证的是 disk cache key 的**稳定性** —— 同一张封面在滚动中被请求多次时，
     * 不能今天算出 A、明天算出 B，否则缓存永远命中不了第二次。
     */
    @Test
    fun resolutionIsIdempotent() {
        val once = biliImageUrlForVariant(rawCover, feedCoverVariant(singleColumn = false))
        val twice = biliImageUrlForVariant(once, feedCoverVariant(singleColumn = false))
        assertTrue("解析必须幂等（否则缓存键不稳定）：once=$once twice=$twice", once == twice)
    }

    /** 输入带**别的**档位后缀时，必须被替换掉而不是叠加。 */
    @Test
    fun existingSuffixIsReplacedNotAppended() {
        val withOtherSize = "$rawCover@120w_120h_1c_!web.jpg"
        val resolved = biliImageUrlForVariant(withOtherSize, feedCoverVariant(singleColumn = false))
        assertTrue(
            "应剥掉原有档位后缀再换上 Small43，实际：$resolved",
            resolved == coverUrlFor(rawCover, BiliCoverSize.Small43)
        )
    }

    // ── 结构防线：两条路径不许再各算各的 ──────────────────────────────────────

    /**
     * `CoverImage` 的分支必须与 [feedCoverVariant] 的映射一致。
     *
     * 预加载按 `singleColumn` 选变体，卡片按 `coverLarge/coverFourThree` 选变体。
     * 两边是两套入参、一个契约，任一边改了分支而另一边没跟，缓存就又对不上了 ——
     * 而这种错**不会有任何报错**，只会让预加载静默失效。
     */
    @Test
    fun coverImageBranchesAgreeWithFeedCoverVariant() {
        val src = sourceOf("component${FS}CoverImage.kt")
        assertTrue(
            "CoverImage 的变体分支必须是 coverLarge -> CardCoverLarge、" +
                "coverFourThree -> CardCover43，与 feedCoverVariant(singleColumn) 对得上。",
            countMatching(src, """coverLarge\s*->\s*BiliImageVariant\.CardCoverLarge""") == 1 &&
                countMatching(src, """coverFourThree\s*->\s*BiliImageVariant\.CardCover43""") == 1
        )
        // HomeVideoPage 传给 CoverImage 的两个 flag 必须正好互补
        val page = sourceOf("video${FS}HomeVideoPage.kt")
        assertTrue(
            "HomeVideoPage 给 CoverImage 传的是 coverLarge=singleColumn / " +
                "coverFourThree=!singleColumn，这两个必须成对出现，" +
                "否则与 feedCoverVariant 的映射脱钩。",
            countMatching(page, """coverLarge\s*=\s*singleColumn""") >= 1 &&
                countMatching(page, """coverFourThree\s*=\s*!singleColumn""") >= 1
        )
    }

    /** 预加载不得再直接用未解析的 `item.cover`。 */
    @Test
    fun preloadGoesThroughTheSharedResolver() {
        val page = sourceOf("video${FS}HomeVideoPage.kt")
        assertTrue(
            "首页预加载必须经 biliImageUrlForVariant 解析 URL。",
            countMatching(page, """biliImageUrlForVariant\(""") >= 1
        )
        assertTrue(
            "预加载列表不得再是 `items.map { it.cover }`（未解析的原图地址，" +
                "与卡片请求的 URL 不同键，等于白下载）。",
            countMatching(page, """items\.map\s*\{\s*it\.cover\s*\}""") == 0
        )
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    private val FS: String get() = File.separator

    private val mainSources: List<File> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile } ?: File("").absoluteFile
        File(root, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
    }

    private fun sourceOf(suffix: String): List<String> {
        val normalized = suffix.replace('/', File.separatorChar)
        val file = mainSources.firstOrNull { it.path.endsWith(normalized) }
            ?: throw AssertionError(
                "找不到 `$suffix`（扫到 ${mainSources.size} 个 main 文件）。" +
                    "本类含\"某写法必须不存在\"的断言，文件读不到时会假绿，所以直接抛。"
            )
        return file.readLines().map { line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) "" else line
        }
    }

    private fun countMatching(lines: List<String>, regex: String): Int {
        val re = Regex(regex)
        return lines.count { it.isNotBlank() && re.containsMatchIn(it) }
    }
}
