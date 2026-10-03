package com.naaammme.bbspace.feature.video.player

import com.naaammme.bbspace.core.model.VideoStaff

/**
 * 「联合创作」判定与文案（PT-08，语义移植自上游 BiliPai
 * `PortraitFullscreenOverlay.kt:669-672` 的 coCreators / isCoCreation）。
 *
 * ## 与上游的差异
 * - 上游 `VideoStaff` 带 `mid`：成员可点进主页、可在弹层里关注；
 *   bbspace 的 [VideoStaff] 只有 `name`/`role`（无 mid）→ 弹层只列"昵称 + 分工"，
 *   无头像、无关注动作（要做需先扩模型，另开一项）。
 * - 上游的成员过滤是 `mid != ownerMid`；bbspace 没有 mid/owner 概念入参，
 *   过滤改为"名字非空"（与上游的兜底条件 `it.name.isNotBlank()` 同源）。
 */
internal fun isCoCreation(staffs: List<VideoStaff>): Boolean {
    return staffs.size > 1 && staffs.any { it.name.isNotBlank() }
}

/** 上游模板串逐字（`PortraitFullscreenOverlay.kt:728`）。 */
internal fun coCreationChipText(staffs: List<VideoStaff>): String {
    return "等 ${staffs.size} 人联合创作 ˅"
}

/** 弹层成员行：名字非空的成员才展示（上游兜底条件同源）。 */
internal fun coCreationSheetMembers(staffs: List<VideoStaff>): List<VideoStaff> {
    return staffs.filter { it.name.isNotBlank() }
}
