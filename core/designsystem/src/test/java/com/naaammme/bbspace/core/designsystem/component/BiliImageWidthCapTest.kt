package com.naaammme.bbspace.core.designsystem.component

import com.naaammme.bbspace.core.common.media.widthLimitedImageUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片变体的**宽度上限**判据（2026-10-01，对照 BiliPai `3d5c296cb` / `8cfc4246b`）。
 *
 * ## 它守的缺陷
 * `thumbnailUrl` 只追加质量后缀（`@15q`），**不带宽度**——图床在没有宽度约束时
 * 按原尺寸返回。于是 `Banner`（直播播放器铺满背景）与 `PreviewThumb`（IM 会话图、
 * 预览九宫格）这两条路下载并解码的是**原始尺寸**的图：一张 4K 封面的 ARGB 位图
 * 就是几十 MB 量级，而它只是背景。上游在同一轮里修的正是"直播封面按屏幕宽度采样
 * 并夹上限"这一类问题。
 *
 * 这些断言全是**纯字符串**判据（`biliImageUrlForVariant` 不碰 Compose），
 * 所以能真的跑，不是"看起来会生效"。
 */
class BiliImageWidthCapTest {

    private val cover = "http://i0.hdslb.com/bfs/archive/abc123.jpg"

    @Test
    fun bannerIsWidthCappedNotJustQualityCapped() {
        val url = biliImageUrlForVariant(cover, BiliImageVariant.Banner).orEmpty()
        assertTrue("Banner 必须带宽度上限，实际：$url", "@${BANNER_MAX_WIDTH_PX}w_" in url)
        assertTrue("不许退回只带质量后缀的无界形态：$url", !url.endsWith("@15q.webp"))
    }

    @Test
    fun previewThumbIsWidthCapped() {
        val url = biliImageUrlForVariant(cover, BiliImageVariant.PreviewThumb).orEmpty()
        assertTrue("PreviewThumb 必须带宽度上限，实际：$url", "@${PREVIEW_THUMB_MAX_WIDTH_PX}w_" in url)
    }

    @Test
    fun capsAreSaneAndOrdered() {
        // 缩略图要比铺满背景更收紧；两者都在"手机够用"的量级，不是拍脑袋的小数字
        assertTrue(PREVIEW_THUMB_MAX_WIDTH_PX in 1 until BANNER_MAX_WIDTH_PX)
        assertTrue(BANNER_MAX_WIDTH_PX <= 1600)
    }

    @Test
    fun widthLimitReplacesExistingSuffixAndKeepsBase() {
        val withSuffix = "https://i0.hdslb.com/bfs/archive/abc@640w_480h_1e_1c_85q.webp"
        val url = widthLimitedImageUrl(withSuffix, 480, q = 20)
        assertEquals("https://i0.hdslb.com/bfs/archive/abc@480w_20q.webp", url)
        // http → https 归一，与其余 helper 同口径
        assertEquals(
            "https://a.com/x.jpg@320w_15q.webp",
            widthLimitedImageUrl("http://a.com/x.jpg", 320)
        )
        // 查询串必须原样留在最后（不能把 `@…` 插到 `?` 后面）
        assertEquals(
            "https://a.com/x.jpg@320w_15q.webp?t=1",
            widthLimitedImageUrl("https://a.com/x.jpg?t=1", 320)
        )
    }

    @Test
    fun blankInputPassesThroughAndZeroWidthIsRejected() {
        assertNull(widthLimitedImageUrl(null, 480))
        assertEquals("", widthLimitedImageUrl("", 480))
        var thrown = false
        try {
            widthLimitedImageUrl("https://a.com/x.jpg", 0)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue("widthPx<=0 必须显式拒绝（它会拼出一个非法后缀）", thrown)
    }

    /**
     * 其余变体**一条都不许被改道**：预加载与显示请求靠同一个解析函数拿键
     * （见 [CoverPreloadUrlTest]），这里若被顺手改动，预加载会整批失效。
     * 断言用"仍含该档的宽度/裁切标记"这种形态判据，不锁死整个后缀串。
     */
    @Test
    fun otherVariantsAreUntouched() {
        val fourThree = biliImageUrlForVariant(cover, BiliImageVariant.CardCover43).orEmpty()
        val small = biliImageUrlForVariant(cover, BiliImageVariant.CardCover).orEmpty()
        val large = biliImageUrlForVariant(cover, BiliImageVariant.CardCoverLarge).orEmpty()
        val preview = biliImageUrlForVariant(cover, BiliImageVariant.PlayerPreview).orEmpty()
        assertTrue("CardCover43 仍是 640×480 裁切档：$fourThree", "@640w_480h" in fourThree && "_1e_1c" in fourThree)
        assertTrue("CardCover 仍是 640×360 裁切档：$small", "@640w_360h" in small && "_1e_1c" in small)
        assertTrue("CardCoverLarge 仍是 960×540 裁切档：$large", "@960w_540h" in large && "_1e_1c" in large)
        // PlayerPreview 特意**不带** `_1e_1c`（不裁切），这是刷视频封面取景的关键
        assertTrue("PlayerPreview 仍是限宽不裁切：$preview", "@1080w" in preview && "_1e_1c" !in preview)
        // Original 仍走"剥掉尺寸后缀"的取原图语义（保存/查看原图用），不得被加上宽度夹取
        val origin = biliImageUrlForVariant(
            "https://i0.hdslb.com/bfs/archive/abc@640w_480h_1e_1c_85q.webp",
            BiliImageVariant.Original
        ).orEmpty()
        assertTrue("Original 必须剥掉 @640w… 后缀：$origin", "@640w" !in origin)
    }
}
