package com.naaammme.bbspace.feature.user.collage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naaammme.bbspace.core.designsystem.component.AvatarImage
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import com.naaammme.bbspace.core.designsystem.theme.asBold
import com.naaammme.bbspace.core.model.User

@Composable
internal fun UserAvatarTileContent(
    user: User?,
    modifier: Modifier = Modifier
) {
    AvatarImage(
        url = user?.avatar?.takeIf(String::isNotBlank),
        contentDescription = user?.name ?: "未登录",
        modifier = modifier.fillMaxSize(),
        fallbackContent = {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                // 业务尺寸（未登录兜底头像的图标直径）：不是间距标度里的一档，保留字面量。
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

@Composable
internal fun UserNameTileContent(
    user: User?,
    modifier: Modifier = Modifier
) {
    val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = BiliSpacing.lg, vertical = BiliSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BiliSpacing.md, Alignment.CenterVertically)
    ) {
        Text(
            text = user?.name ?: "未登录",
            style = MaterialTheme.typography.titleLarge.asBold(),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(BiliSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = user?.coins?.takeUnless { it == 0.0 }?.toString() ?: "--",
                style = MaterialTheme.typography.titleSmall.asBold(),
                color = contentColor
            )
            Text(
                text = "Lv${user?.level ?: 0}",
                style = MaterialTheme.typography.titleSmall.asBold(),
                color = contentColor
            )
        }
    }
}

@Composable
internal fun UserStatTileContent(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(BiliSpacing.smPlus),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.asBold(),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun UserEntryTileContent(
    modifier: Modifier = Modifier,
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    centered: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = BiliSpacing.md, vertical = BiliSpacing.smPlus),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = if (centered) {
            Arrangement.spacedBy(BiliSpacing.xsPlus, Alignment.CenterVertically)
        } else {
            Arrangement.spacedBy(BiliSpacing.xsPlus)
        }
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurface,
                // 业务尺寸（入口图标直径）：语义固定、不随间距标度变化，保留字面量。
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.asBold(),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            textAlign = if (centered) TextAlign.Center else null,
            overflow = TextOverflow.Ellipsis
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = if (centered) TextAlign.Center else null,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
