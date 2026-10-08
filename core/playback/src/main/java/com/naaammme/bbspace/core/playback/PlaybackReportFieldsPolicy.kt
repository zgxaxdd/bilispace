package com.naaammme.bbspace.core.playback

/**
 * 上报字段取值的**纯策略**（文件级 → JVM 单测可直调真代码）。
 *
 * 为什么不写在 [RemotePlaybackReporter] 里：它是 `@Inject constructor` 且依赖
 * `BiliRestClient`/`Context` 等 Android 面，构造期就不可测（与本包
 * [playbackHeartbeatTickDecision]、[PlaybackHeartbeatPolicy] 同一范式）。
 *
 * ## 修掉的是什么
 * 这两类字段此前是**常量**：`network_type` 恒 `"1"`（WiFi）、`scene` 恒 `"front"`（前台）。
 * 于是蜂窝下播放被记成 WiFi、后台/画中画播放被记成前台 —— 客户端**有**真实信号
 * （`NetworkTypeProvider.isUnmetered()`、`PlaybackReporter.setHostBackground`），
 * 只是上报链路没接。
 * 「字段已上报但值是常量」比不报更容易误导分析：分析侧会把它当成真值。
 *
 * ## 取值依据与不确定性（**施工诚实边界，勿删**）
 * - `network_type`：`"1"` = 不限流量（WiFi/以太网）沿用原常量；`"2"` = 计费网络。
 *   依据是 B 站客户端侧的 `NetworkType` 约定 `NT_UNKNOWN=0 / WIFI=1 / CELLULAR=2`
 *   （旁证：另一 B 站客户端 piliplus 的 `grpc/bilibili/app/dynamic/v2.pbenum.dart:1676-1682`）。
 *   **本仓未抓包核对过 `/x/report/heartbeat/mobile` 对 `network_type` 的枚举口径**，见报告 P2-3。
 * - `scene`：`"front"` 沿用原常量；`"background"` 由本策略定义。
 *   原代码只有 `PLAYBACK_HISTORY_SCENE_FRONT` 一个常量，「后台该填什么」**仓内无任何证据**
 *   （BiliPai 未实现该端点，全仓检索无第二处引用）。
 *   ⚠️ 这是**推断值**，不是实测值；若将来抓包证明服务端只认别的字面量，改这一处即可。
 *
 * 两个取值都集中在本文件，就是为了把「口径不确定」收敛到**单一改动点**。
 */
internal object PlaybackReportFieldsPolicy {

    /** 不限流量（WiFi / 以太网）。 */
    const val NETWORK_TYPE_UNMETERED = "1"

    /** 计费网络（蜂窝 / 取不到网络能力时的保守取值）。 */
    const val NETWORK_TYPE_METERED = "2"

    /** 前台播放。 */
    const val SCENE_FOREGROUND = "front"

    /**
     * 后台播放（含画中画 / 音频后台）。
     *
     * 取值本身是推断（见类 KDoc），但「后台不能报成前台」这一点是确定的：
     * 报成前台会让服务端把后台收听算进前台播放时长。
     */
    const val SCENE_BACKGROUND = "background"

    fun networkType(isUnmetered: Boolean): String =
        if (isUnmetered) NETWORK_TYPE_UNMETERED else NETWORK_TYPE_METERED

    fun scene(isHostInBackground: Boolean): String =
        if (isHostInBackground) SCENE_BACKGROUND else SCENE_FOREGROUND
}
