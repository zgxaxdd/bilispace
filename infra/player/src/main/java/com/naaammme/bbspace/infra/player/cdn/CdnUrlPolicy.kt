package com.naaammme.bbspace.infra.player.cdn

import java.net.URI

/**
 * 从 CDN URL 取主机名（小写）；URL 非法时返回空串。
 *
 * ## 为什么单独一个文件
 *
 * 它原本住在 `CdnRegionPolicy.kt`（那 736 行里唯一被活代码用到的函数，见
 * `docs/port-analysis/10-移植件生效核对总表.md` §3）。**未采用**的地区/ISP/自定义规则/
 * 探测/诊断那一簇已经归档出构建（`docs/port-analysis/reference/CdnRegionPolicy.kt.txt`），
 * 所以把唯一的活口搬到这里，避免"为了 1 个函数保留 700 行孤儿"。
 *
 * 调用方（都靠转义后的 URL 定位线路，不做任何 DNS/网络动作）：
 * - `CdnTransferRuntime.rank()` —— 线路排序按 host 取会话内统计；
 * - `CdnPlaybackDataSource` —— 并行分片/续传时判断"换的是不是同一条线路"；
 * - `CdnDashSegmentPrefetcher` —— 预取命中后上报服务该段的 host。
 *
 * 逐字移植自 BiliPai `feature/plugin/CdnRegionPolicy.kt:734-736`。
 */
fun hostFromCdnUrl(url: String): String {
    return runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
}
