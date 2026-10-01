package com.naaammme.bbspace.core.swipe

/**
 * 刷视频「封面 → 画面」交接的**纯时序策略**（可 JVM 单测）。
 *
 * ## 为什么需要它（真机读感问题，不是数字问题）
 * 等首帧加载层原先把**封面 + 暗遮罩 + 粒子**当作一层、共用一个 alpha 一起淡出。
 * 于是淡到一半时用户看到的是「半张暗遮罩压在半条画面上」—— 比交接两端都暗，
 * 读作"翻页闪一下黑"。暗遮罩在"还在等"的时候是必要的（保证白粒子在浅色封面上读得清），
 * 但它**不该跟着内容一起淡出**。
 *
 * 上游 BiliPai 的做法是把两者错开：内容层保持不透明、非内容的暗层先退，
 * 并在画面上来后再停留一拍才撤（`feature/video/ui/section/VideoPlayerSectionPolicy.kt:42-49`：
 * 220ms 揭示 + 96ms hold + 48ms 落定缓冲）。这里搬的是**同一条时序形状**，
 * 时长不抄常数而是从 [com.naaammme.bbspace.core.designsystem.theme.BiliAnimations]
 * 的档位推导 —— 用户的"动效速度"设置于是仍然作用在这段过渡上。
 *
 * ⚠️ 刻意**不**照抄上游的另一条：它当前页一律不铺封面（`PortraitPagerSwitchPolicy.kt:80-89`，
 * 黑底硬切）。bbspace 的封面铺底是真机反馈后定稿的，比它好。
 */
object SwipeHandoffTiming {

    /**
     * 落定起始缩放：封面在淡出的同时从 1.02 收到 1.0。
     *
     * 2% 以内不会被读作"变焦"，但足以让落定有"坐实"的方向感（上游同款量级：
     * `VideoPlayerSectionPolicy.kt:44-46`）。
     */
    const val SETTLE_SCALE = 1.02f

    /**
     * 一次交接的三个时长（毫秒）与落定缩放。
     *
     * @property chromeFadeMs 暗遮罩 + 粒子（非内容层）的退场时长；
     * @property coverHoldMs 遮罩退完后，封面继续**不透明**停留的时长；
     * @property coverFadeMs 封面与画面交叉淡化的时长；
     * @property settleScale 封面开始淡化时的起始缩放（1f = 不做缩放）。
     */
    data class Phases(
        val chromeFadeMs: Int,
        val coverHoldMs: Int,
        val coverFadeMs: Int,
        val settleScale: Float,
    ) {
        /** 整段交接的墙钟时长（用于断言"别把交接做成长动画"）。 */
        val totalMs: Int get() = chromeFadeMs + coverHoldMs + coverFadeMs
    }

    /**
     * @param shortMs / mediumMs 取自动效令牌（`LocalAnimations.short` / `.medium`），
     *   调用方负责传入当前值，本函数不读 CompositionLocal（这样它才可 JVM 单测）。
     * @param reduceMotion 无障碍降级（动效速度=关）：时长全部退化成 1ms、
     *   不做落定缩放，只保留"遮罩先退、封面后退"这个**顺序**（顺序本身不是动画）。
     */
    fun resolve(shortMs: Int, mediumMs: Int, reduceMotion: Boolean): Phases {
        val short = shortMs.coerceAtLeast(0)
        val medium = mediumMs.coerceAtLeast(0)
        return if (reduceMotion) {
            Phases(chromeFadeMs = 1, coverHoldMs = 0, coverFadeMs = 1, settleScale = 1f)
        } else {
            Phases(
                chromeFadeMs = short,
                // 半档停留：够让首帧画面稳住，又不至于读成"停在封面上了"。
                coverHoldMs = short / 2,
                // 交叉淡化比遮罩退场慢一档：凹陷只会出现在"暗层和内容同时淡"的写法里。
                coverFadeMs = medium,
                settleScale = SETTLE_SCALE,
            )
        }
    }
}
