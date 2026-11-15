package com.naaammme.bbspace.core.model.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CDN 属地优选 / 自定义线路策略单测（W4，对照 BiliPai `CdnRegionPolicyTest.kt` 的边界用例）。
 *
 * 覆盖：目录解析（含非法输入）、属地命中优先级、运营商排序、host 改写、
 * 自定义规则（首条命中即停 / 非法正则 / 非受信目标拒绝 / 禁用不生效）、
 * 三路候选合并与**严格自定义模式**、TTL 与打码。
 */
class CdnRegionPolicyTest {

    private val catalog = mapOf(
        "上海" to listOf("cn-sh-ct-01-01.bilivideo.com", "cn-sh-cm-01-01.bilivideo.com"),
        "北京" to listOf("cn-bj-cc-03-14.bilivideo.com"),
        "海外" to listOf("upos-hz-mirrorakam.akamaized.net", "upos-sz-mirrorcosov.bilivideo.com"),
        "南京" to listOf("cn-jsnj-fx-02-05.bilivideo.com"),
        "哈市" to listOf("cn-hljheb-ct-01-02.bilivideo.com"),
        "香港" to listOf("cn-hk-eq-01-01.bilivideo.com")
    )

    // ── 目录解析（A 级）────────────────────────────────────────────────────

    @Test
    fun `parses bundled catalog shape and preserves order`() {
        val parsed = CdnRegionPolicy.parseCatalog(
            """
            {
              "上海": ["a.bilivideo.com", "b.bilivideo.com"],
              "海外": ["upos-hz-mirrorakam.akamaized.net"]
            }
            """.trimIndent()
        )
        assertEquals(listOf("上海", "海外"), parsed.keys.toList())
        assertEquals(listOf("a.bilivideo.com", "b.bilivideo.com"), parsed["上海"])
    }

    @Test
    fun `catalog parser filters blanks and duplicates`() {
        val parsed = CdnRegionPolicy.parseCatalog(
            """{"上海": ["a.bilivideo.com", " ", "a.bilivideo.com", "b.bilivideo.com"]}"""
        )
        assertEquals(listOf("a.bilivideo.com", "b.bilivideo.com"), parsed["上海"])
    }

    @Test
    fun `catalog parser tolerates whitespace and unicode escapes`() {
        val parsed = CdnRegionPolicy.parseCatalog(
            "  { \"\\u4e0a\\u6d77\" : [ \"a.bilivideo.com\" ] }  "
        )
        assertEquals(listOf("a.bilivideo.com"), parsed["上海"])
    }

    @Test
    fun `catalog parser returns empty map on malformed input`() {
        assertEquals(emptyMap<String, List<String>>(), CdnRegionPolicy.parseCatalog(null))
        assertEquals(emptyMap<String, List<String>>(), CdnRegionPolicy.parseCatalog(""))
        assertEquals(emptyMap<String, List<String>>(), CdnRegionPolicy.parseCatalog("{\"上海\": [\"a\""))
        assertEquals(emptyMap<String, List<String>>(), CdnRegionPolicy.parseCatalog("""{"上海": [1, 2]}"""))
        assertEquals(emptyMap<String, List<String>>(), CdnRegionPolicy.parseCatalog("not json"))
    }

    @Test
    fun `empty regions are dropped`() {
        assertEquals(emptyMap<String, List<String>>(), CdnRegionPolicy.parseCatalog("""{"上海": []}"""))
    }

    /** 资源文件必须真的进包且可解析（上游 `bundled catalog uses provided overseas upos cdn hosts`）。 */
    @Test
    fun `bundled catalog file parses and contains overseas and mainland regions`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("找不到仓库根目录")
        val file = File(root, "app/src/main/res/raw/cdn_region_catalog.json")
        assertTrue("目录文件必须存在：${file.path}", file.isFile)
        val parsed = CdnRegionPolicy.parseCatalog(file.readText(Charsets.UTF_8))
        assertTrue("目录不能为空", parsed.size >= 10)
        assertTrue("必须有上海/北京这类大陆区域", "上海" in parsed && "北京" in parsed)
        assertTrue("必须有海外区域", "海外" in parsed)
        assertTrue(
            "海外区域必须含上游同款 upos 海外 host",
            parsed["海外"].orEmpty().contains("upos-sz-mirrorcosov.bilivideo.com")
        )
        // 每个 host 都必须是合法域名（防止 JSON 里混入注释/说明文字）
        parsed.values.flatten().forEach { host ->
            assertTrue("非法 host: $host", host.endsWith(".bilivideo.com") || host.endsWith(".akamaized.net"))
        }
    }

    // ── 属地命中 ────────────────────────────────────────────────────────────

    @Test
    fun `selects direct city match before province`() {
        val selection = CdnRegionPolicy.selectRegion(
            CdnRegionPolicy.IpLocation(city = "上海", province = "北京", country = "中国"),
            catalog
        )
        assertEquals("上海", selection.region)
        assertFalse(selection.fallbackUsed)
        assertTrue(selection.hosts.isNotEmpty())
    }

    @Test
    fun `maps province aliases to nearest catalog region`() {
        val heilongjiang = CdnRegionPolicy.selectRegion(
            CdnRegionPolicy.IpLocation(province = "黑龙江省", country = "中国"),
            catalog
        )
        assertEquals("哈市", heilongjiang.region)

        val sichuan = CdnRegionPolicy.selectRegion(
            CdnRegionPolicy.IpLocation(city = "重庆", province = "重庆市", country = "中国"),
            catalog
        )
        // 重庆在目录里没有独立条目 ⇒ 走别名到成都；本用例目录也没有成都 ⇒ 不应随机回退
        assertTrue("没有成都区域时必须如实回退", sichuan.fallbackUsed)
    }

    @Test
    fun `maps non mainland locations to overseas when available`() {
        val selection = CdnRegionPolicy.selectRegion(
            CdnRegionPolicy.IpLocation(country = "日本", province = "东京都", city = "东京"),
            catalog
        )
        assertEquals("海外", selection.region)
        assertFalse(selection.fallbackUsed)
    }

    @Test
    fun `does not choose random fallback region when location has no catalog match`() {
        val selection = CdnRegionPolicy.selectRegion(
            CdnRegionPolicy.IpLocation(country = "中国", province = "西藏", city = "拉萨"),
            catalog
        )
        assertEquals("", selection.region)
        assertTrue(selection.hosts.isEmpty())
        assertTrue(selection.fallbackUsed)
    }

    @Test
    fun `sorts selected region hosts by isp carrier before rewriting`() {
        val sorted = CdnRegionPolicy.sortHostsForIsp(
            listOf("cn-sh-cm-01-01.bilivideo.com", "cn-sh-ct-01-01.bilivideo.com"),
            isp = "中国电信"
        )
        assertEquals("cn-sh-ct-01-01.bilivideo.com", sorted.first())

        val mobile = CdnRegionPolicy.sortHostsForIsp(
            listOf("cn-sh-ct-01-01.bilivideo.com", "cn-sh-cm-01-01.bilivideo.com"),
            isp = "中国移动"
        )
        assertEquals("cn-sh-cm-01-01.bilivideo.com", mobile.first())
    }

    @Test
    fun `unknown carrier keeps catalog order`() {
        val hosts = listOf("b.bilivideo.com", "a.bilivideo.com")
        assertEquals(hosts, CdnRegionPolicy.sortHostsForIsp(hosts, isp = ""))
    }

    // ── host 改写 ───────────────────────────────────────────────────────────

    @Test
    fun `rewrites bilivideo hosts while preserving path query and port`() {
        val rewritten = CdnRegionPolicy.rewriteHost(
            "https://upos-sz-mirrorcos.bilivideo.com:8443/upgcxcode/a.m4s?e=1&token=abc#frag",
            "cn-sh-ct-01-01.bilivideo.com"
        )
        assertEquals(
            "https://cn-sh-ct-01-01.bilivideo.com:8443/upgcxcode/a.m4s?e=1&token=abc#frag",
            rewritten
        )
    }

    @Test
    fun `does not rewrite non bilibili hosts`() {
        assertNull(
            CdnRegionPolicy.rewriteHost(
                "https://evil.example.com/a.m4s",
                "cn-sh-ct-01-01.bilivideo.com"
            )
        )
        assertNull(CdnRegionPolicy.rewriteHost("not a url", "cn-sh-ct-01-01.bilivideo.com"))
    }

    @Test
    fun `rewrite urls puts generated hosts first and keeps originals as fallback`() {
        val original = listOf("https://upos-sz-mirrorcos.bilivideo.com/a.m4s?t=1")
        val rewritten = CdnRegionPolicy.rewriteUrls(original, listOf("cn-sh-ct-01-01.bilivideo.com"))
        assertEquals(2, rewritten.size)
        assertEquals("cn-sh-ct-01-01.bilivideo.com", CdnRegionPolicy.hostOf(rewritten.first()))
        assertEquals(original, rewritten.drop(1))
        // 空目标 ⇒ 原样
        assertEquals(original, CdnRegionPolicy.rewriteUrls(original, emptyList()))
    }

    // ── 自定义规则 ─────────────────────────────────────────────────────────

    @Test
    fun `custom rules use first valid match without chaining and preserve pairs`() {
        val originals = listOf("https://upos-sz-mirrorcos.bilivideo.com/a.m4s?t=1")
        val rules = listOf(
            CdnRegionPolicy.CustomRule(
                pattern = "upos-sz-mirrorcos\\.bilivideo\\.com",
                replacement = "upos-sz-mirroralib.bilivideo.com"
            ),
            CdnRegionPolicy.CustomRule(
                pattern = "upos-sz-mirroralib\\.bilivideo\\.com",
                replacement = "cn-sh-ct-01-01.bilivideo.com"
            )
        )
        val rewritten = CdnRegionPolicy.applyCustomRules(originals, CdnRegionPolicy.compileCustomRules(rules))
        assertEquals(1, rewritten.size)
        assertEquals("upos-sz-mirroralib.bilivideo.com", CdnRegionPolicy.hostOf(rewritten.single()))
        assertEquals("t=1", rewritten.single().substringAfter("?"))
    }

    @Test
    fun `custom rules reject invalid regex and blank fields`() {
        val validations = CdnRegionPolicy.validateCustomRules(
            listOf(
                CdnRegionPolicy.CustomRule(pattern = "(", replacement = "x"),
                CdnRegionPolicy.CustomRule(pattern = "", replacement = "x"),
                CdnRegionPolicy.CustomRule(pattern = "a", replacement = ""),
                CdnRegionPolicy.CustomRule(pattern = "a", replacement = "b", enabled = false)
            )
        )
        assertTrue(validations[0].error?.contains("正则表达式无效") == true)
        assertTrue(validations[1].error?.contains("匹配表达式不能为空") == true)
        assertTrue(validations[2].error?.contains("替换内容不能为空") == true)
        assertNull("禁用规则永远合法", validations[3].error)
        assertEquals("禁用的规则不编译", 0, CdnRegionPolicy.compileCustomRules(validations.map { it.rule }).size)
    }

    @Test
    fun `custom rules rejected untrusted targets keep original url`() {
        val originals = listOf("https://upos-sz-mirrorcos.bilivideo.com/a.m4s")
        val rules = listOf(
            CdnRegionPolicy.CustomRule(
                pattern = "upos-sz-mirrorcos\\.bilivideo\\.com",
                replacement = "evil.example.com"
            )
        )
        val rewritten = CdnRegionPolicy.applyCustomRules(originals, CdnRegionPolicy.compileCustomRules(rules))
        assertTrue("不受信目标必须拒绝改写", rewritten.isEmpty())
    }

    @Test
    fun `disabled custom rule does not rewrite`() {
        val originals = listOf("https://upos-sz-mirrorcos.bilivideo.com/a.m4s")
        val rules = listOf(
            CdnRegionPolicy.CustomRule(
                pattern = "upos-sz-mirrorcos\\.bilivideo\\.com",
                replacement = "upos-sz-mirroralib.bilivideo.com",
                enabled = false
            )
        )
        assertTrue(CdnRegionPolicy.applyCustomRules(originals, CdnRegionPolicy.compileCustomRules(rules)).isEmpty())
    }

    // ── 三路合并 + 严格模式 ────────────────────────────────────────────────

    @Test
    fun `strict custom mode returns only custom candidates`() {
        val original = listOf("https://upos-sz-mirrorcos.bilivideo.com/a.m4s")
        val merged = CdnRegionPolicy.mergeCandidates(
            originalUrls = original,
            regionHosts = listOf("cn-sh-ct-01-01.bilivideo.com"),
            customRules = listOf(
                CdnRegionPolicy.CustomRule(
                    pattern = "upos-sz-mirrorcos\\.bilivideo\\.com",
                    replacement = "upos-sz-mirroralib.bilivideo.com"
                )
            ),
            strictCustomCdn = true,
            healthByHost = emptyMap()
        )
        assertEquals(1, merged.size)
        assertEquals(CdnRegionPolicy.Source.Custom, merged.single().source)
        assertEquals("upos-sz-mirroralib.bilivideo.com", CdnRegionPolicy.hostOf(merged.single().url))
    }

    @Test
    fun `strict custom mode falls back completely when no rule matched`() {
        val original = listOf("https://upos-sz-mirrorcos.bilivideo.com/a.m4s")
        val merged = CdnRegionPolicy.mergeCandidates(
            originalUrls = original,
            regionHosts = listOf("cn-sh-ct-01-01.bilivideo.com"),
            customRules = emptyList(),
            strictCustomCdn = true,
            healthByHost = emptyMap()
        )
        assertTrue("严格模式没命中时必须完整回退，不能把播放锁死", merged.any { it.source == CdnRegionPolicy.Source.Region })
        assertTrue(merged.any { it.source == CdnRegionPolicy.Source.Original })
    }

    /** 上游 `CdnRegionPolicyTest.kt:183-195` 的产品契约：自定义线路灌满错误分也抢不走首位。 */
    @Test
    fun `custom candidate wins regardless of health`() {
        val original = listOf("https://fast.bilivideo.com/a.m4s")
        val custom = listOf(
            CdnRegionPolicy.CustomRule(
                pattern = "fast\\.bilivideo\\.com",
                replacement = "slow.bilivideo.com"
            )
        )
        val health = mapOf(
            "slow.bilivideo.com" to CdnHealthPolicy.Health(
                host = "slow.bilivideo.com",
                playbackErrorCount = 5,
                firstFrameTimeoutCount = 5
            ),
            "fast.bilivideo.com" to CdnHealthPolicy.Health(
                host = "fast.bilivideo.com",
                readyCount = 10,
                probeSpeedKbps = 20_000L
            )
        )
        val relaxed = CdnRegionPolicy.mergeCandidates(original, emptyList(), custom, false, health)
        assertEquals(CdnRegionPolicy.Source.Custom, relaxed.first().source)
        assertEquals("slow.bilivideo.com", CdnRegionPolicy.hostOf(relaxed.first().url))
        assertTrue("其余线路仍在后面兜底", relaxed.any { it.source == CdnRegionPolicy.Source.Original })
    }

    @Test
    fun `non strict merge orders custom then region then original`() {
        val original = listOf("https://upos-sz-mirrorcos.bilivideo.com/a.m4s")
        val merged = CdnRegionPolicy.mergeCandidates(
            originalUrls = original,
            regionHosts = listOf("cn-sh-ct-01-01.bilivideo.com"),
            customRules = listOf(
                CdnRegionPolicy.CustomRule(
                    pattern = "upos-sz-mirrorcos\\.bilivideo\\.com",
                    replacement = "upos-sz-mirroralib.bilivideo.com"
                )
            ),
            strictCustomCdn = false,
            healthByHost = emptyMap()
        )
        assertEquals(
            listOf(
                CdnRegionPolicy.Source.Custom,
                CdnRegionPolicy.Source.Region,
                CdnRegionPolicy.Source.Original
            ),
            merged.map { it.source }
        )
    }

    @Test
    fun `merge preserves original order without observations`() {
        val original = listOf(
            "https://a.bilivideo.com/v.m4s",
            "https://b.bilivideo.com/v.m4s"
        )
        assertEquals(
            original,
            CdnRegionPolicy.mergeCandidates(original, emptyList(), emptyList(), false, emptyMap()).map { it.url }
        )
    }

    // ── TTL / 打码 / 缓存 host 合并 ─────────────────────────────────────────

    @Test
    fun `does not refresh cached location before ttl expires`() {
        val now = 2 * CdnRegionPolicy.LOCATION_TTL_MS
        assertFalse(
            CdnRegionPolicy.shouldRefreshLocation(
                enabled = true,
                nowMs = now,
                lastRefreshMs = now - CdnRegionPolicy.LOCATION_TTL_MS + 1,
                hasSelection = true
            )
        )
        assertTrue(
            CdnRegionPolicy.shouldRefreshLocation(
                enabled = true,
                nowMs = now,
                lastRefreshMs = now - CdnRegionPolicy.LOCATION_TTL_MS,
                hasSelection = true
            )
        )
        assertFalse(
            "插件关闭时永不刷新",
            CdnRegionPolicy.shouldRefreshLocation(false, now, 0L, hasSelection = false)
        )
        assertTrue(
            "从未选中过区域时必须刷新",
            CdnRegionPolicy.shouldRefreshLocation(true, now, 0L, hasSelection = false)
        )
    }

    @Test
    fun `stale cached hosts fall back to current catalog hosts`() {
        val resolved = CdnRegionPolicy.resolveHosts(
            region = "北京",
            cachedHosts = listOf("cn-sh-ct-01-01.bilivideo.com"),
            catalog = catalog
        )
        assertEquals(catalog["北京"], resolved)
    }

    @Test
    fun `cached subset of catalog is preserved`() {
        val resolved = CdnRegionPolicy.resolveHosts(
            region = "上海",
            cachedHosts = listOf("cn-sh-cm-01-01.bilivideo.com"),
            catalog = catalog
        )
        assertEquals(listOf("cn-sh-cm-01-01.bilivideo.com"), resolved)
        assertTrue(
            CdnRegionPolicy.hasUsableSelection(
                "上海",
                listOf("cn-sh-cm-01-01.bilivideo.com"),
                catalog
            )
        )
        assertFalse(CdnRegionPolicy.hasUsableSelection("", emptyList(), catalog))
    }

    @Test
    fun `masks ip addresses in logs`() {
        assertEquals("113.88.*.*", CdnRegionPolicy.maskIp("113.88.12.34"))
        assertEquals("2001:db8:*", CdnRegionPolicy.maskIp("2001:db8:1234:5678::1"))
        assertEquals("***", CdnRegionPolicy.maskIp("unknown"))
        assertEquals("", CdnRegionPolicy.maskIp("  "))
    }
}
