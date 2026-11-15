package com.naaammme.bbspace.core.perf

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `rememberChromeBackdropSource` 的**身份稳定性**守卫（上游 `a6288fe48` 的反面教材）。
 *
 * ## 为什么需要这条守卫
 * 上游那次的 bug 是：`remember(backdrop, contentLayer, recordingBackground)` 把
 * **逐帧动画的主题背景色**当成了 `remember` 键。后果是每帧新建一个 backdrop source
 * + modifier 链，而 `drawBackdrop` 对 source/modifier 做引用比较 ⇒ 每帧判不等 ⇒
 * 每帧 `invalidateDrawCache()` ⇒ 背板逐帧重录/重采样（抖动、闪烁、掉帧）。
 *
 * 本仓**当前没有这个失效面**：source 的 `remember` 只认两个稳定实例。但这条不变式
 * **没有任何编译期检查**——将来谁想"顺手把主题色传进去省一次重组"，就会当场把
 * 上游踩过的坑重踩一遍，而且症状（轻微抖动）在无设备时根本发现不了。
 *
 * ## 本守卫只冻两件事（都是**结构性**的，不冻具体实现）
 * 1. `rememberChromeBackdropSource` **必须无参**——一旦接受任何"可能会变"的值，
 *    调用方就可能逐帧传新值进来；
 * 2. 该文件里**不许出现 `recordingBackground`**：那是上游用过的"把动画色当键"的名字，
 *    留在这里等于给下一个改动留了个入口。
 *
 * 断言刻意宽松（只认这两条），避免把"将来想加个 reduceMotion 参数"这种合法演化也误杀。
 */
class ChromeBackdropSourceStructureTest {

    private val repoRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: File("").absoluteFile

    private val source: List<String> by lazy {
        val f = File(
            repoRoot,
            listOf(
                "core", "designsystem", "src", "main", "java", "com", "naaammme", "bbspace",
                "core", "designsystem", "glass", "ChromeBackdropSource.kt"
            ).joinToString(File.separator)
        )
        assertTrue("找不到 ChromeBackdropSource.kt（repoRoot=${repoRoot.absolutePath}）", f.isFile)
        f.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
    }

    @Test
    fun scannerReadsTheSource() {
        assertTrue(
            "ChromeBackdropSource.kt 只读到 ${source.size} 行，路径推导可能失效",
            source.size > 40
        )
    }

    @Test
    fun rememberChromeBackdropSourceTakesNoParameters() {
        val decl = source.firstOrNull { it.contains("fun rememberChromeBackdropSource(") }
            ?: error("找不到 fun rememberChromeBackdropSource(")
        assertTrue(
            "rememberChromeBackdropSource 必须**无参**：它一旦接受可变值（尤其是逐帧动画的" +
                "颜色/进度），调用方就可能每帧传入新值 ⇒ remember 键被打穿 ⇒ 每帧重建" +
                "backdrop source + modifier ⇒ drawBackdrop 每帧失效重录（上游 a6288fe48 的 bug）。" +
                "当前声明：$decl",
            decl.substringAfter("fun rememberChromeBackdropSource(").trimStart().startsWith(")")
        )
    }

    @Test
    fun animatedBackgroundIsNeverAKey() {
        val offenders = source.filter { it.contains("recordingBackground") }
        assertTrue(
            "ChromeBackdropSource.kt 里出现了 `recordingBackground`（上游把它当 remember 键，" +
                "导致背板逐帧重录）。本仓正确的做法是：把会变的东西画在**录制子树内部**" +
                "（子层失效 → contentLayer 重录），而不是重建 source。命中行：$offenders",
            offenders.isEmpty()
        )
    }
}
