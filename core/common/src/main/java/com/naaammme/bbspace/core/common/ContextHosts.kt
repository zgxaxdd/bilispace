package com.naaammme.bbspace.core.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * 从任意 Context 解出宿主 [Activity]（PT-09，语义移植自上游 BiliPai
 * `core/util/.../SystemBarCompat.kt:94`）。
 *
 * 上游同文件还有 2 份私有重复实现（`WindowSizeUtils.kt:314`、`AudioModeScreen.kt:146`）——
 * 上游的教训是"重复定义迟早漂移"；bbspace 版**只允许这一处定义**
 * （结构断言见 `ContextHostsTest.hostActivityHasExactlyOneDefinition`）。
 *
 * 语义：沿 `ContextWrapper` 链向上解包，链尾没有 Activity 返回 null（detached 场景）。
 * 注意 W19：JVM 单测的 android.jar 是桩，本函数的链式解包真值在真机/Robolectric 验证；
 * 单测只钉"受控失败类别"契约。
 */
internal fun Context.findHostActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
