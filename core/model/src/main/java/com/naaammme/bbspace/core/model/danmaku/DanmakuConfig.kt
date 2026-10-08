package com.naaammme.bbspace.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class DanmakuConfig(
    /**
     * 弹幕总开关。**默认开**（2026-10-08 产品决策 A1）。
     *
     * 为什么改：从 BiliPai 移植的整套弹幕链路（分段窗口 ±1、邻居预取、覆盖层渲染、设置项）
     * 此前默认关着 —— 移植件在真机上"能跑但用户看不到"。B 站客户端同样默认弹幕开，
     * 播放器里有开关可随时关（`danmaku_enabled`）。
     */
    val enabled: Boolean = true,
    val areaPercent: Int = 100,
    val opacity: Float = 1f,
    val textScale: Float = 1f,
    val speed: Float = 1f,
    val densityLevel: Int = 1,
    val weightFilterLevel: Int = 2,
    val mergeDuplicates: Boolean = true,
    val showTop: Boolean = true,
    val showBottom: Boolean = true,
    val showScrollRl: Boolean = true
)
