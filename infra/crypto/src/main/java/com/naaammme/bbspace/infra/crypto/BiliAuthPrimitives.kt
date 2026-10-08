package com.naaammme.bbspace.infra.crypto

import bilibili.metadata.locale.LocaleOuterClass
import java.security.MessageDigest

/**
 * 鉴权链路共用的两个原语（Q-4 收口，2026-10-04）。
 *
 * ## 为什么单独收口
 *
 * 审查报告 §2.10 / §8 用"同函数名 + 去空白后函数体完全相同"在全仓比对，得到 5 组逐字重复，
 * 其中**两组落在鉴权链路上**：`md5`（`AppSigner` 与 `WbiSigner` 各一份）与
 * `buildLocaleProtobuf`（`BuvidFetcher` 与 `TicketGenerator` 各一份）。
 *
 * 两份现在内容一致，所以不是 bug；风险是"以后只改一处"—— 而这两组都直接进请求头/签名串，
 * 改漏一处的表现是**请求被风控静默拒绝**（B 站返回 -352 一类），排查成本极高。
 * 因此优先收口这两组（其余三组是跨模块的格式化/合并函数，收益与风险比低，不进本次改动）。
 */

/** 小写 hex 的 MD5（B 站 appkey 签名体系要求小写）。 */
internal fun md5Hex(input: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * 鉴权头里的 locale protobuf（`bilibili.metadata.locale.Locale`）。
 *
 * 两处硬编码 `zh/CN`：这是**接口要求**的语言/地区声明（不是用户可见文案），
 * 所以不要接 `Locale.getDefault()` —— 换语言的用户会因此拿不到正常响应。
 */
internal fun buildLocaleProtobuf(): ByteArray {
    val cLocale = LocaleOuterClass.LocaleIds.newBuilder()
        .setLanguage("zh")
        .setRegion("CN")
        .build()
    val sLocale = LocaleOuterClass.LocaleIds.newBuilder()
        .setLanguage("zh")
        .setRegion("CN")
        .build()

    return LocaleOuterClass.Locale.newBuilder().apply {
        this.cLocale = cLocale
        this.sLocale = sLocale
        timezone = ""
    }.build().toByteArray()
}
