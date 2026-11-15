package com.naaammme.bbspace.core.settings

import com.naaammme.bbspace.core.model.player.CdnRegionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CdnRegionConfigCodec] 的往返/脏数据单测（W4 收口）。
 *
 * 钉住四件事：
 * 1. **往返可逆**：host 列表与规则列表编码后再读回，逐字段相等（顺序也必须保持）；
 * 2. **版本门**：版本头不对 ⇒ 整体按空处理（不冒险解释旧格式）；
 * 3. **脏数据只废一行**：字段数不对 / pattern 为空 ⇒ 跳过该行，其余规则仍生效；
 * 4. **元字符清洗**：pattern/replacement 里的制表符/换行会被清掉，避免"一条规则读成两条"。
 */
class CdnRegionConfigCodecTest {

    @Test
    fun `hosts round trip keeps order and drops blanks`() {
        val hosts = listOf("upos-a.bilivideo.com", " upos-b.bilivideo.com ", "", "upos-a.bilivideo.com")
        val encoded = CdnRegionConfigCodec.encodeHosts(hosts)
        assertEquals(
            "编码：一行一个、去空、去重、保持顺序",
            "upos-a.bilivideo.com\nupos-b.bilivideo.com",
            encoded
        )
        assertEquals(
            listOf("upos-a.bilivideo.com", "upos-b.bilivideo.com"),
            CdnRegionConfigCodec.decodeHosts(encoded)
        )
    }

    @Test
    fun `null and blank host payload decode to empty`() {
        assertTrue(CdnRegionConfigCodec.decodeHosts(null).isEmpty())
        assertTrue(CdnRegionConfigCodec.decodeHosts("").isEmpty())
        assertTrue(CdnRegionConfigCodec.decodeHosts("\n \n").isEmpty())
    }

    @Test
    fun `rules round trip keeps all three fields`() {
        val rules = listOf(
            CdnRegionPolicy.CustomRule("upos-sz-mirrorcos", "upos-sz-mirrorcosov", enabled = true),
            CdnRegionPolicy.CustomRule("upos-hz", "upos-hz-mirrorakam", enabled = false)
        )
        val decoded = CdnRegionConfigCodec.decodeRules(CdnRegionConfigCodec.encodeRules(rules))
        assertEquals(rules, decoded)
    }

    @Test
    fun `empty rule list encodes to empty string`() {
        assertEquals("", CdnRegionConfigCodec.encodeRules(emptyList()))
        assertTrue(CdnRegionConfigCodec.decodeRules("").isEmpty())
        // 只有 pattern 空白的规则也不该被写出去。
        assertEquals(
            "",
            CdnRegionConfigCodec.encodeRules(listOf(CdnRegionPolicy.CustomRule("   ", "x")))
        )
    }

    @Test
    fun `wrong version header discards everything`() {
        val raw = "v2\n1\tupos-a\tupos-b"
        assertTrue(
            "版本不对时要整体按空处理，不许按当前格式猜着读",
            CdnRegionConfigCodec.decodeRules(raw).isEmpty()
        )
    }

    @Test
    fun `malformed line only skips that line`() {
        val raw = buildString {
            append("v1")
            append("\n1\tupos-a\tupos-b")     // 合法
            append("\n1\tonly-two-fields")     // 字段数不对 ⇒ 跳过
            append("\n1\t   \tupos-c")         // pattern 空白 ⇒ 跳过
            append("\n0\tupos-d\tupos-e")      // 合法（禁用态）
        }
        val decoded = CdnRegionConfigCodec.decodeRules(raw)
        assertEquals("只保留两条合法规则", 2, decoded.size)
        assertEquals("upos-a", decoded[0].pattern)
        assertTrue("第一条必须是启用态", decoded[0].enabled)
        assertEquals("upos-d", decoded[1].pattern)
        assertEquals("第二条是禁用态", false, decoded[1].enabled)
    }

    @Test
    fun `separator characters are stripped from fields`() {
        val dirty = listOf(
            CdnRegionPolicy.CustomRule("upos\ta", "upos\nb", enabled = true)
        )
        val encoded = CdnRegionConfigCodec.encodeRules(dirty)
        // 元字符被清掉后，整份配置仍然只有"版本头 + 一行"。
        assertEquals("编码里不许出现多余的规则分隔符", 1, encoded.count { it == '\n' })
        val decoded = CdnRegionConfigCodec.decodeRules(encoded)
        assertEquals(1, decoded.size)
        assertEquals("uposa", decoded[0].pattern)
        assertEquals("uposb", decoded[0].replacement)
    }
}
