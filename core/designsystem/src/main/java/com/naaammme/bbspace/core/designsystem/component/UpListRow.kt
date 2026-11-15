package com.naaammme.bbspace.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing

@Composable
fun <T> UpListRow(
    title: String?,
    items: List<T>,
    key: (T) -> Any,
    name: (T) -> String,
    face: (T) -> String?,
    onClick: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        title?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(bottom = BiliSpacing.sm)
            )
        }
        LazyRow(
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md)
        ) {
            items(
                items = items,
                key = key
            ) { item ->
                Column(
                    modifier = Modifier
                        .width(68.dp)
                        // ## 全局拖拽反馈（2026-10-18 第十九轮，**原语层**接入）
                        //
                        // 接在 `UpListRow` 的条目上：它是首页直播 UP 行与动态页 UP 行的
                        // 共同出口，一处挂载覆盖两处消费方。
                        //
                        // 位置在 `clickable` **之前**（包住整条链）：形变作用于头像 + 名字
                        // 这一整格，而不是只让 42dp 头像单独缩放。窄格（68dp）下
                        // `(width/height).coerceAtMost(1f)` 让横向拉伸收益递减，
                        // 不会把头像格拉成面条。
                        //
                        // 父级是 `LazyRow`：横向滚动的 movement 会被滚动识别器先消费，
                        // 形变随即停手（只留按压缩放），不与翻页手势抢。
                        .appElasticPress()
                        .clickable { onClick(item) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BiliSpacing.xsPlus)
                ) {
                    AvatarImage(
                        url = face(item),
                        contentDescription = name(item),
                        modifier = Modifier.size(42.dp)
                    )
                    Text(
                        text = name(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
