package com.naaammme.bbspace.core.designsystem.color

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.naaammme.bbspace.core.designsystem.theme.LocalAnimations
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 视频卡片封面动态取色的**可复用宿主**（推荐页 FeedCard 与排行页 RankVideoCard
 * 共用同一配方；解析 spec / 文字色判据见 [resolveVideoCardAmbientDrawSpec] 与
 * [resolveVideoCardAdaptiveContentColors]）。
 *
 * 封装三件事（此前内联在 FeedCard 里）：
 * 1. 先读缓存——同一张封面在任意卡片/任意次滚动里都命中同一份取色结果；
 * 2. 缓存没有时**主动补一次取色**：取色若只挂在 Coil 解码回调上，封面预加载会让
 *    卡片走缓存命中路径、解码不再发生 → 回调永不触发 → 这张卡永远没有颜色；
 * 3. 取色结果 350ms 渐变入场（直接跳变会让整卡"啪"地换色）。
 *
 * 防串色：异步结果回来时若卡片已被复用给别的封面（key 不匹配）则丢弃。
 *
 * @param coverKey 取色缓存键，用封面 URL 本身；
 * @param requestUrl 必须与封面**实际请求**的 URL 同源（`biliImageUrlForVariant`
 *   按同一变体解析），否则提取的是另一张图、颜色对不上；
 * @param sizePx 取色采样尺寸，与封面预加载尺寸保持一致（命中同一份解码位图）；
 * @param enabled 动态取色总开关（`LocalHomeCardDynamicTintEnabled`，设置页可关），
 *   关闭时恒返回 null（调用方退回中性底色）。
 */
@Composable
fun rememberVideoCardCoverTint(
    coverKey: String,
    requestUrl: String?,
    sizePx: Pair<Int, Int>,
    enabled: Boolean = LocalHomeCardDynamicTintEnabled.current
): Color? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tint by remember(coverKey) { mutableStateOf(VideoCardCoverColorStore.getCachedColor(coverKey)) }
    // 异步提取回来时，若卡片已被复用给别的封面，必须丢弃这次结果（防串色）
    val activeKey by rememberUpdatedState(coverKey)
    LaunchedEffect(coverKey, requestUrl, enabled) {
        if (!enabled || coverKey.isBlank() || requestUrl.isNullOrBlank()) return@LaunchedEffect
        if (tint != null) return@LaunchedEffect
        VideoCardCoverColorStore.requestExtraction(
            appContext = context,
            cacheKey = coverKey,
            url = requestUrl,
            sizePx = sizePx,
            scope = scope
        ) { color ->
            if (activeKey == coverKey) tint = color
        }
    }
    val animated by animateColorAsState(
        targetValue = tint ?: Color.Transparent,
        animationSpec = tween(durationMillis = LocalAnimations.current.long),
        label = "videoCardCoverTint"
    )
    return if (animated.alpha > 0f) animated else null
}
