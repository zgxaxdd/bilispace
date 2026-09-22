package com.naaammme.bbspace.core.common.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ErrorLog(
    val time: Long,
    val tag: String,
    val message: String,
    val stackTrace: String?
)

/**
 * 进程内错误环形缓冲（设置页「错误日志」的数据源）。
 *
 * **release 构建下只计数、不保留堆栈**：本对象是进程级单例，若照旧在 release 下
 * 抓全量堆栈并常驻 200 条，会带来三笔稳定开销：
 * 1. `Throwable.stackTraceToString()` —— 遍历栈 + 构建数 KB 字符串，且很多 `Logger.e`
 *    就在主线程上调用（网络失败路径），属主线程分配抖动；
 * 2. 每次记录都在锁内做一次 `buffer.toList()`（O(200) 分配）并推入 StateFlow；
 * 3. 200 条堆栈字符串常驻到进程结束（数百 KB ~ 1MB+）。
 * 而 release 用户根本看不到这个列表（唯一消费者是设置页的 `ErrorLogViewModel`）。
 *
 * 因此把「是否抓栈」交给 [Logger] 按构建类型决定：debug 全量保留（排查用），
 * release 只留最近若干条纯文本、不抓栈。
 */
object ErrorCollector {
    private const val MAX = 200

    private val buffer = ArrayDeque<ErrorLog>(MAX)
    private val _flow = MutableStateFlow<List<ErrorLog>>(emptyList())
    val flow: StateFlow<List<ErrorLog>> = _flow.asStateFlow()

    /**
     * @param captureStack 是否抓取堆栈字符串。release 下传 false：省掉 `stackTraceToString()`
     *   与随之而来的大字符串常驻。debug 下传 true。
     */
    fun record(tag: String, message: String, tr: Throwable?, captureStack: Boolean = true) {
        val entry = ErrorLog(
            time = System.currentTimeMillis(),
            tag = tag,
            message = message,
            // 只有需要抓栈时才做字符串化；否则仅留异常类型+消息，零栈遍历开销
            stackTrace = when {
                !captureStack -> tr?.let { "${it.javaClass.name}: ${it.message}" }
                else -> tr?.stackTraceToString()
            }
        )
        synchronized(buffer) {
            if (buffer.size >= MAX) buffer.removeFirst()
            buffer.addLast(entry)
            _flow.value = buffer.toList().asReversed()
        }
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _flow.value = emptyList()
        }
    }
}
