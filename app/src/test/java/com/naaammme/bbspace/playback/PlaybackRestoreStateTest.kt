package com.naaammme.bbspace.playback

import com.naaammme.bbspace.core.model.StreamPlaybackTarget
import com.naaammme.bbspace.core.model.VideoTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「视频页恢复判定」的纯 JVM 状态测（AP-03 降级路线 A 的验收）。
 *
 * ## 背景与结论（设计约束，不是待修缺陷）
 *
 * `PlaybackHostState.shouldRestoreVideoPage()` 读的 `currentTarget` 转发自
 * [com.naaammme.bbspace.core.playback.StreamPlaybackSession] —— @Singleton、
 * 进程内单例、**无任何持久化**。进程被系统杀死后重建的新进程上它必为 null，
 * 因此「进程重建后自动恢复视频页」在当前数据模型下**任何设备都验不过**
 * （原 L4-30 挂账正是死于此，已改写为设计约束）。
 *
 * 本测试把这一约束钉成机器可判定的事实：
 * 1. fresh session（currentTarget == null，即新进程的必然形态）→ **恒 false**；
 * 2. 进程内的四个阻塞条件各自真实生效（判定不是"永远 false"的死守卫）；
 * 3. 进程内恢复路径（video target + 详情页已死 + Hidden + 非用户关闭）仍然可用。
 *
 * 若未来走 AP-03 路线 B（持久化 target id + 播放位置），此测试是回归底线：
 * 改造后的判定在 null 场景仍不得崩溃，只能回 false。
 */
class PlaybackRestoreStateTest {

    private val videoTarget = StreamPlaybackTarget.Video(
        target = VideoTarget.Ugc(aid = 1L, cid = 2L)
    )

    /** AP-03 核心断言：新进程（currentTarget == null）上恢复判定恒为 false。 */
    @Test
    fun freshProcessWithNullTargetNeverRestores() {
        assertFalse(
            "currentTarget == null（进程重建后的必然形态）时 shouldRestoreVideoPage 必须 false。" +
                "若为 true，说明判定不再依赖进程内单例 —— 请确认持久化路线（AP-03 B）已按契约落地。",
            PlaybackHostState.shouldRestoreVideoPage(
                videoDetailAlive = false,
                currentTarget = null,
                hostMode = PlaybackHostMode.Hidden,
                isUserClosedVideo = false
            )
        )
    }

    /** 非视频目标（覆盖层音频/直播等）不触发视频页恢复。 */
    @Test
    fun nonVideoTargetDoesNotRestore() {
        assertFalse(
            PlaybackHostState.shouldRestoreVideoPage(
                videoDetailAlive = false,
                currentTarget = null,
                hostMode = PlaybackHostMode.Hidden,
                isUserClosedVideo = false
            )
        )
    }

    /** 详情页实例仍存活（onCreate→onDestroy 之间）时不恢复 —— 否则双实例。 */
    @Test
    fun aliveDetailPageBlocksRestore() {
        assertFalse(
            PlaybackHostState.shouldRestoreVideoPage(
                videoDetailAlive = true,
                currentTarget = videoTarget,
                hostMode = PlaybackHostMode.Hidden,
                isUserClosedVideo = false
            )
        )
    }

    /** 宿主非 Hidden（覆盖层/迷你路径自管组合）时不走 Activity 恢复。 */
    @Test
    fun nonHiddenHostModeBlocksRestore() {
        for (mode in PlaybackHostMode.entries - PlaybackHostMode.Hidden) {
            assertFalse(
                "hostMode=$mode 时不得走 Activity 恢复路径（覆盖层/迷你路径由自身组合恢复）。",
                PlaybackHostState.shouldRestoreVideoPage(
                    videoDetailAlive = false,
                    currentTarget = videoTarget,
                    hostMode = mode,
                    isUserClosedVideo = false
                )
            )
        }
    }

    /** 用户主动收起后不自愈拉起（否则「返回 → 自愈 → 再拉起」死循环）。 */
    @Test
    fun userClosedBlocksRestore() {
        assertFalse(
            PlaybackHostState.shouldRestoreVideoPage(
                videoDetailAlive = false,
                currentTarget = videoTarget,
                hostMode = PlaybackHostMode.Hidden,
                isUserClosedVideo = true
            )
        )
    }

    /** 进程内恢复路径：四个条件同时满足才 expand（自愈仍对"后台回收详情页"生效）。 */
    @Test
    fun inProcessRestorePathStillWorks() {
        assertTrue(
            PlaybackHostState.shouldRestoreVideoPage(
                videoDetailAlive = false,
                currentTarget = videoTarget,
                hostMode = PlaybackHostMode.Hidden,
                isUserClosedVideo = false
            )
        )
    }
}
