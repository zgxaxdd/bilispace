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

import java.util.List;
import java.util.regex.Pattern;

/**
 * 禁用 {@code animate*AsState}：它的值在**组合期**读取，动画每帧都会重组宿主 composable。
 * 正确做法是 {@code Animatable} + 在 {@code graphicsLayer{}} / {@code drawBehind{}} 块内读
 * {@code value}，让每帧只更新图层属性或重绘，不触发重组。
 *
 * <p>**这是 A2 的第一条真实守卫迁移**：替换
 * {@code FrameBudgetLintTest.animateAsState_isFrozen}（数文本棘轮，上限 10）。
 * 迁移动作三步：① 检测器；② 按已知 10 处打 {@code lint.xml} 路径豁免；
 * ③ 删掉棘轮。删之前先用本检测器**证明找到的正是同样那 10 处**（等价性证明），
 * 否则就是拿覆盖换机制。
 *
 * <p>相对数文本的三个增量：
 * <ul>
 *   <li>**报位置不报数**：棘轮只给"当前 10 处、上限 10 处"，加第 11 处时你得自己找在哪；
 *       检测器直接给文件:行。</li>
 *   <li>**类型解析**：只认 {@code androidx.compose.animation.core.AnimationKt} 里的
 *       {@code animate*AsState}。别处同名的函数（自己的封装、别的库）不算 ——
 *       数文本的正则分不清，只能误报。</li>
 *   <li>**认调用不认定义**：{@code getApplicableUastTypes} 只派发调用表达式。</li>
 * </ul>
 *
 * <p>⚠️ 名字匹配用正则 {@code animate[A-Za-z]*AsState}（与棘轮同形）而不是列举：
 * 列举会在 Compose 新增一个变体时静默漏掉，而正则与棘轮语义一致。
 */
public final class AnimateAsStateDetector extends Detector implements Detector.UastScanner {

    private static final Pattern NAME = Pattern.compile("animate[A-Za-z]*AsState");

    /**
     * Compose 的 {@code animate*AsState} 一族都是 **Kotlin 顶层函数**，编译后落在
     * {@code androidx.compose.animation.*} 下的某个 {@code *Kt} 文件facade 里。
     *
     * ⚠️ **不要钉死某一个 facade 类名**：第一版写的是
     * {@code androidx.compose.animation.core.AnimationKt}，实测**一条都没命中** ——
     * 这个 Compose 版本（animation-core 1.12.1）里它们住在
     * {@code AnimateAsStateKt}（javap 已核实）。Compose 在 facade 之间挪函数是常事，
     * 钉类名就是给自己埋一个"静默失效"的雷（这正是本规则要防的那类失败）。
     *
     * 所以判据用**包前缀**：{@code androidx.compose.animation.} 开头的顶层函数才可能是
     * 它们。用户自己的 {@code animateFooAsState} 在自己的包里，天然被排除。
     */
    private static final String ANIMATION_PACKAGE_PREFIX = "androidx.compose.animation.";

    public static final Issue ISSUE =
            Issue.create(
                    "BbspaceAnimateAsState",
                    "不要用 animate*AsState",
                    "animate*AsState 的值在组合期读取，动画每帧都会重组宿主 composable。"
                            + "正确做法是 Animatable + 在 graphicsLayer{}/drawBehind{} 块内读 value，"
                            + "让每帧只更新图层属性或重绘，不触发重组。",
                    Category.PERFORMANCE,
                    /* priority = */ 5,
                    Severity.ERROR,
                    new Implementation(AnimateAsStateDetector.class, Scope.JAVA_FILE_SCOPE));

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        return List.of(UCallExpression.class);
    }

    @Override
    public UElementHandler createUastHandler(JavaContext context) {
        // context 由 createUastHandler 传入，闭包捕获即可 —— UElementHandler 的
        // visitCallExpression 只有一个参数，拿不到 context。
        return new UElementHandler() {
            @Override
            public void visitCallExpression(UCallExpression call) {
                String name = call.getMethodName();
                if (name == null || !NAME.matcher(name).matches()) {
                    return;
                }
                PsiMethod resolved = call.resolve();
                if (resolved == null) {
                    // 解析不出来（比如同名的本地函数）⇒ 不放行。宁可漏报也不误报：
                    // 这条规则的代价是"每次新增都要加豁免"，误报会让豁免文件失控。
                    return;
                }
                PsiClass containing = resolved.getContainingClass();
                if (containing == null) {
                    return;
                }
                String fqn = containing.getQualifiedName();
                if (fqn == null || !fqn.startsWith(ANIMATION_PACKAGE_PREFIX)) {
                    // 自定义封装 / 别的库的同名函数 —— 不归我们管。
                    return;
                }
                context.report(
                        ISSUE,
                        call,
                        context.getLocation(call),
                        "不要用 " + name + "：值在组合期读取，动画每帧都会重组宿主 composable。"
                                + "改用 Animatable + 在 graphicsLayer{}/drawBehind{} 块内读 value",
                        null);
            }
        };
    }

}
