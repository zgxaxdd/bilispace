package com.naaammme.bbspace.feature.video.player

/**
 * 播放页玻璃背板的**重录频率**判据（R2，2026-10-08《视频播放链路性能分析·刷视频》§2.1-R2）。
 *
 * ## 要解决的问题
 *
 * 控制条可见且正在播放时，视频节点会被逐帧重录进 backdrop（`layerBackdrop` + 一个读 tick 的
 * `drawWithContent`），供控制条做"真模糊"采样。门控已经收得很紧（控制条隐藏即停、
 * 暂停/缓冲即停、材质 OFF 即停、守卫降档即停、省电模式整条摘掉），但**开控制条那一段时间
 * 是满帧率的 GPU 重录** —— 60Hz 面板上就是每秒 60 次把整个视频层录进纹理。
 *
 * 而模糊本身是**低频信号**：30Hz 与 60Hz 的磨砂背景在肉眼上分不出来（观感确认待真机走查）。
 * 所以这里把"每帧都录"改成"每个最小间隔录一次"，帧时钟照旧逐帧回调，但只有跨过间隔才
 * 递增 tick（即只有那时才失效重绘）。
 *
 * ## 为什么按**绝对时间**而不是"每 N 帧"
 *
 * 面板刷新率不固定（60/90/120Hz，且高刷机型会动态切换）。按帧数节流会得到"在这台机器 30Hz、
 * 在那台 60Hz"的不一致成本；按时间节流则**与刷新率无关**，永远是"每秒最多约 30 次重录"。
 *
 * ## 为什么这是纯函数
 *
 * 节流只有一个会写错的地方：**首帧没有基线**（`lastTickNanos < 0`）时必须录，
 * 否则第一次出现控制条会等一个间隔才糊上。这条与"帧时间戳倒退"一起放进纯函数里测。
 *
 * 想再降一档（15Hz）只改 [TARGET_INTERVAL_NS] 一个常量即可，消费端与门控都不用动。
 */
internal object VideoGlassTickPolicy {

    /** 两次重录之间的最小间隔：30Hz（= 1/30 秒）。 */
    const val TARGET_INTERVAL_NS = 33_333_333L

    /**
     * 这一帧要不要重录背板。
     *
     * @param frameTimeNanos `withFrameNanos` 给的帧时间戳（单调纳秒）。
     * @param lastTickNanos 上次重录的帧时间戳；负值表示还没录过。
     */
    fun shouldRecord(frameTimeNanos: Long, lastTickNanos: Long): Boolean {
        if (lastTickNanos < 0L) return true
        // 时间戳倒退（单调时钟理论上不会，但帧时钟的基准可能在某些 OEM 上重置）
        // 一律当成"新的一帧"照录 —— 宁可多录一次，也不要卡在"永远不满足间隔"上把控制条糊成静态底。
        if (frameTimeNanos <= lastTickNanos) return true
        return frameTimeNanos - lastTickNanos >= TARGET_INTERVAL_NS
    }
}
