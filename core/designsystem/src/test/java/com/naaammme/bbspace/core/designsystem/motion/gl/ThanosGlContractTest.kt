package com.naaammme.bbspace.core.designsystem.motion.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GL 移植件的**跨语言一致性守卫**。
 *
 * ## 为什么需要它
 *
 * 这些错误**编译期发现不了、JVM 测试也发现不了**，只在真机上表现为"粒子是花的/不动/全黑"：
 *
 * 1. `glGetUniformLocation` 对**不存在的 uniform 名返回 -1**，`glUniform*` 静默失败 ——
 *    着色器里少一个名字、Kotlin 侧拼错一个字母，都不报错，只是效果不对。
 * 2. `#version 300 es` 缺失 ⇒ 与 `GLES30` 的 transform feedback 用法不匹配。
 * 3. stride 常量与缓冲区分配/顶点属性指针必须用**同一个**常量，否则数据错位。
 *
 * 移植时（2026-10-05）静态核对过一遍，这里把它变成测试钉死 ——
 * 因为移植件是 1:1 搬来的，**后人"顺手改个名字"就能悄悄改坏它**。
 */
class ThanosGlContractTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val glDir = File(
        repoRoot,
        "core/designsystem/src/main/java/com/naaammme/bbspace/core/designsystem/motion/gl"
    )
    private val rawDir = File(repoRoot, "core/designsystem/src/main/res/raw")

    private fun shaderText(name: String) = File(rawDir, name).readText()
    private fun rendererText() = File(glDir, "ParticleRenderer.kt").readText()
    private fun policyText() = File(glDir, "ThanosParticlePolicy.kt").readText()

    /** 两个着色器都必须显式声明 GLES3（渲染器用的是 GLES30 + transform feedback）。 */
    @Test
    fun shadersTargetGles3() {
        for (name in listOf("thanos_vertex.glsl", "thanos_fragment.glsl")) {
            val src = shaderText(name)
            assertTrue("$name 必须是 #version 300 es", src.lineSequence().first().trim() == "#version 300 es")
        }
        assertTrue("渲染器必须用 GLES30", rendererText().contains("GLES30"))
    }

    /**
     * **渲染器请求的每一个 uniform 名，都必须真的在着色器里声明。**
     *
     * `uniform("x")` 走到 `glGetUniformLocation`；名字对不上就是 -1 + 静默失败。
     */
    @Test
    fun everyRequestedUniformIsDeclaredInAShader() {
        val renderer = rendererText()
        val requested = Regex("""uniform\("([A-Za-z0-9_]+)"\)""")
            .findAll(renderer)
            .map { it.groupValues[1] }
            .toSortedSet()
        assertTrue("没扫到 uniform 请求，扫描逻辑可能坏了", requested.size >= 10)

        val declared = (shaderText("thanos_vertex.glsl") + "\n" + shaderText("thanos_fragment.glsl"))
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("uniform ") }
            .map { it.removePrefix("uniform ").split(Regex("\\s+")).last().trimEnd(';') }
            .toSet()

        val missing = requested - declared
        assertTrue(
            "以下 uniform 在着色器里没有声明（GL 会返回 -1 并静默失败）：$missing\n" +
                "渲染器请求：$requested\n着色器声明：$declared",
            missing.isEmpty()
        )
    }

    /** 顶点着色器输出的 varying 必须与片元着色器输入的**名字与类型**一致。 */
    @Test
    fun varyingsMatchBetweenVertexAndFragment() {
        fun varyings(src: String, keyword: String) = src.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("$keyword ") && !it.contains("(") }
            .map { it.removePrefix("$keyword ").split(Regex("\\s+")).let { p -> p.last().trimEnd(';') to p.first() } }
            .toSet()

        val out = varyings(shaderText("thanos_vertex.glsl"), "out")
        val inFragment = varyings(shaderText("thanos_fragment.glsl"), "in")
        assertTrue("顶点着色器必须有 varying 输出", out.isNotEmpty())
        assertTrue("片元着色器必须有 varying 输入", inFragment.isNotEmpty())
        val mismatched = inFragment.filterNot { it in out }
        assertTrue("片元读的 varying 在顶点侧不存在或类型不同：$mismatched", mismatched.isEmpty())
    }

    /** stride 常量必须同时用于缓冲区分配与顶点属性指针（错位就会画出花屏）。 */
    @Test
    fun strideConstantIsUsedConsistently() {
        val renderer = rendererText()
        val policy = policyText()
        assertTrue(
            "stride 必须定义在策略里（单一真相）",
            policy.contains("THANOS_PARTICLE_STRIDE_BYTES")
        )
        val uses = Regex("THANOS_PARTICLE_STRIDE_BYTES").findAll(renderer).count()
        assertTrue("渲染器至少要把它用于缓冲分配与属性指针两处（当前 $uses 处）", uses >= 2)
        // 源实现的取值：7 个 float × 4 字节
        assertTrue(
            "stride 应为 28（7 float × 4B），与源一致",
            policy.contains("THANOS_PARTICLE_STRIDE_BYTES = 28")
        )
    }

    /** 时长常量必须与源同值（1.5 缩放秒 / 1.15 时间缩放 / 0.9 尾部秒）。 */
    @Test
    fun timingConstantsMatchTheUpstreamValues() {
        val policy = policyText()
        assertTrue("THANOS_LONGEVITY = 1.5f", policy.contains("THANOS_LONGEVITY = 1.5f"))
        assertTrue("THANOS_TIME_SCALE = 1.15f", policy.contains("THANOS_TIME_SCALE = 1.15f"))
        assertTrue("THANOS_TAIL_SECONDS = 0.9f", policy.contains("THANOS_TAIL_SECONDS = 0.9f"))
        assertEquals(
            "总时长 ≈ (1.5 + 0.9) / 1.15 ≈ 2.087s（源的 2.09s 口径）",
            2.087,
            ((1.5f + 0.9f) / 1.15f).toDouble(),
            0.01
        )
    }

    /** 着色器要从 res/raw 读：两个文件都必须存在且非空。 */
    @Test
    fun shaderAssetsExistAndAreLoadedFromRaw() {
        for (name in listOf("thanos_vertex.glsl", "thanos_fragment.glsl")) {
            val f = File(rawDir, name)
            assertTrue("$name 缺失", f.isFile)
            assertTrue("$name 是空的", f.length() > 200)
        }
        val renderer = rendererText()
        assertTrue(renderer.contains("R.raw.thanos_vertex"))
        assertTrue(renderer.contains("R.raw.thanos_fragment"))
    }
}
