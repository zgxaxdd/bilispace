package com.naaammme.bbspace.infra.player

/**
 * 换线与索引读取的**真实结果**回传口（2026-10-06 闭环修复）。
 *
 * ## 为什么需要它
 *
 * 换线发生在 [CdnFailoverDataSource] 内部（每个加载通道独享实例），
 * 索引读取发生在 [SingleFileDashMediaSourceFactory] 内部 —— 这两处**都拿不到**
 * `CdnHealthStore`（依赖方向相反：`core:playback` → `infra:player`）。
 *
 * 结果是"智能选线"的反馈回路**在数据源层断开了**：
 * - 会话层只知道自己**选中**了哪条线，不知道**实际服务**的是哪条（换线后可能已不是它）；
 * - 换线成功/失败、索引读取失败、403/412/429 —— **都不回写健康度**。
 *   于是 `CdnHealthPolicy` 里 `FirstFrameTimeout` / `PlayerError` 这两个惩罚项
 *   **在真实世界里永远不被触发**（只有单元测试触发过）⇒ 坏线路永远不被降权
 *   ⇒ 选线策略"有排序能力、没有输入"，长期等于按服务端原序取首条。
 *
 * 这个接口把真实结果**倒着传出去**：`infra:player` 只负责"报告事实"，
 * 记账由 `core:playback` 的实现完成（`CdnHealthLineOutcomeListener`），
 * 依赖方向不倒置。
 *
 * ## 约定
 *
 * - 必须**无副作用、不抛异常、不阻塞**（在播放线程上被调用）；
 * - 失败上报要**带上真实失败的线路**（不是"选中"的那条）；
 * - 同一次播放里一条线可能被反复上报，实现侧自行去重/折算。
 */
fun interface CdnLineOutcomeListener {

    /**
     * @param url 真正被尝试的线路 URL（换线后是**换到的那条**）。
     * @param succeeded 该线路这次是否真的拿到了数据/索引。
     */
    fun onOutcome(url: String, succeeded: Boolean)

    companion object {
        /** 默认实现：不记账（无 DI 场景与单元测试用）。 */
        val NoOp: CdnLineOutcomeListener = CdnLineOutcomeListener { _, _ -> }
    }
}
