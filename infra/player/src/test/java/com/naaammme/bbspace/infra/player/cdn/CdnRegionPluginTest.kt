package com.naaammme.bbspace.infra.player.cdn

import com.naaammme.bbspace.core.model.player.CdnHealthPolicy
import com.naaammme.bbspace.core.model.player.CdnRegionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区域/自定义线路插件的数据侧单测（W4）。
 *
 * 钉住三条：
 * 1. **默认关闭 = 零行为变化**（候选内容与顺序逐字节不变）；
 * 2. 打开但没配任何线路时也保持原序（只开开关不能悄悄按健康度重排）；
 * 3. **插件不碰传输预算**：apply 前后 `permits` 与 `parallelEnabled` 必须相等 ——
 *    并行槽位有界等待/降级恢复/卡顿看门狗都在 `CdnPlaybackDataSource` 与引擎里，
 *    插件只能改候选与排序。
 */
class CdnRegionPluginTest {

    private val originals = listOf(
        "https://upos-sz-mirrorcos.bilivideo.com/a.m4s?t=1"
    )

    private val regionConfig = CdnRegionPlugin.Config(
        enabled = true,
        regionHosts = listOf("cn-sh-ct-01-01.bilivideo.com")
    )

    @Test
    fun `disabled config keeps candidates untouched`() {
        val applied = CdnRegionPlugin.applyOrderedUrls(
            originals,
            CdnRegionPlugin.Config.Disabled
        )
        assertEquals(originals, applied)
        assertEquals(
            "默认配置必须是不启用",
            false,
            CdnRegionPlugin.Config.Disabled.enabled
        )
    }

    @Test
    fun `enabled but empty rewrites keeps original order`() {
        val applied = CdnRegionPlugin.applyOrderedUrls(
            originals,
            CdnRegionPlugin.Config(enabled = true)
        )
        assertEquals(
            "只打开开关、没配属地/规则时不许重排（否则会悄悄改变现有选线）",
            originals,
            applied
        )
    }

    @Test
    fun `region config rewrites host and keeps original as fallback`() {
        val applied = CdnRegionPlugin.applyCandidates(originals, regionConfig)
        assertEquals(2, applied.size)
        assertEquals(CdnRegionPolicy.Source.Region, applied.first().source)
        assertEquals(
            "cn-sh-ct-01-01.bilivideo.com",
            CdnRegionPolicy.hostOf(applied.first().url)
        )
        assertEquals(CdnRegionPolicy.Source.Original, applied.last().source)
    }

    @Test
    fun `strict custom config returns only custom candidates`() {
        val config = CdnRegionPlugin.Config(
            enabled = true,
            strictCustomCdn = true,
            regionHosts = listOf("cn-sh-ct-01-01.bilivideo.com"),
            customRules = listOf(
                CdnRegionPolicy.CustomRule(
                    pattern = "upos-sz-mirrorcos\\.bilivideo\\.com",
                    replacement = "upos-sz-mirroralib.bilivideo.com"
                )
            )
        )
        val applied = CdnRegionPlugin.applyCandidates(originals, config)
        assertEquals(1, applied.size)
        assertEquals(CdnRegionPolicy.Source.Custom, applied.single().source)
        assertEquals(
            "upos-sz-mirroralib.bilivideo.com",
            CdnRegionPolicy.hostOf(applied.single().url)
        )
    }

    @Test
    fun `plugin never touches the parallel transfer budget`() {
        // 回归钉：插件只改候选/顺序 —— 不 import、不读改 CdnTransferRuntime 的 permits/parallel。
        val permitsBefore = CdnTransferRuntime.permits.availablePermits()
        val parallelBefore = CdnTransferRuntime.parallelEnabled
        CdnRegionPlugin.applyOrderedUrls(
            originalUrls = originals + listOf("https://upos-sz-mirrorali.bilivideo.com/b.m4s?t=2"),
            config = CdnRegionPlugin.Config(
                enabled = true,
                strictCustomCdn = false,
                regionHosts = listOf("cn-sh-ct-01-01.bilivideo.com", "cn-bj-cc-03-14.bilivideo.com"),
                customRules = listOf(
                    CdnRegionPolicy.CustomRule(
                        pattern = "mirrorali\\.bilivideo\\.com",
                        replacement = "upos-sz-mirroralib.bilivideo.com"
                    )
                )
            ),
            healthByHost = mapOf(
                "cn-sh-ct-01-01.bilivideo.com" to CdnHealthPolicy.Health(
                    host = "cn-sh-ct-01-01.bilivideo.com",
                    playbackErrorCount = 9
                )
            )
        )
        assertEquals(
            "插件不许占用/归还并行连接槽位（槽位预算归数据源）",
            permitsBefore,
            CdnTransferRuntime.permits.availablePermits()
        )
        assertEquals(
            "插件不许改并行开关（降级/恢复归数据源）",
            parallelBefore,
            CdnTransferRuntime.parallelEnabled
        )
    }

    @Test
    fun `diagnostics exposes final host order and source labels`() {
        val diagnostics = CdnRegionPlugin.diagnostics(originals, regionConfig)
        assertTrue(diagnostics.enabled)
        assertEquals(1, diagnostics.regionHostCount)
        assertEquals(1, diagnostics.regionCandidateCount)
        assertEquals(1, diagnostics.originalCandidateCount)
        assertEquals(
            listOf("cn-sh-ct-01-01.bilivideo.com", "upos-sz-mirrorcos.bilivideo.com"),
            diagnostics.orderedHosts
        )
        assertEquals(listOf("属地优选", "原始"), diagnostics.sourceLabels)
    }

    @Test
    fun `source labels match upstream wording`() {
        assertEquals("自定义", CdnRegionPlugin.sourceLabel(CdnRegionPolicy.Source.Custom))
        assertEquals("属地优选", CdnRegionPlugin.sourceLabel(CdnRegionPolicy.Source.Region))
        assertEquals("原始", CdnRegionPlugin.sourceLabel(CdnRegionPolicy.Source.Original))
    }

    @Test
    fun `empty candidate list stays empty`() {
        assertTrue(CdnRegionPlugin.applyCandidates(emptyList(), regionConfig).isEmpty())
        assertTrue(CdnRegionPlugin.applyOrderedUrls(listOf(""), regionConfig).isEmpty())
        assertTrue(regionConfig.hasRewrites)
    }
}
