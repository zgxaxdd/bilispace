package com.naaammme.bbspace.lint;

import com.android.tools.lint.client.api.UElementHandler;
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
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.visitor.AbstractUastVisitor;

import java.util.List;
import java.util.Map;

/**
 * {@code runBlocking} 必须自带上限：只允许两种形态 ——
 * ① 外层套 {@code withTimeoutOrNull(...)}；② 外层套 {@code runCatching { }}。
 *
 * <p>**这是 A2 的第四条真实守卫迁移**，被替换的是
 * {@code FrameBudgetLintTest.runBlocking_isFrozen}（数文本棘轮，上限 2）。
 *
 * <p>本仓现存 2 处（{@code MainActivity.preloadThemeConfig} 与
 * {@code VideoDetailActivity.preloadThemeConfig}）**都满足形态①**，所以是**零基线**：
 * 检测器报告 0，一条 {@code <lint.xml>} 豁免都不用写。这与第三条迁移（Path/Outline）同型。
 *
 * <p>相对计数棘轮的增量 —— 棘轮自己承认的那件事：它的口径描述写错过一轮
 * （注释里留着勘误："本注释此前写'与 MainActivity 的两处同源同目的'，与代码不符
 * ……交接数字未复核就以讹传讹"）。计数棘轮**只知道有几处、不知道那几处长什么样**；
 * 换成"必须带超时/兜底"之后，"数量对但漏了超时"这种情形第一次变得可判。
 *
 * <p>判据是**形状**而不是数字：{@code runBlocking} 的第一个参数（lambda）体内，
 * 直接出现 {@code withTimeoutOrNull(} 或 {@code runCatching(} 调用。
 * 嵌套在别的 lambda 里也算（本仓两处都是 {@code runBlocking { withTimeoutOrNull(...) { … } } }）。
 *
 * <p>⚠️ 与第三条迁移同样的教训提前用上：{@code runBlocking} 是
 * {@code kotlinx.coroutines} 的**顶层函数**（javap 核实住在
 * {@code kotlinx.coroutines.BuildersKt}），**不是** CoroutineScope 上的方法。
 * 所以判据用「包前缀 {@code kotlinx.coroutines.} + 方法名」，不钉 facade 类名
 * （{@code BuildersKt} / {@code BuildersKt__Builders_commonKt} 这类内部 facade 名
 * 在协程版本间变过，钉了就又是"静默失效"）。
 */
public final class RunBlockingNeedsBoundDetector extends Detector implements Detector.UastScanner {

    private static final String COROUTINES_PREFIX = "kotlinx.coroutines.";

    /**
     * 可接受的上限形态 → 它们**各自**所属的包前缀。
     *
     * ⚠️ 不能共用一个前缀：{@code withTimeoutOrNull} / {@code withTimeout} 是
     * {@code kotlinx.coroutines.TimeoutKt}（javap 核实），而 {@code runCatching} 是
     * **Kotlin 标准库**的 {@code kotlin.ResultKt}（javap 核实）—— 它在
     * {@code kotlinx.coroutines} 包里根本不存在。第一版给三者写了同一个协程包前缀，
     * 那会让 {@code runCatching} 这一支**永远匹配不上**（又是静默失效）。
     *
     * 本仓现存 2 处用的是形态①（{@code withTimeoutOrNull}），{@code runCatching}
     * 只是外层兜底、并不在 {@code runBlocking} 体内 —— 列它是给"只包兜底不设上限"
     * 的写法留一条合规路径，但**从当前位置看它不在任何现存调用点内**，所以不影响基线。
     */
    private static final Map<String, String> BOUND_FORMS =
            Map.of(
                    "withTimeoutOrNull", "kotlinx.coroutines.",
                    "withTimeout", "kotlinx.coroutines.",
                    "runCatching", "kotlin.");

    public static final Issue ISSUE =
            Issue.create(
                    "BbspaceUnboundedRunBlocking",
                    "runBlocking 必须带超时或兜底",
                    "runBlocking 会阻塞当前线程：主线程上直接掉帧、启动路径上拖慢冷启。"
                            + "必须在外层套 withTimeoutOrNull(...)（给上限）+ runCatching（异常回退）"
                            + "的至少一种，绝不允许无界阻塞。",
                    Category.PERFORMANCE,
                    /* priority = */ 6,
                    Severity.ERROR,
                    new Implementation(RunBlockingNeedsBoundDetector.class, Scope.JAVA_FILE_SCOPE));

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        return List.of(UCallExpression.class);
    }

    @Override
    public UElementHandler createUastHandler(JavaContext context) {
        return new UElementHandler() {
            @Override
            public void visitCallExpression(UCallExpression call) {
                if (!"runBlocking".equals(call.getMethodName())) {
                    return;
                }
                PsiMethod resolved = call.resolve();
                if (resolved == null) {
                    return;
                }
                PsiClass containing = resolved.getContainingClass();
                if (containing == null) {
                    return;
                }
                String fqn = containing.getQualifiedName();
                if (fqn == null || !fqn.startsWith(COROUTINES_PREFIX)) {
                    // 别的库里同名的 runBlocking —— 不归我们管。
                    return;
                }
                if (isBounded(call)) {
                    return;
                }
                context.report(
                        ISSUE,
                        call,
                        context.getLocation(call),
                        "runBlocking 必须在外层套 withTimeoutOrNull(...)（给上限）"
                                + "或 runCatching（异常回退）—— 无界阻塞会在主线程掉帧、拖慢冷启。",
                        null);
            }
        };
    }

    /**
     * {@code runBlocking} 的唯一 lambda 参数就是它的尾随 lambda —— 而**尾随 lambda 不在
     * {@code getValueArguments()} 里**（见 {@link #containsBoundForm} 的踩坑记录）。
     * 所以这里直接从**整个调用表达式**出发遍历子树，而不是从实参表出发。
     */
    private static boolean isBounded(UCallExpression call) {
        return containsBoundForm(call);
    }

    /**
     * lambda 体内（任意深度）是否出现了带界的调用形态。
     *
     * ⚠️ **不能用 {@code getValueArguments()} 递归**：Kotlin 的**尾随 lambda** 不在实参表里
     * （第一版就是这么写的，结果本仓两处现存调用点**全被误报** —— `runBlocking { … }`
     * 的 `runBlocking` 拿到的实参表是空的，`withTimeoutOrNull(...) { … }` 同理）。
     * 改用 UAST 自带的深度遍历：{@code accept(new AbstractUastVisitor(){...})}，
     * 它会把子树里所有节点都过一遍，与 lambda 的书写位置无关。
     */
    private static boolean containsBoundForm(UElement element) {
        if (element == null) {
            return false;
        }
        final boolean[] found = {false};
        element.accept(
                new AbstractUastVisitor() {
                    @Override
                    public boolean visitCallExpression(UCallExpression call) {
                        String name = call.getMethodName();
                        String prefix = name == null ? null : BOUND_FORMS.get(name);
                        if (prefix != null && isInPackage(call, prefix)) {
                            found[0] = true;
                            // true = 已处理，不再深入该节点；找到就够了。
                            return true;
                        }
                        return super.visitCallExpression(call);
                    }
                });
        return found[0];
    }

    /** 名字对上之后还要包对上，挡掉用户自定义的同名函数。 */
    private static boolean isInPackage(UCallExpression call, String packagePrefix) {
        PsiMethod m = call.resolve();
        if (m == null) {
            return false;
        }
        PsiClass cls = m.getContainingClass();
        String fqn = cls == null ? null : cls.getQualifiedName();
        return fqn != null && fqn.startsWith(packagePrefix);
    }

}
