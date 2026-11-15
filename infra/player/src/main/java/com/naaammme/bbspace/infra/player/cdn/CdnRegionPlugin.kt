package com.naaammme.bbspace.infra.player.cdn

import android.content.Context
import com.naaammme.bbspace.core.model.player.CdnHealthPolicy
import com.naaammme.bbspace.core.model.player.CdnRegionPolicy

/**
 * CDN **区域/自定义插件**的数据侧运行时（W4；对照 BiliPai `feature/plugin/CdnRegionPlugin.kt`
 * 与 `CdnRegionPolicy.kt`）。
 *
 * ## 为什么拆成"设置项 + 纯策略 + 本薄壳"
 *
 * 上游把这三件事塞进一个 894 行的 Compose 插件（设置页 + 属地接口 + 存储 + 候选改写）。
 * 本仓只搬**数据链路**：
 * - 判定与排序：[CdnRegionPolicy] / [CdnSmartSelectionPolicy]（纯函数，可 JVM 单测）；
 * - 本对象：配置快照、目录加载、把配置应用到候选列表、给诊断面板出数据；
 * - 设置项：[com.naaammme.bbspace.core.model.player.PlayerPlaybackPrefs] 的新增四栏；
 * - UI（设置页开关、`VideoCdnDiagnosticsDialog` 的一栏）由 feature 侧接线，
 *   本文件不 import 任何 Compose —— 写域与依赖方向都不允许。
 *
 * ## 与上一轮流畅度修复的硬边界（**插件不许绕过槽位预算**）
 *
 * 本对象只做 `List<String>` → `List<Candidate>` 的**纯重排/改写**：
 * 不 import `CdnTransferRuntime`，不读写 `permits`、`parallelEnabled`、`fallback`、
 * `rescue` 任何一项。并行分片的槽位有界等待（300ms）、降级与重升（≤2 次）、
 * 卡顿阶梯恢复全部仍在 `CdnPlaybackDataSource` / `Media3PlayerEngine` 里，
 * 由那里唯一的预算与看门狗决定。回归测试 `CdnRegionPluginTest` 钉住这一点
 * （apply 前后 `permits.availablePermits()` 必须相等）。
 *
 * ## 默认关闭 = 零行为变化
 *
 * [CdnRegionConfig.enabled] 默认 false；即便打开，`regionHosts` 与 `customRules` 都为空时
 * 也返回**原序**（不按健康度重排），保证"只开了开关、没配任何线路"不会改变现有选线。
 */
object CdnRegionPlugin {

    /** raw 资源名（`app/src/main/res/raw/cdn_region_catalog.json`）。 */
    const val CATALOG_RESOURCE_NAME = "cdn_region_catalog"

    /** 插件配置快照（由会话层从设置项构造，见 `StreamPlaybackSessionImpl`）。 */
    data class Config(
        val enabled: Boolean = false,
        val strictCustomCdn: Boolean = false,
        val regionHosts: List<String> = emptyList(),
        val customRules: List<CdnRegionPolicy.CustomRule> = emptyList()
    ) {
        /** 有没有**实际可应用**的内容（只开开关不算）。 */
        val hasRewrites: Boolean get() = regionHosts.isNotEmpty() || customRules.isNotEmpty()

        companion object {
            val Disabled = Config()
        }
    }

    /**
     * 诊断面板的一栏数据（UI 接线由 feature 侧完成，本仓只出数据）。
     *
     * @param orderedHosts 应用插件后**最终**的候选 host 顺序（首元素 = 主线路）
     * @param sourceLabels 与 [orderedHosts] 一一对应的来源标签（"自定义"/"属地优选"/"原始"）
     */
    data class Diagnostics(
        val enabled: Boolean = false,
        val strictCustomCdn: Boolean = false,
        val regionHostCount: Int = 0,
        val customRuleCount: Int = 0,
        val customCandidateCount: Int = 0,
        val regionCandidateCount: Int = 0,
        val originalCandidateCount: Int = 0,
        val orderedHosts: List<String> = emptyList(),
        val sourceLabels: List<String> = emptyList()
    )

    /**
     * 把配置应用到一组候选 URL（**纯列表变换**，无副作用、不碰任何传输预算）。
     *
     * @param originalUrls 会话层选出的候选（首元素 = 已选主线路）
     * @return 最终候选（首元素 = 应作为主线路的地址）；关闭或无改写内容时返回原序
     */
    fun applyCandidates(
        originalUrls: List<String>,
        config: Config,
        healthByHost: Map<String, CdnHealthPolicy.Health> = emptyMap()
    ): List<CdnRegionPolicy.Candidate> {
        val originals = originalUrls.filter(String::isNotBlank).distinct()
        if (originals.isEmpty()) return emptyList()
        if (!config.enabled || !config.hasRewrites) {
            return originals.map { CdnRegionPolicy.Candidate(it, CdnRegionPolicy.Source.Original) }
        }
        return CdnRegionPolicy.mergeCandidates(
            originalUrls = originals,
            regionHosts = config.regionHosts,
            customRules = config.customRules,
            strictCustomCdn = config.strictCustomCdn,
            healthByHost = healthByHost
        )
    }

    /** [applyCandidates] 的 URL-only 便捷形式（引擎/会话层实际用的是这个）。 */
    fun applyOrderedUrls(
        originalUrls: List<String>,
        config: Config,
        healthByHost: Map<String, CdnHealthPolicy.Health> = emptyMap()
    ): List<String> = applyCandidates(originalUrls, config, healthByHost).map { it.url }

    /**
     * 组装诊断数据。[originalUrls] 传**插件应用前**的候选（内部会再算一遍应用后的顺序，
     * 保证面板显示的就是真正下发给引擎的那张表）。
     */
    fun diagnostics(
        originalUrls: List<String>,
        config: Config,
        healthByHost: Map<String, CdnHealthPolicy.Health> = emptyMap()
    ): Diagnostics {
        val applied = applyCandidates(originalUrls, config, healthByHost)
        return Diagnostics(
            enabled = config.enabled,
            strictCustomCdn = config.strictCustomCdn,
            regionHostCount = config.regionHosts.size,
            customRuleCount = config.customRules.size,
            customCandidateCount = applied.count { it.source == CdnRegionPolicy.Source.Custom },
            regionCandidateCount = applied.count { it.source == CdnRegionPolicy.Source.Region },
            originalCandidateCount = applied.count { it.source == CdnRegionPolicy.Source.Original },
            orderedHosts = applied.map { CdnRegionPolicy.hostOf(it.url) },
            sourceLabels = applied.map { sourceLabel(it.source) }
        )
    }

    /** 候选来源的中文标签（与上游 `cdnCandidateSourceLabel` 同文案）。 */
    fun sourceLabel(source: CdnRegionPolicy.Source): String = when (source) {
        CdnRegionPolicy.Source.Custom -> "自定义"
        CdnRegionPolicy.Source.Region -> "属地优选"
        CdnRegionPolicy.Source.Original -> "原始"
    }

    /**
     * 读 raw 目录（`app/src/main/res/raw/cdn_region_catalog.json`）。
     *
     * 用 `getIdentifier` 而不是 `R.raw.xxx`：目录文件在 **app 模块**，而本模块是
     * `infra:player` —— 直接引 app 的 R 会把依赖方向倒置。资源缺失/损坏一律返回空表
     * （插件随后按"未命中区域"处理，保持原序），不抛异常、不影响播放。
     */
    fun loadCatalog(appContext: Context): Map<String, List<String>> {
        val resources = appContext.resources
        val resId = runCatching {
            resources.getIdentifier(CATALOG_RESOURCE_NAME, "raw", appContext.packageName)
        }.getOrDefault(0)
        if (resId == 0) return emptyMap()
        return runCatching {
            resources.openRawResource(resId).use { input ->
                CdnRegionPolicy.parseCatalog(String(input.readBytes(), Charsets.UTF_8))
            }
        }.getOrDefault(emptyMap())
    }
}
