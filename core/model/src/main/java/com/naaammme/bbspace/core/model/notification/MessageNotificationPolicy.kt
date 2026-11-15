package com.naaammme.bbspace.core.model.notification

/**
 * 消息通知的**纯策略层**（去重 / 漏报补报 / 误清防止 / 轮询预算）。
 *
 * 上游来源：BiliPai `app/.../feature/message/notification/`
 * - `MessageNotificationPolicy.kt`（纯函数）；
 * - `MessageNotificationChecker.kt:183-465`（轮询主流程的去重/漏报/误清判定）；
 * - `MessageNotificationStateStore.kt:20-40`（跨轮状态字段）；
 * - `MessageNotificationWorker.kt:33-49`（周期/退避预算）；
 * - 关联提交 `9037354d4 fix(message): 修复动态通知漏报与误清，支持 16 个 UP 并发轮询`。
 *
 * ## 本仓为什么放在 core/model
 * 本仓目前**没有消息通知链路**（只有 media3 播放通知），也没有可接的 Worker/Notifier。
 * 因此这里只沉淀"与 Android 无关、可 JVM 单测"的判定，等未来出现消息通知宿主
 * （Service/Worker + Notifier）时直接接上，避免把判定散落进那一层。
 *
 * ## 与上游的模型差异
 * 上游直接吃 `SessionItem` / `DynamicItem` / `FollowedLiveRoom`（app 层网络模型）与
 * `ScreenRoutes`；本模块不能依赖 app/feature，所以：
 * - `SessionItem` → [NotificationSession]（只保留判定需要的字段）；
 * - `FollowedLiveRoom` → [LiveSession]；
 * - 动态项"该不该通知"依赖 `DynamicCardPrimaryAction`（feature/dynamic 的策略），
 *   本层只保留 **ID/预览预算** 部分，资格判定留给将来的宿主层（见报告差异）。
 *
 * ## 关键不变量（每条都有对应单测）
 * 1. **去重**：同一会话只按 `talkerId_sessionType` 一个键；同一 `msgKey` 不重复通知。
 * 2. **漏报**：扫描失败/分页未覆盖时，旧 pending 必须原样保留到下一轮（不因"这次没看到"而丢）；
 *    只有**明确的作者级已读**或"通知已不在系统栏"才允许丢弃/取消。
 * 3. **误清**：用户划掉通知后，下一轮不得再为同一 pending 重建；计数归零只在"通知仍在场"
 *    时发取消（否则会误清其它入口的未读）。
 * 4. **预算**：单类目单轮最多 5 条、动态预览最多 3 条、动态扫描并发上限 16
 *    （**不是**每个 UP 一个协程 —— `9037354d4` 的 W8 反例）。
 */

/** 通知轮询模式（上游 `MessageNotificationMode`）。 */
enum class NotificationPollMode {
    /** 省电：周期 30 分钟、常驻轮询 120 秒、要求电量不低。 */
    POWER_SAVING,

    /** 更及时：周期 15 分钟、常驻轮询 60 秒、不要求电量。 */
    MORE_TIMELY,
}

/** 两档时序（上游 `MessageNotificationTiming`，数值逐字一致）。 */
data class MessageNotificationTiming(
    val periodicMinutes: Long,
    val residentPollSeconds: Long,
    val requiresBatteryNotLow: Boolean,
)

fun resolveMessageNotificationTiming(mode: NotificationPollMode): MessageNotificationTiming = when (mode) {
    NotificationPollMode.POWER_SAVING -> MessageNotificationTiming(30, 120, true)
    NotificationPollMode.MORE_TIMELY -> MessageNotificationTiming(15, 60, false)
}

/**
 * 常驻轮询的空闲下限（上游 `RESIDENT_IDLE_POLL_MS`）：屏幕熄灭且未充电时退化到 15 分钟，
 * 避免 60~120 秒轮询整夜烧射频/CPU；更慢的兜底交给周期任务。
 */
const val RESIDENT_IDLE_POLL_MS: Long = 15 * 60 * 1000L

fun resolveResidentPollDelayMs(
    mode: NotificationPollMode,
    screenOn: Boolean,
    charging: Boolean,
): Long {
    val base = resolveMessageNotificationTiming(mode).residentPollSeconds * 1000
    return if (screenOn || charging) base else RESIDENT_IDLE_POLL_MS
}

/** 周期任务的线性退避基准（上游 WorkManager `BackoffPolicy.LINEAR, 5min`）。 */
const val NOTIFICATION_WORK_BACKOFF_MINUTES: Long = 5

// ── 预算上限（上游常量逐字一致） ───────────────────────────────────────────────

/** 单类目单轮最多合并多少条通知（上游各 `take(5)`）。 */
const val MAX_NOTIFICATION_ITEMS_PER_CATEGORY: Int = 5

/** 动态通知的标题预览上限（上游 `MAX_DYNAMIC_NOTIFICATION_PREVIEWS`）。 */
const val MAX_DYNAMIC_NOTIFICATION_PREVIEWS: Int = 3

/**
 * 动态扫描的并发上限（上游 `MAX_DYNAMIC_NOTIFICATION_CONCURRENCY = 16`）。
 *
 * **W8 反例（`9037354d4` 前）**：按关注 UP 数量起协程，关注 200 人就是 200 路并发请求。
 * 上游修法是"固定 worker 数，每个 worker 顺序认领一个 UP 的全部翻页"。
 */
const val MAX_DYNAMIC_SCAN_CONCURRENCY: Int = 16

/** 会话 msgKey 记忆窗口（上游写死 100）。 */
const val MAX_SEEN_SESSION_KEYS: Int = 100

/** 系统通知游标记忆窗口（上游写死 100）。 */
const val MAX_SEEN_SYSTEM_CURSORS: Int = 100

/** 实际 worker 数：目标数不为负，且不得超过预算上限（防空任务）。 */
fun resolveDynamicScanWorkers(targets: Int): Int {
    if (targets <= 0) return 0
    return minOf(MAX_DYNAMIC_SCAN_CONCURRENCY, targets)
}

// ── 私信会话去重 ─────────────────────────────────────────────────────────────

/**
 * 判定所需的最小会话快照（替代上游 `SessionItem`）。
 *
 * @param talkerId 对方 mid（>0 才可能是真实会话）
 * @param sessionType 会话类型（与 talkerId 一起构成通知键）
 * @param unreadCount 未读数（0 = 已读）
 * @param isDnd 消息免打扰
 * @param isIntercept 拦截（陌生人/黑名单）
 * @param lastMsgKey 最后一条消息的 msgKey（单调递增；0/负值不可用于去重）
 * @param lastSenderUid 最后一条消息的发送者（自己发的不能通知自己）
 */
data class NotificationSession(
    val talkerId: Long,
    val sessionType: Int = 1,
    val unreadCount: Int = 0,
    val isDnd: Int = 0,
    val isIntercept: Int = 0,
    val lastMsgKey: Long = 0,
    val lastSenderUid: Long = 0,
)

fun sessionNotificationKey(session: NotificationSession): String =
    "${session.talkerId}_${session.sessionType}"

/**
 * 找出"需要通知的新私信会话"。
 *
 * 逐条对应上游 `filterNewPrivateSessions`：
 * 有未读、未免打扰、未拦截、最后一条消息来自别人且不是已知 msgKey；同键只留第一条。
 *
 * @param knownMsgKeys 上一次已通知过的 `key -> msgKey`（见 [mergeSessionMsgKeys]）
 */
fun filterNewPrivateSessions(
    sessions: List<NotificationSession>,
    selfMid: Long,
    knownMsgKeys: Map<String, Long>,
): List<NotificationSession> = sessions.filter { session ->
    session.talkerId > 0 &&
        session.unreadCount > 0 &&
        session.isDnd == 0 &&
        session.isIntercept == 0 &&
        session.lastMsgKey > 0 &&
        session.lastSenderUid != selfMid &&
        knownMsgKeys[sessionNotificationKey(session)] != session.lastMsgKey
}.distinctBy(::sessionNotificationKey)

/**
 * 把本轮的会话 msgKey 并入记忆表（上游 `keys.remove(key); keys[key] = msgKey`），
 * 超出 [MAX_SEEN_SESSION_KEYS] 时按"最旧未使用"淘汰。
 */
fun mergeSessionMsgKeys(
    known: Map<String, Long>,
    sessions: List<NotificationSession>,
    cap: Int = MAX_SEEN_SESSION_KEYS,
): Map<String, Long> {
    require(cap > 0) { "cap must be positive" }
    val merged = LinkedHashMap(known)
    for (session in sessions.asReversed()) {
        if (session.talkerId <= 0 || session.lastMsgKey <= 0) continue
        val key = sessionNotificationKey(session)
        merged.remove(key)
        merged[key] = session.lastMsgKey
        if (merged.size > cap) merged.entries.iterator().apply { next(); remove() }
    }
    return merged
}

// ── 通用有界记忆（seen 列表 / 游标 / 预览） ──────────────────────────────────

/**
 * 保留"最新 [cap] 个"的可重入有界集合：重复项移到末尾（最近），超出上限从最旧端淘汰。
 */
fun <T> evictBounded(items: List<T>, cap: Int): List<T> {
    require(cap > 0) { "cap must be positive" }
    val recent = LinkedHashSet<T>()
    for (item in items) {
        recent.remove(item)
        recent.add(item)
        if (recent.size > cap) recent.iterator().apply { next(); remove() }
    }
    return recent.toList()
}

/**
 * 合并"已见过"游标（上游 `mergeNotificationSeen`）。
 *
 * 分页是**新到旧**，持久化列表是**旧到新**：先反转让新项落到末尾，
 * 再按 [evictBounded] 淘汰旧项。
 */
fun <T> mergeNotificationSeen(seen: List<T>, newestFirst: List<T>, cap: Int): List<T> =
    evictBounded(seen + newestFirst.asReversed(), cap)

// ── 未读计数：漏报补报 / 误清 ─────────────────────────────────────────────────

/**
 * 未读数相对基线的**增加量**（上游 `feed()` 的 increase）。
 *
 * 缺基线（首次安装/升级/上次请求失败）⇒ 0（静默建立基线，不把历史未读当新通知）；
 * 未读下降 ⇒ 0（用户已读，不能补报）；未读为负按 0 处理。
 */
fun resolveUnreadIncrease(previousCount: Int?, currentUnread: Int): Int {
    val count = currentUnread.coerceAtLeast(0)
    if (previousCount == null) return 0
    return (count - previousCount).coerceAtLeast(0)
}

/**
 * 未读归零时是否应发"取消通知"（上游 `feed()`）：
 * 只有该通知**仍在系统栏**才取消 —— 用户自己划掉的通知不需要、也不能再清一次。
 */
fun shouldCancelUnreadNotification(currentUnread: Int, notificationActive: Boolean): Boolean =
    currentUnread == 0 && notificationActive

/**
 * 系统通知的新游标（上游"取第一条不在已见集合里的"）。
 * 返回 null = 没有新通知。
 */
fun resolveSystemNoticeCandidate(
    cursorsNewestFirst: List<Long>,
    seenCursors: List<Long>,
): Long? {
    val known = seenCursors.toHashSet()
    return cursorsNewestFirst.firstOrNull { it !in known }
}

/**
 * 是否真的该发系统通知（上游 `if ("sysmsg" in initialized && latest != null && systemUnreadCount != 0)`）。
 *
 * @param initialized 该类目是否已完成首轮静默初始化（首轮只记基线不通知）
 * @param systemUnread 系统未读数；`null` = 本轮未取到（按上游口径视为"不等于 0"）
 */
fun shouldNotifySystemNotice(
    initialized: Boolean,
    candidateCursor: Long?,
    systemUnread: Int?,
): Boolean = initialized && candidateCursor != null && systemUnread != 0

// ── 动态通知：待发集合（漏报保留 / 已读取消） ─────────────────────────────────

/**
 * 一轮动态扫描后，跨轮 pending 集合的收敛结果。
 *
 * @property kept 下一轮仍要保留的 `动态ID -> 作者mid`
 * @property cancelKeys 需要主动从通知栏撤掉的键（形如 `dynamic:<id>`）
 */
data class NotificationPendingResolution(
    val kept: Map<String, Long>,
    val cancelKeys: Set<String>,
)

/**
 * 收敛"上一轮 pending + 本轮活跃通知"（上游 `check()` 的动态段）。
 *
 * 规则（防漏报/防误清）：
 * 1. 活跃键里有 `dynamic:summary`（群摘要）⇒ **全部保留**（摘要代表溢出条目，看不见单体通知不代表已读）；
 * 2. 否则只保留"单体通知仍在系统栏"的 pending；已不在系统栏的直接丢弃，**不发取消**；
 * 3. 只有 `authorRead[mid] == false`（作者级明确已读）才取消并移除 —— 缺失/失败的快照不是已读证据。
 */
fun resolvePendingNotifications(
    storedPending: Map<String, Long>,
    activeKeys: Set<String>,
    summaryKey: String,
    authorRead: Map<Long, Boolean>,
): NotificationPendingResolution {
    val keepAll = summaryKey in activeKeys
    val kept = LinkedHashMap<String, Long>()
    val cancelled = linkedSetOf<String>()
    for ((id, mid) in storedPending) {
        val singleKey = "dynamic:$id"
        if (!keepAll && singleKey !in activeKeys) continue
        if (authorRead[mid] == false) {
            cancelled += singleKey
            continue
        }
        kept[id] = mid
    }
    return NotificationPendingResolution(kept, cancelled)
}

/**
 * 把本轮新扫到的动态 ID 并入 pending（上游 `pending[id] = mid`）。
 * [authorRead] 为 false 的作者整批跳过（已读即静默推进基线，不补报）。
 */
fun mergePendingNotificationCandidates(
    kept: Map<String, Long>,
    candidatesByAuthor: Map<Long, List<String>>,
    authorRead: Map<Long, Boolean>,
): Map<String, Long> {
    val merged = LinkedHashMap(kept)
    for ((mid, ids) in candidatesByAuthor) {
        if (authorRead[mid] == false) continue
        for (id in ids) merged[id] = mid
    }
    return merged
}

// ── 动态 ID / 预览预算 ───────────────────────────────────────────────────────

/**
 * 动态 ID 是否比基线更新。ID 是十进制发布时间戳：**按长度再按字典序**比较，
 * 避免 `Long` 溢出与 `BigInteger` 分配（上游 `isNewerDynamicNotificationId`）。
 */
fun isNewerNotificationId(id: String, baseline: String): Boolean =
    id.isNotEmpty() && (id.length > baseline.length || (id.length == baseline.length && id > baseline))

/**
 * 把新动态 ID 按"新到旧"插入预览列表，最多保留 [cap] 个（上游
 * `addNewestDynamicNotificationPreview`）；返回是否插入。
 */
fun addNewestPreviewId(ids: MutableList<String>, id: String, cap: Int = MAX_DYNAMIC_NOTIFICATION_PREVIEWS): Boolean {
    if (id.isEmpty()) return false
    var index = 0
    while (index < ids.size && !isNewerNotificationId(id, ids[index])) index++
    if (index >= cap) return false
    ids.add(index, id)
    if (ids.size > cap) ids.removeAt(cap)
    return true
}

// ── 直播开播：只在真实状态转移时通知 ─────────────────────────────────────────

/** 直播房间的最小快照（替代上游 `FollowedLiveRoom`）。 */
data class LiveSession(
    val uid: Long,
    val roomId: Long,
    val liveStatus: Int,
    val liveTime: Long,
)

/**
 * 计算本条通知的直播开播与最新会话表（上游 `resolveLiveSessionTransitions`）。
 *
 * 首轮（[initialized] = false）只建基线不通知；`liveTime` 必须非 0 且**与上次不同**
 * 才算"新开播"（同一场直播重复轮询不重复通知）。`liveStatus` 只认 1（直播中）。
 */
fun resolveLiveSessionTransitions(
    living: List<LiveSession>,
    stored: Map<Long, Long>,
    initialized: Boolean,
): Pair<List<LiveSession>, Map<Long, Long>> {
    val current = linkedMapOf<Long, Long>()
    val notifications = mutableListOf<LiveSession>()
    for (room in living) {
        if (room.liveStatus != 1 || room.uid <= 0 || room.roomId <= 0) continue
        if (current.containsKey(room.uid)) continue
        current[room.uid] = room.liveTime
        if (initialized && stored[room.uid] != room.liveTime) notifications += room
    }
    return notifications to current
}
