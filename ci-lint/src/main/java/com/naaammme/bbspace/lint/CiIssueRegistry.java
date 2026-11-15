package com.naaammme.bbspace.lint;

import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.client.api.Vendor;
import com.android.tools.lint.detector.api.ApiKt;
import com.android.tools.lint.detector.api.Issue;

import java.util.List;

/**
 * bbspace 自有 Lint 规则登记表（A2）。
 *
 * 机制照上游 BiliPai {@code ci-lint} 搬：{@code Lint-Registry-v2} manifest 属性 +
 * {@code lintChecks(project(":ci-lint"))} 接线，检测器由 Lint 而非 JVM 单测驱动。
 *
 * <p>当前登记 <b>6</b> 条规则，全部替换了原先「扫源码 + 钉计数」的 JVM 守卫：
 * <ul>
 *   <li>{@link NoPrintlnDetector} —— 禁用 {@code println(}（机制证明，本仓命中为 0）；</li>
 *   <li>{@link AnimateAsStateDetector} —— 禁用 {@code animate*AsState}（取代
 *       {@code FrameBudgetLintTest} 的文本棘轮）；</li>
 *   <li>{@link HardcodedAnimationDurationDetector} —— 动画时长必须走 token；</li>
 *   <li>{@link PathOutlineAllocationDetector} —— {@code Path()/Outline.*} 只许在
 *       {@code createOutline/build*Path} 里；</li>
 *   <li>{@link RunBlockingNeedsBoundDetector} —— {@code runBlocking} 必须带超时/兜底；</li>
 *   <li>{@link SpLiteralDetector} —— theme 包外禁用 {@code N.sp} 字号字面量。</li>
 * </ul>
 *
 * <p><b>这条清单必须与下方 {@code ISSUES} 保持同步</b>：登记表是唯一真源，注释是它的
 * 人读镜像。历史上这里曾写「当前只登记 1 条规则」而实际已登记 6 条 —— 陈旧注释比没有
 * 注释更糟（它会让人以为新增规则没生效）。新增规则时请同时更新本段。
 */
public final class CiIssueRegistry extends IssueRegistry {

    /**
     * 登记表本体。{@code public} 是为了让 {@code DetectorRegistrationTest} 能在不依赖
     * Lint 运行时的前提下断言「登记的规则集 == 预期的规则集」——
     * 这是本仓唯一能证明"规则真的被登记了"的手段（Lint 漏跑是静默的）。
     */
    public static final List<Issue> ISSUES =
            List.of(
                    NoPrintlnDetector.ISSUE,
                    AnimateAsStateDetector.ISSUE,
                    HardcodedAnimationDurationDetector.ISSUE,
                    PathOutlineAllocationDetector.ISSUE,
                    RunBlockingNeedsBoundDetector.ISSUE,
                    SpLiteralDetector.ISSUE);

    private static final Vendor VENDOR =
            new Vendor("bbspace", "com.naaammme.bbspace.lint", "https://github.com/naaammme/bbspace");

    @Override
    public List<Issue> getIssues() {
        return ISSUES;
    }

    @Override
    public int getApi() {
        return ApiKt.CURRENT_API;
    }

    @Override
    public int getMinApi() {
        return ApiKt.CURRENT_API;
    }

    @Override
    public Vendor getVendor() {
        return VENDOR;
    }
}
