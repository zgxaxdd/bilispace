package com.naaammme.bbspace.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 深链解析的纯 JVM 真值钉（B-1 / B-2 / L-1）。
 *
 * ## 为什么必须有这组测试
 *
 * 报告 §16.3 的验证要求 + 真机实证（2026-10-04，PLC110）：
 * - **B-1**：`https://spaceevil.example/2`（与本应用无关的域名）被解析成 `ToSpace(2)`，
 *   应用真的进了 UID 2 的空间页。根因是 `host.startsWith("space")` 那条**不依赖
 *   scheme / 域名**的析取项，以及 `host.endsWith("bilibili.com")` 会命中 `notbilibili.com`。
 * - **L-1**：`space.bilibili.com/2/video` 解析失败 → `Stay` → 调用方丢掉 → 用户看到
 *   "停在首页、无提示、不回落浏览器"。现在外部入口用 [WebLinkParser.parseExternal]，
 *   这类链接必须变成 `External`。
 * - **B-2**：`b23.tv` 短链在应用内的语义仍是 `Stay`（交给 WebView 跟 302），
 *   由 [WebLinkParser.isShortLink] 告诉外部入口"这需要先解析 302"。
 */
class WebLinkParserTest {

    // ── B-1：恶意 / 无关域名不许命中站内路由 ──────────────────────────────────

    @Test
    fun `无关域名不命中站内路由`() {
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://spaceevil.example/2"))
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://liveevil.example/9"))
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://notbilibili.com/video/BV1xx411c7mD"))
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://evilbilibili.com/video/BV1xx411c7mD"))
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://spaceevil.example/2/video"))
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://notb23.tv/abc123"))
    }

    @Test
    fun `真站内域名仍正常命中`() {
        assertEquals(WebLinkTarget.ToSpace(2L), WebLinkParser.parse("https://space.bilibili.com/2"))
        assertEquals(WebLinkTarget.ToLive(9L), WebLinkParser.parse("https://live.bilibili.com/9"))
        assertTrue(WebLinkParser.parse("https://www.bilibili.com/video/BV1xx411c7mD") is WebLinkTarget.ToVideo)
        assertTrue(WebLinkParser.parse("https://www.bilibili.com/bangumi/play/ep123") is WebLinkTarget.ToVideo)
        assertTrue(WebLinkParser.parse("bilibili://space/2") == WebLinkTarget.ToSpace(2L))
        assertTrue(WebLinkParser.parse("bilibili://live/9") == WebLinkTarget.ToLive(9L))
    }

    @Test
    fun `host 大小写不敏感但不放宽边界`() {
        assertEquals(WebLinkTarget.ToSpace(2L), WebLinkParser.parse("https://SPACE.BILIBILI.COM/2"))
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://SPACEEVIL.EXAMPLE/2"))
    }

    // ── L-1：外部入口必须把"接管了但解析不了"的链接升级为 External ─────────────

    @Test
    fun `外部入口把声明接管的未支持链接升级为 External`() {
        // 真机故障用例：这条以前停在首页
        assertEquals(
            WebLinkTarget.External,
            WebLinkParser.parseExternal("https://space.bilibili.com/2/video")
        )
        assertEquals(
            WebLinkTarget.External,
            WebLinkParser.parseExternal("https://live.bilibili.com/blanc/abc")
        )
        assertEquals(
            WebLinkTarget.External,
            // F-2 之后 read/cv 已被接管（见 opus 用例），这里改用仍未支持的番剧媒体页，
            // 保证"声明接管但解析不了 → External"这条语义继续被钉住
            WebLinkParser.parseExternal("https://www.bilibili.com/bangumi/media/md28234544")
        )
        assertEquals(
            WebLinkTarget.External,
            WebLinkParser.parseExternal("bilibili://space/not-a-number")
        )
    }

    @Test
    fun `外部入口对未声明域名保持 Stay`() {
        assertEquals(
            WebLinkTarget.Stay,
            WebLinkParser.parseExternal("https://spaceevil.example/2")
        )
        assertEquals(
            WebLinkTarget.Stay,
            WebLinkParser.parseExternal("https://example.com/whatever")
        )
    }

    @Test
    fun `外部入口对可解析链接原样返回`() {
        assertEquals(
            WebLinkTarget.ToSpace(2L),
            WebLinkParser.parseExternal("https://space.bilibili.com/2")
        )
        assertTrue(
            WebLinkParser.parseExternal("https://www.bilibili.com/video/BV1xx411c7mD")
                is WebLinkTarget.ToVideo
        )
    }

    // ── B-2：b23.tv 的两种语义（应用内 Stay / 外部需解析）──────────────────────

    @Test
    fun `b23tv 在应用内仍是 Stay 由 WebView 跟 302`() {
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://b23.tv/abc1234"))
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://www.b23.tv/abc1234"))
    }

    @Test
    fun `isShortLink 只认 b23 tv 域名`() {
        assertTrue(WebLinkParser.isShortLink("https://b23.tv/abc1234"))
        assertTrue(WebLinkParser.isShortLink("https://B23.TV/abc1234"))
        assertFalse(WebLinkParser.isShortLink("https://notb23.tv/abc1234"))
        assertFalse(WebLinkParser.isShortLink("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertFalse(WebLinkParser.isShortLink("not a url"))
    }

    // ── F-2：专栏 / 图文动态（opus）接管 ─────────────────────────────────────

    @Test
    fun `opus 与专栏链接解析为动态内容页`() {
        // 图文动态（统一内容页）→ opusType 0，与动态流卡片同口径
        assertEquals(
            WebLinkTarget.ToDynamic(opusId = 987654321L, opusType = 0),
            WebLinkParser.parse("https://www.bilibili.com/opus/987654321")
        )
        // 移动域同样命中（isBiliHost 按域名边界，含子域）
        assertEquals(
            WebLinkTarget.ToDynamic(opusId = 987654321L, opusType = 0),
            WebLinkParser.parse("https://m.bilibili.com/opus/987654321")
        )
        // 专栏 read/cv → cv 号即 opusId，opusType 1（与 HistoryTarget.Article 同口径）
        assertEquals(
            WebLinkTarget.ToDynamic(opusId = 123456L, opusType = 1),
            WebLinkParser.parse("https://www.bilibili.com/read/cv123456")
        )
        // 站内 deep link 的两种历史形态
        assertEquals(
            WebLinkTarget.ToDynamic(opusId = 42L, opusType = 1),
            WebLinkParser.parse("bilibili://opus/detail/42")
        )
        assertEquals(
            WebLinkTarget.ToDynamic(opusId = 42L, opusType = 1),
            WebLinkParser.parse("bilibili://article/42")
        )
    }

    @Test
    fun `opus 解析对脏 id 与未支持形态保持 Stay`() {
        // 非数字 / 0 一律不接管，避免"接管后进一个失败页"
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://www.bilibili.com/opus/abc"))
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://www.bilibili.com/opus/0"))
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://www.bilibili.com/read/cvabc"))
        // t.bilibili.com 是**动态 id**，本仓动态详情走 OpusDetailReq —— 刻意不接管
        assertEquals(WebLinkTarget.Stay, WebLinkParser.parse("https://t.bilibili.com/123"))
    }

    @Test
    fun `外部入口把可解析的 opus 链接原样交给站内路由`() {
        assertEquals(
            WebLinkTarget.ToDynamic(opusId = 987654321L, opusType = 0),
            WebLinkParser.parseExternal("https://www.bilibili.com/opus/987654321")
        )
        assertEquals(
            WebLinkTarget.ToDynamic(opusId = 123456L, opusType = 1),
            WebLinkParser.parseExternal("https://www.bilibili.com/read/cv123456")
        )
    }
}
