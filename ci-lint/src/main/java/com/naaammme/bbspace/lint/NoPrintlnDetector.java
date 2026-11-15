package com.naaammme.bbspace.lint;

import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;

import org.jetbrains.uast.UCallExpression;

import java.util.Collections;
import java.util.List;

/**
 * 禁用 {@code println}：Android 上它直接写 stdout，没有 tag、不过项目的 Logger 管道，
 * release 包里也看不见。应改用 {@code core/common} 的 Logger（带 tag 与级别）。
 *
 * <p>**为什么这条适合当 A2 的第一条规则**：本仓 main 源码对 {@code println(} 的命中
 * 为 0（已 grep 实测）。零基线意味着 lint 报告除了我们故意种下的样本外应当是干净的 ——
 * 这给「检测器真的跑起来了」一个无噪声的信号。换成「硬编码 dp」那种 957 处命中的规则，
 * 就得先做 baseline / 路径豁免，噪声会把机制问题埋掉。
 *
 * <p>判据是**类型解析**而不是名字匹配：只认 Kotlin 标准库的
 * {@code kotlin.io.ConsoleKt.println}。自定义的同名函数、{@code System.out.println}、
 * 别的库里的 {@code println} 都不归我们管 —— 这正是 Lint 相对「扫源码数次数」的
 * 核心优势：数文本的守卫分不清这些，只能误报或漏报。
 */
public final class NoPrintlnDetector extends Detector implements Detector.UastScanner {

    /** Kotlin 顶层 {@code println} 编译后落在 {@code kotlin.io.ConsoleKt}。 */
    private static final String KOTLIN_CONSOLE_Kt = "kotlin.io.ConsoleKt";

    public static final Issue ISSUE =
            Issue.create(
                    "BbspaceNoPrintln",
                    "不要用 println 输出日志",
                    "println 在 Android 上直接写 stdout：没有 tag、不过项目的 Logger 管道，"
                            + "release 包里也看不到。请改用 core/common 的 Logger（带 tag 与级别）。",
                    Category.CORRECTNESS,
                    /* priority = */ 6,
                    Severity.ERROR,
                    new Implementation(NoPrintlnDetector.class, Scope.JAVA_FILE_SCOPE));

    @Override
    public List<String> getApplicableMethodNames() {
        return Collections.singletonList("println");
    }

    /**
     * 用 {@code visitMethodCall} 而不是同名的 {@code visitMethod}：后者在 lint-api 32.x
     * 已标 deprecated（已实测编译警告），换个名字而已，语义完全相同。
     */
    @Override
    public void visitMethodCall(JavaContext context, UCallExpression call, PsiMethod method) {
        PsiClass containing = method.getContainingClass();
        if (containing == null || !KOTLIN_CONSOLE_Kt.equals(containing.getQualifiedName())) {
            // 自定义 println / System.out.println / 其它库的同名函数 —— 不归我们管。
            // 名字相同的调用点归别人拥有，正是类型解析才分得清、数文本分不清的那部分。
            return;
        }
        // 参数个数不限：println() / println(x) / println(a, b) 一并不许。
        int argCount = call.getValueArgumentCount();
        context.report(
                ISSUE,
                call,
                context.getLocation(call),
                "不要用 println 输出日志（" + argCount + " 个参数）：改用 core/common 的 Logger",
                null);
    }

}
