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

import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UQualifiedReferenceExpression;

import java.util.List;

/**
 * theme 包**外**不许出现 `N.sp` 字号字面量：字号要走 {@code iOSTypography} /
 * {@code MaterialTheme.typography}。直接写 sp 会绕开字重、行高、字体族这一整套配套值 ——
 * 改主题时它不会跟着变。
 *
 * <p>**这是 A2 的第五条真实守卫迁移**，被替换的是
 * {@code DesignTokenLintTest.spLiteralsOutsideTheme_isFrozen}（数文本棘轮，上限 4）。
 *
 * <p>本仓 theme 包外现存 **4 处**（匹配棘轮上限），全部登记在
 * {@code core/designsystem/lint.xml} 与 {@code feature/home/lint.xml}、
 * {@code feature/rank/lint.xml} 的 {@code <ignore path>} 里。
 *
 * <p>相对计数棘轮的增量：
 * <ul>
 *   <li>**类型解析而不是文本**：只认 {@code androidx.compose.ui.unit.TextUnit} 上的
 *       {@code sp} 扩展属性（javap 核实是 {@code TextUnitKt.sp} 之类的顶层扩展）。
 *       文本正则 {@code \b\d+(\.\d+)?\.sp\b} 会误伤注释里的示例、字符串里的 "12.sp"、
 *       以及任何名为 sp 的局部变量/属性 —— 本仓注释极密，这个误伤是日常成本。</li>
 *   <li>**报位置**：直接给文件:行；棘轮只给一个总数。</li>
 *   <li>**豁免按路径**（{@code lint.xml}），与棘轮的"排除整个 theme 目录"等价，
 *       但不必在测试代码里硬编码目录名。</li>
 * </ul>
 *
 * <p>⚠️ 沿前四轮的教训：**不钉 facade 类名**。{@code sp} 是 {@code androidx.compose.ui.unit}
 * 包下 {@code TextUnit} 的扩展属性，编译后落在某个 {@code *Kt} facade 里；用**包前缀**
 * 判定，facade 之间挪函数也不会静默失效。
 */
public final class SpLiteralDetector extends Detector implements Detector.UastScanner {

    private static final String UNIT_PACKAGE_PREFIX = "androidx.compose.ui.unit.";

    public static final Issue ISSUE =
            Issue.create(
                    "BbspaceSpLiteral",
                    "不要在 theme 外硬编码 .sp 字号",
                    "字号走 iOSTypography / MaterialTheme.typography。直接写 N.sp 会绕开"
                            + "字重、行高、字体族这一整套配套值，改主题时不会跟着变。",
                    Category.CORRECTNESS,
                    /* priority = */ 5,
                    Severity.ERROR,
                    new Implementation(SpLiteralDetector.class, Scope.JAVA_FILE_SCOPE));

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        return List.of(UQualifiedReferenceExpression.class);
    }

    @Override
    public UElementHandler createUastHandler(JavaContext context) {
        return new UElementHandler() {
            @Override
            public void visitQualifiedReferenceExpression(UQualifiedReferenceExpression node) {
                // `12.sp` 解析为一个限定引用：selector 名为 sp、receiver 是数字字面量。
                String selector = node.getSelector().getSourcePsi() == null
                        ? null
                        : node.getSelector().getSourcePsi().getText();
                if (!"sp".equals(selector)) {
                    return;
                }
                if (!(node.getSelector() instanceof org.jetbrains.uast.UReferenceExpression)) {
                    return;
                }
                org.jetbrains.uast.UReferenceExpression ref =
                        (org.jetbrains.uast.UReferenceExpression) node.getSelector();
                if (!(ref.resolve() instanceof PsiMethod)) {
                    // 解析不出方法（例如局部变量叫 sp）⇒ 放行。宁可漏报也不误报：
                    // 误报的代价是往 lint.xml 里加一条本不该有的豁免。
                    return;
                }
                PsiMethod method = (PsiMethod) ref.resolve();
                PsiClass containing = method.getContainingClass();
                String fqn = containing == null ? null : containing.getQualifiedName();
                if (fqn == null || !fqn.startsWith(UNIT_PACKAGE_PREFIX)) {
                    // 不是 Compose 的 TextUnit.sp 扩展 —— 不归我们管。
                    return;
                }
                context.report(
                        ISSUE,
                        node,
                        context.getLocation(node),
                        "不要在 theme 外硬编码 .sp 字号：改用 iOSTypography / "
                                + "MaterialTheme.typography（否则字重/行高/字体族不会跟着主题变）",
                        null);
            }
        };
    }

}
