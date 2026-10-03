package com.naaammme.bbspace.infra.player

import androidx.media3.exoplayer.SeekParameters

/**
 * seek 参数策略（PB-02，语义移植自上游 BiliPai `VideoPlayerState.kt:1026,1072-1075`）。
 *
 * ## 上游与本仓的差别（**这是本项最重要的一处"不照搬"**）
 * 上游按**设置项**决定全局 seek 行为：`seekFastEnabled` 默认 true →
 * `if (seekFastEnabled) SeekParameters.CLOSEST_SYNC else SeekParameters.DEFAULT`，
 * 即"开着就**所有** seek 都走关键帧"。
 *
 * 本仓**不引入设置项**，而是按**seek 的意图**分档 —— 因为 bbspace 里"seek"至少有四类来源，
 * 混在一起会有实际副作用：
 *
 * | 来源 | 该走哪档 | 为什么 |
 * |---|---|---|
 * | 进度条拖拽松手（用户主动） | [forUserSeek] | 用户要的是"快点到那儿"，吸附到关键帧更快落点 |
 * | SponsorBlock 跳恰饭/片头片尾 | [forPreciseSeek] | **跳段必须精确落到片段边界**；向后吸附会重新落进片段内 → 触发一次重入（靠 `skippedUuids` 才没死循环） |
 * | 自动片尾跳转 | [forPreciseSeek] | 目标是"跳到片尾"，吸附会偏 |
 * | 续播 / 恢复（`setSource(positionMs)`） | [forPreciseSeek] | 续播位置会被**写回历史**（`LocalPlaybackHistoryRecorder`），吸附后的粗值原地污染观看记录 |
 *
 * 上游那套"全局开"之所以可行，是因为它的 seek 来源里没有 bbspace 这种
 * "同一个人口既要精确边界又要快速落点"的混用；本仓按意图分档后才能既拿到关键帧的收益、
 * 不污染续播与跳段语义。
 */
object SeekParametersPolicy {

    /** 用户主动 seek（拖进度条、跳段手势）：允许吸附到关键帧，换取更快的落点。 */
    fun forUserSeek(): SeekParameters = SeekParameters.CLOSEST_SYNC

    /**
     * 需要**精确位置**的 seek：续播 / 恢复 / SponsorBlock 跳段 / 自动片尾。
     *
     * 显式返回 [SeekParameters.DEFAULT]（而不是"什么都不设"）：这样每次 seek 之前
     * 都把参数**重新断言**一遍，避免"上一次用户 seek 设的 CLOSEST_SYNC 残留到下一次
     * 续播"——媒体3 的 `setSeekParameters` 是会保持的状态，不是每次 seek 的临时参数。
     */
    fun forPreciseSeek(): SeekParameters = SeekParameters.DEFAULT
}
