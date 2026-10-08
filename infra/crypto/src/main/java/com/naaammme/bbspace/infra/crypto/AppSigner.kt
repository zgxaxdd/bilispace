package com.naaammme.bbspace.infra.crypto

import com.naaammme.bbspace.core.common.BiliConstants

/**
 * Bilibili 标准参数签名算法
 *
 * 签名流程：
 * 1. 按 key 字母排序
 * 2. URL encode 拼接 key=value&key=value...
 * 3. 末尾追加 appsec
 * 4. 对整个字符串做 MD5
 */
object AppSigner {

    // bilibili 自定义 URL encode 保留字符: A-Z a-z 0-9 -_.~
    private const val SAFE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    /**
     * bilibili 自定义 URL encode
     * 保留: A-Z a-z 0-9 -_.~
     * 其他字符用 %XX（大写）编码
     */
    private fun biliUrlEncode(s: String): String {
        val sb = StringBuilder()
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            if (c.toChar() in SAFE_CHARS) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    /**
     * 对参数进行签名
     * @param params 参数 Map（原始值，未 URL 编码）
     * @param appKey APP Key（默认使用 TV 版 android_hd）
     * @param appSec APP Secret（默认使用 TV 版 android_hd）
     * @return 签名后的查询字符串（值已 URL 编码，包含 sign 参数）
     */
    fun sign(
        params: Map<String, String>,
        appKey: String = BiliConstants.APP_KEY,
        appSec: String = BiliConstants.APP_SEC
    ): String {
        val mutableParams = params.toMutableMap()
        mutableParams["appkey"] = appKey

        // 保留空值并按 key 排序，与官方签名行为一致
        val sortedParams = mutableParams
            .toSortedMap()

        // URL encode 拼接
        val encodedQueryString = sortedParams.entries.joinToString("&") { (key, value) ->
            "${biliUrlEncode(key)}=${biliUrlEncode(value)}"
        }

        // 追加 appsec 并 MD5（Q-4：原实现是本文件私有的 `md5`，
        // 与 WbiSigner 里那份逐字重复 —— 已收口到 BiliAuthPrimitives.md5Hex）
        val sign = md5Hex(encodedQueryString + appSec)

        return "$encodedQueryString&sign=$sign"
    }
}
