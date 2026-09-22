#!/usr/bin/env python3
"""theme 包外 dp/sp 字面量的清点与语义归类工具（T4.1）。

为什么需要脚本而不是直接 grep：
    T4.1 的结论必须是"某个值在语义上是不是同一个东西"，而不是"它出现了几次"。
    grep 只能给次数；归类要看**每一处所在的调用上下文**。所以这里把每处出现
    连同它所属的修饰符/参数名一起抽出来，按启发式分桶，并保留原始代码行作为证据，
    让人（而不是脚本）来判定"这批该不该合成一个 token"。

统计口径（与 DesignTokenLintTest 对齐，但**两个计数不同**，务必看清用的是哪个）：
    - 范围：仓库内所有 `src/main/**` 下的 `.kt`
    - 排除：路径含 `build/` 的产物；以及 theme 包
      `core/designsystem/src/main/java/com/naaammme/bbspace/core/designsystem/theme/`
      （token 的定义本来就得写字面量，禁了等于逼人绕过棘轮）
    - 跳过：注释行（`//`、`*`、`/*` 开头）与 import 行
      —— 解释"为什么这么写"的注释里必然出现 `16.dp` 这类词，不该让计数上升
    - **出现次数**：一行里有几个字面量就算几个（本脚本的频次表用这个，归类更细）
    - **命中行数**：一行只算一次（**棘轮 DesignTokenLintTest 用的是这个**）
      两者会差一截（实测约 1118 vs 962），因为 `padding(horizontal = 8.dp, vertical = 6.dp)`
      这种一行两个字面量的写法很常见。对数字时别拿错口径。

用法：
    python scripts/design-token-audit.py                 # 频次分布 + 总数
    python scripts/design-token-audit.py --top 15        # 只看前 15 个高频值
    python scripts/design-token-audit.py --classify 6.dp # 某个值的逐处归类清单
    python scripts/design-token-audit.py --classify 10.dp
"""

import argparse
import collections
import os
import re
import sys

THEME_DIR = os.path.join(
    "core", "designsystem", "src", "main", "java",
    "com", "naaammme", "bbspace", "core", "designsystem", "theme",
)

LITERAL_RE = re.compile(r"\b(\d+(?:\.\d+)?)\.(dp|sp)\b")

# 归类启发式：**只看所在行的调用上下文**，不做跨行解析。
# 故意保守 —— 分错的代价是"这一类不进候选清单"，而不是"误合并成 token"。
BUCKET_RULES = [
    ("内边距", re.compile(r"\bpadding\s*\(|paddingFrom|\bcontentPadding\b|\bspacedBy\s*\(")),
    ("间距/占位", re.compile(r"\bSpacer\s*\(|\bArrangement\.|\bGap\b")),
    ("控件/图标尺寸", re.compile(r"\.size\s*\(|\bsizeIn\s*\(|\bIcon\s*\(|\bimageSize\b|\bDpSize\b")),
    ("线宽/描边", re.compile(r"\bBorderStroke\s*\(|border\s*\(|\bwidth\s*=\s*\d|\bStroke\s*\(|\bdrawLine|\bDivider\s*\(|\bthickness\b")),
    ("形状圆角", re.compile(r"RoundedCornerShape|CornerSize|\bshape\s*=")),
    ("位置/偏移", re.compile(r"\boffset|\balignBy|\btranslateY|\bx\s*=|\by\s*=")),
    ("字号", re.compile(r"\bsp\b|fontSize|lineHeight|letterSpacing")),
]

NAME_ARG_RE = re.compile(r"([A-Za-z][A-Za-z0-9]*)\s*=\s*$")


def classify_line(line: str, col: int) -> str:
    """给一处字面量打一个语义桶标签。"""
    for name, rx in BUCKET_RULES:
        if rx.search(line):
            return name
    # 退化判据：看它是不是某个命名实参的值（`foo = 6.dp`）
    head = line[:col]
    m = NAME_ARG_RE.search(head + " ")
    if m:
        return f"命名实参:{m.group(1)}"
    return "其他"


def iter_sources(root: str):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".git")]
        if THEME_DIR in os.path.join(dirpath, ""):
            continue
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            full = os.path.join(dirpath, fn)
            if os.path.join("src", "main", "") not in full:
                continue
            yield full


def is_skippable(stripped: str) -> bool:
    return (
        stripped.startswith("//")
        or stripped.startswith("*")
        or stripped.startswith("/*")
        or stripped.startswith("import ")
    )


def collect(root: str):
    """返回 (records, total_by_unit)。record = (file, line_no, value, unit, bucket, text)"""
    records = []
    for path in iter_sources(root):
        try:
            with open(path, encoding="utf-8") as fh:
                lines = fh.read().splitlines()
        except UnicodeDecodeError:
            print(f"!! 非 UTF-8，跳过：{path}", file=sys.stderr)
            continue
        for idx, line in enumerate(lines, start=1):
            if is_skippable(line.strip()):
                continue
            for m in LITERAL_RE.finditer(line):
                value = f"{m.group(1)}.{m.group(2)}"
                records.append(
                    (os.path.relpath(path, root), idx, value, m.group(2),
                     classify_line(line, m.start()), line.strip())
                )
    return records


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".", help="仓库根目录")
    ap.add_argument("--top", type=int, default=25, help="频次表显示前 N 个")
    ap.add_argument("--classify", help="列出某个值（如 6.dp）的每一处归类与代码行")
    args = ap.parse_args()

    records = collect(args.root)
    if not records:
        print("没有扫到任何字面量 —— 路径推导可能失效（这正是最危险的假绿形态）。", file=sys.stderr)
        return 2

    if args.classify:
        sel = [r for r in records if r[2] == args.classify]
        buckets = collections.Counter(r[4] for r in sel)
        print(f"=== {args.classify}：共 {len(sel)} 处 ===")
        print("按语义桶分布（桶是启发式，需人工核对代表性行）：")
        for bucket, n in buckets.most_common():
            print(f"  {bucket:24s} {n:4d}  {n * 100.0 / len(sel):5.1f}%")
        print()
        for bucket, _ in buckets.most_common():
            print(f"--- [{bucket}] ---")
            for r in [x for x in sel if x[4] == bucket][:12]:
                print(f"  {r[0]}:{r[1]}  {r[5][:110]}")
            if buckets[bucket] > 12:
                print(f"  ... 另有 {buckets[bucket] - 12} 处")
            print()
        return 0

    by_value = collections.Counter(r[2] for r in records)
    by_unit = collections.Counter(r[3] for r in records)
    print(f"扫描根：{os.path.abspath(args.root)}")
    print(f"theme 包外字面量合计：{len(records)}  "
          f"(dp={by_unit['dp']}  sp={by_unit['sp']})")
    print()
    print(f"=== 频次前 {args.top} ===")
    print(f"{'值':>10} {'次数':>6}  占比")
    for value, n in by_value.most_common(args.top):
        print(f"{value:>10} {n:6d}  {n * 100.0 / len(records):5.1f}%")
    print()
    print("提示：`--classify 6.dp` 看逐处语义归类与代码证据；")
    print("     棘轮口径见 core/designsystem/src/test/.../core/perf/DesignTokenLintTest.kt")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
