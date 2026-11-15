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
import org.jetbrains.uast.UMethod;

import java.util.List;
import java.util.Set;

/**
 * {@code Path()} / {@code Outline.*} 只许出现在形状自己的 {@code createOutline} /
 * {@code build*Path} 里。
 *
 * <p>**这是 A2 的第三条真实守卫迁移**，也是第一条**不需要任何基线**的迁移：
 * 被替换的 {@code ListAllocationStructureTest.pathAndOutlineAllocations_areFrozenAtMeasuredBaseline}
 * 是"全仓不超过 4 处"的计数棘轮（4 处全在 {@code UserCollageCanvas} 的
 * {@code createOutline}/{@code build*Path} 里）。换成"按**所在函数**判定"的检测器后，
 * 现存 4 处**全部合规** ⇒ 报告为 0，一条 {@code <ignore>} 都不用写。
 *
 * <p>相对计数棘轮的三个增量：
 * <ul>
 *   <li>**判据从"总量"变成"位置"**：棘轮说"总共不超过 4 处"，于是别处再添 3 处仍绿；
 *       检测器说"只能在 createOutline / build*Path 里"，别处添 1 处就红。</li>
 *   <li>**类型解析**：只认 {@code androidx.compose.ui.graphics.Path} 的构造与
 *       {@code androidx.compose.ui.graphics.Outline} 的工厂。文本正则
 *       {@code \bPath\(\s*\)} 分不清 {@code android.graphics.Path()}（那是另一套 API，
 *       常量的用法也不一样）。</li>
 *   <li>**报位置**：直接给文件:行。</li>
 * </ul>
 *
 * <p>W7 的教训（原测试的注释里写着）：**棘轮的覆盖面 = 它真正会红的写法集合**，
 * 不是它的名字。这条检测器把覆盖面钉在"所在函数"上，比钉在"出现次数"上更接近规则本意。
 */
public final class PathOutlineAllocationDetector extends Detector implements Detector.UastScanner {

    private static final String GRAPHICS_PACKAGE_PREFIX = "androidx.compose.ui.graphics.";

    /**
     * {@code Outline} 及其嵌套类的前缀。
     *
     * ⚠️ **实测踩坑（本轮第二次）**：{@code Outline.Generic(...)} 这一类写法在不同
     * Compose 版本/不同 UAST 解析路径下有两种形态 ——
     * ① 嵌套类构造器（containing class = {@code androidx.compose.ui.graphics.Outline$Generic}）；
     * ② 抽象类上的静态工厂（containing class = {@code ...Outline}，方法名 {@code Generic}）。
     * javap 核实 1.12.1 是形态①（{@code Outline} 是抽象类、无静态工厂，嵌套类是
     * {@code Outline$Generic}/{@code Outline$Rectangle}/{@code Outline$Rounded}），
     * 但**只认一种形态就会静默漏另一半**。
     * 所以这里用「前缀 + 方法名集合」同时覆盖两种形态。
     */
    private static final String OUTLINE_PREFIX = "androidx.compose.ui.graphics.Outline";

    /**
     * {@code Outline} 的嵌套类名 = 它的全部构造入口。{@code Outline} 是 sealed class，
     * 嵌套类是封闭集合（Generic / Rectangle / Rounded；ConvexPolygon / RoundedRect 是
     * 旧版 API，一并列上以防回归）。**若 Compose 新增嵌套 Outline 类，往这里加一个名字** ——
     * 这是少数适合列举的场景：集合本身封闭且小。
     */
    private static final Set<String> OUTLINE_NESTED_NAMES =
            Set.of("Generic", "Rectangle", "Rounded", "ConvexPolygon", "RoundedRect");

    /**
     * 允许出现这两类分配的宿主函数名。
     *
     * {@code createOutline} 是 {@code Shape} 接口的方法（形状 API 要求在这里返回
     * Outline）；{@code build*Path} 是本仓画布形状自己拼路径的私有工厂
     * （{@code UserCollageCanvas.buildPolygonPath/buildStarPath}）。
     * 判据用"前缀 build + 后缀 Path"而不是列举两个名字：新增一种画布形状时
     * 照命名规范写就天然合规，不需要回来改检测器。
     */
    private static boolean isAllowedHost(String functionName) {
        if (functionName == null) {
            // 顶层属性初始化 / 非函数体 —— 不属于形状自己的构建路径。
            return false;
        }
        return "createOutline".equals(functionName)
                || (functionName.startsWith("build") && functionName.endsWith("Path"));
    }

    public static final Issue ISSUE =
            Issue.create(
                    "BbspacePathOutlineAllocation",
                    "Path()/Outline.* 只能出现在 createOutline / build*Path 里",
                    "在别处分配 Path()/Outline.* 等于每次调用/每次组合/每次重绘各拿一份。"
                            + "Shape 的实现请把分配放在 createOutline（或自己的 build*Path 工厂）里，"
                            + "并由调用方 remember 形状本身。",
                    Category.PERFORMANCE,
                    /* priority = */ 5,
                    Severity.ERROR,
                    new Implementation(PathOutlineAllocationDetector.class, Scope.JAVA_FILE_SCOPE));

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        return List.of(UCallExpression.class);
    }

    @Override
    public UElementHandler createUastHandler(JavaContext context) {
        return new UElementHandler() {
            @Override
            public void visitCallExpression(UCallExpression call) {
                PsiMethod resolved = call.resolve();
                if (resolved == null) {
                    return;
                }
                PsiClass containing = resolved.getContainingClass();
                if (containing == null) {
                    return;
                }
                String fqn = containing.getQualifiedName();
                if (fqn == null || !fqn.startsWith(GRAPHICS_PACKAGE_PREFIX)) {
                    // 别的包里的 Path/Outline（例如 android.graphics.Path）—— 不归我们管。
                    // 这正是类型解析相对文本正则 \bPath\(\s*\) 的增量：那个正则分不清两套 API。
                    return;
                }
                // Path() 是**顶层工厂函数**（实测住在 AndroidPath_androidKt，即 actual 实现），
                // 不是 Path 接口的构造器 —— javap 核实 Path 是 interface、Companion 里只有 combine。
                // 同样不钉 facade 类名：Compose 换 actual 实现就会换类名。
                boolean isPathFactory = "Path".equals(resolved.getName());
                // Outline.Generic(...) 是**嵌套类构造器**（javap 已核实，见 OUTLINE_PREFIX 注释）。
                // 判据同时容纳"嵌套类构造器"与"抽象类静态工厂"两种解析形态。
                boolean isOutlineCtor =
                        fqn.startsWith(OUTLINE_PREFIX)
                                && OUTLINE_NESTED_NAMES.contains(resolved.getName());
                if (!isPathFactory && !isOutlineCtor) {
                    return;
                }
                // 向上找宿主函数。用 UAST 的 parent 链而不是 PSI：Kotlin 侧没有 PsiMethod，
                // 只有 UAST 能把 KtNamedFunction 与 PsiMethod 统一成 UMethod。
                String host = null;
                for (UElement p = call.getUastParent(); p != null; p = p.getUastParent()) {
                    if (p instanceof UMethod) {
                        host = ((UMethod) p).getName();
                        break;
                    }
                }
                if (isAllowedHost(host)) {
                    return;
                }
                context.report(
                        ISSUE,
                        call,
                        context.getLocation(call),
                        (isPathFactory ? "Path()" : "Outline." + containing.getName())
                                + " 只能出现在 createOutline / build*Path 里"
                                + "（当前所在函数：" + (host == null ? "<非函数体>" : host) + "）",
                        null);
            }
        };
    }

}
