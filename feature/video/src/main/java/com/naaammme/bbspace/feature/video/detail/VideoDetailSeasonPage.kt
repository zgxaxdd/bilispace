package com.naaammme.bbspace.feature.video.detail

/**
 * 分P / 合集（season / page）入口卡与选择弹层。
 * 由 VideoDetailPage.kt 拆分而来（纯搬运，未改行为）。
 */

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.BiliAsyncImage
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.model.VideoPagePart
import com.naaammme.bbspace.core.model.VideoSeason
import com.naaammme.bbspace.core.model.VideoSeasonEpisode
import com.naaammme.bbspace.core.model.VideoTarget
import com.naaammme.bbspace.feature.video.formatDuration
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.motion.rememberAppElasticPressState

@Composable
internal fun SeasonEntryCard(
    season: VideoSeason,
    curCid: Long?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val (title, subTitle, countText) = seasonEntryText(season, curCid)

    Card(
        onClick = onClick,
        // 全局拖拽反馈（第 34 轮 T2）：合集/分P入口卡。
        modifier = modifier.fillMaxWidth().appElasticPress(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(BiliSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
        ) {
            Text(
                text = "合集列表",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subTitle.isNotBlank()) {
                Text(
                    text = subTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
@Composable
internal fun PageEntryCard(
    pages: List<VideoPagePart>,
    curCid: Long?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val (title, subTitle, countText) = remember(pages, curCid) {
        pageEntryText(pages, curCid)
    }

    Card(
        onClick = onClick,
        // 全局拖拽反馈（第 34 轮 T2）：合集/分P入口卡。
        modifier = modifier.fillMaxWidth().appElasticPress(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(BiliSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
        ) {
            Text(
                text = "分P列表",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subTitle.isNotBlank()) {
                Text(
                    text = subTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
@Composable
internal fun SeasonSheetContent(
    season: VideoSeason,
    curCid: Long?,
    onOpenEpisode: (VideoTarget) -> Unit
) {
    val initIdx = remember(season, curCid) { seasonSheetIndex(season, curCid) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initIdx)

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
    ) {
        item(
            key = "season_title",
            contentType = "title"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)) {
                Text(
                    text = season.title,
                    style = MaterialTheme.typography.titleLarge
                )
                season.subTitle?.takeIf(String::isNotBlank)?.let { subTitle ->
                    Text(
                        text = subTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        season.sections.forEachIndexed { secIdx, sec ->
            item(
                key = "sec_$secIdx",
                contentType = "section"
            ) {
                if (secIdx > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = BiliSpacing.xs))
                }
                if (sec.title.isNotBlank()) {
                    Text(
                        text = sec.title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = BiliSpacing.xs)
                    )
                }
            }

            items(
                items = sec.eps,
                key = { "ep_${it.cid}_${it.title}" },
                contentType = { "episode" }
            ) { ep ->
                SeasonEpisodeRow(
                    ep = ep,
                    selected = ep.cid == curCid,
                    onClick = {
                        if (ep.cid != curCid) {
                            onOpenEpisode(ep.target)
                        }
                    }
                )
            }
        }
    }
}
@Composable
internal fun PageSheetContent(
    pages: List<VideoPagePart>,
    curCid: Long?,
    onSwitchPage: (Long) -> Unit
) {
    val pageSheetUi = remember(pages) { buildPageSheetUi(pages) }
    val initIdx = remember(pages, curCid) { pageSheetIndex(pageSheetUi, curCid) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initIdx)

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.sm)
    ) {
        item(
            key = "page_title",
            contentType = "title"
        ) {
            Text(
                text = "分P列表",
                style = MaterialTheme.typography.titleLarge
            )
        }

        items(
            items = pageSheetUi.items,
            key = { page -> "page_${page.cid}" },
            contentType = { "page" }
        ) { page ->
            PageSheetRow(
                title = page.title,
                subTitle = page.subTitle,
                selected = page.cid == curCid,
                onClick = {
                    if (page.cid != curCid) {
                        onSwitchPage(page.cid)
                    }
                }
            )
        }
    }
}
@Composable
internal fun SeasonEpisodeRow(
    ep: VideoSeasonEpisode,
    selected: Boolean,
    onClick: () -> Unit
) {
    val rowMod = if (selected) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            // 全局拖拽反馈（第 34 轮 T2）：**未选中**的选集行可点（选中项不可点，
            // 故那一支不挂 —— 按下去没反应的东西不该有形变）。
            // 整行宽 ⇒ 用 0.6 克制档，避免满强度下整行横移。
            .appElasticPress(
                state = rememberAppElasticPressState(feedbackStrength = 0.6f)
            )
            .clickable(onClick = onClick)
    }

    Row(
        modifier = rowMod.padding(vertical = BiliSpacing.smPlus),
        horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
        verticalAlignment = Alignment.Top
    ) {
        ep.cover?.takeIf(String::isNotBlank)?.let { cover ->
            BiliAsyncImage(
                url = cover,
                contentDescription = ep.title,
                modifier = Modifier
                    .width(112.dp)
                    .aspectRatio(16f / 10f)
                    .clip(MaterialTheme.shapes.large),
                contentScale = ContentScale.Crop
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = ep.title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (selected) {
                    CurBadge()
                }
            }
            ep.subTitle?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(modifier = Modifier.padding(top = BiliSpacing.xs))
        }
    }
}
@Composable
internal fun PageSheetRow(
    title: String,
    subTitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val rowMod = if (selected) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            // 同上（分集卡片行）：未选中才可点，整行宽用 0.6 克制档。
            .appElasticPress(
                state = rememberAppElasticPressState(feedbackStrength = 0.6f)
            )
            .clickable(onClick = onClick)
    }

    Column(
        modifier = rowMod.padding(vertical = BiliSpacing.smPlus),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                CurBadge()
            }
        }
        subTitle?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = BiliSpacing.xs))
    }
}
internal fun seasonEntryText(
    season: VideoSeason,
    curCid: Long?
): Triple<String, String, String> {
    val curEp = season.sections
        .asSequence()
        .flatMap { it.eps.asSequence() }
        .firstOrNull { it.cid == curCid }
    return Triple(
        curEp?.title ?: season.title,
        curEp?.subTitle.orEmpty().ifBlank { season.subTitle.orEmpty() },
        "${season.sections.sumOf { it.eps.size }} 个视频"
    )
}
internal fun pageEntryText(
    pages: List<VideoPagePart>,
    curCid: Long?
): Triple<String, String, String> {
    val curIdx = curCid?.let { pages.indexOfFirst { it.cid == curCid } } ?: -1
    val curPage = pages.getOrNull(curIdx)
    return Triple(
        if (curPage != null) buildPageTitle(curIdx, curPage.part) else "查看分P列表",
        curPage?.durationSec?.takeIf { it > 0L }?.let { formatDuration(it * 1000) }.orEmpty(),
        "${pages.size} 个分 P"
    )
}
internal fun seasonSheetIndex(season: VideoSeason, curCid: Long?): Int {
    if (curCid == null) return 0
    var secItemIdx = 1
    season.sections.forEach { sec ->
        val epIdx = sec.eps.indexOfFirst { it.cid == curCid }
        if (epIdx >= 0) {
            return secItemIdx + 1 + epIdx
        }
        secItemIdx += 1 + sec.eps.size
    }
    return 0
}
internal fun pageSheetIndex(
    pageSheetUi: PageSheetUi,
    curCid: Long?
): Int {
    val pageIdx = curCid?.let { targetCid -> pageSheetUi.indexByCid[targetCid] } ?: -1
    return if (pageIdx >= 0) pageIdx + 1 else 0
}
internal fun buildPageSheetUi(pages: List<VideoPagePart>): PageSheetUi {
    val items = ArrayList<PageSheetItem>(pages.size)
    val indexByCid = HashMap<Long, Int>(pages.size)
    pages.forEachIndexed { index, page ->
        items += PageSheetItem(
            cid = page.cid,
            title = buildPageTitle(index, page.part),
            subTitle = page.durationSec.takeIf { it > 0L }?.let { formatDuration(it * 1000) }
        )
        indexByCid[page.cid] = index
    }
    return PageSheetUi(
        items = items,
        indexByCid = indexByCid
    )
}
internal fun buildPageTitle(index: Int, part: String): String {
    return "P${index + 1} $part"
}
internal fun formatPubTime(ts: Long): String {
    return DateFormat.format("yyyy-MM-dd HH:mm", ts * 1000).toString()
}
