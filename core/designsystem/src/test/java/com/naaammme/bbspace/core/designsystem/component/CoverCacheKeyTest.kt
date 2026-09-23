package com.naaammme.bbspace.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 封面缓存键的**同源**契约（[resolveCoverCacheKey]）。
 *
 * ## 为什么需要这个文件
 *
 * 卡片动态取色挂在 Coil 的 `onSuccess` 上，所以"取色结果的缓存键"必须与
 * "Coil 图片缓存的键"是**同一个**。两者一旦分叉，症状是静默的：
 * 有些卡片算过色但查不到、有些查得到但没算过 —— 表现出来就是
 * 「很多卡片没有取色 / 颜色不对」，而且**没有任何报错**。
 *
 * 这个坑在本项目已经踩过两次：
 * 1. 预加载传未解析的 `item.cover`，与卡片的变体 URL 不是一个键 → 预加载 100% 白做
 *    （见 [CoverPreloadUrlTest]）；
 * 2. 取色键用原始 URL、而 Coil 按解析后的 URL 派生键 → 取色与图片缓存不同源。
 *
 * 所以这里同时断言**行为**（键怎么算）与**结构**（三处调用点真的用了同一个函数），
 * 后者是为了防止将来有人在某处又手写一个键。
 */
class CoverCacheKeyTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun sourceOf(suffix: String): List<String> {
        val f = File(repoRoot, "").walkTopDown()
            .firstOrNull { it.isFile && it.path.endsWith(suffix) && !it.path.contains("${File.separator}build${File.separator}") }
        assertTrue(
            "找不到源文件 $suffix（repoRoot=${repoRoot.absolutePath}）。" +
                "路径推导失效时本类所有断言都会假绿。",
            f != null
        )
        return f!!.readLines()
    }

    private fun homeVideoPage() = sourceOf("home${File.separator}video${File.separator}HomeVideoPage.kt")

    // ── 行为：键怎么算 ────────────────────────────────────────────────────

    @Test
    fun keyIsStable_forSameIdentity() {
        // 同一视频在任意次滚动/重组里必须算出同一个键，否则颜色查不回来
        val a = resolveCoverCacheKey("video|BV1xx411c7mD|3", useLargeCover = false)
        val b = resolveCoverCacheKey("video|BV1xx411c7mD|3", useLargeCover = false)
        assertEquals(a, b)
    }

    @Test
    fun keyDiffersAcrossQualityTiers() {
        // 单列与双列请求的是**不同尺寸的位图**。共用键会让小图命中大图槽位，
        // Coil 因尺寸不足重新解码 —— 等于白算一次取色。
        val large = resolveCoverCacheKey("video|BV1xx411c7mD|3", useLargeCover = true)
        val normal = resolveCoverCacheKey("video|BV1xx411c7mD|3", useLargeCover = false)
        assertNotEquals(large, normal)
        assertTrue("大图档应带 l 标记：$large", large.endsWith("_l"))
        assertTrue("普通档应带 n 标记：$normal", normal.endsWith("_n"))
    }

    @Test
    fun keyDiffersAcrossVideos() {
        val a = resolveCoverCacheKey("video|BV1xx411c7mD|3", useLargeCover = false)
        val b = resolveCoverCacheKey("video|BV1yy411c7mE|4", useLargeCover = false)
        assertNotEquals(a, b)
    }

    @Test
    fun blankIdentity_yieldsBlankKey_notAUniverseKey() {
        // 关键：空 identity **不能**退化成 "cover__n" 这种"所有未知视频共用一把键"。
        // 那会让任意两张卡互相串色，而且看起来像"取色随机错乱"。
        // 返回空串 → 调用方（rememberBiliImageRequest / 预加载）据此跳过显式键，
        // 退回 Coil 默认行为，不会串。
        assertEquals("", resolveCoverCacheKey("", useLargeCover = false))
        assertEquals("", resolveCoverCacheKey("   ", useLargeCover = false))
    }

    @Test
    fun identityIsTrimmed() {
        // 带空白的 identity 与不带空白必须同键，否则同一视频会算出两把键
        assertEquals(
            resolveCoverCacheKey("video|BV1|3", useLargeCover = false),
            resolveCoverCacheKey("  video|BV1|3  ", useLargeCover = false)
        )
    }

    @Test
    fun keyIsPrefixed_forDebuggability() {
        // 前缀让人一眼看出这是封面键（Coil 的缓存 dump、日志里可辨）
        assertTrue(resolveCoverCacheKey("x", useLargeCover = false).startsWith("cover_"))
    }

    // ── 结构：三处调用点必须用同一个函数 ──────────────────────────────────
    //
    // 这部分是**防复发**：行为测试只能证明"函数算得对"，证明不了"调用方用了它"。
    // 而本项目两次踩坑都发生在调用方（各算各的键），不是函数本身。

    @Test
    fun homeCard_derivesTintKeyFromIdentity_notFromCoverUrl() {
        val lines = homeVideoPage()
        val usesResolver = lines.count { it.contains("resolveCoverCacheKey(") }
        assertTrue(
            "HomeVideoPage 应通过 resolveCoverCacheKey 派生封面键，实际用了 $usesResolver 处。",
            usesResolver >= 1
        )
        // 反向断言：不得再出现"拿 item.cover 直接当取色键"的老写法
        val legacyKey = lines.count { it.contains("coverTintKey = item.cover") }
        assertEquals(
            "仍在用 item.cover 直接作取色键（$legacyKey 处）。" +
                "该写法在封面 URL 变化时会丢色，是本次修复的对象。",
            0,
            legacyKey
        )
    }

    @Test
    fun cardRequest_passesCacheKeyToCoil() {
        // 只算键但不传给 Coil = 白算（Coil 仍按 URL 派生自己的键）
        val joined = homeVideoPage().joinToString("\n")
        assertTrue(
            "HomeVideoPage 必须把 cacheKey 传给 CoverImage，否则 Coil 的位图缓存与取色缓存不同源。",
            joined.contains("cacheKey = coverCacheKey")
        )
    }

    @Test
    fun preload_passesTheSameCacheKey() {
        // 预加载若只带 URL 不带键，它填的缓存卡片读不到 —— 静默白做
        val joined = homeVideoPage().joinToString("\n")
        assertTrue(
            "预加载请求必须显式设置 memoryCacheKey，与卡片同源。",
            joined.contains("memoryCacheKey(entry.cacheKey)")
        )
        assertTrue(
            "预加载请求必须显式设置 diskCacheKey。",
            joined.contains("diskCacheKey(entry.cacheKey)")
        )
        // 预加载的键也必须来自同一个解析函数，不能自己拼
        assertTrue(
            "预加载的键应由 resolveCoverCacheKey 派生，不得手写。",
            joined.contains("cacheKey = resolveCoverCacheKey(")
        )
    }

    @Test
    fun coverImage_forwardsCacheKeyToAsyncImage() {
        // CoverImage 是中间层：漏转发就等于没传
        val lines = sourceOf("component${File.separator}CoverImage.kt")
        val joined = lines.joinToString("\n")
        assertTrue("CoverImage 需要 cacheKey 形参", joined.contains("cacheKey: String? = null"))
        assertTrue("CoverImage 必须把它转发给 BiliAsyncImage", joined.contains("cacheKey = cacheKey"))
    }

    @Test
    fun asyncImage_appliesCacheKeyToBothCaches() {
        val joined = sourceOf("component${File.separator}BiliAsyncImage.kt").joinToString("\n")
        assertTrue(
            "BiliAsyncImage 必须同时设置 memory 与 disk cache key —— 只设一个会让" +
                "内存命中与磁盘命中走不同键，症状是「重进应用后颜色没了」。",
            joined.contains("memoryCacheKey(cacheKey)") && joined.contains("diskCacheKey(cacheKey)")
        )
    }

    @Test
    fun scannerActuallyReadsSources() {
        // 防假绿：路径推导失效时，上面所有 contains 断言都会因为读到空串而失败，
        // 但 count==0 类的断言会**通过**。这条把"文件真的读到了"变成显式前提。
        val page = homeVideoPage()
        assertTrue("HomeVideoPage 只读到 ${page.size} 行，路径推导可能失效", page.size > 300)
        val cover = sourceOf("component${File.separator}CoverImage.kt")
        assertTrue("CoverImage 只读到 ${cover.size} 行", cover.size > 20)
    }
}
