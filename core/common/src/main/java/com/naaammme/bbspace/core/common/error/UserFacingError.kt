package com.naaammme.bbspace.core.common.error

import com.naaammme.bbspace.core.common.log.Logger
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把异常转成**可以给用户看**的文案。
 *
 * 为什么需要：此前多处 ViewModel 直接把 `e.message` 塞进 uiState，再由界面原样渲染
 * （例如 `AdaptiveMediaGrid` 的错误卡）。于是用户会看到
 * `Failed to connect to /...:443`、`timeout`、`[0] Empty response` 这类信息——
 * 既看不懂，也暴露内部实现。
 *
 * 边界（重要）：本文件位于 `core:common`，该模块**不依赖任何 infra/core 模块**，
 * 因此这里只处理 JDK 自带的网络/IO 异常。项目自定义异常（如 infra 层的
 * `BiliApiException`）需要带类型信息判断，由**各自模块**处理，不要为了统一而让
 * core:common 反向依赖 infra —— 那会破坏 `infra → core → feature` 的依赖方向。
 */
object UserFacingError {

    /** 网络不可达 / IO 失败 / 其他未知异常的兜底文案。 */
    const val NETWORK = "网络不太顺畅，请稍后重试"

    /** 服务端返回了内容但无法解析。 */
    const val PARSE = "数据解析失败，请稍后重试"

    /**
     * 将 [throwable] 映射为用户文案。
     *
     * 已知的网络/IO 异常给固定文案；其余异常**不直接透传 message**，而是回落到
     * 通用文案——原始 message 只应进日志（调用方已有 `Logger.e`）。
     */
    fun messageOf(throwable: Throwable?): String = when (throwable) {
        null -> NETWORK
        is UnknownHostException -> "连不上服务器，请检查网络"
        is SocketTimeoutException -> "请求超时，请稍后重试"
        is IOException -> NETWORK
        else -> NETWORK
    }

    /**
     * 记录原始异常（进日志）并返回可展示文案 —— 界面用的入口。
     *
     * 把「打日志」和「转文案」绑在一起，是为了避免调用方只换文案而漏掉日志：
     * 原始 message 不再上屏之后，日志是它唯一的去处。
     *
     * @param what 出错的业务动作（如「加载首页失败」），用于日志定位。
     */
    fun logAndMessage(tag: String, throwable: Throwable, what: String): String {
        Logger.e(tag, throwable) { what }
        return messageOf(throwable)
    }
}
