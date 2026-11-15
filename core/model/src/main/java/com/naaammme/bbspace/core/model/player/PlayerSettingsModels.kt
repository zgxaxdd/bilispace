package com.naaammme.bbspace.core.model

import androidx.compose.runtime.Immutable
import com.naaammme.bbspace.core.model.player.CdnRegionPolicy

@Immutable
enum class PlayerBufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playBufferMs: Int,
    val rebufferMs: Int,
    val backBufferMs: Int
) {
    // 起播速度由 playBufferMs 决定（起播只等这么久的媒体数据，保持小值）；
    // maxBufferMs 是播放期间持续预读的上限，直接决定网络抖动时的后备缓冲深度——
    // 三档整体上调（15→20 / 20→25 / 30→40s），"视频缓冲慢/看着看着卡一下"的
    // 主要缓解点在这里，起播时间不受影响。
    FastStart(
        minBufferMs = 5_000,
        maxBufferMs = 20_000,
        playBufferMs = 250,
        rebufferMs = 1_000,
        backBufferMs = 2_000
    ),
    Balanced(
        minBufferMs = 5_000,
        maxBufferMs = 25_000,
        playBufferMs = 500,
        rebufferMs = 1_000,
        backBufferMs = 5_000
    ),
    Stable(
        minBufferMs = 10_000,
        maxBufferMs = 40_000,
        playBufferMs = 750,
        rebufferMs = 1_500,
        backBufferMs = 5_000
    )
}

@Immutable
data class PlayerBufferSettings(
    val profile: PlayerBufferProfile = PlayerBufferProfile.FastStart
)

@Immutable
enum class VideoCdnMode(
    val label: String,
    val description: String
) {
    /**
     * 智能选线：**按本机实测的健康度排序**，用当前最稳的一条；
     * 后续备用线路同样按健康度排，于是引擎"打开失败依次切换"的次序自然就是最优序。
     *
     * 与其它档的本质区别：其它档是"用我指定的那条"，本档是"用你实测最好的那条"。
     * 健康度只看本机观察（出画面/缓冲/起播超时），不联网、不上报、重启归零。
     */
    Smart("智能选线（推荐）", "按本机实测的线路健康度自动排序，越用越准"),
    BaseUrl("基础 URL（不推荐）", "直接使用服务端原始 base_url"),
    Backup1("备用 URL 1", "直接使用服务端第一个备用线路"),
    Backup2("备用 URL 2", "直接使用服务端第二个备用线路"),
    Ali("Ali", "阿里云 (推荐)"),
    Alib("Alib", "阿里云"),
    Alio1("Alio1", "阿里云"),
    Cos("Cos", "腾讯云"),
    Cosb("Cosb", "腾讯云，VOD 加速类型"),
    Coso1("Coso1", "腾讯云"),
    Hw("Hw", "华为云，融合 CDN"),
    Hwb("Hwb", "华为云，融合 CDN"),
    Hwo1("Hwo1", "华为云，融合 CDN"),
    Hw08C("08c", "华为云，融合 CDN"),
    Hw08H("08h", "华为云，融合 CDN"),
    Hw08Ct("08ct", "华为云，融合 CDN"),
    TfHw("TfHw", "华为云 TF 线路"),
    TfTx("TfTx", "腾讯云 TF 线路"),
    Akamai("Akamai", "Akamai 海外"),
    Aliov("Aliov", "阿里云海外"),
    Cosov("Cosov", "腾讯云海外"),
    Hwov("Hwov", "华为云海外"),
    HkBcache("HkBcache", "Bilibili 海外");

    val host: String?
        get() = when (this) {
            // 智能档没有固定 host：它由健康度决定，见 selectPlaybackCdn
            Smart,
            BaseUrl,
            Backup1,
            Backup2 -> null
            Ali -> "upos-sz-mirrorali.bilivideo.com"
            Alib -> "upos-sz-mirroralib.bilivideo.com"
            Alio1 -> "upos-sz-mirroralio1.bilivideo.com"
            Cos -> "upos-sz-mirrorcos.bilivideo.com"
            Cosb -> "upos-sz-mirrorcosb.bilivideo.com"
            Coso1 -> "upos-sz-mirrorcoso1.bilivideo.com"
            Hw -> "upos-sz-mirrorhw.bilivideo.com"
            Hwb -> "upos-sz-mirrorhwb.bilivideo.com"
            Hwo1 -> "upos-sz-mirrorhwo1.bilivideo.com"
            Hw08C -> "upos-sz-mirror08c.bilivideo.com"
            Hw08H -> "upos-sz-mirror08h.bilivideo.com"
            Hw08Ct -> "upos-sz-mirror08ct.bilivideo.com"
            TfHw -> "upos-tf-all-hw.bilivideo.com"
            TfTx -> "upos-tf-all-tx.bilivideo.com"
            Akamai -> "upos-hz-mirrorakam.akamaized.net"
            Aliov -> "upos-sz-mirroraliov.bilivideo.com"
            Cosov -> "upos-sz-mirrorcosov.bilivideo.com"
            Hwov -> "upos-sz-mirrorhwov.bilivideo.com"
            HkBcache -> "cn-hk-eq-bcache-01.bilivideo.com"
        }
}

/**
 * 循环播放模式。
 *
 * 用枚举而非布尔，便于后续不加字段就扩到「列表循环 / 随机」。
 */
enum class PlayerRepeatMode {
    /** 不循环：播完停在结尾 */
    Off,

    /** 单个视频循环：播完自动从头再播 */
    One
}

@Immutable
data class PlayerPlaybackPrefs(
    val backgroundPlayback: Boolean = true,
    val reportPlayback: Boolean = true,
    val preferSoftwareDecode: Boolean = false,
    val decoderFallback: Boolean = true,
    val autoRotateFullscreen: Boolean = true,
    val gestureSpeed: Float = 2f,
    val videoCdnMode: VideoCdnMode = VideoCdnMode.Backup2,
    /**
     * **CDN 并行分片下载**（对齐 BiliPai `CdnRegionPluginCache.parallelDownloadEnabled`）。
     *
     * 开：一次范围请求切成 2~3 片并发竞速（并发数按缓冲/码率自适应，见
     * `resolveCdnConnections`），弱网与起播更快；关：回落到单连接顺序读
     * （`CdnPlaybackDataSource` 会自己降级，见 `read()` 的 `parallel = false` 分支）。
     *
     * ⚠️ **默认值与源仓库相反**：BiliPai 默认关（要在插件面板里手动开），本仓默认开 ——
     * 因为并行数据源在本仓是播放主链的一部分，改成默认关等于把已发布的能力收回；
     * 开关语义仍按源仓库口径（可关）。
     */
    val cdnParallelDownload: Boolean = true,
    /**
     * **DASH 分段自适应预取**（对齐 BiliPai `CdnRegionPluginCache.prefetchEnabled` +
     * `VideoPlaybackViewModel.scheduleCdnDashPrefetch`）。
     *
     * 开：进度轮询里发现"缓冲已经够厚（≥15s）"时，按缓冲自适应预取 3/5/8 个后续分段；
     * 只在 Wi‑Fi/以太网下做（蜂窝不预取，避免偷跑流量）；缓冲变薄立即取消。
     */
    val cdnAdaptivePrefetch: Boolean = true,
    /**
     * **CDN 属地优选**（W4，对照 BiliPai `CdnRegionPluginCache` 的 selectedHosts /
     * `experimentalRewriteEnabled`）。
     *
     * 开：把服务端返回的候选 URL 按属地目录改写 host（详见 `CdnRegionPolicy.mergeCandidates`），
     * 用作主/备线路；关（默认）：候选与排序**逐字节保持现状**。
     *
     * ⚠️ 属地来自 IP 归属地接口，本仓网络层暂无该接口 ⇒ 目前由 [cdnRegionHosts] 手动/上层注入；
     * 自动定位属于后续接线（见 task 报告）。
     */
    val cdnRegionEnabled: Boolean = false,
    /** 属地优选的目标 host（空 = 无候选，插件自然退回原序）。 */
    val cdnRegionHosts: List<String> = emptyList(),
    /**
     * **自定义 CDN 改写规则**（对照 BiliPai `CdnCustomRule`）：按顺序匹配完整播放 URL，
     * 首条命中即停；替换结果必须仍指向 B 站域（`CdnRegionPolicy.applyCustomRules`）。
     */
    val cdnCustomRules: List<CdnRegionPolicy.CustomRule> = emptyList(),
    /**
     * **严格只用自定义线路**（对照 BiliPai `strictCustomCdn`）：有自定义候选就只用它，
     * 健康度再差也排第一；规则全没命中时完整回退（不会锁死播放）。
     */
    val cdnStrictCustomCdn: Boolean = false,
    /** 双击画面点赞：关闭时双击为暂停（默认） */
    val doubleTapLike: Boolean = false,
    /** ColorOS 系统级无缝过渡动画（首页卡片 → 全屏详情）：关闭则退回应用内覆盖层路径 */
    val seamlessTransition: Boolean = true,
    /**
     * 竖屏连播的「预加载下一个视频」（默认开，与 BiliPai `prefetchVideoEnabled` 同默认）。
     *
     * 开：WiFi 下停稳时预取后面 1~3 支的播放地址；蜂窝下预取 1 支。
     * 关：只在 WiFi 下预取 2 支（不额外花蜂窝流量）。
     * 这是"上滑之后画面来得快不快"的主要开关（见 `SwipePrefetchPolicy`）。
     */
    val prefetchVideo: Boolean = true,
    /** 单视频循环播放（播完自动重播） */
    val repeatMode: PlayerRepeatMode = PlayerRepeatMode.Off,
    /**
     * 竖屏连播：播完**自动翻到下一支**（默认开，与 BiliPai 的连播行为一致）。
     *
     * 关掉后播完就停在结尾 —— 想「一支看完停下来回味/截图」的用户需要这个开关；
     * 与 [repeatMode] 的关系：单曲循环时播放器不会发 `STATE_ENDED`，自动翻页天然不生效；
     * 开着自动翻页但非循环时，播完前进一支（这条判据在 [SwipeAutoAdvancePolicy] 里）。
     */
    val swipeAutoAdvance: Boolean = true,
    /**
     * 跨会话「已推去重」（SW-05，决策 D7 默认开）：推荐流/story 流给过的视频，
     * 7 天内（上限 500 条）不再进新会话的队列，防"每次进来都刷到同几支"。
     * 关掉即回到旧行为；relate 接力不受此开关影响（那是上下文，不是推送）。
     */
    val crossSessionDedup: Boolean = true,
    /**
     * 刷视频模式**防烧屏**（用户要求，默认开）：常驻控件静置 15s 后变暗到 55%
     * （不隐藏），并每 60s 做一次 ±2px 的缓慢像素漂移（3×3 游走）。
     * 关掉则常驻控件恒满亮、不漂移（OLED 上长时间连播有烧屏风险，默认开）。
     */
    val burnInGuard: Boolean = true
)

@Immutable
data class PlayerSettingsState(
    val buffer: PlayerBufferSettings = PlayerBufferSettings(),
    val playback: PlayerPlaybackPrefs = PlayerPlaybackPrefs(),
    val danmaku: DanmakuConfig = DanmakuConfig()
)
