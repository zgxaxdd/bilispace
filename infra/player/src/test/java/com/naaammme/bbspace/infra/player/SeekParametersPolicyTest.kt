package com.naaammme.bbspace.infra.player

import androidx.media3.exoplayer.SeekParameters
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * PB-02：seek 参数策略的两档取值。
 *
 * 断言用 `assertSame` 而不是 `assertEquals`：`SeekParameters` 没有重写 equals，
 * 语义就是"必须就是那一个常量实例"。
 */
class SeekParametersPolicyTest {

    @Test
    fun userSeekSnapsToKeyframes() {
        assertSame(
            "用户主动 seek 必须用 CLOSEST_SYNC（吸附关键帧 = 更快落点，这是本项唯一的收益来源）",
            SeekParameters.CLOSEST_SYNC,
            SeekParametersPolicy.forUserSeek()
        )
    }

    @Test
    fun preciseSeekUsesDefault() {
        assertSame(
            "精确 seek 必须是 DEFAULT（续播位置会被写回历史、跳段边界不能被吸附）",
            SeekParameters.DEFAULT,
            SeekParametersPolicy.forPreciseSeek()
        )
    }

    /**
     * 两档必须**真的不同** —— 防的是"有人把某一档复制粘贴成另一档"，
     * 那时上游/本仓的语义差异会静默消失（用户拖拽变精确 / 跳段变吸附）。
     */
    @Test
    fun theTwoIntentsAreDifferent() {
        assertNotEquals(
            "两档取值不允许相同（否则分档形同虚设）",
            SeekParametersPolicy.forPreciseSeek(),
            SeekParametersPolicy.forUserSeek()
        )
    }
}
