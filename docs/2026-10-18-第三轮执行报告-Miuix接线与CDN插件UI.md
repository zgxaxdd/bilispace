# 第三轮执行报告：Miuix 接线 + 动效库 + CDN 插件 UI（2026-10-18 03:30）

> 上接 `docs/2026-10-18-第二轮执行报告.md`。本轮目标 = 把上一轮"仅落库、零接线"的三块补齐到**用户能用**。
> 产物：`dist/bbspace-1.8.13-arm64-debug.apk`（versionCode 10813 / 1.8.13-debug / md5 `3848c102069f8fc23a8ee0290d98756e` / 128,382,168 B）。

## 1. 门禁（11 模块 919 条测试，0 失败）

`:core:designsystem` **230**（189 基线 + 41 新增动效单测）、`:feature:video` 139、`:infra:player` 151、`:core:model` 84、`:core:playback` 98、`:core:settings` 26、`:feature:comment` 27、`:feature:rank` 3、`:feature:settings` **17**（12 + 5 CDN 规则解析）、`:infra:network-http` 4、`:app` 140。

## 2. 本轮落地（按用户可见度排序）

### 2.1 Miuix 接线（需求 2 从"仅库"变成"用户能看到"）
| 落点 | 改动 | 覆盖面 |
|---|---|---|
| `StateMessageCard` | `M3 Card` → `AppMiuixCard`；`TextButton` → `AppMiuixTextAction` | **全站 78 处三态出口**（空/错误/提示卡） |
| `IOSSwitch` | 自绘 51×31 开关 → `AppMiuixSwitch`（API 不变） | 设置页**所有**开关（经 `SettingItem` 一处） |
| `FilledTabRow` iOS 分支 | 自绘 `IOSSegmented`（BoxWithConstraints + animateDpAsState + offset）→ `AppMiuixSegmentedControl`（miuix TabRow，库内 spring 指示器） | 首页/动态/搜索等**所有分段 Tab** |
| 守卫迁移 | `ListAllocationStructureTest` 的两条"禁 offset(x=)/要求 offset{}"断言 → 三条"必须委托 `AppMiuixSegmentedControl` / 禁回潮 / 禁自绘动画"断言（**语义收窄而非放宽**，违规仍红） | — |

⚠️ **未真机验证**：miuix 的轨道形变、squircle 圆角、指示器位移观感全部无实测。若观感不合意，回退成本极低（每处 1~2 行改回原实现）。

### 2.2 动效库（需求 3 的一半）
新增 `core/designsystem/.../motion/` 四件，全部纯逻辑 + JVM 单测（41 例）：
- `MotionTier`（+ `MotionTierPolicy`）：设备性能分档 → 动效强度/玻璃上限；**已接 2 处**（`Skeleton` 低端机停呼吸、中端降频；`BiliCardSurface` 按压缩放受档位控制）。
- `DampedDragAnimation`：阻尼跟手 0.55 / 越界压缩 28% / 速度投影 0.12s / 松手 lattice 吸附 + 边界钳制，`Modifier.dampedHorizontalDrag` 壳。
- `LiquidIndicatorMotion` + `LiquidIndicatorVelocity`：指示器 spring 参数集中、速度形变、一次最多跨一档、可确定性推进（JVM 可测）。
- `MatchedLiquidIndicatorGeometry`：指示器与目标 pill 的匹配几何（静止 56/64、按压 bloom 78、角半径=半高、lerp 端点精确）。

**未接线（如实）**：后三件仅库。写域内没有可接的水平拖拽面（Sheet 手势归 M3、CapsuleGroup 是纵向、下拉刷新用原生），真正消费者是 **app 层 Dock 指示器**（下一轮）。

### 2.3 液态玻璃（需求 3 的另一半）：只做可行性，不落实现
结论（只读评审）：
1. 本仓 `glass/liquid/LiquidEffects.kt` 的 `lens()` 已通过 miuix-blur 的 `runtimeShaderEffect` 实现**同血统**的圆角 SDF 折射 + 色散 ⇒ **不需要搬上游独立 AGSL 字符串**（搬了就是重复实现）。
2. 真正缺的是 `LiquidGlassTuning`（参数解析层）与 `LiquidGlassAdaptiveReadability`（背景亮度迟滞 → 前景 tone）；这两件是**叠加**，不需要新增玻璃面。
3. **`drawBackdrop` 棘轮仍 4**（未动）；只有"Dock 滑块面 / Sheet 顶沿面各自独立折射"这一产品目标才需要 4→6。
4. 平台门槛：RuntimeShader API 33+ / RenderEffect 31+ / PixelCopy 26+，fallback 必须保留（现有 `lens()` 已 early-return）。

### 2.4 CDN 智能选线插件 UI（需求 4 收口）
- **设置页**：`设置 → 音视频设置` 底部新增「CDN 智能选线（实验）」——属地优选开关、严格模式开关（带 ⚠️ 可能播不了 + 自动回退说明、未开属地时禁用）、host 编辑器、规则编辑器（实时"有效 N 条 · 忽略 M 行" + 错误行号）。
- **纯函数**：`CdnRuleTextFormat`（分隔符兼容中文逗号/顿号/分号/空白；粘贴完整 URL 自动取 host；规则非法行忽略并给 1 基行号）。
- **诊断面板**：`CDN 线路诊断` 新增「选线来源」——始终显示（未启用/已启用无改写两种空态都有文案），逐行「序号. host … 来源」，首条主干用主色。
- **数据链**：会话开播时把**真正下发的**候选表发布到 `CdnTransferRuntime.state`（不是面板重算的预测顺序），面板只读。

## 3. 仍需真机 / 下一轮

| 项 | 状态 |
|---|---|
| miuix 观感（Tab/开关/卡片 squircle） | **未验证**——装 10813 即可看；不合意可 1~2 行回退 |
| Dock 指示器接线（DampedDragAnimation + LiquidIndicatorMotion + MatchedGeometry） | 下一轮，落点已定：`app/.../playback/capsule/` |
| 液态玻璃 Tuning + Readability | 下一轮可无设备落地纯逻辑部分（参数解析 + 迟滞判定 + 可注入采样器） |
| 播放器档位组复用动效库 | 下一轮（本轮刻意不动） |
| 上游同步剩余 A/B 项（配色体系等） | 下一轮 |
| 真机三行读数（FrameDiag/MemoryDiag/StallDiag） | 需用户配合 |
