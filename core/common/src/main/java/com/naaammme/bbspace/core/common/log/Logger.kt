package com.naaammme.bbspace.core.common.log

import android.util.Log

object Logger {
    var isDebug = false
        private set

    fun init(debug: Boolean) {
        isDebug = debug
    }

    inline fun d(tag: String, msg: () -> String) {
        if (isDebug) {
            Log.d(tag, msg())
        }
    }

    inline fun i(tag: String, msg: () -> String) {
        if (isDebug) {
            Log.i(tag, msg())
        }
    }

    inline fun w(tag: String, msg: () -> String) {
        if (isDebug) {
            Log.w(tag, msg())
        }
    }

    inline fun e(tag: String, tr: Throwable? = null, msg: () -> String) {
        val message = msg()
        // captureStack = isDebug：release 下不抓全量堆栈。
        //
        // 此前 `ErrorCollector.record` 在 isDebug 门控**之外**，release 也照跑
        // `stackTraceToString()`（遍历栈 + 构建数 KB 字符串）并在锁内做 O(200) 列表拷贝
        // 后推入 StateFlow —— 而很多 Logger.e 就在主线程的网络失败路径上。且 200 条
        // 堆栈字符串常驻进程，而 release 用户根本看不到这个列表（唯一消费者是设置页）。
        ErrorCollector.record(tag, message, tr, captureStack = isDebug)
        if (isDebug) {
            if (tr != null) {
                Log.e(tag, message, tr)
            } else {
                Log.e(tag, message)
            }
        }
    }
}
