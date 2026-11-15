# iOS 化 · 第一波规格：按钮族 + 全站按压反馈

> 交付：设计师。日期 2026-10-16 23:4x。
> 上游：`PENDING-ios3-控件体系派单.md`。
> 边界遵守：**只读仓库、未改任何源码、未跑 Gradle**。
> **本文不引用任何行号**，一律符号定位（`VideoPlayerPane.kt` 等文件正被并发改写）。
> 配套（后续）：`d-ios3-inventory.md`（盘点表）、`d-ios3-waves.md`（分波次计划）。

---

## 0. 选波结论

**第一波 = 按钮族（`Button` / `TextButton` / `OutlinedButton` / `IconButton`）+ 全站按压反馈从 Material 涟漪改为 iOS 按压态。**

### 0.1 为什么是它（按「可见收益 ÷ 改动量」）

| 候选 | 调用点 | 改动量 | 判定 |
|---|---|---|---|
| **按钮族** | **198**（`TextButton` 97 + `IconButton` 69 + `Button` 18 + `OutlinedButton` 14） | **1 个新文件 + 1 处 CompositionLocal + 18 处 `Button(` 迁移** | ✅ **比值最高** |
| 卡片 | 80（`Card(`） | 日常面已单源（`BiliCardSurface`） | ❌ 边际收益低 |
| 对话框 | 36（`AlertDialog(`） | **25 个文件** | ⏸ 收益高但改动大 ⇒ **第二波** |

**三条决定性理由**：

1. **涟漪是全站唯一的、也是最大的"安卓味"来源**。它不由调用点决定，而由 **foundation 的 `LocalIndication` 一个来源**决定 ——
   **改一处，198 个按钮族 + 52 处 `.clickable(`（含 8 处显式 `indication = null`，不受影响）一起换掉**。
   这是本次 iOS 化里**杠杆最高的一处改动**，任何按控件族逐个重绘的方案都达不到这个比值。
   （措辞订正：原文"所有 `clickable`"偏大；显式传别的 indication 的地方不受控。）
2. **覆盖调用点最多**：198 处，是 `AlertDialog`(36) 的 5.5 倍、`Card`(80) 的 2.5 倍。
   而且按钮出现在**每一屏**（顶栏图标、列表行、对话框动作、设置项），"看得见"的频率最高。
3. **不撞守卫**：新增组件与提供 `LocalIndication` **不动任何现有调用点**，其余波次可以增量跟进。

### 0.2 我在派单计数上抽验出的偏差（**请以本文数字为准，理由见下**）

派单的计数是**子串口径**，把自定义组件名也算进去了。我用与 `DesignTokenLintTest` 相同的
**行级口径**（跳过注释行与 `import` 行）重扫，结果：

| 控件 | 派单 | **我实测** | 差在哪 |
|---|---|---|---|
| `Card(` | 289 | **80** | 289 含 `XxxCard(` 子串：`StateMessageCard` 77、`SheetSwitchCard` 13、`InfoCard` 8… |
| `Button(` | 213 | **18** | 213 含 `IconButton(`/`TextButton(` 子串；真身是 198 处**按钮族** |
| `AlertDialog(` | 36 | 36 | ✅ 一致 |

> 这个纠正**不影响选波**（结论一致），但影响**工作量估计**：
> 按 289 估卡片、按 213 估按钮会严重高估；按 198 估按钮族才是真实规模。

**另外两条抽验发现（与派单不同）**：
- **现有 iOS 组件不是"全站仅 1 处使用"**：实测 `IOSChevron` 4 处、`IOSAlertDialog` 3 处、
  `IOSActionSheet` 2 处、`IOSSwitch` 2 处。**仍属极低，但"1 处"这个数不准**。
- **`uiStyle` 出厂默认是 `UiStyle.MATERIAL`**（`ThemeConfig.uiStyle`）。
  这意味着**已建成的 iOS 资产在默认配置下全部不可见** —— 见 §5 的 1D，这是"装机可见"的前提。

---

## 1. 第一波范围（四条，按落地顺序）

| 编号 | 内容 | 调用点影响 | 可见性 |
|---|---|---|---|
| **1A** | 全站按压反馈：涟漪 → iOS 按压叠色 | **0 处调用点改动**（`LocalIndication` 一处） | 立刻（点任何东西都能感觉到） |
| **1B** | 新增按钮三档 + 图标钮（`core:designsystem`） | 0（纯新增） | 迁移后可见 |
| **1C** | `Button(` 18 处全量迁移到 iOS 主操作档 | 18 处 / 13 文件 | 立刻 |
| **1D** | **建议**：翻转 `uiStyle` 出厂默认为 `IOS` | 1 行 | **决定"已有 iOS 资产是否可见"** |

---

## 2. 1A · 全站按压反馈（**本波核心**）

### 2.1 落点（符号级）

| 项 | 位置 |
|---|---|
| **提供点（订正×3，已用 javap 落到底）** | `Theme.kt` 的 `BiliTheme`，在既有的 `CompositionLocalProvider(LocalUiStyle / LocalGlassConfig / LocalGlassGuard)` 块内**再加一项 `LocalIndication provides …`** |
| 新文件 | `core/designsystem/.../component/IOSPressIndication.kt` |
| 消费方 | 全部 M3 组件（`Button`/`IconButton`/`Card`/`Checkbox`/`Switch`…）**自动**；无参 `Modifier.clickable` 也能覆盖 —— **无需改任何调用点**（证据见 §2.2′） |

### 2.2 规格

| 项 | 取值 |
|---|---|
| 指示通道（javap 实测） | `androidx.compose.foundation.IndicationKt` 里 `getLocalIndication()` **是 public**；`LocalIndication` 是 `compositionLocalOf { DefaultDebugIndication }`，**这个 local 是 `Providable` 的、可被 provide 覆盖**。无参 `clickable` 走 foundation 的 `clickableWithIndicationIfNeeded` ⇒ **读的就是它** |
| **门控（订正×3）** | `else` 分支**写 `LocalIndication.current`** 即可——这是 foundation 的同一个 local，MATERIAL 档拿回的就是它原值（**不是"拿不到涟漪"**，旧稿与我上一轮的担心都撤回）。简洁写法：`CompositionLocalProvider(LocalIndication provides if (config.uiStyle == IOS) iOSPressedIndication else LocalIndication.current)` |
| **顺带一条真坑** | foundation 1.13.0-alpha01 里 `LocalIndication` 的**默认值已被改名为 `DefaultDebugIndication`** —— 万一漏了 provide，表现是"点了什么都不变"，**不是涟漪**。故 1A 的验收里必须包含"MATERIAL 档点按钮**看得见涟漪**"（§8 #2 已列） |
| 按下表现 | 在控件自身范围内叠一层 **`roles.fill`**（已有语义色，浅色 ≈ 黑 12% / 深色 ≈ 白 18%），**无扩散圆、无位移** |
| 松开 | 直接消失（**不做涟漪的衰减动画**；iOS 无此隐喻） |
| 动画 | 淡入 **不加时长**：直接跟 `collectIsPressedAsState`；若需缓动，走 `LocalAnimations.short`，**禁止写字面量时长**（`BbspaceHardcodedAnimationDuration` 会红） |
| 形状 | **不需要 Shape** —— 叠色画在节点自身 bounds 内，由外层既有 `clip` 收边 |
| reduceMotion | 无影响（本就无位移动画）；**不要**为它加分支 |

> ⚠️ **必须避开 `ListAllocationStructureTest.shapeConstantsAreNeverBuiltInsideComposables`（0 容忍）**：
> 若实现里需要任何 `RoundedCornerShape(...)`，**必须写成文件级 `private val`**（顶层 `object`/`val`），
> **绝不能**写在 `fun` 体内。上面"不需要 Shape"的设计正是为规避它。

### 2.2′ 我实测的两条 API 事实（**决定 1A 是否可做，务必先看**）

**事实 A（仓库侧，`file:line`）**
- `Theme.kt:194-198` 的 `MaterialTheme(colorScheme=, typography=, shapes=)` **没有 `indication` 参数** —— 我昨晚说"提供点就在 MaterialTheme 的 `indication =`"，**那句是错的**，已撤回。
- 全仓 `LocalIndication` **0 命中**、`CompositionLocals.Indication` **0 命中**、`ripple(`/`rememberRipple`/`RippleConfiguration` **各 0 命中** ⇒ 本仓**没有任何一处**在替换按压反馈，**1A 是"从零接入"而不是"改一个现成开关"**。
- `Theme.kt:187-191` 是现成的 `CompositionLocalProvider(LocalUiStyle / LocalGlassConfig / LocalGlassGuard)`，**1A 的 provide 加进这个块**是代价最小的落点。

**事实 B（依赖侧，javap 反查 `gradle-home` 里 BOM `2026.08.00` 解析出的实际 aar）**
| 产物 | 版本 | 实测结论 |
|---|---|---|
| `foundation-android` | **1.13.0-alpha01** | `IndicationKt.getLocalIndication()` public；`LocalIndication = compositionLocalOf { DefaultDebugIndication }`（默认值**已不是涟漪**）。无参 `clickable` 走 `clickableWithIndicationIfNeeded` ⇒ **读这个 local** |
| `material3-ripple-android` | 1.5.0-alpha29 | 类表只有 `RippleKt/RippleNode/RippleModifierNode/RippleNodeConfiguration*`；`RippleKt` 只有 `createRippleModifierNode` 与一个 **private** 的 `DefaultTweenSpec`、**没有任何 public CompositionLocal 或工厂常量** ⇒ 想用"本库官方入口换涟漪"**读不出现成的**，只能自己实现 |
| `material3-android` | 1.4.0 / 1.5.0-alpha29 | 两者 `classes.jar` 里**都没有** `CompositionLocals` 类；`MaterialTheme` 无 `indication` 参数 |

**⇒ 1A 的可行实现（已确定，不再是待查项）**：自己写一个 `IndicationNodeFactory`（`foundation` 1.13 有该类），
节点里 `collectIsPressedAsState` + `drawWithContent` 叠 `roles.fill`，然后 `LocalIndication provides` 它。
**代价**：会同时改掉 M3 组件的涟漪（因为它们也无参走同一个 local）—— 这正是我们要的。

> ⚠️ 我**没有**在真机或单测里验证过上面这条实现能编过；它依赖 API 均为 public（javap 已确认签名存在），
> 但**首次实现必须由构建手在 `:core:designsystem` 单测里落一次编译**才算数。**不许当作已验证。**


⇒ **`indication = null` 的那 8 处（`AppNavHost.kt:1050`、`FilledTabRow.kt:199`、`IOSActionSheet.kt:129`、`IOSAlertDialog.kt:94/105`、`IOSControls.kt:80`、`DownloadPlayerPane.kt:158`、`LivePlayerPane.kt:213`）本来就没涟漪**，不需要也不应该被 1A 触及。

### 2.3 Material → iOS 差异点（**这是重绘的实质**）

| # | Material | iOS | 为什么不是"只换颜色" |
|---|---|---|---|
| 1 | **涟漪**：以触摸点为圆心的扩散水波，表达"触摸面被扰动" | **按压叠色**：整块均匀变暗/变淡，表达"控件被按下" | 涟漪是**材质隐喻**（水波/油墨），iOS 是**状态隐喻**（按下=变暗）。两者的**空间形态**不同，不只是颜色 |
| 2 | 涟漪**越过**控件边界再被裁剪，有**时间轴**（~450ms 衰减） | 无时间轴：按下即变、松开即复原 | 交互的**时序语言**不同 |
| 3 | 高程/色调叠加表达层级 | **无高程**，层级靠填充与发丝线 | 本波只处理按压；高程在 1B 里处理 |

### 2.4 验收判据

- **可机器验**：`LocalIndication` 的提供点在 `Theme.kt` 内、且**门控含 `UiStyle.IOS`**（源码扫描）；
  `IOSPressIndication.kt` 内**不存在** `RoundedCornerShape(` 出现在 `fun` 体内（沿用既有守卫即可）。
- **真机验（需设备）**：点任意按钮/图标 —— **看不到水波**，只看到整块变暗。
- **零回归**：切到 MATERIAL 档，涟漪**照旧存在**。

---

## 3. 1B · 按钮三档 + 图标钮

### 3.1 组件与落点

新文件 `core/designsystem/.../component/IOSButtons.kt`（三个 `@Composable` + 一个图标钮），
**全部新增，不改任何现有组件**。

| 组件 | 对应 Material 用法 | 语义 |
|---|---|---|
| `IOSButtonProminent` | `Button(` | 主操作（确认/提交/前往下载） |
| `IOSButtonTinted` | `TextButton(` 带底场景、次级动作 | 次级操作 |
| `IOSButtonPlain` | `TextButton(` 纯文字、`OutlinedButton(` | 轻量动作（取消/更多） |
| `IOSIconButton` | `IconButton(` | 顶栏/行尾图标 |

### 3.2 尺寸与 token（**逐项自证，零新增 dp 字面量**）

| 项 | 值 | **合规表达** | 依据 |
|---|---|---|---|
| 触控高度 | 44dp | **`BiliSpacing.xxxl + BiliSpacing.md`**（32+12） | iOS 最小触控行；与 `IOSAlertDialog` 的 44dp 行高**同值**，不必新字面量 |
| 水平内边距 | 16dp | `BiliSpacing.lg` | 现有 token |
| 图标钮触控区 | 44×44 | 同上表达式 | 同上 |
| 图标尺寸 | 20dp | `BiliSpacing.xl` | 现有 token |
| 圆角（主/次级） | 12dp | **`MaterialTheme.shapes.medium`**（iOS 档 = `iOSShapes().medium` = 12dp） | **读**主题形状（非构造），不撞 Shape 守卫 |
| 圆角（胶囊，可选） | 全圆端 | **文件级 `private val IosCapsuleShape = RoundedCornerShape(percent = 50)`** | 顶层常量 ✅；**写在函数体内即红** |
| 图标钮圆角 | 全圆 | 同上（复用同一个文件级常量） | — |
| 按钮间距 | 8dp | `BiliSpacing.sm` | 现有 token |
| 文字 | 15sp / 17sp | `MaterialTheme.typography.bodyMedium` / `bodyLarge` | iOS 档分别 15sp/17sp；**不得内联 `.copy(`**（见 §6） |

> **自证**：上表**没有任何一个 `N.dp` 字面量**。全部落在 `BiliSpacing` 既有档位、
> `MaterialTheme.shapes` 或**文件级常量**上。
> 特别地 **44dp 用 `xxxl + md` 表达**，而不是写 `44.dp` —— 这是本波唯一"需要凑"的尺寸，
> 也是它必须被点名的原因。

**3.2′ dp 棘轮余量实测（2026-10-17 只读复扫 `DesignTokenLintTest.kt`，决定 1B 能不能直接写字面量）**

实测上限（`assertRatchet(... limit = ...)` 逐条读出来的）：

| 项 | 上限 | 有没有"余量"这回事 |
|---|---|---|
| **总数 `\d+\.dp`** | **957** | ⚠️ **按总数卡的是硬上限**：**任何新增一个 dp 字面量都要 +1 计数** |
| `16.dp` | 176 | 同理，加一个就 +1 |
| `12.dp` 144 / `8.dp` 145 / `6.dp` 104 / `10.dp` **80** | — | 全都被单独钉住 |
| **`44.dp`** | **无单独上限** | 但**仍计入总数 957** |

**⚠️ 我上一版的手稿这里写错了，必须撤回**：我写过"`44.dp` 无上限、可以直接写"——
**错**。`44.dp` 确实没有单列 ratchet，但它**照样计入 `dpLiteralsOutsideTheme_isFrozen` 的总数 957**，
新增 44 处 = 总数 +44，无余量时必红。**"没单列"≠"不算数"。**

**⇒ 1B 的正确口径（按仓内既有先例，不用 `xxxl + md` 这种假 token）**：
- **触控行高度 = 一个文件级 `private val IosControlRowHeight = 44.dp`**，全文件只此一处字面量 ⇒ **总计数 +1**。
- 写成 `44.dp` 而不是 `xxxl + md` 的理由：`xxxl + md` 让读代码的人误以为这是"可调间距"，
  而 44 是 iOS 触控目标**规范常量**（与 48dp 同性质）—— 这正是本文件 `:176-181` 记录
  "48dp 刻意保留字面量、把上限 +1"的同一处置，**照抄这套体例，并在 KDoc 里写清 +1 的理由**。
- **其余所有尺寸仍走 token**：`lg` 16 / `sm` 8 / `xl` 20；`0.5.dp` 发丝是既有先例不新增语义。

> **对构建手的话**：如果 1B 落地时总数从 957 变 958，**这不是错误，是有记录的一次 +1**；
> 必须在 `DesignTokenLintTest` 的上限说明里按 `:176-181` 的体例追加一行变更记录，否则下一个人会以为棘轮漂了。



### 3.3 颜色（**按 Admin 2026-10-17 裁定分两域**）

| 域 | 取值 | 依据 |
|---|---|---|
| **播放页浮层**（叠在视频画面上） | **严格零彩色**：只用 `roles.primaryLabel` / `roles.secondaryLabel` / `roles.fill` / 黑白色阶 | 用户拍板口径；彩色压在画面上必然打架 |
| **全站其他控件**（`Button(` 18 处所在页） | **中性色板 + 沿用本仓既有语义色**：`roles.primary`（用户自选 tint）、`roles.destructive`（语义红）**照旧可用** | Admin 裁定 (a)：用户要 iOS 的**形状/层级/间距/反馈**，不是"全站变灰白" |
| **禁止（两域通用）** | **不新增彩色常量、不引入彩色渐变** | Admin 裁定 (a) 原文 |

| 部件 | 取值 |
|---|---|
| 主操作底 | `roles.primaryLabel`（浅色=纯黑 / 深色=纯白）—— ⇒ **主操作按钮永远是黑/白实心，用户 tint 不作用于它** |
| 主操作字 | `MaterialTheme.colorScheme.surface`（**反色**，保证 ≥4.5:1） |
| 次级底 | `roles.fill`（iOS systemFill 族，中性半透明灰 —— **已存在，零新增**） |
| 次级/轻量字 | `roles.primaryLabel`；轻量档可用 `roles.secondaryLabel` 降一级 |
| 禁用 | `Modifier.alpha(0.4f)` 作用于整块（**不换色**，iOS 惯例） |
| 按下叠加 | `roles.fill`（与 1A 同一来源） |
| 描边 | 轻量档可选 `0.5.dp` 发丝（**已有先例**：`IOSAlertDialog` 用 `0.5.dp`；不新增语义） |

> **裁定落点**：原稿"与零彩色两处张力，需决策"一节**已结案**——张力只存在于播放页；
> 播放页那三类按钮**根本不出现**（浮层只有图标钮，走 1B 的 `IOSIconButton`，全白/全黑）。
> ⇒ 主操作/次级/轻量三档**全部中性实现**，`roles.destructive` **本波不碰**，
> `IOSComponentStructureTest` 两条断言**不用改**。


### 3.4 四态（**逐态给足**）

| 态 | 主操作 | 次级 | 轻量 | 图标钮 |
|---|---|---|---|---|
| **默认** | `primaryLabel` 实心 + 反色字 | `roles.fill` 底 + `primaryLabel` 字 | 无底 + `primaryLabel` 字 | 无底 + `primaryLabel` 图标 |
| **按下** | 底叠 `roles.fill` 后**整体再压暗**：改为在实心底上叠 `Color.Black.copy(alpha=0.12f)`（深色档用 `Color.White.copy(alpha=0.12f)`） | 底由 `roles.fill` **加深一档**（叠同色一次） | 出现 `roles.fill` 底 | 出现 `roles.fill` 底 + 全圆裁剪 |
| **禁用** | `alpha(0.4f)`，**不响应点击**，语义上仍可读（不做"灰字"处理） | 同左 | 同左 | 同左 |
| **选中** | 按钮**本身无选中态**。需要选中语义的走 `IOSButtonPlain` 的变体：底 `primaryLabel` 实心 + 反色字（与主操作同款），**不加对勾、不换色** | — | 同左 | 同左 |

> **为什么"选中"要这么定**：Apple 的按钮没有 `selected` 概念 —— 选中属于
> **分段控件 / 开关 / 列表勾选**（那是第二、三波的控件族）。
> 强行给按钮加选中态会造出"两套选中语言"。这里只给**一个可用的表达**（填充反转），
> 并明确**不鼓励**在按钮上使用选中语义。

### 3.5 Material → iOS 差异点（**5 条，逐条给"实质"**）

| # | 维度 | Material | iOS | 实质差异 |
|---|---|---|---|---|
| 1 | 按压 | 涟漪（水波） | 整块叠色 | **空间形态**：点状扩散 vs 面状均匀 |
| 2 | 层级 | `tonalElevation` + 阴影 + `primary` 实心 | **无高程**：靠"有无填充"和发丝线区分层级 | **表达手段**：光影 vs 填充 |
| 3 | 形状 | 走 `MaterialTheme.shapes` 档位，**随 `CornerStyle` 在 0/12/16/20dp 漂移** | **固定 12dp**（`iOSShapes`），iOS 无"圆角风格"维度 | **可变性**：Material 的形状是**用户可调维度**，iOS 不是 |
| 4 | 尺寸 | M3 默认 40dp 高、内边距 24dp | **44dp 触控行**、内边距 16dp | **触控几何**：iOS 的最小行高更大、内边距更小（更"满"） |
| 5 | 文字 | `labelLarge` 14sp Medium | `bodyMedium` 15sp / `bodyLarge` 17sp | **字号语言**：iOS 按钮文字**更大**、更接近正文；Material 更接近标签 |

---

## 4. 1C · `Button(` 18 处迁移

**范围**：13 个文件、18 处（`Button(` 全量）。**不迁 `TextButton(` 97 处** ——
它们绝大多数是**对话框/弹层的动作按钮**，会在**第二波（对话框改造）**里被整体替换，
现在逐个改是重复劳动（这是选波时把 `TextButton` 排除的原因）。

**迁移规则**（机械可执行）：
1. `Button(` → `IOSButtonProminent(`，参数名不变（`onClick` / `enabled` / `content`）；
2. `ButtonDefaults.buttonColors(...)` → 删掉（颜色由组件决定）；
3. `ButtonDefaults.shape` / 自定义 `shape =` → 删掉；
4. `ButtonDefaults.ContentPadding` → 删掉（组件自带 16dp）；
5. **不改** `enabled` / `onClick` / 文案 / 业务逻辑 —— 本波只换皮。

**代表性落点**（`Button(` 行级实测，**18 处 / 12 文件**）：`DownloadScreen`（4 处：344/352/541/562）、
`SmsLoginScreen`（2：169/190）、`VideoDetailSheetCoinFav`（2：98/181）、`PrivacySettingsScreen`（2：245/309）、
`AccountScreen`（1：105）、`LoginScreen`（1：234）、`CommentSearchPane`（1：225）、`CommentEditorSheet`（1：243）、
`DynamicScreen`（1：322）、`InterestDialog`（1：154）、`ListenDetailScreen`（1：152）、`VideoScreen`（1：1537）。

> **与其他波次的边界（2026-10-17 按 Admin 裁定 (c) 收窄）**：其中 `InterestDialog`、`CommentEditorSheet`
> 是**弹层内动作**，按裁定归**第二波（对话框族）**一并处理；但它们是 `Button(` 不是 `TextButton(`，
> **留在本波 18 处清单里**，迁移时按 1B 的 `IOSButtonProminent` 处理即可，**等第二波统一对话框形状时不冲突**
> （组件是换皮的，形状随 `IOSShapes` 走）。

---

## 5. 1D · ~~翻转 `uiStyle` 出厂默认~~ → **已裁定：不翻**

**Admin 2026-10-17 裁定 (b)：不翻。理由（Admin 原文要点）**：真机手实测用户当前机器**已经是 iOS 档**
（外观设置页显示 iOS 分支文案）⇒ **本波可见性不受影响**；翻默认是产品级改动，等第一波落地、两档都验过再谈。

**⇒ 对本波的直接影响（必须写进验收）**：1A/1B/1C 的可见性前提**从"翻默认值"改为"用户机器已在 iOS 档"**。
因此 §8 判据必须**先核一次现场档位**（设置页或 `ThemeConfig.uiStyle` 落盘值），
**若档位是 MATERIAL，则本波的按钮/按压反馈看不见 —— 不能报"通过"，报"未能验证（前置档位不符）"**。


---

## 6. 必须一并处理的守卫（**否则"实现必红"**）

| 守卫 | 约束 | 本波怎么避 |
|---|---|---|
| `ListAllocationStructureTest.shapeConstantsAreNeverBuiltInsideComposables` | **0 容忍**：`fun` 体内不许就地构造 Shape | 胶囊形状写成**文件级 `private val`**；圆角优先**读** `MaterialTheme.shapes`（读不算构造）；1A 的按压叠色**不需要 Shape** |
| `ListAllocationStructureTest.typographyInlineCopy_isFrozen` | **0 容忍**：业务层禁内联 `typography.x.copy(` | 按钮文字只用**现成档位**（`bodyMedium`/`bodyLarge`）。若需新字重，**在 `TextStyleVariants.kt` 加具名出口** |
| 具名排版出口的写法 | 新增必须是**普通 `@Composable` 函数**，**不能**写 `fun TextStyle.xxx()` 扩展（同文件断言该扩展形式计数 **== 3**） | 若确实需要新变体，照 `softShadowLabelSmall` 的形态写 |
| `DesignTokenLintTest` dp 棘轮 | 见 §3.2 自证 | **零新增 dp 字面量** |
| `IOSComponentStructureTest.alertSpec` / `actionSheetSpec` | 断言 `IOSAlertDialog.kt` / `IOSActionSheet.kt` 内含 `roles.destructive` | **本波不改这两个文件** ⇒ 不撞。第二波若要动 destructive，须同批改这两条断言并写明理由（**不是放宽标准**，是"纯白口径"改变了语义色的适用范围） |
| `GlassMountRegistryTest` | 钉 `VideoPlayerPane.kt` 挂载点行号 | 本波**不碰** `VideoPlayerPane.kt` ⇒ 不撞 |
| `BbspaceHardcodedAnimationDuration` | 禁硬编码动画时长 | 1A 的按压**不加动画**；若加，走 `LocalAnimations` |

---

## 7. 待决策 / 未验证（**如实列出**）

### 7.1 ~~需决策者裁决（3 条）~~ → **2026-10-17 Admin 全部裁定，已结案**

| # | 原问题 | 裁定 | 落到本文哪里 |
|---|---|---|---|
| 1 | "零彩色"范围 | **播放页浮层严格零彩色；全站其他控件 = 中性色板 + 沿用既有语义色；不新增彩色常量/渐变** | §3.3 已改写成两域表 |
| 2 | 是否翻 `uiStyle` 默认值 | **不翻**（用户机器已是 iOS 档，可见性不受影响；翻默认是产品级改动，两档都验过再谈） | §5 改为"已裁定：不翻"，并把"先核现场档位"写进验收前提 |
| 3 | `TextButton(` 97 处是否等第二波 | **等第二波**。第一波只做 198 按钮族（`Button` 18 + 无参 `clickable`）；`TextButton` 多用于对话框/次级文字操作，iOS 没有对应涟漪语言，需单独设计 | §4 范围不变，另补两处弹层按钮的边界说明 |


### 7.2 我实测 vs 派单的差异（**已在上文标注，此处汇总**）

- 棘轮我用与 `DesignTokenLintTest` 相同的行级口径（跳过注释/`import`、排除 theme 包）实测：
  总 **942**、`10.dp` **77**、`12.dp` **142**、`6.dp` **100**、`8.dp` **144**、`16.dp` **171**。
  与派单数字有差（派单偏高），**以 `DesignTokenLintTest` 实跑为准**；
  但两者指向同一结论：**余量极小，必须零新增**。
- 控件计数：见 §0.2（`Card` 80 / `Button` 18 / 按钮族 198）。

### 7.3 未验证

- **未跑 Gradle / 未跑任何测试**（需向决策者申领，且移植手占用中）；
- **未上真机**（设备未在线）⇒ "按压反馈观感"与"按钮手感"**均未验证**；
- 我**未修改任何源码**，§2~§4 的落点均为只读核对所得。

**U1 → 已结案（原"未验证"作废，留痕）**
我原先写"`material3-ripple` 的内部 CompositionLocal 名读不出、是 1A 的硬阻塞"。**已用 javap 反查推翻**：
`material3-ripple` 1.5.0-alpha29 的 `RippleKt` **没有任何 public CompositionLocal 或工厂常量**，
所以"从本库现成入口换涟漪"这条路根本不存在；**1A 改走 foundation 的 `LocalIndication`**（§2.2′ 事实 B 已给签名）。
**剩余未验证**：该实现**能否编译通过**——需构建手在 `:core:designsystem` 落一次编译，**未跑 = 未能验证**。

**U2（1A 的覆盖边界，口径已修正）**：无参 `Modifier.clickable`（全仓 **52** 处 `.clickable(`，其中 `indication = null` 8 处）
与 M3 组件**共用 foundation 的 `LocalIndication`** ⇒ **都会被 1A 覆盖**；那 8 处显式传 `indication = null`，
**显式实参优先，不受影响、也不该受影响**。
⇒ 1A 真实覆盖 = "198 个按钮族 + 无参 clickable"，**"全站所有点击"这句仍偏大**（显式传别的 indication 的地方不受控）。


---

## 8. 第一波验收判据（汇总）

| 判据 | 怎么验 | 三态 |
|---|---|---|
| 0 | **前置**：现场 `uiStyle` 确为 `IOS` | 设置页「界面风格」显示 iOS 分支，或读落盘的 `ThemeConfig.uiStyle` | **不符则全波判"未能验证（前置档位不符）"** |
| 1 | 按钮/图标**无水波** | 真机点任意按钮 | 待真机 |
| 2 | MATERIAL 档**涟漪照旧**（**必验，不省**：foundation 1.13 的 `LocalIndication` 默认值是 `DefaultDebugIndication`，漏 provide 的表现是"点了什么都不变"而非涟漪） | 切 MATERIAL 档点按钮 | 待真机 |
| 3 | `LocalIndication` 门控含 `UiStyle.IOS` | 源码扫描 | 可静态验 |
| 4 | **零新增 dp 字面量** | `DesignTokenLintTest` 全绿且**总量不升** | 待跑 |
| 5 | 18 处 `Button(` 已迁移（**行级实测 18 / 12 文件，清单见 §4**） | 全仓行首 `Button(` 计数 = 0 | 可静态验 |
| 6 | 新增组件内**无 `fun` 体内 Shape 构造** | 既有守卫全绿 | 待跑 |
| 7 | 禁用态 `alpha 0.4` 且不可点 | 真机看灰按钮 | 待真机 |
| 8 | 主操作对比度 ≥4.5:1 | 黑底白字 / 白底黑字，天然达标 | 可算 |
| 9 | **播放页浮层零彩色**（裁定 (a)） | 源码扫描：浮层相关文件内无 `roles.primary` / 彩色常量 | 可静态验 |
| 10 | **未新增彩色常量 / 彩色渐变**（裁定 (a)） | 新增文件 diff 里无 color 字面量与渐变 | 可静态验（走 diff） |

---

## 9. 下一步（第二、三波预告，**不在本次交付**）

- **第二波 = 对话框族**（`AlertDialog(` 行级 36 处 / 25 文件；`*Dialog*.kt` 内 `TextButton(` **11** 处）：
  `AlertDialog(` → iOS 270dp 居中卡 + 发丝分隔 + 横排按钮。
  **证据**：用户最近一次真机截图里最"安卓味"的就是它
  （`AppUpdateDialog` 走的是 Material `AlertDialog`，左对齐正文 + 粉色文字按钮 + 大圆角）。
  已有 `IOSAlertDialog` 可直接复用（当前仅 3 处使用），**不需要重做**。
  **已裁定 (c)**：`TextButton(` 97 处归本波，且"iOS 无涟漪语言"⇒ 需要**单独设计**按压反馈，
  **不能**直接套 1A 的整块叠色（文字按钮没有底可叠色，要改成"按下变 `secondaryLabel`"这一类的字色反馈）。
- **第三波 = 卡片 / 列表 / 开关 / 分段控件 / 输入框**（`d-ios3-inventory.md` 会给完整盘点）。

> 本波刻意**不碰** `BiliCardSurface`（日常面已单源、且已 iOS 化）与对话框
> （改动面 25 文件）—— 把"最高杠杆"先兑现，再按可见度递减推进。

