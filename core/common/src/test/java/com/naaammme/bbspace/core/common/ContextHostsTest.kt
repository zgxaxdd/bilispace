package com.naaammme.bbspace.core.common

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `findHostActivity` 的契约测（PT-09；W19 口径）。
 *
 * ## W19 限制（实测踩过）
 * JVM 单测的 android.jar 是桩：`Context` 的**扩展函数在测试编译期无法对桩接收器解析**
 * （`receiver type mismatch`，实测于本文件 v1）——所以行为真值（沿 ContextWrapper 链
 * 解包到 Activity）只能在真机/Robolectric 验证。这里用**反射**钉住契约本身：
 * 1. 签名存在且形态正确（接收 `Context`、返回 `Activity?`）；
 * 2. 全仓 main 源码里**只允许一处定义**（上游 3 份副本的教训——重复定义迟早漂移）。
 */
class ContextHostsTest {

    @Test
    fun findHostActivitySignatureIsPresent() {
        val methods = Class.forName("com.naaammme.bbspace.core.common.ContextHostsKt")
            .declaredMethods
            .filter { it.name == "findHostActivity" }
        assertTrue("findHostActivity 必须存在（被改名/删除了？）", methods.size == 1)
        val f = methods.single()
        assertTrue(
            "接收器必须是 android.content.Context：${f.parameterTypes.contentToString()}",
            f.parameterTypes.size == 1 && f.parameterTypes[0] == android.content.Context::class.java
        )
        assertTrue(
            "返回类型必须是 Activity?（平台类型）",
            f.returnType == android.app.Activity::class.java
        )
    }

    @Test
    fun hostActivityHasExactlyOneDefinition() {
        val repoRoot = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: File("").absoluteFile
        val mainSources = File(repoRoot, "").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.path.contains("${File.separator}src${File.separator}main${File.separator}") }
            .filterNot { it.path.contains("${File.separator}build${File.separator}") }
            .toList()
        val defs = mainSources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("import ")) {
                    return@mapIndexedNotNull null
                }
                if (line.contains("fun Context.findHostActivity()")) {
                    "${file.name}:${index + 1}"
                } else {
                    null
                }
            }
        }
        assertTrue(
            "findHostActivity 全仓只允许一处定义（上游 3 份副本的教训）：$defs",
            defs.size == 1
        )
    }
}
