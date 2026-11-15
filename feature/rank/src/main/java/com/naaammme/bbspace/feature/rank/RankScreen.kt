package com.naaammme.bbspace.feature.rank

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naaammme.bbspace.core.designsystem.component.AdaptiveMediaGrid
import com.naaammme.bbspace.core.designsystem.component.BiliCardSurface
import com.naaammme.bbspace.core.designsystem.component.BiliImageVariant
import com.naaammme.bbspace.core.designsystem.component.CoverImage
import com.naaammme.bbspace.core.designsystem.component.StateMessageCard
import com.naaammme.bbspace.core.designsystem.component.biliImageUrlForVariant
import com.naaammme.bbspace.core.designsystem.color.rememberVideoCardCoverTint
import com.naaammme.bbspace.core.designsystem.color.resolveVideoCardAdaptiveContentColors
import com.naaammme.bbspace.core.designsystem.color.resolveVideoCardAmbientDrawSpec
import com.naaammme.bbspace.core.designsystem.seamless.CoverExpandOrigin
import com.naaammme.bbspace.core.designsystem.seamless.LocalBottomNavOcclusionRatio
import com.naaammme.bbspace.core.designsystem.seamless.LocalSeamlessCardOpener
import com.naaammme.bbspace.core.designsystem.seamless.OplusViewSeamlessCompat
import com.naaammme.bbspace.core.designsystem.seamless.SeamlessAnchor
import com.naaammme.bbspace.core.designsystem.seamless.coverExpandSourceFade
import com.naaammme.bbspace.core.designsystem.seamless.coverExpandTrack
import com.naaammme.bbspace.core.designsystem.seamless.listCoverRatio
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessAnchorState
import com.naaammme.bbspace.core.designsystem.seamless.rememberSeamlessCardSpec
import com.naaammme.bbspace.core.designsystem.theme.BiliRadius
import com.naaammme.bbspace.core.designsystem.theme.BiliCardDensity
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.SpaceRoute
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.core.model.VideoTargetTool
import com.naaammme.bbspace.core.model.rank.RankEntry
import com.naaammme.bbspace.core.model.rank.RankPgcItem
import com.naaammme.bbspace.core.model.rank.RankSeriesEntry
import com.naaammme.bbspace.core.model.rank.RankType
import com.naaammme.bbspace.core.model.rank.RankVideoItem

/**
 * 排行卡片容器形状：与推荐页 `FeedCardShape` 同源（[BiliRadius.card]），整卡大圆角。
 *
 * 此前是 `MaterialTheme.shapes.large`（ROUNDED 24 / iOS 16），随 [CornerStyle] 档位
 * 漂移、且与封面自身的 [BiliRadius.card] 是两套来源；统一后卡片外壳与封面同一语言。
 * 文件级常量：composable 体内就地构造 Shape 被 ListAllocationStructureTest 禁止。
 */
private val RankCardShape = RoundedCornerShape(BiliRadius.card)

/**
 * 类型/期数选择胶囊：与 [BiliRadius.groupedList] 同族（16dp）。
 * chip 实测高 27~31dp，16dp 即全圆端胶囊；此前 `shapes.extraLarge` 随档位
 * 在 20~32dp 间漂移，且 CIRCULAR 档下 28dp 会在矮 chip 上裁成方角。
 */
private val RankChipShape = RoundedCornerShape(BiliRadius.groupedList)

/** 封面块：只圆上两角，下沿直角，与信息区无缝拼接（推荐页 FeedCardCoverShape 同款）。 */
private val RankCardCoverShape: Shape = BiliRadius.topRounded(BiliRadius.card)

/**
 * 封面角标（名次 / 播放数 / 时长 / 期数）的圆角。
 *
 * 文件级常量而不是内联 `shape = RoundedCornerShape(...)`：
 * `ListAllocationStructureTest.shapeAllocationBypassForms_areFrozenAtMeasuredBaseline`
 * 把全仓"具名实参直接构造 Shape"的计数钉死，写在 composable 参数位上每次都新建一份。
 */
private val RankCoverBadgeShape = RoundedCornerShape(BiliRadius.badge)

/** 封面取色采样尺寸：与信息流双列卡的封面预加载尺寸一致（640×480），命中同一份解码位图。 */
private const val RANK_COVER_SAMPLE_WIDTH_PX = 640
private const val RANK_COVER_SAMPLE_HEIGHT_PX = 480

/**
 * 前三名的门槛。名次本身是数据属性，但"第几名要更醒目"是**呈现决策**，
 * 所以放在 UI 层而不是模型层。
 */
private const val TOP_RANK_HIGHLIGHT = 3

/**
 * 首页「排行」页：热门/分区排行榜（UGC rid）、PGC 季度排行、每周必看、入站必刷。
 * 分区表与接口对照 PiliPlus pages/rank 与 lib/http/video.dart。
 * 类型选择行作为网格 header 随内容滚动（与推荐页顶栏行为一致，不固定悬浮）。
 */
@Composable
fun RankScreen(
    isActive: Boolean,
    topContentPadding: Dp,
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit,
    viewModel: RankViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyStaggeredGridState()
    // 无缝返回动画的遮挡渐隐比例（顶部悬浮顶栏 + 底部悬浮导航栏），与推荐页同口径
    val density = LocalDensity.current
    val screenHpx = LocalWindowInfo.current.containerSize.height
    val bottomNavRatio = LocalBottomNavOcclusionRatio.current
    // 与推荐页同口径：遮挡比延迟到点击时才算，理由见 HomeVideoPage 的同名注释
    // （组合期读会在 Dock 合并动画期间按帧重组；且遮挡比是异步实测的，remember 住
    //   会把首帧的 0f 缓存成永久值）。
    val listCover: () -> Pair<Float, Float>? = {
        listCoverRatio(
            topOcclusionPx = with(density) { topContentPadding.roundToPx() },
            screenHeightPx = screenHpx,
            bottomOcclusionRatio = bottomNavRatio()
        )?.let { it.top to it.bottom }
    }
    // ── 名次（本页新增的第三级信息）──────────────────────────────────────────
    // 接口按名次返回条目，所以**列表下标 + 1 就是名次**，不需要另加数据字段。
    // 先一次性算成 key→名次：若写在卡片里现算，每张可见卡每帧都要对整份榜单
    // （最多 100 条）做一次线性查找；这里只在 entries 换页时算一次。
    val rankByKey = remember(state.entries) {
        state.entries.mapIndexed { index, entry -> entry.key to index + 1 }.toMap()
    }

    AdaptiveMediaGrid(
        items = state.entries,
        isRefreshing = state.isRefreshing || (state.isLoading && state.entries.isEmpty()),
        isLoadingMore = state.isLoadingMore,
        // 必须把错误接进网格：不传时网格视为无错误，失败一律渲染成 emptyContent
        // 的"暂无内容"——请求被风控/断网时用户只看到空态，无从重试也无法得知原因。
        errorMessage = state.errorMessage,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        loadMoreEnabled = state.canLoadMore,
        // 尾部提前预取：与推荐页一致，距末尾 4 条就取下一页（该网格有 header 项，
        // 可见下标整体偏移 1，阈值取 4 即"还剩约 5 条"时触发）
        loadMoreThreshold = 4,
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        key = { _, item -> item.key },        contentType = { _, item -> item::class.simpleName },
        // 边距与推荐页同口径（10/10/12）：推荐页注释里点名过"两处不同步就会出现
        // 首页贴边、排行页留白这种不一致"，卡片同款之后边距也必须一致
        contentPadding = PaddingValues(
            start = BiliCardDensity.pageHorizontal,
            top = topContentPadding,
            end = BiliCardDensity.pageHorizontal,
            bottom = BiliCardDensity.pageBottom
        ),
        headerContent = {
            RankSelectors(
                state = state,
                onSelectType = viewModel::select,
                onSelectSeries = viewModel::selectSeries
            )
        },
        emptyContent = {
            Column(modifier = Modifier.fillMaxWidth()) {
                RankSelectors(
                    state = state,
                    onSelectType = viewModel::select,
                    onSelectSeries = viewModel::selectSeries
                )
                StateMessageCard(
                    // 空态补一句"下一步能做什么"：原实现只有「暂无内容」四个字，
                    // 层级上也只有一档字重，与错误态、加载态的呈现不成体系。
                    text = "换个分区或下拉刷新试试",
                    title = "暂无内容",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(BiliSpacing.lg)
                )
            }
        },
        errorContent = { message ->
            // 列表为空时的错误 = 整页失败：保留选择行以便切换分区重试；
            // 列表非空时的错误 = 加载更多失败：仅展示错误卡片
            if (state.entries.isEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    RankSelectors(
                        state = state,
                        onSelectType = viewModel::select,
                        onSelectSeries = viewModel::selectSeries
                    )
                    StateMessageCard(
                        text = message,
                        title = "加载失败",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(BiliSpacing.lg),
                        isError = true
                    )
                }
            } else {
                StateMessageCard(
                    text = message,
                    title = "加载失败",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(BiliSpacing.lg),
                    isError = true
                )
            }
        },
        loadingContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    // 骨架占位 = 卡片同形状（此前 12.dp 裸字面量，与卡片语言脱节）
                    .background(MaterialTheme.colorScheme.surfaceVariant, RankCardShape)
            )
        }
    ) { entry ->
        val rank = rankByKey[entry.key] ?: 0
        when (entry) {
            is RankEntry.Video -> RankVideoCard(
                item = entry.item,
                rank = rank,
                listCover = listCover,
                onOpenVideo = onOpenVideo,
                onOpenSpace = onOpenSpace
            )

            is RankEntry.Pgc -> RankPgcCard(
                item = entry.item,
                rank = rank,
                onOpenVideo = onOpenVideo
            )
        }
    }
}

/** 类型选择行 + 每周必看期数行（仅每周必看类型时出现） */
@Composable
private fun RankSelectors(
    state: RankUiState,
    onSelectType: (RankType) -> Unit,
    onSelectSeries: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = BiliCardDensity.infoHorizontal, vertical = BiliCardDensity.infoVertical),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RankType.entries.forEach { type ->
                val selectedNow = type == state.type
                Surface(
                    shape = RankChipShape,
                    color = if (selectedNow) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    // F-5：这是单选项那组 chip，语义上不是 Button 而是 RadioButton ——
                    // 补 role + selected 后 TalkBack 才会播报"已选中/未选中"。
                    modifier = Modifier.selectable(
                        selected = selectedNow,
                        role = Role.RadioButton,
                        onClick = { onSelectType(type) }
                    )
                ) {
                    Text(
                        text = type.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedNow) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = BiliCardDensity.badgeHorizontal, vertical = BiliCardDensity.badgeVertical)
                    )
                }
            }
        }
        if (state.type == RankType.SERIES && state.seriesList.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    // 与上一行同口径（原为 8/2dp 内联字面量）：两行选择器读作同一组控件
                    .padding(
                        horizontal = BiliCardDensity.infoHorizontal,
                        vertical = BiliCardDensity.infoVertical
                    ),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.seriesList.forEach { entry ->
                    val selectedNow = entry.number == state.seriesNumber
                    Surface(
                        shape = RankChipShape,
                        // 与类型行同一套选中语言（原为 tertiaryContainer，同屏两行两个色调）。
                        // 本仓是单色体系，选中态靠 primary 明暗而不是第三种颜色区分。
                        color = if (selectedNow) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        // 与类型行同样补 RadioButton 语义（两者都是单选组）
                        modifier = Modifier.selectable(
                            selected = selectedNow,
                            role = Role.RadioButton,
                            onClick = { onSelectSeries(entry.number) }
                        )
                    ) {
                        Text(
                            text = entry.name.ifBlank { "第${entry.number}期" },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selectedNow) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            modifier = Modifier.padding(
                                horizontal = BiliSpacing.smPlus,
                                vertical = BiliSpacing.xs
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RankVideoCard(
    item: RankVideoItem,
    rank: Int,
    listCover: () -> Pair<Float, Float>? = { null },
    onOpenVideo: (VideoTarget) -> Unit,
    onOpenSpace: (SpaceRoute) -> Unit
) {
    val target = remember(item.aid, item.cid, item.bvid) {
        VideoTarget.Ugc(
            aid = item.aid,
            cid = item.cid,
            bvid = item.bvid,
            src = VideoTargetTool.default()
        )
    }
    val spaceRoute = remember(item.ownerMid) {
        item.ownerMid.takeIf { it > 0L }?.let { SpaceRoute(mid = it, name = item.ownerName) }
    }
    // 与首页 FeedCard 相同的无缝动画接入：注册成功走共享执行器，失败降级幽灵。
    // 版本门禁前置到组合期：不支持的设备不为每张卡实例化锚点 View
    val anchorState = rememberSeamlessAnchorState()
    val seamlessSpec = rememberSeamlessCardSpec(
        shape = RoundedCornerShape(BiliRadius.card),
        containerColor = MaterialTheme.colorScheme.surface
    )
    val seamlessOpener = LocalSeamlessCardOpener.current
    val canSeamless = seamlessOpener != null && OplusViewSeamlessCompat.isVersionSupported()

    // ── 推荐页同款视觉（用户需求：排行页 UI 与推荐页同步）────────────────────────
    // 配方与 FeedCard 完全同源，取色/染色/文字色判据都来自 core/designsystem：
    // 封面动态取色（rememberVideoCardCoverTint）→ 整卡染色底（resolveVideoCardAmbientDrawSpec）
    // → 信息区封面同调微光 + 自适应文字色（resolveVideoCardAdaptiveContentColors，
    // 浅色主题遇深色封面正文翻白）。排行卡只是数据源不同（无关注态/不感兴趣菜单）。
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val coverTint = rememberVideoCardCoverTint(
        coverKey = item.cover.orEmpty(),
        requestUrl = biliImageUrlForVariant(item.cover, BiliImageVariant.CardCover43),
        sizePx = RANK_COVER_SAMPLE_WIDTH_PX to RANK_COVER_SAMPLE_HEIGHT_PX
    )
    val infoDrawSpec = resolveVideoCardAmbientDrawSpec(
        coverTint = coverTint,
        isDarkTheme = darkTheme,
        defaultContainerColor = containerColor,
        defaultBorderColor = Color.Transparent
    )
    val adaptiveColors = resolveVideoCardAdaptiveContentColors(
        coverTint = coverTint,
        isDarkTheme = darkTheme,
        defaultOnSurface = MaterialTheme.colorScheme.onSurface,
        defaultOnSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // 标题：改用 iOSTypography.titleSmall（14 SemiBold + 等宽数字），与下一行
    // bodySmall（13 Light）拉开"标题 ↔ meta"两个字重档。
    // 此前是 bodyLarge.withFontSize(14.sp).copy(fontWeight = Medium)：与 meta 只差一档，
    // 首屏读下来整张卡是"一片同重的字"，这正是用户说的「文字摆在上面太单调」。
    val titleStyle = MaterialTheme.typography.titleSmall

    BiliCardSurface(
        onClick = {
            // 与首页 FeedCard 同一套无缝时序（用户第 34 轮反馈点名「尤其是排行界面的
            // 动画」——本卡此前走的是「直接 tryOpen、无截图」的旧路径，与首页两条路：
            //
            // ① **没有 PixelCopy**：锚点是零绘制 View，框架捕获不到内容就退回
            //    `BUNDLE_BITMAP`（软件封面位图）兜底；而这张卡是「4:3 封面 + 简介区」
            //    的近 1:1 矩形，封面会被**纵向拉伸铺满整卡** ⇒ 打开/返回时都能看到
            //    「封面糊住简介区」的半帧。
            // ② **没有 fallbackColor**：框架取不到色时填黑，返回途中拖出一道黑轨迹。
            //
            // 现在与首页完全同源：点击这一帧先 PixelCopy 截整卡真实像素（含简介文字），
            // 回调里再 tryOpen + startActivity —— 官方「setSeamlessView 必须在
            // startActivity 之前」的时序契约仍然成立（两者在回调里先后紧邻）。
            val anchorView = anchorState.anchor
            // 直接对 seamlessOpener 判空（而不是 `canSeamless && …` 再在里面 Elvis）：
            // Kotlin 不会因为一个布尔变量为真就对其内部的非空判断做智能转换，
            // 写成 `canSeamless && …` 再 `seamlessOpener ?: return` 会让编译器把 Elvis
            // 判为恒真（实测 warning: always returns the left operand）。
            // 这里把判空提到条件里，语义等价、类型收窄是真的。
            val opener = seamlessOpener
            if (opener != null && anchorView != null && OplusViewSeamlessCompat.isVersionSupported()) {
                opener.captureCardBitmap(anchorView, anchorState) { captured ->
                    val opened = opener.tryOpen(
                        anchor = anchorView,
                        target = target,
                        coverUrl = item.cover,
                        coverLarge = false,
                        anchorRadiusPx = seamlessSpec.radiusPx,
                        listCover = listCover(),
                        // 官方 BUNDLE_COLOR：view 之外区域的填充色（不传则框架取色，
                        // 取错表现为打开/返回瞬间闪黑）
                        fallbackColor = seamlessSpec.fallbackColor,
                        keepAnchorVisible = true,
                        cardBitmap = captured,
                        // 真实显示矩形（含全局拖拽反馈的 graphicsLayer 形变）——
                        // 排行卡在瀑布流里、点击前常带一次未走完的按压回弹，
                        // 这是"按住卡片点开时动画从错误矩形起飞"的修法。
                        anchorState = anchorState
                    )
                    if (!opened) {
                        captured?.recycle()
                        CoverExpandOrigin.record(
                            target = target,
                            coverUrl = item.cover,
                            coverLarge = false
                        )
                        onOpenVideo(target)
                    }
                }
                return@BiliCardSurface
            }
            CoverExpandOrigin.record(target = target, coverUrl = item.cover, coverLarge = false)
            onOpenVideo(target)
        },
        // 卡片外壳与封面同一语言（此前 shapes.large 与封面 card 是两套来源）
        shape = RankCardShape,
        // 整卡染色底：有封面色时是与封面同调的色，无封面色时退 surfaceContainerLow
        // （推荐页同款——不透明底色是染色"只兑一次白"的前提，见该卡同名注释）
        containerColor = infoDrawSpec.containerColor,
        // 回退 morph 期间源卡片随幽灵进度淡出（系统无缝路径恒 alpha=1，见该函数注释）
        modifier = Modifier.fillMaxWidth().coverExpandSourceFade(target),
        // 无缝锚点铺满**整张卡片**（封面 + 信息区），与首页 FeedCard 同口径。
        //
        // 为什么从封面 Box 里挪出来：锚点矩形就是系统的过渡几何范围。此前它
        // `matchParentSize()` 在只包封面的 Box 里 ⇒ 锚点 = 封面矩形（4:3），
        // 而系统打开的目标是整屏播放器。于是「封面单独放大成播放器、信息区原地不动」，
        // 返回时同理 —— 这是用户说的「排行界面动画有 bug」最直接的观感来源。
        // 首页 FeedCard 早已用 fullCardOverlay 解决同一问题（其注释记录了完整历史），
        // 排行卡漏了这一步。
        //
        // 放在 fullCardOverlay 而不是内容里：内容装在 Column（要用 weight），
        // 而 matchParentSize 是 BoxScope 限定修饰符，放 Column 里会被当成真实子项。
        fullCardOverlay = if (canSeamless) {
            { SeamlessAnchor(anchorState, Modifier.matchParentSize()) }
        } else {
            null
        },
        // 锚点真组合时**整卡形变必须让位**（排行页是用户报的"动画 bug"最明显的一页）。
        // 详见 `BiliCardSurface.elasticPressEnabled` 的 KDoc。
        elasticPressEnabled = !canSeamless
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .coverExpandTrack(target)
        ) {
            CoverImage(
                url = item.cover,
                contentDescription = item.title,
                // 封面只圆上两角，下沿直角与信息区无缝拼接（推荐页同款）
                shape = RankCardCoverShape,
                coverLarge = false,
                coverFourThree = true,
                modifier = Modifier.matchParentSize()
            ) {
                // 名次角标：榜单页此前**完全不显示名次**，用户只能靠数位置猜——
                // 这是本页观感提升最快的一处（前三名另有更重/更大的专属档）。
                RankBadge(
                    rank = rank,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(BiliSpacing.sm)
                )
                // 播放数从左上让到左下：名次占了左上，且"左下角信息"与首页卡位置语言一致
                item.view.takeIf { it > 0L }?.let { view ->
                    RankCoverBadge(
                        text = formatRankCount(view),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(BiliSpacing.sm)
                    )
                }
                item.durationSec.takeIf { it > 0L }?.let { duration ->
                    RankCoverBadge(
                        text = formatRankDuration(duration),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(BiliSpacing.sm)
                    )
                }
            }
        }
        // 信息区：只叠封面同调的竖向渐变微光（FeedCard infoModifier 同款；
        // 发丝描边省去——0.5.dp 是被冻结的 dp 字面量，视觉收益接近于零）
        Column(
            modifier = Modifier
                .then(
                    if (coverTint != null && infoDrawSpec.coverGlowAlpha > 0f) {
                        Modifier.drawWithCache {
                            val brush = Brush.verticalGradient(
                                colors = listOf(
                                    coverTint.copy(alpha = infoDrawSpec.coverGlowAlpha),
                                    coverTint.copy(alpha = infoDrawSpec.coverGlowAlpha * 0.35f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = size.height * 0.85f
                            )
                            onDrawBehind { drawRect(brush) }
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = BiliCardDensity.infoHorizontal, vertical = BiliCardDensity.infoVertical)
        ) {
            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 浅色主题 + 深色封面时翻白（推荐页同款可读性兜底）
                color = adaptiveColors.titleColor,
                style = titleStyle
            )
            Spacer(modifier = Modifier.height(BiliCardDensity.titleToMeta))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.ownerName.isNotBlank()) {
                    Text(
                        text = item.ownerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = adaptiveColors.subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (spaceRoute != null) {
                                    Modifier.clickable { onOpenSpace(spaceRoute) }
                                } else {
                                    Modifier
                                }
                            )
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                item.tname?.takeIf { it.isNotBlank() }?.let { tname ->
                    Text(
                        text = tname,
                        style = MaterialTheme.typography.labelSmall,
                        color = adaptiveColors.subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun RankPgcCard(
    item: RankPgcItem,
    rank: Int,
    onOpenVideo: (VideoTarget) -> Unit
) {
    val target = remember(item.seasonId, item.epId) {
        if ((item.seasonId ?: 0L) > 0L || (item.epId ?: 0L) > 0L) {
            VideoTarget.Pgc(
                epId = item.epId ?: 0L,
                seasonId = item.seasonId,
                src = VideoTargetTool.default()
            )
        } else {
            null
        }
    }
    val anchorState = rememberSeamlessAnchorState()
    val seamlessSpec = rememberSeamlessCardSpec(
        shape = RoundedCornerShape(BiliRadius.card),
        containerColor = MaterialTheme.colorScheme.surface
    )
    val seamlessOpener = LocalSeamlessCardOpener.current
    // 与 RankVideoCard 同款组合期门禁（2026-10-12 审查补）：本卡原先只判 `opener != null`
    // 且**从未放置 SeamlessAnchor** —— `anchorState.anchor` 恒为 null，`tryOpen` 恒返回
    // false，PGC 排行卡的无缝路径 100% 是死代码（每次点击都白跑一趟 tryOpen 再降级）。
    val canSeamless = seamlessOpener != null && OplusViewSeamlessCompat.isVersionSupported()

    // 推荐页同款视觉（与 RankVideoCard 同一配方，见那边注释）
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val coverTint = rememberVideoCardCoverTint(
        coverKey = item.cover.orEmpty(),
        requestUrl = biliImageUrlForVariant(item.cover, BiliImageVariant.CardCover43),
        sizePx = RANK_COVER_SAMPLE_WIDTH_PX to RANK_COVER_SAMPLE_HEIGHT_PX
    )
    val infoDrawSpec = resolveVideoCardAmbientDrawSpec(
        coverTint = coverTint,
        isDarkTheme = darkTheme,
        defaultContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        defaultBorderColor = Color.Transparent
    )
    val adaptiveColors = resolveVideoCardAdaptiveContentColors(
        coverTint = coverTint,
        isDarkTheme = darkTheme,
        defaultOnSurface = MaterialTheme.colorScheme.onSurface,
        defaultOnSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    )
    // 标题与 RankVideoCard 同一档（14 SemiBold + tnum），两卡并排时标题同一字重语言
    val titleStyle = MaterialTheme.typography.titleSmall

    BiliCardSurface(
        onClick = {
            val currentTarget = target ?: return@BiliCardSurface
            // 与 RankVideoCard 完全同一套修复（见那张卡的详细注释）：补 PixelCopy
            // 整卡像素 + fallbackColor + 真实显示矩形，并把锚点提到整卡。
            val anchorView = anchorState.anchor
            // 同 RankVideoCard：把 seamlessOpener 的判空提到条件里，让类型收窄成为真的
            //（用 canSeamless 布尔变量不会触发智能转换，Elvis 会被判恒真）。
            val opener = seamlessOpener
            if (opener != null && anchorView != null && OplusViewSeamlessCompat.isVersionSupported()) {
                opener.captureCardBitmap(anchorView, anchorState) { captured ->
                    val opened = opener.tryOpen(
                        anchor = anchorView,
                        target = currentTarget,
                        coverUrl = item.cover,
                        coverLarge = false,
                        anchorRadiusPx = seamlessSpec.radiusPx,
                        fallbackColor = seamlessSpec.fallbackColor,
                        keepAnchorVisible = true,
                        cardBitmap = captured,
                        anchorState = anchorState
                    )
                    if (!opened) {
                        captured?.recycle()
                        CoverExpandOrigin.record(
                            target = currentTarget,
                            coverUrl = item.cover,
                            coverLarge = false
                        )
                        onOpenVideo(currentTarget)
                    }
                }
                return@BiliCardSurface
            }
            CoverExpandOrigin.record(target = currentTarget, coverUrl = item.cover, coverLarge = false)
            onOpenVideo(currentTarget)
        },
        enabled = target != null,
        shape = RankCardShape,
        containerColor = infoDrawSpec.containerColor,
        // 回退 morph 期间源卡片随幽灵进度淡出（系统无缝路径恒 alpha=1，见该函数注释）
        modifier = Modifier.fillMaxWidth().coverExpandSourceFade(target),
        // 锚点铺满整卡（理由见 RankVideoCard 的 fullCardOverlay 注释）
        fullCardOverlay = if (canSeamless) {
            { SeamlessAnchor(anchorState, Modifier.matchParentSize()) }
        } else {
            null
        },
        // 锚点真组合时**整卡形变必须让位**：`appElasticPress` 是绘制期 graphicsLayer，
        // 而 OPPO 框架按布局期 `getLocationInWindow` 取锚点矩形 ⇒ 按住卡片点开时
        // 动画会从"屏幕上已不存在的矩形"起飞（排行页最明显：瀑布流里点击前常带
        // 一次未走完的按压回弹）。详见 `BiliCardSurface.elasticPressEnabled` 的 KDoc。
        elasticPressEnabled = !canSeamless
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
        ) {
            CoverImage(
                url = item.cover,
                contentDescription = item.title,
                shape = RankCardCoverShape,
                coverLarge = false,
                coverFourThree = true,
                modifier = Modifier.matchParentSize()
            ) {
                RankBadge(
                    rank = rank,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(BiliSpacing.sm)
                )
                item.indexShow?.let { indexShow ->
                    RankCoverBadge(
                        text = indexShow,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(BiliSpacing.sm)
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .then(
                    if (coverTint != null && infoDrawSpec.coverGlowAlpha > 0f) {
                        Modifier.drawWithCache {
                            val brush = Brush.verticalGradient(
                                colors = listOf(
                                    coverTint.copy(alpha = infoDrawSpec.coverGlowAlpha),
                                    coverTint.copy(alpha = infoDrawSpec.coverGlowAlpha * 0.35f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = size.height * 0.85f
                            )
                            onDrawBehind { drawRect(brush) }
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = BiliCardDensity.infoHorizontal, vertical = BiliCardDensity.infoVertical)
        ) {
            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = adaptiveColors.titleColor,
                style = titleStyle
            )
            Spacer(modifier = Modifier.height(BiliCardDensity.titleToMeta))
            Text(
                text = buildString {
                    append(formatRankCount(item.follow))
                    append(" 追番 · ")
                    append(formatRankCount(item.view))
                    append(" 播放")
                },
                style = MaterialTheme.typography.bodySmall,
                color = adaptiveColors.subtitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 名次角标（本页的"第三级信息"：名次 ↔ 标题 ↔ meta）。
 *
 * 三档层级，全部取自既有 typography token，零新增字号：
 * - **前三名**：`titleSmall`（14 SemiBold，自带 tnum）+ 更实的黑底（0.78）+ 更大内边距；
 * - **其余名次**：`labelMedium`（12 Medium，自带 tnum）+ 半透底（0.55）+ 更紧内边距。
 *
 * 为什么数字要用带 tnum 的槽位：一列卡片的个位/两位/三位数若字宽不一致，
 * 角标会随名次"忽宽忽窄"；tnum 让数字位宽恒定，整列对齐。
 * 为什么不用彩色：本仓是单色体系（见 BRIEF §2），层级靠**字号/字重/底实度**表达。
 */
@Composable
private fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    if (rank <= 0) return
    val isTop = rank <= TOP_RANK_HIGHLIGHT
    Surface(
        modifier = modifier,
        shape = RankCoverBadgeShape,
        color = Color.Black.copy(alpha = if (isTop) 0.78f else 0.55f)
    ) {
        Text(
            text = rank.toString(),
            modifier = Modifier.padding(
                horizontal = if (isTop) BiliSpacing.sm else BiliSpacing.xs,
                vertical = BiliSpacing.xs
            ),
            color = Color.White,
            style = if (isTop) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.labelMedium
            }
        )
    }
}

@Composable
private fun RankCoverBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RankCoverBadgeShape,
        color = Color.Black.copy(alpha = 0.62f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun formatRankDuration(durationSec: Long): String {
    val totalSec = durationSec.coerceAtLeast(0L)
    val hours = totalSec / 3_600
    val minutes = (totalSec % 3_600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun formatRankCount(count: Long): String {
    return when {
        count >= 100_000_000L -> {
            val value = count / 100_000_000.0
            trimRankNumber(value) + "亿"
        }

        count >= 10_000L -> {
            val value = count / 10_000.0
            trimRankNumber(value) + "万"
        }

        else -> count.toString()
    }
}

private fun trimRankNumber(value: Double): String {
    val rounded = (value * 10).toInt() / 10.0
    return if (rounded % 1.0 == 0.0) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}
