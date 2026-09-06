package com.naaammme.bbspace.core.common

import android.content.Context
import android.content.Intent

/**
 * 系统分享（ACTION_SEND 纯文本）与分享链接构造
 */
object ShareUtils {

    /** 视频链接：优先 BV，其次 av；page 为分 P 序号（从 1 开始，0/1 表示 P1 不带参数） */
    fun videoUrl(bvid: String?, aid: Long, page: Int = 0): String {
        val base = if (!bvid.isNullOrBlank()) {
            "${BiliConstants.BASE_URL_WEB}/video/$bvid/"
        } else {
            "${BiliConstants.BASE_URL_WEB}/av$aid/"
        }
        return if (page > 1) "$base?p=$page" else base
    }

    /** PGC（番剧/国创）分享链接，epId 为分集 id */
    fun pgcUrl(epId: Long): String = "${BiliConstants.BASE_URL_WEB}/bangumi/play/ep$epId/"

    /** 动态分享链接（opus 统一内容页格式） */
    fun dynamicUrl(dynId: String): String = "${BiliConstants.BASE_URL_WEB}/opus/$dynId"

    fun commentUrl(bvid: String?, aid: Long, rpid: Long, page: Int = 0): String {
        val base = videoUrl(bvid, aid, page)
        val separator = if (base.contains('?')) "&" else "?"
        return "$base${separator}comment_root_id=$rpid"
    }

    /** 弹出系统分享面板，文本 = 「【标题】 内容」 */
    fun shareText(context: Context, title: String?, text: String) {
        val content = buildString {
            if (!title.isNullOrBlank()) {
                append("【")
                append(title)
                append("】 ")
            }
            append(text)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }
        context.startActivity(Intent.createChooser(intent, "分享"))
    }
}
