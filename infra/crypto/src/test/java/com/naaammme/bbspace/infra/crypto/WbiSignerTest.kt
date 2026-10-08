package com.naaammme.bbspace.infra.crypto

import bilibili.metadata.locale.LocaleOuterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WBI 签名的真值钉（T-1：`infra:crypto` 此前零单测）。
 *
 * ## 真值来源
 *
 * 期望的 `w_rid` 由独立复现脚本（Python `hashlib.md5`）按协议算得：
 * 1. `mixin_key` = `(img_key + sub_key)` 按 B 站 mixinKeyEncTab 抽位后取前 32 位；
 *    对下面的示例键，复现值 = `ea1db124af3c7062474693fa704f4ff8`，与
 *    **bilibili-API-collect 公开文档里的示例逐字一致** —— 这条同时证明了复现口径没跑偏；
 * 2. 参数值先剔除 `!'()*`，再并入 `wts`，按 key 排序后拼接 `k=v&k=v`；
 * 3. `w_rid` = md5(query + mixin_key)。
 *
 * 这里用固定 `wts` 调用，所以断言可以精确到字符串；`wts` 一旦被忽略、或抽位表被改动，
 * 这条会立刻红（而线上表现只是"签名错误、内容加载失败"，很难排查）。
 */
class WbiSignerTest {

    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun `sign 的 w_rid 与协议真值一致`() {
        val signed = WbiSigner.sign(
            params = mapOf("foo" to "114", "bar" to "514"),
            imgKey = imgKey,
            subKey = subKey,
            wts = 1702204169L
        )
        // query = "bar=514&foo=114&wts=1702204169"，mixin_key 见类注释
        assertEquals("ed791ce4979dfe1e2aad3b03b73b13cc", signed["w_rid"])
        assertEquals("1702204169", signed["wts"])
        // 原始参数必须原样带回（调用方直接用这个 Map 拼请求）
        assertEquals("114", signed["foo"])
        assertEquals("514", signed["bar"])
    }

    @Test
    fun `值里的 4 个特殊字符在签名前被剔除`() {
        // filteredChars = !'()*  —— "a!'()*b" 与 "ab" 的签名串必须相同
        val withSpecials = WbiSigner.sign(
            params = mapOf("q" to "a!'()*b"),
            imgKey = imgKey,
            subKey = subKey,
            wts = 1702204169L
        )
        val withoutSpecials = WbiSigner.sign(
            params = mapOf("q" to "ab"),
            imgKey = imgKey,
            subKey = subKey,
            wts = 1702204169L
        )
        assertEquals(withoutSpecials["w_rid"], withSpecials["w_rid"])
    }

    @Test
    fun `wts 参与签名`() {
        val a = WbiSigner.sign(mapOf("foo" to "114"), imgKey, subKey, wts = 1L)
        val b = WbiSigner.sign(mapOf("foo" to "114"), imgKey, subKey, wts = 2L)
        assertTrue("wts 必须进签名串（否则可被重放）", a["w_rid"] != b["w_rid"])
    }
}

/**
 * Q-4：两份逐字重复的 `buildLocaleProtobuf` 收口后的行为钉
 * （`BuvidFetcher` 与 `TicketGenerator` 的鉴权头共用这一份）。
 */
class BiliAuthPrimitivesTest {

    @Test
    fun `locale protobuf 仍是 zh CN 且只声明 locale`() {
        val locale = LocaleOuterClass.Locale.parseFrom(buildLocaleProtobuf())
        assertEquals("zh", locale.cLocale.language)
        assertEquals("CN", locale.cLocale.region)
        assertEquals("zh", locale.sLocale.language)
        assertEquals("CN", locale.sLocale.region)
        assertEquals("", locale.timezone)
    }

    @Test
    fun `md5Hex 是小写 hex 且对负字节正确补零`() {
        // 空串与 "abc" 的 MD5 是公开真值；两者都含高位为 1 的字节（负数），
        // 一旦把 `%02x` 写成有符号展开或大写就会红。
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
    }
}
