# -*- coding: utf-8 -*-
"""移植件"生效核对"：统计每个顶层声明的仓库内引用点（区分 main / 同文件 / test）。

用法:
    python tools/audit-ported-symbols.py [输出文件]

判据（只看**引用点**，不看文件是否存在 —— 因为"移植了但没接线"与"没移植"行为完全一样）:
    LIVE      仓库 main 源码里有调用点（含别的模块）        -> 生效
    INTERNAL  只在自己文件里被用                             -> 私有实现细节，正常
    TEST-ONLY 只有测试引用                                   -> 搬运了但生产路径不走
    DEAD      一处引用都没有                                 -> 彻底孤儿

已知误差（读表时注意）:
  1. 引用按**标识符名字**统计（词边界），同名但不同文件的函数可能被误计为 LIVE
     （例：`readUInt32` 在弹幕/直播两个包里各有一份）。
  2. 形如 `fun Long.div(...)` 的扩展函数会被正则误当成名为 `Long` 的声明，已按基础类型名跳过。
  3. 顶层 `private` 声明（同文件可见）只会出现在 INTERNAL 里，属正常。

投放位置: <repo>/tools/audit-ported-symbols.py（本仓约定：`tools/` 放一次性/诊断脚本）
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {"build", ".gradle", ".kotlin", ".git", "dist"}

# 需要核对的"移植件"（本仓从 BiliPai 搬过来的文件）
PORTED = [
    r"infra\player\src\main\java\com\naaammme\bbspace\infra\player\cdn\CdnTransferPolicy.kt",
    r"infra\player\src\main\java\com\naaammme\bbspace\infra\player\cdn\CdnRangeRequest.kt",
    r"infra\player\src\main\java\com\naaammme\bbspace\infra\player\cdn\CdnTransferRuntime.kt",
    r"infra\player\src\main\java\com\naaammme\bbspace\infra\player\cdn\CdnPlaybackDataSource.kt",
    r"infra\player\src\main\java\com\naaammme\bbspace\infra\player\cdn\CdnOptimizationPolicy.kt",
    # CdnRegionPolicy.kt（736 行）已在 R-11 轮归档出构建：只有 hostFromCdnUrl 是活口，
    # 其余 17 个公开声明无调用方 ⇒ 搬到 CdnUrlPolicy.kt，原件存
    # docs/port-analysis/reference/CdnRegionPolicy.kt.txt
    r"infra\player\src\main\java\com\naaammme\bbspace\infra\player\cdn\CdnUrlPolicy.kt",
    r"infra\player\src\main\java\com\naaammme\bbspace\infra\player\cdn\CdnDashSegmentPrefetcher.kt",
    r"infra\player\src\main\java\com\naaammme\bbspace\infra\player\CdnLineOutcomeListener.kt",
    r"infra\player\src\main\java\com\naaammme\bbspace\infra\player\CdnDashPrefetchPlan.kt",
    r"infra\player\src\main\java\com\naaammme\bbspace\infra\player\PlayerErrorRecoveryPolicy.kt",
    r"core\model\src\main\java\com\naaammme\bbspace\core\model\danmaku\DanmakuSegmentWindowPolicy.kt",
    r"core\model\src\main\java\com\naaammme\bbspace\core\model\player\CdnHealthPolicy.kt",
    r"core\model\src\main\java\com\naaammme\bbspace\core\model\player\CdnSmartSelectionPolicy.kt",
    r"core\playback\src\main\java\com\naaammme\bbspace\core\playback\CdnHealthStore.kt",
    r"core\playback\src\main\java\com\naaammme\bbspace\core\playback\CdnHealthPersistence.kt",
    r"core\playback\src\main\java\com\naaammme\bbspace\core\playback\CdnHealthLineOutcomeListener.kt",
    r"core\playback\src\main\java\com\naaammme\bbspace\core\playback\VodDanmakuSession.kt",
    r"core\designsystem\src\main\java\com\naaammme\bbspace\core\designsystem\motion\DissolvableSurface.kt",
    r"core\designsystem\src\main\java\com\naaammme\bbspace\core\designsystem\motion\gl\ThanosParticlePolicy.kt",
    r"core\designsystem\src\main\java\com\naaammme\bbspace\core\designsystem\motion\gl\ParticleRenderer.kt",
    r"core\designsystem\src\main\java\com\naaammme\bbspace\core\designsystem\motion\gl\ThanosEffectView.kt",
    r"core\designsystem\src\main\java\com\naaammme\bbspace\core\designsystem\theme\SystemReduceMotion.kt",
]

# 正则会把 `fun Long.div(..)` 的接收者当成声明名，这些是噪音
PRIMITIVE_NAMES = {
    "Long", "Int", "Short", "Byte", "Float", "Double", "Boolean", "Char", "String",
    "Unit", "Any", "List", "Map", "Set", "Collection", "Array", "Modifier", "Flow",
}

DECL = re.compile(
    r"^(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:public\s+|internal\s+|private\s+|expect\s+|actual\s+|open\s+|abstract\s+|sealed\s+|data\s+|enum\s+|value\s+|annotation\s+|inline\s+|suspend\s+)*"
    r"(fun|val|var|class|interface|object|typealias)\s+"
    r"(?:<[^>]*>\s*)?"
    r"([A-Za-z_][A-Za-z0-9_]*)",
    re.M,
)


def collect_kotlin_files():
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if name.endswith(".kt"):
                yield os.path.join(dirpath, name)


def main():
    texts = {}
    for path in collect_kotlin_files():
        try:
            with open(path, "r", encoding="utf-8") as fh:
                texts[path] = fh.read()
        except OSError:
            pass

    out = [
        "# 移植件符号核对（自动生成）",
        "",
        "> 生成方式：`python tools/audit-ported-symbols.py`（读源码 grep 引用点，不编译）。",
        "> 判定只看**引用点**：`LIVE` = 生产路径有调用；`INTERNAL` = 仅自身文件内使用；",
        "> `TEST-ONLY` = 只有测试引用；`DEAD` = 无任何引用。",
    ]
    summary = {"LIVE": 0, "INTERNAL": 0, "TEST-ONLY": 0, "DEAD": 0}
    for rel in PORTED:
        path = os.path.join(ROOT, rel)
        if not os.path.exists(path):
            out.append(f"## {rel}\n\n**文件不存在**\n")
            continue
        src = texts.get(path, "")
        rows, seen = [], set()
        for m in DECL.finditer(src):
            line = src[: m.start()].count("\n") + 1
            kind, name = m.group(1), m.group(2)
            if name in PRIMITIVE_NAMES or name in seen:
                continue
            seen.add(name)
            word = re.compile(r"\b" + re.escape(name) + r"\b")
            main_refs = same_refs = test_refs = 0
            where = []
            for other, text in texts.items():
                n = len(word.findall(text))
                if not n:
                    continue
                if other == path:
                    same_refs += n - 1
                    continue
                if "\\src\\test\\" in other or "\\src\\androidTest\\" in other:
                    test_refs += n
                else:
                    main_refs += n
                    where.append(os.path.relpath(other, ROOT))
            verdict = (
                "LIVE" if main_refs
                else "INTERNAL" if same_refs > 0
                else "TEST-ONLY" if test_refs
                else "DEAD"
            )
            summary[verdict] += 1
            rows.append((verdict, kind, name, line, main_refs, same_refs, test_refs, where))
        order = {"LIVE": 0, "INTERNAL": 1, "TEST-ONLY": 2, "DEAD": 3}
        rows.sort(key=lambda r: (order[r[0]], r[3]))
        out.append(f"## {rel}")
        out.append("")
        out.append("| 判定 | 声明 | 行 | main | 同文件 | test | 外部调用点 |")
        out.append("|---|---|---|---|---|---|---|")
        for verdict, kind, name, line, main_refs, same_refs, test_refs, where in rows:
            callers = ", ".join(sorted({os.path.basename(w) for w in where})[:4]) or "—"
            out.append(
                f"| {verdict} | `{kind} {name}` | {line} | {main_refs} | {same_refs} | {test_refs} | {callers} |"
            )
        out.append("")

    text = "\n".join(out) + f"\n### 合计 {summary}\n"
    target = sys.argv[1] if len(sys.argv) > 1 else None
    if target:
        with open(target, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"written -> {target}")
    else:
        print(text)
    print("summary:", summary, file=sys.stderr)


if __name__ == "__main__":
    main()
