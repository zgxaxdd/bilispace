package com.naaammme.bbspace.core.model.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 通知策略回归测试：上游 `MessageNotificationPolicyTest.kt` 中**不依赖 Android/app 模型**的
 * 用例逐条移植，另补本仓纯数据化的差异用例。
 *
 * 为什么这些判据值得钉：它们分别对应三类真实事故 ——
 * 去重判错 → 同一条消息反复通知；漏报判错 → 通知永久丢失；误清判错 →
 * 用户划掉/已读后通知又被重建，或把别处的未读误清。
 */
class MessageNotificationPolicyTest {

    private fun session(
        talkerId: Long,
        sessionType: Int = 1,
        unreadCount: Int = 1,
        isDnd: Int = 0,
        isIntercept: Int = 0,
        lastMsgKey: Long = 100L,
        lastSenderUid: Long = talkerId,
    ) = NotificationSession(
        talkerId = talkerId,
        sessionType = sessionType,
        unreadCount = unreadCount,
        isDnd = isDnd,
        isIntercept = isIntercept,
        lastMsgKey = lastMsgKey,
        lastSenderUid = lastSenderUid,
    )

    // ── 时序与预算 ───────────────────────────────────────────────────────────

    @Test
    fun timelyModeShortensBothSchedulesWithoutBatteryConstraint() {
        assertEquals(
            MessageNotificationTiming(30, 120, true),
            resolveMessageNotificationTiming(NotificationPollMode.POWER_SAVING),
        )
        assertEquals(
            MessageNotificationTiming(15, 60, false),
            resolveMessageNotificationTiming(NotificationPollMode.MORE_TIMELY),
        )
    }

    @Test
    fun residentPollDropsToIdleFloorOnlyWhenScreenOffAndDischarging() {
        val timely = NotificationPollMode.MORE_TIMELY
        val saving = NotificationPollMode.POWER_SAVING
        assertEquals(60_000L, resolveResidentPollDelayMs(timely, screenOn = true, charging = false))
        assertEquals(60_000L, resolveResidentPollDelayMs(timely, screenOn = false, charging = true))
        assertEquals(120_000L, resolveResidentPollDelayMs(saving, screenOn = true, charging = true))
        assertEquals(RESIDENT_IDLE_POLL_MS, resolveResidentPollDelayMs(timely, screenOn = false, charging = false))
        assertEquals(RESIDENT_IDLE_POLL_MS, resolveResidentPollDelayMs(saving, screenOn = false, charging = false))
        assertEquals(15 * 60 * 1000L, RESIDENT_IDLE_POLL_MS)
    }

    @Test
    fun dynamicScanWorkersStayWithinTheSixteenWorkerBudget() {
        // W8 反例（9037354d4 之前）：按关注 UP 数量起协程。这里钉死"固定 worker 上限"。
        assertEquals(0, resolveDynamicScanWorkers(0))
        assertEquals(0, resolveDynamicScanWorkers(-3))
        assertEquals(3, resolveDynamicScanWorkers(3))
        assertEquals(16, resolveDynamicScanWorkers(16))
        assertEquals(16, resolveDynamicScanWorkers(100))
        assertEquals(16, MAX_DYNAMIC_SCAN_CONCURRENCY)
        assertEquals(5, MAX_NOTIFICATION_ITEMS_PER_CATEGORY)
        assertEquals(3, MAX_DYNAMIC_NOTIFICATION_PREVIEWS)
        assertEquals(5L, NOTIFICATION_WORK_BACKOFF_MINUTES)
    }

    // ── 私信会话去重 ─────────────────────────────────────────────────────────

    @Test
    fun privateMessagesRespectReadStateMutingSenderAndSessionIdentity() {
        val message = session(talkerId = 9, unreadCount = 1, lastMsgKey = 100, lastSenderUid = 9)
        val sessions = listOf(
            message,
            message.copy(sessionType = 2),
            message.copy(talkerId = 10, unreadCount = 0),
            message.copy(talkerId = 11, isDnd = 1),
            message.copy(talkerId = 12, isIntercept = 1),
            message.copy(talkerId = 13, lastSenderUid = 1),
            message.copy(talkerId = 14, lastMsgKey = 0),
            message.copy(talkerId = 0),
        )
        val known = mapOf("9_1" to 100L)
        assertEquals(listOf("9_2"), filterNewPrivateSessions(sessions, 1, known).map(::sessionNotificationKey))
        assertEquals(
            listOf("9_1", "9_2"),
            filterNewPrivateSessions(sessions, 1, emptyMap()).map(::sessionNotificationKey),
        )
    }

    @Test
    fun sessionMsgKeysMergeUpdatesAndEvictsOldest() {
        val first = session(talkerId = 9, lastMsgKey = 100)
        val second = session(talkerId = 10, lastMsgKey = 200)
        // 接口契约：入参最新在前（与上游 sessions() 一致）；实现内部反转成"旧→新"处理。
        val newestFirst = listOf(second, first)
        val merged = mergeSessionMsgKeys(mapOf("9_1" to 50L), newestFirst, cap = 2)
        assertEquals(mapOf("9_1" to 100L, "10_1" to 200L), merged)
        // cap=1：最旧的键被淘汰（9 先写入、10 后写入）
        val evicted = mergeSessionMsgKeys(mapOf("9_1" to 50L), newestFirst, cap = 1)
        assertEquals(mapOf("10_1" to 200L), evicted)
    }

    // ── 有界记忆 ─────────────────────────────────────────────────────────────

    @Test
    fun boundedSeenRetainsNewestItemsEvenWithPageOverlap() {
        assertEquals(
            listOf(3L, 4L, 5L, 6L),
            mergeNotificationSeen(listOf(1L, 2L, 3L), listOf(6L, 5L, 4L, 3L), 4),
        )
        // 分页里重复出现的旧游标只保留一份，且顺序稳定（旧→新）
        assertEquals(listOf(2L, 3L), evictBounded(listOf(1L, 2L, 3L), 2))
    }

    @Test
    fun nonPositiveCapIsRejectedInsteadOfSilentlyEmpty() {
        try {
            evictBounded(listOf(1), 0)
            fail("cap=0 必须拒绝")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ── 未读计数：漏报补报 / 误清 ─────────────────────────────────────────────

    @Test
    fun unreadBaselineIsSilentOnFirstScanAndOnlyCountsIncreases() {
        // 缺基线（首装/升级/上次失败）⇒ 不把历史未读当新通知
        assertEquals(0, resolveUnreadIncrease(null, 120))
        assertEquals(2, resolveUnreadIncrease(5, 7))
        // 用户已读（未读下降）不能补报
        assertEquals(0, resolveUnreadIncrease(7, 5))
        // 负数未读按 0 处理
        assertEquals(0, resolveUnreadIncrease(5, -1))
        // 不变
        assertEquals(0, resolveUnreadIncrease(5, 5))
    }

    @Test
    fun zeroUnreadCancelsOnlyWhenNotificationIsStillActive() {
        assertTrue(shouldCancelUnreadNotification(0, notificationActive = true))
        assertFalse(shouldCancelUnreadNotification(0, notificationActive = false))
        assertFalse(shouldCancelUnreadNotification(3, notificationActive = true))
    }

    // ── 系统通知：游标去重 ───────────────────────────────────────────────────

    @Test
    fun systemNoticeUsesCursorDedupeAndUnreadGuard() {
        val candidate = resolveSystemNoticeCandidate(
            cursorsNewestFirst = listOf(7L, 6L, 5L),
            seenCursors = listOf(5L, 6L, 7L),
        )
        assertNull(candidate)
        val fresh = resolveSystemNoticeCandidate(listOf(8L, 7L), listOf(5L, 6L, 7L))
        assertEquals(8L, fresh)
        assertTrue(shouldNotifySystemNotice(initialized = true, candidateCursor = fresh, systemUnread = 1))
        assertTrue(shouldNotifySystemNotice(initialized = true, candidateCursor = fresh, systemUnread = null))
        assertFalse(shouldNotifySystemNotice(initialized = true, candidateCursor = fresh, systemUnread = 0))
        assertFalse(shouldNotifySystemNotice(initialized = false, candidateCursor = fresh, systemUnread = 1))
        assertFalse(shouldNotifySystemNotice(initialized = true, candidateCursor = null, systemUnread = 1))
        // 游标记忆窗口：旧游标保留、新游标追加到末尾（旧→新），窗口满才淘汰
        assertEquals(
            listOf(5L, 6L, 7L, 8L),
            mergeNotificationSeen(listOf(5L, 6L), listOf(8L, 7L), MAX_SEEN_SYSTEM_CURSORS),
        )
    }

    // ── 动态 pending：漏报保留 / 已读取消 ──────────────────────────────────────

    @Test
    fun pendingDynamicSurvivesFailedRoundsButExplicitAuthorReadCancelsIt() {
        val stored = mapOf("90" to 9L, "100" to 10L)
        // 本轮只扫到 90 的单体通知在场：100 的通知被用户划掉 ⇒ 丢弃，但**不发取消**
        val quiet = resolvePendingNotifications(
            storedPending = stored,
            activeKeys = setOf("dynamic:90"),
            summaryKey = "dynamic:summary",
            authorRead = emptyMap(),
        )
        assertEquals(mapOf("90" to 9L), quiet.kept)
        assertEquals(emptySet<String>(), quiet.cancelKeys)

        // 群摘要在场 ⇒ 溢出条目全部保留（看不见单体通知 ≠ 已读）
        val summary = resolvePendingNotifications(
            storedPending = stored,
            activeKeys = setOf("dynamic:summary"),
            summaryKey = "dynamic:summary",
            authorRead = emptyMap(),
        )
        assertEquals(stored, summary.kept)

        // 只有"作者级明确已读"才取消（缺失/失败的快照不是已读证据）
        val read = resolvePendingNotifications(
            storedPending = stored,
            activeKeys = setOf("dynamic:summary"),
            summaryKey = "dynamic:summary",
            authorRead = mapOf(9L to false),
        )
        assertEquals(mapOf("100" to 10L), read.kept)
        assertEquals(setOf("dynamic:90"), read.cancelKeys)
    }

    @Test
    fun mergePendingCandidatesSkipsAuthorsAlreadyRead() {
        val merged = mergePendingNotificationCandidates(
            kept = mapOf("90" to 9L),
            candidatesByAuthor = mapOf(9L to listOf("91", "92"), 10L to listOf("110")),
            authorRead = mapOf(9L to false),
        )
        assertEquals(mapOf("90" to 9L, "110" to 10L), merged)
    }

    // ── 动态 ID 与预览预算 ───────────────────────────────────────────────────

    @Test
    fun newerIdComparesByLengthThenLexicographically() {
        assertTrue(isNewerNotificationId("100", "99"))
        assertTrue(isNewerNotificationId("99", "99") == false)
        assertTrue(isNewerNotificationId("1000", "999"))
        assertFalse(isNewerNotificationId("999", "1000"))
        assertFalse(isNewerNotificationId("", "0"))
        assertTrue(isNewerNotificationId("101", "100"))
    }

    @Test
    fun previewListKeepsNewestThreeInDescendingOrder() {
        val ids = mutableListOf<String>()
        assertTrue(addNewestPreviewId(ids, "100"))
        assertTrue(addNewestPreviewId(ids, "120"))
        assertTrue(addNewestPreviewId(ids, "110"))
        assertEquals(listOf("120", "110", "100"), ids)
        // 比末尾还旧 ⇒ 不插入
        assertFalse(addNewestPreviewId(ids, "90"))
        // 新的插入后超出 3 条 ⇒ 淘汰最旧
        assertTrue(addNewestPreviewId(ids, "130"))
        assertEquals(listOf("130", "120", "110"), ids)
    }

    // ── 直播开播转移 ─────────────────────────────────────────────────────────

    @Test
    fun liveSessionsOnlyNotifyForRealStartTransitions() {
        val room = LiveSession(uid = 9, roomId = 100, liveStatus = 1, liveTime = 100)
        val initial = resolveLiveSessionTransitions(
            living = listOf(room, LiveSession(uid = 10, roomId = 1, liveStatus = 2, liveTime = 1)),
            stored = emptyMap(),
            initialized = false,
        )
        assertEquals(emptyList<LiveSession>(), initial.first)
        assertEquals(mapOf(9L to 100L), initial.second)
        // 同一场直播重复轮询：不重复通知
        assertEquals(emptyList<LiveSession>(), resolveLiveSessionTransitions(listOf(room), initial.second, true).first)
        // liveTime 变化 = 新一场开播
        assertEquals(
            listOf(room.copy(liveTime = 200)),
            resolveLiveSessionTransitions(listOf(room.copy(liveTime = 200)), initial.second, true).first,
        )
        // 下播：会话表被清空；同一 liveTime 再出现视为新开播（与上游一致）
        val offline = resolveLiveSessionTransitions(emptyList(), initial.second, true)
        assertEquals(emptyMap<Long, Long>(), offline.second)
        assertEquals(listOf(room), resolveLiveSessionTransitions(listOf(room), offline.second, true).first)
        // liveTime 未知（0）：与基线相同，不通知
        assertEquals(
            emptyList<LiveSession>(),
            resolveLiveSessionTransitions(listOf(room.copy(liveTime = 0)), mapOf(9L to 0L), true).first,
        )
    }
}
