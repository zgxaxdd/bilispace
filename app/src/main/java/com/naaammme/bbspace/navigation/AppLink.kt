package com.naaammme.bbspace.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.net.toUri
import com.naaammme.bbspace.core.common.log.Logger
import com.naaammme.bbspace.core.model.WebLinkTarget

private const val TAG = "AppLink"

/**
 * 一条从**外部入口**进来的待消费链接（Manifest 声明的 deep link / 显式组件投递）。
 *
 * ## 为什么必须带上原始 URL
 *
 * [WebLinkTarget.External] 表示"本应用声明接管了这类链接，但站内路由表达不出来"
 * （例如 `space.bilibili.com/2/video`、`www.bilibili.com/read/cv123`）。它的语义是
 * **回落系统里的其它应用打开**（L-1），而这必须有原始地址 —— 修复前 `MainActivity`
 * 只把 `WebLinkTarget` 传下去、`External` 与 `Stay` 一起被丢掉，用户看到的是
 * "点了链接应用装死"：无提示、无日志、不回落（真机实证 2026-10-04）。
 */
data class AppLinkRequest(
    val target: WebLinkTarget,
    val url: String
)

/**
 * 把本应用接管的、但站内路由表达不了的链接交回系统（L-1 的"回落"语义）。
 *
 * ## 为什么要显式排除自己
 *
 * 回落用的是同一个 `ACTION_VIEW`（本应用正是在 manifest 里声明了这类 host/scheme），
 * 直接 `startActivity` 会被系统再次交给本应用 → `onNewIntent` → 又是 External →
 * **自我循环**。所以这里要求系统列出候选后**显式剔除本包名**。
 *
 * ## 为什么必须指定**具体组件**而不是只 `setPackage`（真机实测，2026-10-05）
 *
 * 只 `setPackage("com.UCMobile")` 时，ColorOS 上系统把它解析成了
 * `android/com.android.internal.app.ResolverActivity`（**弹选择器**）而不是直接打开浏览器 ——
 * logcat 实证：`START u0 {… pkg=com.UCMobile cmp=android/com.android.internal.app.ResolverActivity}`。
 * 用户看到的是"又冒出来一个选择框"，与"已在其它应用打开"的承诺不符。
 * 用 `ComponentName(pkg, activity)` 钉到具体 Activity 才会绕过解析器。
 *
 * 可见性：Android 11+ 的包可见性会过滤 `queryIntentActivities` 的结果，
 * 因此 manifest 里补了对应 http(s) VIEW 的 `<queries>`（否则浏览器不在候选里，
 * 会误判成"没有其它应用可以打开"）。
 */
fun Context.openExternalLink(url: String) {
    val viewIntent = Intent(Intent.ACTION_VIEW, url.toUri())
    val selfPackage = packageName
    // 优先按"默认处理者"（带 CATEGORY_DEFAULT）筛，避免把只会拦截链接的桩组件选成目标；
    // 筛不到再退回全量候选（某些机型/浏览器不声明 DEFAULT）。
    val candidates = run {
        val defaults = packageManager.queryIntentActivities(
            viewIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        defaults.ifEmpty { packageManager.queryIntentActivities(viewIntent, 0) }
    }
    val target = candidates
        .firstOrNull { it.activityInfo?.packageName != selfPackage && it.activityInfo?.enabled == true }
        ?.activityInfo
    if (target == null) {
        Logger.w(TAG) { "深链无法回落：本机没有其它应用可打开 url=$url" }
        Toast.makeText(this, "本应用暂不支持该链接，且没有其它应用可以打开", Toast.LENGTH_LONG).show()
        return
    }
    Logger.w(TAG) { "深链站内无对应路由，回落 ${target.packageName}/${target.name}：$url" }
    Toast.makeText(this, "本应用暂不支持该链接，已在其它应用打开", Toast.LENGTH_SHORT).show()
    val explicit = Intent(viewIntent).setComponent(ComponentName(target.packageName, target.name))
    runCatching { startActivity(explicit) }
        .onFailure {
            Logger.w(TAG) { "深链回落启动失败：$it" }
            Toast.makeText(this, "打开链接失败", Toast.LENGTH_SHORT).show()
        }
}
