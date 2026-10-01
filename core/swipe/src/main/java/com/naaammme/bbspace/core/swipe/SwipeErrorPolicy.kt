package com.naaammme.bbspace.core.swipe

/**
 * 刷视频失败态的**分类与重试策略**（任务书 SW-13）。纯函数 + 调用方给钟，可 JVM 单测。
 *
 * 为什么需要分类：失败文案与"该不该自动重试"完全取决于类型——
 * - 网络不可用：自动重试没意义（等到网络回来才有用），文案要说清"检查网络"；
 * - 取流失败（gRPC/HTTP 非 2xx、限流）：值得自动退避重试；
 * - 解码失败：重试同一地址大概率还失败（引擎已有自己的会话重建闸门），文案给"重试"但**不自动**。
 *
 * 重试去抖：手动重试有 [MANUAL_RETRY_DEBOUNCE_MS] 间隔（防连点把引擎打爆）；
 * 自动重试上限 [MAX_AUTO_RETRIES] 次、退避 [AUTO_RETRY_BACKOFF_MS]。
 */
object SwipeErrorPolicy {

    enum class Kind {
        /** 无网络/网络不可达。 */
        Network,

        /** 取流失败（接口错误、限流、超时）。 */
        Stream,

        /** 解码/渲染失败。 */
        Decode,

        /** 其他/未知。 */
        Unknown,
    }

    /** 自动重试上限（超过就不再自动，只留手动入口）。 */
    const val MAX_AUTO_RETRIES = 2

    /** 自动重试退避（第 n 次用 backoff[n]，越界取最后一个）。 */
    val AUTO_RETRY_BACKOFF_MS = longArrayOf(1_000L, 3_000L)

    /** 手动重试去抖：两次点击之间的最小间隔。 */
    const val MANUAL_RETRY_DEBOUNCE_MS = 800L

    /**
     * 错误分类。判据只看**稳定的关键词**（引擎/网络层的错误串由各自模块给出，
     * 这里不引入它们的依赖）：命中不了就给 [Kind.Unknown]（宁可文案泛化，不误判）。
     */
    fun classify(errorMessage: String?, networkAvailable: Boolean = true): Kind {
        val message = errorMessage?.lowercase().orEmpty()
        if (!networkAvailable || containsAny(message, NETWORK_MARKERS)) return Kind.Network
        if (containsAny(message, DECODE_MARKERS)) return Kind.Decode
        if (containsAny(message, STREAM_MARKERS)) return Kind.Stream
        return Kind.Unknown
    }

    /** 失败态文案（veil 里那一行）：按类型给"人话"，不给错误码。 */
    fun messageOf(kind: Kind): String = when (kind) {
        Kind.Network -> "网络好像断了，检查后重试"
        Kind.Stream -> "这一支加载失败了"
        Kind.Decode -> "这一支播放不了，换一支试试"
        Kind.Unknown -> "这一支加载失败了"
    }

    /**
     * 要不要**自动**重试（手动入口永远可用）。
     * @param attempts 已经自动重试过的次数（0 = 首次失败）
     * @param kind 失败类型
     * @return 需要等待的毫秒数；null = 不自动重试
     */
    fun autoRetryDelayMs(attempts: Int, kind: Kind): Long? {
        if (attempts < 0) return null
        if (attempts >= MAX_AUTO_RETRIES) return null
        // 网络类不自动（等网络回来是用户的动作，自动重试只是空转）
        if (kind == Kind.Network) return null
        // 解码类不自动（同一地址大概率仍失败；引擎自身的会话重建闸门已覆盖）
        if (kind == Kind.Decode) return null
        return AUTO_RETRY_BACKOFF_MS[attempts.coerceAtMost(AUTO_RETRY_BACKOFF_MS.lastIndex)]
    }

    /** 手动重试是否被去抖拦下（连点保护）。 */
    fun isManualRetryDebounced(
        nowMs: Long,
        lastRetryMs: Long?,
        debounceMs: Long = MANUAL_RETRY_DEBOUNCE_MS,
    ): Boolean {
        val last = lastRetryMs ?: return false
        val delta = nowMs - last
        // 时钟回拨按"刚点过"处理（宁可多挡一次，别把引擎打爆）
        return delta < debounceMs
    }

    private fun containsAny(message: String, markers: List<String>): Boolean =
        markers.any { message.contains(it) }

    private val NETWORK_MARKERS = listOf(
        "unknownhost", "unable to resolve host", "no address associated",
        "connectexception", "connection refused", "failed to connect",
        "network is unreachable", "no route to host", "timeout", "timed out",
        "socket", "ssl", "offline", "network"
    )

    private val DECODE_MARKERS = listOf(
        "decoder", "mediacodec", "decode", "codec init", "surface"
    )

    private val STREAM_MARKERS = listOf(
        "403", "404", "429", "500", "502", "503", "504",
        "http", "grpc", "playurl", "stream"
    )
}
