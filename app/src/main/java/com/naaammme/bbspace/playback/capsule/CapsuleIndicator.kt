package com.naaammme.bbspace.playback.capsule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.motion.DissolveAnimationPreset
import com.naaammme.bbspace.core.designsystem.motion.MaybeDissolvableVideoCard
import com.naaammme.bbspace.core.designsystem.icon.AppIcons
import com.naaammme.bbspace.core.designsystem.seamless.LocalBottomNavOcclusionRatio
import com.naaammme.bbspace.core.designsystem.seamless.LocalSeamlessCardOpener
import com.naaammme.bbspace.core.designsystem.seamless.OplusViewSeamlessCompat
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessAnchor
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessAnchorState
import com.naaammme.bbspace.core.model.PlaybackState
import com.naaammme.bbspace.core.model.StreamPlaybackSessionState
import com.naaammme.bbspace.core.model.StreamPlaybackTarget
import android.view.View
import android.view.ViewGroup

private val NowPlayingRadius = 28.dp

/**
 * 小横条无缝动画的 `BUNDLE_LIST_COVER` 比例对。
 *
 * 官方语义（接入指引 13772）：传入后，返回动画末端会对**被遮挡区域**做 alpha 渐隐，
 * 避免"缩回时被底栏/顶栏硬切一刀"。
 *
 * 小横条贴着屏幕底部：它**上方**直到屏幕顶都还在屏内（不算遮挡），真正被遮挡的是
 * 小横条**下方**那一条（底栏自身 + 系统导航区），比例由 App 层实测后经
 * [LocalBottomNavOcclusionRatio] 下发；顶部比例固定 0（小横条之上没有 chrome 遮挡）。
 *
 * 锚点尺寸为 0（尚未布局）或比例全为 0 时返回 null —— 不传该字段，系统退回默认行为。
 */
private fun listCoverFor(anchor: View, bottomOcclusionRatio: Float): Pair<Float, Float>? {
    if (anchor.width <= 0 || anchor.height <= 0) return null
    val bottom = bottomOcclusionRatio.coerceIn(0f, 1f)
    if (bottom <= 0f) return null
    return 0f to bottom
}

/**
 * 联动底栏的小横条内容（任务 1 规格）：**同一个节点**由 [LinkedBottomDock] 在行间
 * 移动——`merge=0` 满宽独占导航上方一行，`merge=1` 收窄到「首页图标之后」与导航同行，
 * 全程保持组合（外壳由 Dock 统一绘制，四个表面材质一致）。
 *
 * [merge] 同时驱动次级元素（UP 名 / 关闭钮）的消隐：收窄后放不下，淡出并在
 * `merge > 0.5` 起禁止点击，避免留下不可见的命中区。
 *
 * 交互：单击回详情；播放/暂停只切播放态；× 才真正停播并解除入坞；
 * 播放自然结束（[PlaybackState.Ended]）自动停播收起。
 *
 * 单击走 ColorOS「View 无缝动画」：横条内挂一个零绘制透明 View 锚点，点击瞬间把
 * 锚点交给共享的 [com.naaammme.bbspace.core.designsystem.seamless.SeamlessCardOpener]，
 * 由系统在窗口层做「横条 → 全屏详情」的过渡（与首页卡片同一套执行器）。
 * 设备不支持 / 注册失败 / 开关关闭时，`tryOpen` 返回 false，原样回落 [onOpen]。
 */
@Composable
internal fun LinkedNowPlayingRow(
    capsuleState: CapsuleIndicatorState,
    merge: Float,
    /**
     * 小横条内容布局是否已稳定（合并动画结束且 IME 落定）。
     *
     * 上游配套参数：合并动画期间行高/位置每帧都在变，内容此时若再做自己的布局决策
     * （跑马灯重测、次级元素消隐）会与几何插值打架，表现为落位前"顿一下"。
     * false 时应跳过昂贵的自测量。
     */
    layoutStable: Boolean = true,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sessionState by capsuleState.sessionState.collectAsStateWithLifecycle()
    val progress by capsuleState.progress.collectAsStateWithLifecycle()
    val seamlessOpener = LocalSeamlessCardOpener.current
    val anchorState = rememberSeamlessAnchorState()
    val density = LocalDensity.current
    // 下发的是 provider（见 ListCoverRatio.kt）：这里只在 openClick 里读，
    // 组合期不读值，避免 Dock 合并动画按帧重组胶囊。
    val bottomNavRatio = LocalBottomNavOcclusionRatio.current
    // 锚点圆角必须与横条真实圆角一致（官方：有圆角的 view 必传 BUNDLE_RADIUS）。
    // 横条自身圆角是 NowPlayingRadius，而不是卡片 token —— 用错会在 morph 中途跳一下。
    val anchorRadiusPx = with(density) { NowPlayingRadius.toPx() }
    // 过渡填充色兜底：小横条又宽又扁（1188×168px），而详情页是整屏竖屏比例，
    // 两者宽高比差异极大。系统把锚点放大到全屏比例时，view 之外的区域若取不到色
    // 就会填黑——实测表现为"退回途中拖出一道黑轨迹"。这里显式给页面底色。
    val seamlessFallbackColor = MaterialTheme.colorScheme.background.toArgb()
    // 版本门禁前置到组合期：不支持无缝的设备不额外实例化锚点 View
    val canSeamless = seamlessOpener != null && OplusViewSeamlessCompat.isVersionSupported()

    LaunchedEffect(sessionState.playbackState) {
        if (sessionState.playbackState == PlaybackState.Ended) {
            capsuleState.dismissAndStop()
        }
    }

    val progressColor = MaterialTheme.colorScheme.primary
    val progressFraction = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val secondaryAlpha = (1f - merge).coerceIn(0f, 1f)
    val compact = merge > 0.5f

    // 单击入口：优先无缝，失败回落普通跳转。
    val openClick: () -> Unit = {
        val videoTarget = (sessionState.target as? StreamPlaybackTarget.Video)?.target
        val seamless = videoTarget != null && canSeamless && seamlessOpener.tryOpen(
            anchor = anchorState.anchor,
            target = videoTarget,
            coverUrl = sessionState.cover,
            // 横条封面是 43 比例的小图（CapsuleCover 的 40dp），取 Small43 档
            coverLarge = false,
            anchorRadiusPx = anchorRadiusPx,
            listCover = anchorState.anchor?.let { listCoverFor(it, bottomNavRatio()) },
            fallbackColor = seamlessFallbackColor,
            // 小横条**锚点自身就有实时内容**（封面 + 标题 + 按钮 + 玻璃壳），
            // 不需要框架的兜底位图；而兜底位图是一张 16:9 封面，被拉伸到
            // 1188×168 的横条矩形里就会在打开/返回动画中留下"预览图痕迹"。
            // 置 true 让框架直接采真实 View（官方 BUNDLE_VIEW_VISIBLE）。
            keepAnchorVisible = true,
            // 关键：不要用封面位图装饰小横条锚点。
            // 小横条是 1188×168（≈7:1），封面是 16:9；框架走兜底路径时会把封面
            // 拉伸铺满整个横条 —— 用户反馈的"预览图填充在视频小横条中"就是它。
            // 改为只用 BUNDLE_COLOR（横条底色）兜底，最差是一块纯色。
            decorateAnchor = false,
            // 把锚点状态传下去：执行器据此用真实显示矩形（含 Dock 合并动画 /
            // 全局拖拽反馈的 graphicsLayer 形变）作为 BUNDLE_RECT。横条在合并
            // 动画里尺寸持续变化，View 的布局矩形未必等于此刻屏幕上那块矩形。
            anchorState = anchorState
        )
        if (!seamless) onOpen()
    }

    // 小横条「× 关闭」的粒子消散（**直接移植 BiliPai 的 GL Thanos 实现**：
    // `DissolvableSurface.kt` + `motion/gl/*` + `res/raw/thanos_*.glsl`）。
    // 语义照源实现：`isDissolving=true` 起粒子，`onDissolveComplete` 里才真正停播并释放会话 ——
    // 所以 dismissAndStop() 挪到了回调里，而不是点下去立刻执行。
    var dissolving by remember { mutableStateOf(false) }
    val dissolveCardId = "capsule-${sessionState.title.hashCode()}"

    // 消散被取消（用户又打开了详情页等）时把锚点还原，避免壳一直不可见
    LaunchedEffect(dissolving) {
        if (!dissolving) { anchorState.anchor?.alpha = 1f; capsuleState.endDissolve() }
    }

    // 外层 Box 只负责给锚点一个「与横条同边界」的宿主：`matchParentSize()` 是
    // BoxScope 限定修饰符，直接放进 Row 会把锚点当成一个真实的布局子项（撑开宽度、
    // 挤掉封面与标题）。锚点零绘制且不拦截事件，层级压在内容之下。
    //
    // 消散宿主包在**最外层**：横条自身有 `clip(RoundedCornerShape(...))`，
    // 而粒子要飞出横条边界（GL 宿主挂在 window 的 decorView 上，不受此裁剪影响）。
    MaybeDissolvableVideoCard(
        isDissolving = dissolving,
        onDissolveComplete = { capsuleState.dismissAndStop() },
        cardId = dissolveCardId,
        modifier = modifier,
        // 预设对齐 BiliPai 的调用点写法（`WatchLaterScreen.kt:1271` 用的是 TELEGRAM_FAST）
        preset = DissolveAnimationPreset.TELEGRAM_FAST,
        // 粒子首帧出现后，按**框架自己的方式**释放锚点：
        // 正常路径（无粒子）之所以干净，是因为胶囊离开组合 → `SeamlessAnchor.onRelease`
        // → 系统无缝覆盖层被拆除。而 `graphicsLayer { alpha = 0 }` 管不到
        // AndroidView 与系统覆盖层，所以这里手动走同一条释放路径
        // （把锚点从父容器移除并置空 state），但**保留组件在组合里** ——
        // 组件一旦被卸载，粒子渲染会话会跟着销毁（粒子会被截断）。
        onContentHidden = {
            // 与 Dock 的玻璃壳同步：粒子接管的那一刻，模糊壳也撤
            capsuleState.beginDissolve()
            anchorState.anchor?.let { anchor ->
                (anchor.parent as? ViewGroup)?.removeView(anchor)
            }
            anchorState.anchor = null
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(NowPlayingRadius))
        ) {
        if (canSeamless) {
            SeamlessAnchor(anchorState, Modifier.matchParentSize())
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    // BiliPai 式细进度线：画在横条底边 2dp、tint 色。draw 阶段读值，
                    // 进度刷新只重绘不重组。
                    if (progressFraction > 0f) {
                        val lineH = 2.dp.toPx()
                        drawRect(
                            color = progressColor,
                            topLeft = Offset(0f, size.height - lineH),
                            size = Size(size.width * progressFraction, lineH)
                        )
                    }
                }
                .clickable(onClick = openClick)
                .padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CapsuleCover(
                sessionState = sessionState,
                modifier = Modifier.padding(end = 8.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) {
                Text(
                    text = sessionState.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!sessionState.subtitle.isNullOrBlank()) {
                    Text(
                        text = sessionState.subtitle.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = secondaryAlpha }
                    )
                }
            }
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (sessionState.isPlaying) AppIcons.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (sessionState.isPlaying) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = {
                    // ① **当场停播**（用户反馈「点关闭的叉叉后视频没有立即关闭，有延迟」）：
                    //    消散是 ~2.09s 的动画，而"关掉它"是交互的实质结果 —— 若等粒子跑完
                    //    才停，那两秒里视频照常播放（声音继续、画面继续动），读作"关不掉"。
                    //    所以这里先停播放器（pause，不 close —— 见 stopPlaybackNow 的注释），
                    //    粒子只负责把本体送走的视觉收尾。
                    // ② 起粒子消散；真正的会话释放仍在 onDissolveComplete 里
                    //   （BiliPai 同序：粒子首帧出现后隐藏本体，消散完成再释放会话）。
                    //    动效关闭 / 非 GLES3 / 抓图失败时它会立刻回调，不会卡住关闭。
                    capsuleState.stopPlaybackNow()
                    dissolving = true
                },
                enabled = !compact,
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer { alpha = secondaryAlpha }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            }
        }
    }
}

@Composable
internal fun CapsuleCover(
    sessionState: StreamPlaybackSessionState,
    modifier: Modifier = Modifier
) {
    val cover = sessionState.cover
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
    ) {
        if (cover != null) {
            BiliAsyncImage(
                url = cover,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        }
    }
}
