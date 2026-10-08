package com.naaammme.bbspace.core.model

import com.naaammme.bbspace.core.model.player.CdnHealthPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能选线的**真实故障回归测试**（2026-10-06，用户实测"选了智能选线后任何视频都看不了"）。
 *
 * ## 故障与原因
 *
 * 用户报错原文：`单文件 dash 索引缺失: http://58.222.41.85:8000/v1/resource/upgcxcode/...&os=mcdn`。
 * 这是 **PCDN（`mcdn`）资源 URL**：本仓单文件 DASH 必须先 `Range: bytes=0-91535`
 * 拉索引段，**裸 PCDN 读不到索引** ⇒ 打不开。
 *
 * 智能档在"没有观测数据"时按服务端原序取**首条**，而不少视频首条就是 PCDN；
 * 旧默认档 `Backup2` 取第 3 条（普通 CDN），所以一直没暴露。
 *
 * ## 修法与 BiliPai 的对应关系
 *
 * 源实现的排序里 **来源优先于健康度**（`orderCandidates` 第一键是 `Source`，
 * 并用测试钉死"给自定义线路灌满错误分也抢不走首位"）。我此前把这一层丢了，
 * 让分数直接排全部候选 ⇒ 把"读不到索引的 PCDN"排到了第一位。
 * 修法：智能档先分「可直连 / PCDN」两档，档内再按健康度。
 */
class SmartCdnPcdnOrderingTest {

    private val pcdn = "http://58.222.41.85:8000/v1/resource/upgcxcode/35/85/42306568535/" +
        "42306568535-1-30280.m4s?os=mcdn&mcdnid=50059380&sign=fa8b0f"
    private val normalA = "https://upos-sz-mirror08c.bilivideo.com/upgcxcode/35/85/42306568535/" +
        "42306568535-1-30280.m4s?os=upos&sign=aaa"
    private val normalB = "https://upos-hz-mirrorakam.akamaized.net/upgcxcode/35/85/42306568535/" +
        "42306568535-1-30280.m4s?os=upos&sign=bbb"

    /**
     * **无观测时必须与既有默认档（Backup2）逐字一致** —— 回到"不比现状更差"。
     *
     * 这是 2026-10-06 故障的**第二道防线**：第一道是"PCDN 垫底"（有观测时生效），
     * 第二道是"没有证据就不换掉长期可播的那条"。
     */
    @Test
    fun smartWithNoObservationsFallsBackToTheExistingDefault() {
        val urls = listOf(normalA, normalB, pcdn)
        assertEquals(
            "无观测时智能档的选择必须与 Backup2 相同（第 3 条）",
            urls.getOrNull(2),
            orderPlaybackUrlsForMode(urls, selected = pcdn, mode = VideoCdnMode.Smart).first()
        )
    }

    /** **核心回归（有观测时）**：PCDN 不能排在可直连候选之前。 */
    @Test
    fun smartNeverPicksPcdnWhenAPlayableAlternativeExists() {
        val observed = CdnHealthPolicy.Health(host = "upos-hz-mirrorakam.akamaized.net")
        val ordered = orderPlaybackUrlsForMode(
            urls = listOf(pcdn, normalA, normalB),
            selected = pcdn,
            mode = VideoCdnMode.Smart,
            healthByHost = mapOf("upos-hz-mirrorakam.akamaized.net" to observed)
        )
        assertFalse(
            "智能档首条不能是 PCDN（会导致『单文件 dash 索引缺失』）—— 实际: ${ordered.first()}",
            ordered.first().startsWith("http://58.222.41.85")
        )
        assertEquals("可直连的候选应排在最前", normalA, ordered.first())
        assertEquals("PCDN 仍留在表里兜底（不丢候选）", 3, ordered.size)
    }

    /** 就算 PCDN 的**健康度更高**，也不能把它排到可直连候选前面 —— 这是 BiliPai 的"来源优先于分数"。 */
    @Test
    fun playabilityOutranksHealth() {
        val healthyPcdn = CdnHealthPolicy.Health(
            host = "58.222.41.85",
            readyCount = 10,
            firstFrameTimeoutCount = 0,
            bufferingCount = 0,
            playbackErrorCount = 0,
            probeLatencyMs = null,
            probeSpeedKbps = null,
            lastProbeAtMs = 0L,
            lastUpdatedAtMs = 0L,
            windowStartedAtMs = 0L
        )
        val ordered = orderPlaybackUrlsForMode(
            urls = listOf(pcdn, normalA),
            selected = pcdn,
            mode = VideoCdnMode.Smart,
            healthByHost = mapOf("58.222.41.85" to healthyPcdn)
        )
        assertEquals(
            "健康度再高也不能越过『能不能直连』这一层",
            normalA,
            ordered.first()
        )
    }

    /**
     * 全是 PCDN **且已有观测**时，用既有代理兜底（保证至少有一条可播）。
     *
     * 注意：**无观测**时走的是"与既有默认档一致"的护栏（不代理包装）——
     * 因为旧默认档在同样输入下也不会包装 ✗，无证据时不该制造新的选择差异。
     */
    @Test
    fun allPcdnFallsBackToProxyWrapper() {
        val observed = CdnHealthPolicy.Health(host = "58.222.41.85")
        val ordered = orderPlaybackUrlsForMode(
            urls = listOf(pcdn),
            selected = pcdn,
            mode = VideoCdnMode.Smart,
            healthByHost = mapOf("58.222.41.85" to observed)
        )
        assertEquals(1, ordered.size)
        assertTrue(
            "唯一候选是 PCDN 时应转成代理 URL，实际: ${ordered.first()}",
            ordered.first().startsWith("https://proxy-tf-all-ws.bilivideo.com?url=")
        )
    }

    /** 档内仍按健康度排（修 PCDN 不能把健康度排序弄丢）。 */
    @Test
    fun healthStillOrdersWithinThePlayableTier() {
        val sick = CdnHealthPolicy.Health(
            host = "upos-sz-mirror08c.bilivideo.com",
            readyCount = 0,
            firstFrameTimeoutCount = 5,
            bufferingCount = 5,
            playbackErrorCount = 3,
            probeLatencyMs = null,
            probeSpeedKbps = null,
            lastProbeAtMs = 0L,
            lastUpdatedAtMs = 0L,
            windowStartedAtMs = 0L
        )
        val ordered = orderPlaybackUrlsForMode(
            urls = listOf(normalA, normalB),
            selected = normalA,
            mode = VideoCdnMode.Smart,
            healthByHost = mapOf("upos-sz-mirror08c.bilivideo.com" to sick)
        )
        assertEquals("坏线路应被排后（档内仍按健康度）", normalB, ordered.first())
    }

    /** 其它档位的行为**必须一字不变**（这次只该影响智能档）。 */
    @Test
    fun otherModesKeepTheirExistingBehaviour() {
        val urls = listOf(normalA, normalB, pcdn)
        assertEquals(
            "Backup2 仍取第 3 条（旧默认行为，不能被我改动）",
            listOf(pcdn, normalA, normalB),
            orderPlaybackUrlsForMode(urls, selected = pcdn, mode = VideoCdnMode.Backup2)
        )
        assertEquals(
            "BaseUrl 仍取第 1 条",
            listOf(normalA, normalB, pcdn),
            orderPlaybackUrlsForMode(urls, selected = normalA, mode = VideoCdnMode.BaseUrl)
        )
    }
}
