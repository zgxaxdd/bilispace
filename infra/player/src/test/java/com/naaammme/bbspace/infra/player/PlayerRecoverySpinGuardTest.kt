package com.naaammme.bbspace.infra.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「断网时播放器重建自旋」的回归守卫（真机实测缺陷，2026-10-06 PLC110）。
 *
 * ## 缺陷形态
 *
 * `onRenderedFirstFrame` 里无条件 `rebuildThrottle.reset(); networkRetryCount = 0`。
 * 断网后 `PlaybackMediaCache` 的缓存**仍能供出 1 帧** ⇒ 每次出帧都把两道预算清零 ⇒
 * 引擎每 ~3.3 秒重建一次 ExoPlayer。真机日志：55 秒内 `recovering from system error` **28 次**，
 * `retained released players after recover` 涨到 16，**0 次** `recover abandoned`（预算从未生效）。
 *
 * ## 修法（本守卫钉住的四条）
 *
 * 1. 出帧只记时刻（`recoveryFirstFrameAtMs`），**不再直接清零预算**；
 * 2. 稳定播放满 [RECOVERY_SETTLE_MS] 才在进度轮询里清零（而轮询只在"正在播放且 READY"时跑）；
 * 3. 再出错立即作废稳定期（`recoveryFirstFrameAtMs = -1L`）；
 * 4. 断网时**不为网络错误重建**（直接落错误态，交给 UI 的重试路径）。
 *
 * 结构断言（与 `CdnPlaybackRuntimeFeedTest` 同风格）：这类"接线被改回去"的缺陷只有
 * 源码级断言能拦住 —— 引擎依赖 ExoPlayer/Android 运行时，行为层无法在 JVM 单测里复现。
 */
class PlayerRecoverySpinGuardTest {

    @Test
    fun firstFrameAloneMustNotClearTheRecoveryBudget() {
        val body = blockBody(codeOf("Media3PlayerEngine.kt"), "override fun onRenderedFirstFrame()")
        assertFalse(
            "出帧回调里不许直接清零重建预算 —— 缓存里的一帧会冒充'恢复成功'，" +
                "断网时表现为每 ~3s 重建一次的活锁（真机 55s / 28 次）。" +
                "清零必须等 isRecoverySettled() 判定稳定播放满 RECOVERY_SETTLE_MS。",
            body.any { it.contains("rebuildThrottle.reset()") } ||
                body.any { it.contains("networkRetryCount = 0") }
        )
        assertTrue(
            "出帧必须记下时刻，供稳定期判定使用",
            body.any { it.contains("recoveryFirstFrameAtMs = android.os.SystemClock.elapsedRealtime()") }
        )
    }

    @Test
    fun budgetIsClearedOnlyAfterSustainedPlaybackInProgressPolling() {
        val lines = codeOf("Media3PlayerEngine.kt")
        val body = blockBody(lines, "private fun updatePlaybackProgress(")
        assertTrue(
            "稳定期判定（isRecoverySettled）必须落在进度轮询里 —— 该轮询只在" +
                "`isPlaying && STATE_READY` 时运行，所以「能走到这里」本身就是「确实在播」的证据。",
            body.any { it.contains("isRecoverySettled(") }
        )
        assertTrue("判定通过后才能清零重建防抖窗口", body.any { it.contains("rebuildThrottle.reset()") })
        assertTrue("判定通过后才能清零网络重试计数", body.any { it.contains("networkRetryCount = 0") })
    }

    @Test
    fun errorInvalidatesTheSettleWindow() {
        val body = blockBody(codeOf("Media3PlayerEngine.kt"), "override fun onPlayerError(error: PlaybackException)")
        assertTrue(
            "错误一到就要作废稳定期（否则上一段的出帧时刻会被算成本次恢复成功）",
            body.any { it.contains("recoveryFirstFrameAtMs = -1L") }
        )
    }

    @Test
    fun offlineNetworkErrorsAreNotRebuiltAtAll() {
        val body = blockBody(codeOf("Media3PlayerEngine.kt"), "override fun onPlayerError(error: PlaybackException)")
        assertTrue(
            "网络类错误必须先判「当前有没有网」：断网时重建 ExoPlayer 没有任何意义",
            body.any { it.contains("isNetworkPlaybackError(error.errorCode)") } &&
                body.any { it.contains("networkTypeProvider.isNetworkAvailable()") }
        )
    }

    @Test
    fun newSessionAlsoResetsTheSettleMarker() {
        // ⚠️ 锚点随 2026-10-17 夜的结构性改动更新：`resetRuntimeState` 新增了
        // `keepStallLadder` 参数（卡顿阶梯的 REBUILD 级重建要保留档位，见
        // `StallRecoveryPolicy.onSessionRebuilt`）。**断言语义一字未改** —— 仍然是
        // "换源/重建后必须作废稳定期计时"。锚点故意只匹配到左括号：参数增删不再让守卫失效。
        val body = blockBody(codeOf("Media3PlayerEngine.kt"), "private fun resetRuntimeState(")
        assertTrue(
            "换源/重建后要作废稳定期计时，否则会把上一个会话的出帧时刻算进来",
            body.any { it.contains("recoveryFirstFrameAtMs = -1L") }
        )
    }

    /** 稳定期常量只能有一个来源（策略文件），引擎不许写死毫秒。 */
    @Test
    fun settleDurationHasASingleSource() {
        val engine = codeOf("Media3PlayerEngine.kt")
        assertFalse(
            "引擎里不许写死稳定期毫秒数（单一来源是 PlayerErrorRecoveryPolicy.RECOVERY_SETTLE_MS）",
            engine.any { it.contains("10_000L") }
        )
        assertTrue(
            "常量必须定义在 PlayerErrorRecoveryPolicy.kt 里",
            codeOf("PlayerErrorRecoveryPolicy.kt").any { it.contains("const val RECOVERY_SETTLE_MS") }
        )
    }

    /**
     * 重建防抖额度**只有一个记账点**（`recoverSession`）。
     *
     * ## 缺陷形态（真机反馈「播一段时间会卡住、再也起不来」）
     *
     * `RebuildThrottle(3, 60s)` 的 `tryAcquire` 每次入队一个时间戳。修复前
     * `onPlayerError` 的 else 分支自己先领一次、随后调用的 `recoverSession` 再领一次
     * ⇒ **一次重建吃掉两个额度** ⇒ 60 秒窗口里实际只能恢复 **1** 次，第二次就
     * `recover abandoned: rebuild limit hit` 落在错误态。而设计意图（`reset()` 的
     * 注释）是"稳定播放满 RECOVERY_SETTLE_MS 后重新给满 3 次"。
     *
     * 与之对照：`isRecoverable` 分支**直接调** `recoverSession`、不额外领 ⇒ 那条路只吃
     * 一个额度 —— 同一份额度在两条路上口径不同，根因就在这里。
     */
    @Test
    fun rebuildBudgetIsAcquiredExactlyOncePerRebuild() {
        val pane = codeOf("Media3PlayerEngine.kt")
        // 错误回调里不许再自己领额度：`when` 的每个分支都必须只经 recoverSession 记账。
        val onError = blockBody(pane, "override fun onPlayerError(error: PlaybackException)")
        assertEquals(
            "onPlayerError 里不许出现额度领取 —— 记账点只能有一个（recoverSession 内部），" +
                "否则一次重建吃两个额度，60 秒窗口实际只能恢复 1 次。",
            0,
            onError.count { it.contains("tryAcquire(") }
        )
        // 唯一记账点必须**恰好一次**：两次 = 双取缺陷复发。
        val recover = blockBody(pane, "private fun recoverSession(")
        assertEquals(
            "recoverSession 必须恰好领一次额度（它是四条恢复路径的唯一出口）：" +
                "多一次就是双取、少一次就是无限重建。",
            1,
            recover.count { it.contains("tryAcquire(") }
        )
    }

    /** 被防抖拒绝时不许白加网络重试数（否则 `retryCount < maxRetries` 会提前判 GIVE_UP）。 */
    @Test
    fun networkRetryCountOnlyGrowsWhenTheRebuildActuallyHappened() {
        val onError = blockBody(codeOf("Media3PlayerEngine.kt"), "override fun onPlayerError(error: PlaybackException)")
        val increments = onError.indexOfFirst { it.contains("networkRetryCount++") }
        assertTrue("错误回调里必须计网络重试数（有预算的重试路径）", increments >= 0)
        // 计数必须**紧跟在**一个「recoverSession 返回 true」的判定之后，而不是随便某个 if 之后：
        // 逐行向前找最近的那次 recoverSession 调用，要求它就在本次计数之上、且判定形式是
        // `if (recoverSession(`（true 分支）——写成 `if (!recoverSession(` 就是把逻辑搞反了。
        val nearestCall = (increments - 1 downTo 0).firstOrNull { onError[it].contains("recoverSession(") }
        assertTrue(
            "`networkRetryCount++` 必须位于某个 recoverSession 调用之下（同一分支内）",
            nearestCall != null
        )
        val guardLine = onError[nearestCall!!]
        assertTrue(
            "计数的唯一入口必须是「recoverSession 返回 true（真的重建了）」那个 true 分支：" +
                "被拒绝时白加计数会让重试预算提前耗尽、提前 GIVE_UP。",
            guardLine.contains("if (recoverSession(")
        )
        assertTrue(
            "计数与它的守卫之间不许插入另一次 recoverSession 调用（否则说明写错了分支）",
            onError.subList(nearestCall + 1, increments).none { it.contains("recoverSession(") }
        )
    }

    /**
     * 行为断言：把"一次重建 = 一次领取"这个约定钉在 [RebuildThrottle] 上，
     * 并**反向演示**双取形态为什么会退化成"只恢复一次"。
     */
    @Test
    fun oneAcquirePerRebuildYieldsThreeRebuildsWhileDoubleAcquireYieldsOne() {
        val correct = RebuildThrottle(maxRebuilds = 3, windowMs = 60_000)
        assertTrue("第 1 次重建", correct.tryAcquire(1_000))
        assertTrue("第 2 次重建", correct.tryAcquire(1_100))
        assertTrue("第 3 次重建", correct.tryAcquire(1_200))
        assertFalse("第 4 次应被拒（窗口内最多 3 次）", correct.tryAcquire(1_300))

        // 反向演示：同一次重建取两次额度 ⇒ 60 秒窗口（上限 3）实际只够**一次**重建。
        //
        // 额度账（逐次列出，避免再算错）：
        //   #1 第 1 次重建的第 1 次领取 → 占 1，剩 2，true
        //   #2 第 1 次重建的第 2 次领取 → 占 2，剩 1，true
        //   #3 第 2 次重建的第 1 次领取 → 占 3，剩 0，**true**（恰好到上限）
        //   #4 第 2 次重建的第 2 次领取 → 超限，**false** ← recoverSession 在这里被拒
        // ⇒ 双取形态下第 2 次重建**走不到重建**（第二次领取即被拒）。
        val buggy = RebuildThrottle(maxRebuilds = 3, windowMs = 60_000)
        assertTrue("双取形态第 1 次重建的第 1 次领取", buggy.tryAcquire(1_000))
        assertTrue("双取形态第 1 次重建的第 2 次领取", buggy.tryAcquire(1_000))
        assertTrue("第 2 次重建的第 1 次领取（恰好占满 3 个额度）", buggy.tryAcquire(1_100))
        assertFalse(
            "双取形态下第 2 次重建的第 2 次领取必被拒 —— 60 秒窗口实际只剩 1 次机会，" +
                "这正是「播一段时间后卡住、再也起不来」的成因。",
            buggy.tryAcquire(1_100)
        )
    }

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private fun codeOf(fileName: String): List<String> {
        val matches = repoRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .filter { it.name == fileName }
            .toList()
        assertEquals(
            "`$fileName` 命中 ${matches.size} 个文件（要求恰好 1）：" + matches.joinToString { it.path },
            1,
            matches.size
        )
        return matches.single().readLines().map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                trimmed.startsWith("/*") || trimmed.startsWith("import ")
            ) {
                ""
            } else {
                line
            }
        }
    }

    private fun blockBody(lines: List<String>, signaturePrefix: String): List<String> {
        val start = lines.indexOfFirst { it.trimStart().startsWith(signaturePrefix) }
        assertTrue("找不到 `$signaturePrefix`，无法验证其内部结构。", start >= 0)
        var depth = 0
        var opened = false
        val body = mutableListOf<String>()
        for (i in start until lines.size) {
            body += lines[i]
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> {
                        depth++
                        opened = true
                    }

                    '}' -> depth--
                }
            }
            if (opened && depth <= 0) break
        }
        return body
    }
}
