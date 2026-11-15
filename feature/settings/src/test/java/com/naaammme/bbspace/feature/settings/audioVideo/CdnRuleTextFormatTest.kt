package com.naaammme.bbspace.feature.settings.audioVideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「CDN 智能选线」两栏文本输入的解析口径（W4 UI 入口）。
 *
 * 为什么值得单独测：这两栏是**用户手写文本**进入 `AppSettings` 的唯一闸门。
 * 解析太严 → 用户填的 host 被静默丢掉（表现为"开了开关没效果"）；解析太松 →
 * 非法正则也被保存（表现为"规则永远不命中"）；计数不对 → 设置页提示说谎。
 */
class CdnRuleTextFormatTest {

    // ── 目标 host ────────────────────────────────────────────────────────────

    @Test
    fun hostsAcceptNewlinesCommasAndChinesePunctuation() {
        val parsed = CdnRuleTextFormat.parseHosts(
            """
            upos-sz-mirrorcos.bilivideo.com
            upos-sz-mirrorali.bilivideo.com, upos-hz-mirrorakam.akamaized.net
            cn-hk-eq-bcache-01.bilivideo.com，dup.bilivideo.com
            dup.bilivideo.com

            """.trimIndent()
        )
        assertEquals(
            listOf(
                "upos-sz-mirrorcos.bilivideo.com",
                "upos-sz-mirrorali.bilivideo.com",
                "upos-hz-mirrorakam.akamaized.net",
                "cn-hk-eq-bcache-01.bilivideo.com",
                "dup.bilivideo.com",
            ),
            parsed
        )
    }

    @Test
    fun hostsToleratePastableUrls() {
        // 用户经常直接把浏览器/抓包里的完整 URL 贴进来：落库必须是纯 host。
        val parsed = CdnRuleTextFormat.parseHosts(
            "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1/2/3.m4s?sig=abc\n" +
                "upos-sz-mirrorali.bilivideo.com:443/upgcxcode/x.m4s"
        )
        assertEquals(
            listOf("upos-sz-mirrorcos.bilivideo.com", "upos-sz-mirrorali.bilivideo.com"),
            parsed
        )
    }

    // ── 自定义规则 ───────────────────────────────────────────────────────────

    @Test
    fun rulesKeepOnlyWellFormedAndCompilableLines() {
        val result = CdnRuleTextFormat.parseRules(
            """
            upos-sz-mirrorcos\.bilivideo\.com => upos-hz-mirrorakam.akamaized.net

            upos-sz-mirrorali.bilivideo.com => 
            没有箭头的一行
            (未闭合 => upos-sz-mirrorcos.bilivideo.com
            候选\.bilivideo\.com => 新的线路.bilivideo.com
            """.trimIndent()
        )
        assertEquals("只有 2 行合法（空行不算非法）", 2, result.rules.size)
        // 第 3 行替换内容为空、第 4 行没有箭头、第 5 行正则非法（`(未闭合`）。
        assertEquals(listOf(3, 4, 5), result.invalidLineNumbers)
        assertEquals(3, result.invalidLineCount)
        assertEquals("upos-sz-mirrorcos\\.bilivideo\\.com", result.rules[0].pattern)
        assertEquals("upos-hz-mirrorakam.akamaized.net", result.rules[0].replacement)
        assertTrue("解析出来的规则必须默认启用", result.rules.all { it.enabled })
    }

    @Test
    fun formattedRulesRoundTripThroughParser() {
        val source = CdnRuleTextFormat.parseRules(
            "upos-sz-mirrorcos\\.bilivideo\\.com => upos-hz-mirrorakam.akamaized.net\n" +
                "upos-sz-mirrorali\\.bilivideo\\.com => upos-sz-mirrorcos.bilivideo.com"
        )
        assertEquals(2, source.rules.size)

        // 保存到设置后再次打开编辑器：文本 → 规则 → 文本 → 规则 必须稳定。
        val reopened = CdnRuleTextFormat.parseRules(
            CdnRuleTextFormat.formatRules(source.rules)
        )
        assertEquals(source.rules, reopened.rules)
        assertEquals(0, reopened.invalidLineCount)
    }

    @Test
    fun emptyInputMeansClearConfiguration() {
        assertTrue(CdnRuleTextFormat.parseHosts("  \n\n ").isEmpty())
        val result = CdnRuleTextFormat.parseRules("")
        assertTrue(result.rules.isEmpty())
        assertEquals(0, result.invalidLineCount)
        assertEquals(0, result.nonEmptyLineCount)
    }
}
