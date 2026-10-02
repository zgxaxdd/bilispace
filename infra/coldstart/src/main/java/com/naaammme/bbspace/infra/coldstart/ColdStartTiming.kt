package com.naaammme.bbspace.infra.coldstart

/**
 * 冷启动链路的**阶段耗时口径**（BACKLOG PF-03）。
 *
 * ## 为什么需要
 * 冷启动这条链（建请求 → gRPC → 解析 → 写区域缓存）原先只在出错时打一行自由文本，
 * 成功时什么都不说。于是"冷启动慢在哪一段"只能靠猜：本机测到的 ~170ms 是个总数，
 * 里面 DNS、TCP 建连、服务端处理各占多少完全没有数据，任何优化都无法被验证或证伪。
 *
 * ## 设计约束
 * - **一行、key=value、可解析**（见 [parse]）。自由文本没法跨版本比较，
 *   也不会被任何脚本/日志过滤器捞到。
 * - 时钟**注入**：默认用 `System.nanoTime()`（单调，不受墙上时间跳变影响），
 *   测试传一个假序列，于是"耗时算术"本身能被确定性地测，而不是测时序运气。
 * - **失败也要出数据**：异常路径照样渲染已经记下的阶段，
 *   否则最需要看数据的两次启动（超时、解析炸）反而没有日志。
 *
 * 阶段名用 [BUILD] / [GRPC] / [PARSE] / [CACHE] 这几个常量，不许现场拼字符串 ——
 * 名字漂了，跨版本的耗时对比就废了。
 */
class ColdStartTiming(
    private val ticker: () -> Long = { System.nanoTime() },
    private val thresholdWarnMs: Long = WARN_THRESHOLD_MS
) {
    private val start = ticker()
    private var previous = start
    private val phases = LinkedHashMap<String, Long>()

    /** 最近一次 mark 的阶段名；null = 连第一段都没标完。 */
    private var lastMarkedPhase: String? = null

    /** 记一段：从上一次 mark（或构造）到现在的耗时。 */
    fun mark(
        phase: String
    ): ColdStartTiming {
        val now = ticker()
        phases[phase] = ((now - previous) / 1_000_000L).coerceAtLeast(0L)
        previous = now
        lastMarkedPhase = phase
        return this
    }

    /** 末尾一段 + 总计，渲染成一行可解析的日志。 */
    fun render(
        outcome: String,
        lastPhase: String
    ): String {
        mark(lastPhase)
        val totalMs = ((ticker() - start) / 1_000_000L).coerceAtLeast(0L)
        val body = phases.entries.joinToString(" ") { "${it.key}=${it.value}ms" }
        val slow = if (totalMs >= thresholdWarnMs) " slow=1" else ""
        return "$PREFIX outcome=$outcome $body $TOTAL=${totalMs}ms$slow"
    }

    /**
     * AP-09：异常路径渲染 —— **正在飞的那一段**记到它真实的名字下。
     *
     * 原实现把异常路径硬渲染成 `render(outcome, CACHE)`：gRPC 中途抛异常时，整段
     * gRPC 耗时被归进 `cache=` 且行里**没有 `grpc=` 键** —— 最需要看数据的那次启动
     * 字段恰恰是错的（PF-03 的"失败也出数据"做到了，归因没对）。现在按已 mark 的
     * 阶段推断在飞段：null→BUILD、BUILD→GRPC、GRPC→PARSE、其余→CACHE。
     */
    fun renderFailure(outcome: String): String {
        val inFlight = when (lastMarkedPhase) {
            null -> BUILD
            BUILD -> GRPC
            GRPC -> PARSE
            else -> CACHE
        }
        return render(outcome, inFlight)
    }

    companion object {
        /** 日志行的锚点前缀，grep / 脚本按它捞取。 */
        const val PREFIX = "coldstart"

        const val BUILD = "build"
        const val GRPC = "grpc"
        const val PARSE = "parse"
        const val CACHE = "cache"
        const val TOTAL = "total"

        const val OUTCOME_OK = "ok"
        const val OUTCOME_EMPTY = "empty"
        const val OUTCOME_ERROR = "error"
        const val OUTCOME_CANCELLED = "cancelled"

        /** 超过这个总耗时就在行尾标 slow=1，方便直接捞出慢的那几次。 */
        const val WARN_THRESHOLD_MS = 300L

        /**
         * 把 [render] 出来的行读回 `阶段 -> 毫秒`。
         *
         * 这是"可解析"的**定义**：日志写出去之后能被机器还原成数字，
         * 才谈得上跨版本对比。测试用 `render -> parse` 的往返来钉住格式。
         */
        fun parse(
            line: String
        ): Map<String, Long> {
            val out = LinkedHashMap<String, Long>()
            for (token in line.split(' ')) {
                val eq = token.indexOf('=')
                if (eq <= 0) continue
                val value = token.substring(eq + 1).removeSuffix("ms")
                val number = value.toLongOrNull() ?: continue
                out[token.substring(0, eq)] = number
            }
            return out
        }
    }
}
