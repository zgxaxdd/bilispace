package com.naaammme.bbspace.infra.coldstart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 冷启动阶段耗时的口径测试（BACKLOG PF-03）。
 *
 * 时钟是注入的，所以这里测的是**耗时算术与日志格式本身**，不是时序运气 ——
 * 一个 sleep 出来的测试既慢又飘，飘了还说不清是代码错还是机器忙。
 */
class ColdStartTimingTest {

    /** 按给定序列返回"当前纳秒"，用完就停在最后一个值。 */
    private fun clock(
        vararg millis: Long
    ): () -> Long {
        val ticks = millis.map { it * 1_000_000L }.toMutableList()
        var index = 0
        return {
            val value = ticks.getOrElse(index) { ticks.last() }
            index += 1
            value
        }
    }

    @Test
    fun eachMarkMeasuresTheSliceSinceThePreviousOne() {
        val timing = ColdStartTiming(clock(0, 5, 12, 13, 20))
        timing.mark(ColdStartTiming.BUILD)
        timing.mark(ColdStartTiming.GRPC)
        timing.mark(ColdStartTiming.PARSE)
        val line = timing.render(ColdStartTiming.OUTCOME_OK, ColdStartTiming.CACHE)
        val parsed = ColdStartTiming.parse(line)
        assertEquals(
            mapOf("build" to 5L, "grpc" to 7L, "parse" to 1L, "cache" to 7L, "total" to 20L),
            parsed
        )
        assertTrue("日志行必须以前缀+outcome 开头：$line", line.startsWith("${ColdStartTiming.PREFIX} outcome=ok"))
    }

    /** render 的行必须能被 parse 还原 —— 这就是"可解析"的定义。 */
    @Test
    fun renderedLineRoundTripsThroughTheParser() {
        val timing = ColdStartTiming(clock(0, 2, 40, 41))
        timing.mark(ColdStartTiming.BUILD)
        timing.mark(ColdStartTiming.GRPC)
        val line = timing.render(ColdStartTiming.OUTCOME_ERROR, ColdStartTiming.PARSE)
        val parsed = ColdStartTiming.parse(line)
        assertEquals(2L, parsed["build"])
        assertEquals(38L, parsed["grpc"])
        assertEquals(1L, parsed["parse"])
        assertTrue("必须给出 total 才能跨版本比较：$line", parsed.containsKey(ColdStartTiming.TOTAL))
        assertEquals(41L, parsed[ColdStartTiming.TOTAL])
        assertTrue(line.contains("outcome=error"))
    }

    @Test
    fun slowRunIsFlaggedAndFastRunIsNot() {
        val slow = ColdStartTiming(clock(0, 100, 250, 300, 301), thresholdWarnMs = 300L)
        slow.mark(ColdStartTiming.BUILD)
        assertTrue(slow.render(ColdStartTiming.OUTCOME_OK, ColdStartTiming.GRPC).contains("slow=1"))

        val fast = ColdStartTiming(clock(0, 1, 2, 3, 4), thresholdWarnMs = 300L)
        fast.mark(ColdStartTiming.BUILD)
        assertFalse(fast.render(ColdStartTiming.OUTCOME_OK, ColdStartTiming.GRPC).contains("slow="))
    }

    /** 时钟回拨（理论上不该发生，但 nanoTime 跨设备休眠后可能给怪值）不许产出负数。 */
    @Test
    fun backwardsClockNeverProducesNegativeDurations() {
        val timing = ColdStartTiming(clock(1000, 900, 800))
        timing.mark(ColdStartTiming.BUILD)
        val parsed = ColdStartTiming.parse(timing.render(ColdStartTiming.OUTCOME_EMPTY, ColdStartTiming.GRPC))
        assertTrue("回拨时钟不该产生负数：$parsed", parsed.values.all { it >= 0L })
        assertTrue(parsed.containsKey(ColdStartTiming.TOTAL))
    }

    /** 阶段名保持顺序，读日志的人才能按链路顺序看。 */
    @Test
    fun phaseOrderIsInsertionOrder() {
        val timing = ColdStartTiming(clock(0, 1, 2, 3))
        timing.mark(ColdStartTiming.GRPC)
        timing.mark(ColdStartTiming.BUILD)
        val keys = ColdStartTiming.parse(timing.render(ColdStartTiming.OUTCOME_OK, ColdStartTiming.PARSE))
            .keys.toList()
        assertEquals(listOf("grpc", "build", "parse", "total"), keys)
    }

    /** 解析器对非数字与没有 = 的片段要无害（日志里还有 outcome= / slow=1 这类键）。 */
    @Test
    fun parserIgnoresNonDurationTokens() {
        val parsed = ColdStartTiming.parse("coldstart outcome=ok grpc=12ms slow=1 nonsense total=30ms")
        assertEquals(12L, parsed["grpc"])
        assertEquals(30L, parsed["total"])
        assertEquals("slow=1 也被读成数字是刻意的：它只有 0/1 两个取值", 1L, parsed["slow"])
        assertFalse(parsed.containsKey("outcome"))
        assertFalse(parsed.containsKey("nonsense"))
    }

    /**
     * 接线检查：三条出口（成功/空数据、取消、异常）都要真的出日志。
     * 口径写好了但没人调用 = 冷启动依然不可测，而这在编译期与运行时都不报错。
     *
     * 成功与"拿到空数据"共用一次 render（差别只在 outcome 变量），所以数的是 render
     * **调用点**不少于三处，再单独确认四个出口常量都被用上。
     */
    @Test
    fun clientRendersEveryExitPath() {
        val rel = "infra/coldstart/src/main/java/com/naaammme/bbspace/infra/coldstart/ColdStartClient.kt"
        val file = repoRootFile(rel)
        assertTrue("找不到 $rel", file.isFile)
        val code = file.readText()
        assertTrue("必须实例化计时器", code.contains("val timing = ColdStartTiming()"))
        val renders = Regex("""timing\.render\(""").findAll(code).count()
        assertTrue("render 只有 $renders 处，至少要有成功/取消/异常三条出口", renders >= 3)
        // 查的是**符号名**而不是常量的值：源码里写的是 ColdStartTiming.OUTCOME_OK，
        // 用值（"ok"）去 contains 会在大小写不同的地方漏判或误判
        listOf("OUTCOME_OK", "OUTCOME_EMPTY", "OUTCOME_ERROR", "OUTCOME_CANCELLED").forEach { symbol ->
            assertTrue("出口常量 $symbol 没被客户端使用", code.contains(symbol))
        }
        listOf("BUILD", "GRPC", "PARSE").forEach { symbol ->
            assertTrue("阶段 $symbol 没被 mark", code.contains("mark(ColdStartTiming.$symbol)"))
        }
    }

    private fun repoRootFile(rel: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && File(dir, "settings.gradle.kts").isFile.not()) dir = dir.parentFile
        return File(dir ?: File(""), rel.replace('/', File.separatorChar))
    }
}
