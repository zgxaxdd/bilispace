package com.naaammme.bbspace.feature.home.interest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.naaammme.bbspace.core.designsystem.component.BiliSheetGrabber
import com.naaammme.bbspace.core.designsystem.theme.biliSheetShape
import com.naaammme.bbspace.core.designsystem.theme.BiliSpacing
import androidx.compose.ui.graphics.Color
import com.naaammme.bbspace.core.model.InterestChoose
import com.naaammme.bbspace.core.designsystem.motion.appElasticPress

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InterestDialog(
    data: InterestChoose,
    onDismiss: () -> Unit,
    onConfirm: (Int, String, String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedGenderId by remember { mutableStateOf<Int?>(null) }
    var selectedAgeId by remember { mutableStateOf<Int?>(null) }
    val selected = remember { mutableStateOf(setOf<String>()) }

    val interestPosIds = remember(data) {
        data.items.joinToString(",") { item ->
            if (item.subItems.isEmpty()) "${item.id}"
            else item.subItems.joinToString(",") { sub -> "${item.id}.${sub.id}" }
        }
    }

    fun buildInterestResult(): String {
        val parts = mutableListOf<String>()
        data.items.forEach { item ->
            if (item.subItems.isEmpty()) {
                if ("${item.id}" in selected.value) parts.add("${item.id}")
            } else {
                item.subItems.forEach { sub ->
                    if ("${item.id}.${sub.id}" in selected.value) parts.add("${item.id}.${sub.id}")
                }
            }
        }
        selectedGenderId?.let { parts.add("$it") }
        selectedAgeId?.let { parts.add("$it") }
        return parts.joinToString(",")
    }

    val canConfirm = selected.value.isNotEmpty() || selectedGenderId != null || selectedAgeId != null

    ModalBottomSheet(
        // 弹层形状唯一来源（见 biliSheetShape）：iOS 28dp 只圆上两角，Material M3 默认
        shape = biliSheetShape(),
        // 风格自适应 grabber：iOS 36×5dp 细拉手，Material 保持 M3 默认
        dragHandle = { BiliSheetGrabber() },

        scrimColor = Color.Black.copy(alpha = 0.40f),
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BiliSpacing.lg)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Text(data.title, style = MaterialTheme.typography.titleLarge)
            Text(data.subTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(BiliSpacing.lg))

            if (data.genders.isNotEmpty()) {
                Text(data.genderTitle, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(BiliSpacing.sm))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
                    data.genders.forEach { g ->
                        FilterChip(
                            selected = selectedGenderId == g.id,
                            onClick = { selectedGenderId = if (selectedGenderId == g.id) null else g.id },
                            // 全局拖拽反馈（第 34 轮 T2）：兴趣选择弹层里的性别/年龄芯片。
                            // `FilterChip` 自身仅颜色态、无形变 ⇒ 挂外层不双抖。
                            modifier = Modifier.appElasticPress(),
                            label = { Text(g.title) }
                        )
                    }
                }
                Spacer(Modifier.height(BiliSpacing.lg))
            }

            if (data.ages.isNotEmpty()) {
                Text(data.ageTitle, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(BiliSpacing.sm))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
                    data.ages.forEach { a ->
                        FilterChip(
                            selected = selectedAgeId == a.id,
                            onClick = { selectedAgeId = if (selectedAgeId == a.id) null else a.id },
                            modifier = Modifier.appElasticPress(),
                            label = { Text(a.title) }
                        )
                    }
                }
                Spacer(Modifier.height(BiliSpacing.lg))
            }

            Text("你想看什么", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(BiliSpacing.sm))
            data.items.forEach { item ->
                Text(item.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = BiliSpacing.xs))
                if (item.subItems.isEmpty()) {
                    val key = "${item.id}"
                    FilterChip(
                        selected = key in selected.value,
                        onClick = {
                            selected.value = if (key in selected.value) selected.value - key else selected.value + key
                        },
                        modifier = Modifier.appElasticPress(),
                        label = { Text(item.name) }
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(BiliSpacing.sm)) {
                        item.subItems.forEach { sub ->
                            val key = "${item.id}.${sub.id}"
                            FilterChip(
                                selected = key in selected.value,
                                onClick = {
                                    selected.value = if (key in selected.value) selected.value - key else selected.value + key
                                },
                                modifier = Modifier.appElasticPress(),
                                label = { Text(sub.name) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(BiliSpacing.sm))
            }

            Spacer(Modifier.height(BiliSpacing.lg))
            Button(
                onClick = { onConfirm(data.uniqueId, buildInterestResult(), interestPosIds) },
                enabled = canConfirm,
                // 全局拖拽反馈 + `enabled` 透传（未选够时按钮禁用，形变同步关掉）。
                modifier = Modifier
                    .fillMaxWidth()
                    .appElasticPress(enabled = canConfirm)
            ) {
                Text(data.confirmText.ifEmpty { "确认" })
            }
            Spacer(Modifier.height(BiliSpacing.sm))
        }
    }
}
