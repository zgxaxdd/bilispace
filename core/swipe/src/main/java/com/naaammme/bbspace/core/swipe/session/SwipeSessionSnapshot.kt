package com.naaammme.bbspace.core.swipe.session

import com.naaammme.bbspace.core.model.VideoDimension
import com.naaammme.bbspace.core.model.VideoSrc
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.swipe.SwipeEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 会话快照（进程重建恢复，SW-02 不变式 4）：还原会话所需的最小字段。
 * 只在 `Ready`/粘滞态生成、`Idle` 时丢弃；`Bootstrapping`（起步在途）与
 * `PlaybackFailed` 不落盘——前者本来就没有可恢复的队列，后者用户要么重试要么退出。
 *
 * 编解码手写为纯函数（JsonObject 构建器，不引 @Serializable 插件）：
 * `SwipeEntry` 的身份字段（aid/cid/bvid + [VideoSrc] 四元组）必须无损往返，
 * 否则恢复后的 `openSwipeTarget` 短路判据会把"同一支"误判成新目标、白白重开播放。
 */
data class SwipeSessionSnapshot(
    val via: SwipeEntryVia,
    val page: Int,
    val anchor: SwipeEntry,
    val queue: List<SwipeEntry>,
)

private const val KEY_VIA = "via"
private const val KEY_PAGE = "page"
private const val KEY_ANCHOR = "anchor"
private const val KEY_QUEUE = "queue"
private const val KEY_AID = "aid"
private const val KEY_CID = "cid"
private const val KEY_BVID = "bvid"
private const val KEY_TITLE = "title"
private const val KEY_COVER = "cover"
private const val KEY_AUTHOR = "author"
private const val KEY_DURATION_TEXT = "durationText"
private const val KEY_OWNER_MID = "ownerMid"
private const val KEY_DURATION_SEC = "durationSec"
private const val KEY_DIM_WIDTH = "dimWidth"
private const val KEY_DIM_HEIGHT = "dimHeight"
private const val KEY_DIM_ROTATE = "dimRotate"
private const val KEY_SRC_FROM = "srcFrom"
private const val KEY_SRC_SPMID = "srcSpmid"
private const val KEY_SRC_TRACK = "srcTrack"
private const val KEY_SRC_FLOW = "srcFlow"

// ⚠️ dislikeContext（SW-04 字段）**刻意不进快照**：它的 reportData/trackId 是
// KB 级长串，整队列落盘会把 SavedStateHandle 撑大一个量级。恢复后的会话里
// 该字段为 null → SW-24 的"不感兴趣"入口按既有规格隐藏（null = 不做假按钮），
// 语义自洽；SW-24 接手时若要恢复负反馈入口，须连同 SavedStateHandle 容量一起评估。

/** 防御上限：快照异常膨胀时不至于把 SavedStateHandle 撑爆（正常队列几十条封顶）。 */
private const val MAX_SNAPSHOT_QUEUE = 200

/** 会话不落盘的瞬态：起步在途 / 失败态（见类注释）。 */
fun SwipeSessionState.isSnapshotWorthy(): Boolean = when (this) {
    is SwipeSessionState.Ready, SwipeSessionState.Extending, SwipeSessionState.Exhausted -> true
    is SwipeSessionState.Idle, is SwipeSessionState.Bootstrapping, is SwipeSessionState.PlaybackFailed -> false
}

fun swipeSessionSnapshotJson(snapshot: SwipeSessionSnapshot): String = buildJsonObject {
    put(KEY_VIA, snapshot.via.name)
    put(KEY_PAGE, snapshot.page)
    put(KEY_ANCHOR, snapshot.anchor.toJsonObject())
    put(
        KEY_QUEUE,
        buildJsonArray {
            snapshot.queue.take(MAX_SNAPSHOT_QUEUE).forEach { add(it.toJsonObject()) }
        }
    )
}.toString()

/** 解析失败/字段残缺返回 null（调用方按"无快照"处理；恢复是尽力而为）。 */
fun parseSwipeSessionSnapshot(json: String): SwipeSessionSnapshot? = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val viaName = root[KEY_VIA]?.jsonPrimitive?.contentOrNull ?: return null
    val via = SwipeEntryVia.entries.firstOrNull { it.name == viaName } ?: return null
    val page = root[KEY_PAGE]?.jsonPrimitive?.intOrNull ?: return null
    val anchor = (root[KEY_ANCHOR] as? JsonObject)?.toSwipeEntry() ?: return null
    val queue = (root[KEY_QUEUE] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.toSwipeEntry() }
    SwipeSessionSnapshot(via = via, page = page, anchor = anchor, queue = queue)
}.getOrNull()

private fun SwipeEntry.toJsonObject(): JsonObject = buildJsonObject {
    put(KEY_AID, target.aid)
    put(KEY_CID, target.cid)
    put(KEY_BVID, bvid)
    put(KEY_TITLE, title)
    put(KEY_COVER, cover)
    author?.let { put(KEY_AUTHOR, it) }
    durationText?.let { put(KEY_DURATION_TEXT, it) }
    ownerMid?.let { put(KEY_OWNER_MID, it) }
    durationSec?.let { put(KEY_DURATION_SEC, it) }
    dimension?.let {
        put(KEY_DIM_WIDTH, it.width)
        put(KEY_DIM_HEIGHT, it.height)
        put(KEY_DIM_ROTATE, it.rotate)
    }
    put(KEY_SRC_FROM, target.src.from)
    put(KEY_SRC_SPMID, target.src.fromSpmid)
    target.src.trackId?.let { put(KEY_SRC_TRACK, it) }
    target.src.reportFlowData?.let { put(KEY_SRC_FLOW, it) }
}

/** 身份字段缺失（aid/cid 全 0 且 bvid 空）的条目直接丢弃：恢复出来的队列必须可播。 */
private fun JsonObject.toSwipeEntry(): SwipeEntry? {
    val aid = this[KEY_AID]?.jsonPrimitive?.longOrNull ?: 0L
    val cid = this[KEY_CID]?.jsonPrimitive?.longOrNull ?: 0L
    val bvid = this[KEY_BVID]?.jsonPrimitive?.contentOrNull.orEmpty()
    if (aid <= 0L && bvid.isBlank()) return null
    val src = VideoSrc(
        from = this[KEY_SRC_FROM]?.jsonPrimitive?.contentOrNull ?: VideoSrc().from,
        fromSpmid = this[KEY_SRC_SPMID]?.jsonPrimitive?.contentOrNull ?: VideoSrc().fromSpmid,
        trackId = this[KEY_SRC_TRACK]?.jsonPrimitive?.contentOrNull,
        reportFlowData = this[KEY_SRC_FLOW]?.jsonPrimitive?.contentOrNull
    )
    val dimWidth = this[KEY_DIM_WIDTH]?.jsonPrimitive?.intOrNull ?: 0
    val dimHeight = this[KEY_DIM_HEIGHT]?.jsonPrimitive?.intOrNull ?: 0
    val dimRotate = this[KEY_DIM_ROTATE]?.jsonPrimitive?.intOrNull ?: 0
    return SwipeEntry(
        target = VideoTarget.Ugc(aid = aid, cid = cid, bvid = bvid.takeIf(String::isNotEmpty), src = src),
        bvid = bvid,
        title = this[KEY_TITLE]?.jsonPrimitive?.contentOrNull.orEmpty(),
        cover = this[KEY_COVER]?.jsonPrimitive?.contentOrNull.orEmpty(),
        author = this[KEY_AUTHOR]?.jsonPrimitive?.contentOrNull,
        durationText = this[KEY_DURATION_TEXT]?.jsonPrimitive?.contentOrNull,
        ownerMid = this[KEY_OWNER_MID]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L },
        durationSec = this[KEY_DURATION_SEC]?.jsonPrimitive?.longOrNull?.takeIf { it > 0L },
        dimension = VideoDimension.from(
            width = dimWidth.toLong(),
            height = dimHeight.toLong(),
            rotate = dimRotate.toLong()
        )
    )
}

/** 供日志用的短标识（不落盘）。 */
internal val SwipeSessionSnapshot.describe: String
    get() = "via=$via page=$page queue=${queue.size} anchor=${anchor.legacyKey()}"
