package com.naaammme.bbspace.core.common.data

/**
 * 分页合并去重（AP-11）：**重复 key 会让 LazyColumn 直接崩溃**。
 *
 * 服务端分页窗口会重叠（历史持续写入、游标回退、重连回放），"两页拼接"必须按
 * 业务 key 去重。仓内既有范式：`HistoryViewModel` 的 `distinctBy(HistoryItem::key)`
 * （注释原话："重复 key 会让 LazyColumn 崩溃"）、`ImViewModel.mergeSessions` 的
 * LinkedHashMap 合并。此前会话页 / 历史搜索页 / 直播消息流漏掉，本函数统一收口。
 *
 * @param reset   翻页重置（刷新 / 换 tab）时直接以 incoming 为准；
 * @param keyOf   业务 key（必须与列表 `key = { ... }` 用的是**同一个字段**）。
 *                重复条目保留 current 侧（先到的），incoming 侧的重复被丢弃 ——
 *                "保留哪一份"对用户可见，改动需同步 UI-04 与测试。
 */
fun <T> mergePagesUnique(
    current: List<T>,
    incoming: List<T>,
    reset: Boolean,
    keyOf: (T) -> Any
): List<T> {
    if (reset) return incoming.distinctBy(keyOf)
    if (current.isEmpty()) return incoming.distinctBy(keyOf)
    if (incoming.isEmpty()) return current
    return (current + incoming).distinctBy(keyOf)
}
