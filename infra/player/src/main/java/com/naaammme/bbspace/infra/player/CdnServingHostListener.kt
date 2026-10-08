package com.naaammme.bbspace.infra.player

/**
 * 「这条字节**实际**是哪条线路给的」回传口（2026-10-08 记账口径修正）。
 *
 * ## 为什么需要它
 *
 * 会话层记账（`ready` / `buffering`）原来只知道"**开播时选中的**那条 host"，
 * 换线之后就把好线路的功劳与坏线路的过错都记到原来那条头上（代码注释里如实登记过这个限制）。
 * 传输层其实**知道**真实用的哪条 —— `CdnFailoverDataSource` 里 `delegate.open(spec)` 成功的
 * 那个 `url`、`CdnPlaybackDataSource` 里的 `delegateUri` 就是它。
 *
 * 与 [CdnLineOutcomeListener] 的分工：
 * - [CdnLineOutcomeListener]：这次**通没通**（成败 → 加减分），已有；
 * - 本接口：这次是**哪条**在供数（给会话层记账用目标 host），新增。
 *
 * 注意：**预取链不传本监听器**（默认 [NoOp]）—— 后台预取的线路不该影响"当前播放在用哪条"的判断。
 */
fun interface CdnServingHostListener {

    /** [host] 已经是 host（不含 scheme/path/query），取不到时调用方不该回调。 */
    fun onServingHost(host: String)

    companion object {
        val NoOp: CdnServingHostListener = CdnServingHostListener { }
    }
}
