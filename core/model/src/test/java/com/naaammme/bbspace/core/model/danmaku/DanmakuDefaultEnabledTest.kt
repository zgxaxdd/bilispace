package com.naaammme.bbspace.core.model.danmaku

import com.naaammme.bbspace.core.model.DanmakuConfig
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弹幕**默认开**（2026-10-08 产品决策 A1，R-19）。
 *
 * ## 为什么值得一条测试
 *
 * 从 BiliPai 移植的弹幕链路（分段窗口 ±1、邻居预取、覆盖层渲染、十几项设置）很长一段时间
 * 默认关着 —— 代码全在、真机也验过，但用户**看不到**（移植件"能跑没生效"的典型）。
 * 默认值只有一处（[DanmakuConfig.enabled]），改回 false 就等于把整条链路的可见性又关掉，
 * 所以在这里钉住；关掉弹幕是**用户设置**（`danmaku_enabled`）的事，不是默认值的事。
 */
class DanmakuDefaultEnabledTest {

    @Test
    fun danmakuIsOnByDefault() {
        assertTrue(
            "弹幕默认必须是开（B 站同口径）：默认关 = 移植过来的整套弹幕链路用户看不到",
            DanmakuConfig().enabled
        )
    }
}
