package com.naaammme.bbspace.core.feed

import com.naaammme.bbspace.core.model.FeedItem
import com.naaammme.bbspace.core.model.VideoTarget

/**
 * 「首页推荐流 WEB + MOBILE 合并」策略（PT-10 §4.5）。
 *
 * 语义**逐字移植**自上游 `feature/home/HomeFeedMergePolicy.kt`
 * （`D:\111j\c\_ref\BiliPai-024`，v0.2.4）—— 交错合并而非"web 全部在前"，
 * 交错顺序固定为 **app 在前、web 在后**（同一页序号上先 app[i] 再 web[i]）。
 *
 * 纯函数、无副作用，可纯 JVM 单测（方案 §6 V7）。
 */
internal object FeedMergePolicy {

    /**
     * 交错合并两条推荐流并按 [keyOf] 去重。
     *
     * - 按索引交替取 `app[i]`、`web[i]`（app 在前）；
     * - 重复项只保留首次出现；
     * - 一方耗尽后把另一方剩余项追加到末尾；
     * - 任一输入为空时安全返回另一侧。
     */
    fun mergeFeeds(web: List<FeedItem>, app: List<FeedItem>): List<FeedItem> {
        if (web.isEmpty()) return app
        if (app.isEmpty()) return web

        val seen = HashSet<String>(web.size + app.size)
        val merged = ArrayList<FeedItem>(web.size + app.size)
        val maxLen = maxOf(web.size, app.size)
        for (i in 0 until maxLen) {
            if (i < app.size) {
                val item = app[i]
                if (seen.add(keyOf(item))) merged.add(item)
            }
            if (i < web.size) {
                val item = web[i]
                if (seen.add(keyOf(item))) merged.add(item)
            }
        }
        return merged
    }

    /**
     * 视频唯一 key。上游口径是 `dynamicId > bvid > aid > id`；bbspace 的 [FeedItem]
     * 没有 dynamicId 字段（动态卡走 `liveRoute`/无 target），落到 [VideoTarget.Ugc] 上：
     * `bvid > aid`，再退到 `param`（app 协议里 param 就是 aid 字符串），最后退到
     * [`FeedItem.actionKey`] 兜底 —— **保证任何条目都有 key，不会因 key 相同而误删**。
     */
    fun keyOf(item: FeedItem): String {
        val ugc = item.target as? VideoTarget.Ugc
        ugc?.bvid?.takeIf { it.isNotBlank() }?.let { return "bvid:$it" }
        if (ugc != null && ugc.aid > 0L) return "aid:${ugc.aid}"
        if (item.param.isNotBlank()) return "param:${item.param}"
        return "key:${item.actionKey}"
    }
}
