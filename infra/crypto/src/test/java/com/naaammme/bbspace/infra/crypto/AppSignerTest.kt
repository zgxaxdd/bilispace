package com.naaammme.bbspace.infra.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 鉴权签名串的真值钉（T-1：`infra:crypto` 此前零单测）。
 *
 * ## 真值从哪来（不是自洽往返）
 *
 * 期望值由**独立实现**算得：Python `hashlib.md5` 对下面列出的**明文拼接串**求值，
 * 拼接规则写死在测试注释里，任何一处（排序 / 编码白名单 / appsec 拼接）被改都会红。
 * 并且同一份 Python 复现脚本算出的 WBI `mixin_key` 与 B 站公开文档
 * （bilibili-API-collect）的示例值 `ea1db124af3c7062474693fa704f4ff8` 逐字一致，
 * 说明该复现口径与上游协议一致（见 WbiSignerTest）。
 *
 * ## 为什么要测这个
 *
 * 这是全仓最"错了也不炸、只是静默被风控拒绝"的地方：签名串错一个字符，请求返回
 * -352/签名错误，用户看到的是"内容加载失败"。而它此前没有任何测试。
 */
class AppSignerTest {

    @Test
    fun `sign 按 key 排序、注入 appkey、末尾追加 appsec`() {
        val signed = AppSigner.sign(
            params = mapOf("foo" to "114", "bar" to "514"),
            appKey = "testkey",
            appSec = "testsec"
        )
        // 明文： appkey=testkey&bar=514&foo=114  +  testsec   → md5（Python hashlib 核对）
        assertEquals(
            "appkey=testkey&bar=514&foo=114&sign=59e6ee9fb2acf3c8f0dc855f3898e772",
            signed
        )
    }

    @Test
    fun `sign 的 URL 编码白名单是字母数字与 -_~，其余按 UTF-8 逐字节大写 %XX`() {
        val signed = AppSigner.sign(
            params = mapOf("q" to "中 文/&"),
            appKey = "k",
            appSec = "s"
        )
        // 明文： appkey=k&q=%E4%B8%AD%20%E6%96%87%2F%26  +  s   → md5（Python 核对）
        // 注意：空格 → %20（不是 +）、'/' → %2F、'&' → %26、中文按 UTF-8 逐字节大写 hex。
        assertEquals(
            "appkey=k&q=%E4%B8%AD%20%E6%96%87%2F%26&sign=495aec286317648cf3d630b5403ef455",
            signed
        )
    }

    @Test
    fun `sign 保留空值参数（与官方签名行为一致）`() {
        val signed = AppSigner.sign(
            params = mapOf("a" to "", "b" to "1"),
            appKey = "k",
            appSec = "s"
        )
        assertTrue("空值参数必须参与签名串：$signed", signed.startsWith("a=&appkey=k&b=1&sign="))
    }
}
