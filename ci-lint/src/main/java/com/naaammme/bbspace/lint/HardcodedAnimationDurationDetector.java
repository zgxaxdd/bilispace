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
import org.jetbrains.uast.UExpression;

import java.util.List;
import java.util.Set;

/**
 * 禁用硬编码动画时长/参数：{@code tween(150)} / {@code spring(0.5f, 300f)} 这类字面量
 * 让「动效速度」档位与 {@code reduceMotion}（系统"移除动画"）在这些动画上失效 ——
 * 那是无障碍问题，不只是观感。应改用 {@code LocalAnimations.current} 的
 * short/medium/long/extraLong/morph。
 *
 * <p>**这是 A2 的第二条真实守卫迁移**：替换
 * {@code AnimationTokenRatchetTest.hardcodedDurationsAreFrozen}（数文本棘轮，
 * 上限 4 且要求命中项全部来自 {@code DissolvableSurface.kt}）。
 * 迁移动作四步：① 检测器；② 等价性证明；③ 存量打基线；④ 删棘轮。
 *
 * <p>**为什么这条比 {@link AnimateAsStateDetector} 更适合迁移**：棘轮的形态是
 * "上限 4 + 命中项必须全部来自某一个文件"，翻译成 Lint 就是"那一个文件
 * {@code <ignore path>}、别处一律 ERROR"——**一条豁免对一条规则**，形态完全对应。
 * 而"存量 957 处"的那种（硬编码 dp）就得先决定 baseline 怎么打，噪声会埋掉机制问题。
 *
 * <p>判据三层，全部靠**类型解析**而不是文本：
 * <ol>
 *   <li>方法名是 {@code tween} 或 {@code spring}；</li>
 *   <li>解析后的 containing class 在 {@code androidx.compose.animation.core} 包下
 *       （实测住在 {@code AnimationSpecKt}；**不钉类名** —— Compose 在 facade 之间
 *       挪函数是常事，钉类名就是给自己埋"静默失效"的雷，见 AnimateAsStateDetector 的踩坑记录）；</li>
 *   <li>**第一个参数是数字字面量**（{@code tween(80)} / {@code tween(durationMillis = 80)}
 *       都算）。这正是棘轮正则 {@code (tween|spring)\(\s*(durationMillis\s*=\s*)?\d}
 *       的语义，用常量求值而不是文本匹配来实现。</li>
 * </ol>
 */
public final class HardcodedAnimationDurationDetector extends Detector implements Detector.UastScanner {

    private static final Set<String> NAMES = Set.of("tween", "spring");

    /**
     * Compose 的 {@code tween} / {@code spring} 是 Kotlin 顶层函数，编译后落在
     * {@code androidx.compose.animation.core} 包下的某个 {@code *Kt} facade 里
     * （实测为 {@code AnimationSpecKt}）。**用包前缀而不是类名**，理由见类注释。
     */
    private static final String ANIMATION_CORE_PREFIX = "androidx.compose.animation.core.";

    public static final Issue ISSUE =
            Issue.create(
                    "BbspaceHardcodedAnimationDuration",
                    "不要硬编码动画时长/参数",
                    "tween(150) / spring(0.5f, 300f) 这类字面量会让「动效速度」档位与 "
                            + "reduceMotion（系统「移除动画」）在这些动画上失效 —— 那是无障碍问题，"
                            + "不只是观感。请改用 LocalAnimations.current 的 "
                            + "short/medium/long/extraLong/morph。",
                    Category.A11Y,
                    /* priority = */ 6,
                    Severity.ERROR,
                    new Implementation(
                            HardcodedAnimationDurationDetector.class, Scope.JAVA_FILE_SCOPE));

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        return List.of(UCallExpression.class);
    }

    @Override
    public UElementHandler createUastHandler(JavaContext context) {
        return new UElementHandler() {
            @Override
            public void visitCallExpression(UCallExpression call) {
                String name = call.getMethodName();
                if (name == null || !NAMES.contains(name)) {
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
                if (fqn == null || !fqn.startsWith(ANIMATION_CORE_PREFIX)) {
                    // 别的库里同名的 tween/spring —— 不归我们管。
                    return;
                }
                // 第一个参数必须是**数字字面量**。evaluate() 只对常量表达式返回值，
                // 变量/计算表达式返回 null ⇒ 天然只命中字面量，不会误报
                // "tween(someVar)" 这种本该放行的情况。
                List<UExpression> args = call.getValueArguments();
                if (args.isEmpty()) {
                    return;
                }
                Object first = args.get(0).evaluate();
                if (!(first instanceof Number)) {
                    return;
                }
                context.report(
                        ISSUE,
                        call,
                        context.getLocation(call),
                        "不要硬编码 " + name + " 的参数（首参 = " + first + "）："
                                + "改用 LocalAnimations.current 的 short/medium/long/extraLong/morph",
                        null);
            }
        };
    }

}
