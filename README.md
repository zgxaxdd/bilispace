<p align="center">
  <img src="images/banner.jpg" alt="bilispace banner" width="920" />
</p>

<h2 align="center">哔哩空间 · bilispace</h2>

<p align="center">
  <a href="https://github.com/zgxaxdd/bilispace/stargazers">
    <img src="https://img.shields.io/github/stars/zgxaxdd/bilispace?style=flat" alt="stars" />
  </a>
  <a href="https://github.com/zgxaxdd/bilispace/releases">
    <img src="https://img.shields.io/github/v/release/zgxaxdd/bilispace?style=flat" alt="release" />
  </a>
  <a href="https://github.com/zgxaxdd/bilispace/releases">
    <img src="https://img.shields.io/github/downloads/zgxaxdd/bilispace/total?style=flat" alt="downloads" />
  </a>
  <a href="https://developer.android.com/about/versions/13">
    <img src="https://img.shields.io/badge/Android-13%2B-3DDC84?style=flat&logo=android&logoColor=white" alt="android 13+" />
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/github/license/zgxaxdd/bilispace?style=flat" alt="license" />
  </a>
</p>

<p align="center">
  一个强大的第三方哔哩哔哩 Android 客户端。<br/>
  基于官方 <a href="https://github.com/naaammme/bbspace">BBSpace</a> 源码深度二次开发。
</p>

<p align="center">
  <a href="https://github.com/zgxaxdd/bilispace/releases"><strong>下载 APK</strong></a>
  ·
  <a href="https://github.com/zgxaxdd/bilispace/issues"><strong>反馈问题</strong></a>
</p>

---

## 这个项目和原版有什么不同

bilispace 起于官方 **BBSpace v1.8.04**，之后做了 **267 个提交、741 个文件、约 10.7 万行**的增量开发。
它不是换皮，而是把「能看视频的客户端」做成了「刷起来顺手的客户端」——新增了一整套**竖屏刷视频引擎**、
**播放器内核改造**、**液态玻璃视觉体系**与**性能度量/门禁体系**。

一句话概括：**原版能看，这个版本刷。**

> 完整逐条对照见 [docs/FEATURES-vs-upstream.md](docs/FEATURES-vs-upstream.md)（含文件级证据）。
> 下面只列用户能直接感知的部分。

### 1. 全新形态：竖屏刷视频（原版没有）

这是本版最大的增量。原版只有「首页选片 → 点进详情 → 观看」的单向路径；本版补上了一条**抖音式沉浸流**。

| 能力 | 说明 |
| --- | --- |
| **竖屏刷视频会话** | 从视频详情页简介区**边缘下滑**即可进入，上下滑动换片，退出即回原位 |
| **独立会话引擎** | 自带 `core/swipe` 模块：会话状态机、队列编排、多源编排、看过的去重、进程被杀后恢复 |
| **跟手翻页动效** | 翻页缩放/淡出、越界阻尼、换页吸附动画（时长与曲线走 token，尊重系统「减弱动效」） |
| **会话内信息层** | 常驻细进度条 → 标题 + UP → 话题/合集 → 可展开简介，三层递进 |
| **侧边互动栏** | 点赞 / 收藏 / 分享 / 评论，长按直接三连；粒子爱心、计数滚动、状态形变 |
| **可拖进度条** | 拖动时出时间气泡，拖动期间自动抑制翻页，避免误触 |
| **预取与补货** | L1 预取容量 16、相邻页预热详情（翻过去不空一拍）、推荐流不足时用队尾 related 补 |
| **「不感兴趣」闭环** | 会话内直接标记，退队 / 补货 / 前进 / 撤回全链路 |
| **横屏观影** | 会话内横屏自动清屏，侧滑返回切回竖屏，保留返回/弹幕/横竖屏切换/播放设置 |
| **QoE 观测** | 本机刷视频质量埋点 + 独立诊断页（`设置 → 刷视频诊断`） |

### 2. 播放器内核（原版是基础播放，本版重做了链路）

| 能力 | 说明 |
| --- | --- |
| **CDN 智能选线** | `CdnSmartSelectionPolicy`：按实测健康度选线，故障自动切换（`CdnFailoverDataSource`） |
| **自定义 CDN 地域插件** | 可导入 JSON 规则，强制走指定线路；带规则文本格式化与校验 |
| **DASH 分段预取** | `CdnDashSegmentPrefetcher` + 预取计划，起播头部字节预热（仅 Wi-Fi） |
| **字节缓存 SimpleCache** | 128MiB 媒体字节缓存，带命中率度量 |
| **单文件 DASH 支持** | `SingleFileDashIndexParser` 兼容扩展大小盒头与零长度盒 |
| **关键帧吸附 seek** | 用户主动拖动走关键帧吸附，内部跳转保持精确 |
| **卡顿恢复策略** | `StallRecoveryPolicy`：READY 500ms 采样 + BUFFERING 2s 采样（原版进缓冲即停观测） |
| **网络恢复重试** | 断网重连后自动恢复，且重试前失效 playurl 缓存（否则必然拿回同一份失效 URL） |
| **首帧耗时解剖** | `FirstFrameTimeline` / 首帧超时策略，切页优化有度量尺 |
| **稳态观测** | 丢帧、解码器重建、字节缓存命中率三类埋点 |

### 3. 视觉与动效（原版是标准 Material 3）

| 能力 | 说明 |
| --- | --- |
| **液态玻璃材质** | `core/designsystem/glass` 整套：背景取色、亮度采样、可读性兜底、绘制预算与守卫 |
| **HCT 取色与配色表** | 封面动态取色（含壁纸色板 Y 轴插值）、主题种子色、多套调色板风格 |
| **iOS 风格控件** | `IOSActionSheet` / `IOSAlertDialog` / `IOSControls`，可选 UI 风格 |
| **卡片组件体系** | `BiliCardSurface` / `CoverImage` / 骨架屏 / 渐进淡入 / 自适应网格 |
| **阻尼与弹性动效** | `DampedDragAnimation` / `AppElasticPress` / 液态指示器（含速度追踪与匹配几何） |
| **动效分级** | `MotionTier` 按设备能力降级，尊重「减弱动效」 |
| **可调外观** | 主题模式、种子色、圆角风格、UI 风格、玻璃材质/强度、字体与 UI 缩放、纯黑模式、刷新率模式、转场样式、动效速度 |

### 4. 内容与功能补齐（原版标为「正在补全」的）

| 能力 | 说明 |
| --- | --- |
| **互动闭环** | 视频点赞 / 投币 / 收藏 / 一键三连 / 双击点赞，状态回显与失败回滚 |
| **评论互动** | 评论点赞 / 点踩，评论页缓存与骨架槽位策略 |
| **动态互动** | 动态点赞 / 转发 |
| **空降助手** | SponsorBlock 完整接入（`core/sponsorblock`）+ 独立设置页，可按类别配置跳过行为 |
| **排行榜** | 全新 `core/rank` + `feature/rank`，卡面与推荐页同源取色 |
| **拉黑名单管理** | 「查看 / 撤销」页（原版无入口） |
| **推荐来源三档** | 首页推荐可在 WEB / MOBILE / MERGED 间切换 |
| **用户主页拼贴** | 空间页 collage 画布与瓦片布局 |
| **分享工具** | 全场景分享链路 |

### 5. 工程与质量（用户间接受益）

| 能力 | 说明 |
| --- | --- |
| **自定义 Lint 规则** | 独立 `ci-lint` 模块 6 条源码级检测：`AnimateAsState`、硬编码动画时长、`println`、`Path` 每帧分配、无条件 `runBlocking`、`sp` 字面量 |
| **守卫测试** | 大量「源码结构断言」测试，防止性能特性被无声改回去 |
| **性能棘轮** | 帧预算、玻璃绘制链、组合期读动画值等指标设上限，只能改善不能回退 |
| **OPPO 无缝动画** | ColorOS 16.1+ 接入 `OplusViewSeamless` 系统级无缝过渡（卡片矩形 → 全屏，跟手/阻断/回弹）；非该设备自动降级为应用内封面展开 |
| **冷启动阶段耗时** | 可解析的启动链路埋点 |
| **配置缓存友好** | 签名凭据走 `providers.environmentVariable`，开配置缓存也能正确打包 |

### 相对原版的技术底座升级

| 项 | 原版 | 本版 |
| --- | --- | --- |
| minSdk | 24（Android 7.0） | **33（Android 13）** —— 液态玻璃 AGSL 从 33 起可用 |
| compileSdk | 36 | **37.2** |
| Gradle / AGP | 旧 | **Gradle 9.6 + AGP 9.x**（`optimization` 新 DSL） |
| 模块数 | 47 | **51**（新增 `core/swipe`、`core/rank`、`feature:rank`、`core:sponsorblock`、`ci-lint`） |
| 源码文件（.kt） | 349 | **776** |

---

## 功能总览

### 账号

- [x] 扫码登录
- [x] 短信登录（含极验验证码）
- [x] 多账号管理

### 内容

- [x] 首页推荐视频流（支持单列/双列、推荐来源三档切换）
- [x] 视频搜索 / 搜索筛选 / 搜索历史
- [x] 视频详情页（简介区重设计、取色光氛围）
- [x] 评论区（半透明悬浮面板、缓动展开/收起）
- [x] 动态 / 动态详情
- [x] 个人空间（含拼贴画布）
- [x] 稍后再看 / 播放历史（含搜索）
- [x] 收藏夹 / 收藏夹详情
- [x] 排行榜
- [x] 直播
- [x] 专栏 / 图文

### 播放

- [x] 视频播放（Media3）
- [x] 弹幕（DanmakuFlameMaster）
- [x] **竖屏刷视频模式**
- [x] 听视频 / 听书
- [x] 应用内小窗 + 播放胶囊（与底栏融合为 `LinkedBottomDock`）
- [x] 应用内缓存 / 下载
- [x] 视频音频一键下载导出
- [x] 后台播放
- [x] 倍速 / 循环 / 播完自动下一个

### 互动

- [x] 视频点赞 / 投币 / 收藏 / 一键三连
- [x] 双击点赞
- [x] 评论点赞 / 点踩
- [x] 动态点赞 / 转发
- [x] 会话内（刷视频）点赞 / 收藏 / 分享 / 评论

### 社交

- [x] 即时消息 / 消息流
- [x] 用户关系查询

### 增强

- [x] 空降助手（SponsorBlock）
- [x] 发评反诈
- [x] 评论弹幕记录 / 评论历史查询
- [x] 拉黑名单管理

### 仍在补全

- [ ] 字幕
- [ ] 分类搜索
- [ ] 其他细节完善

---

## 技术实现

- `Kotlin` + `Jetpack Compose` + `Material 3`，多模块分层（`infra` / `core` / `feature` / `app`）
- `Media3` 负责播放，`DanmakuFlameMaster` 负责弹幕渲染
- `Room` 和 `DataStore` 管理本地数据（设置走「镜像快照」，首帧主题不需要主线程 `runBlocking`）
- `OkHttp` `Retrofit` `Protobuf` 负责网络与协议层
- 自行实现 B 站 **gRPC over HTTP/1.1** 请求封装
- `OplusViewSeamless`（ColorOS 系统能力，`compileOnly`）负责系统级无缝过渡
- Hilt 依赖注入；自定义 Lint 模块参与构建门禁

## 下载与运行

- 用户安装：前往 [Releases](https://github.com/zgxaxdd/bilispace/releases) 下载最新 APK
- 系统要求：**Android 13（API 33）及以上**，架构 **arm64-v8a**
- 开发环境：JDK 17、Android SDK 37.2

### 从源码构建

```bash
# 调试包
./gradlew :app:assembleDebug

# 发布包（需提供签名凭据）
./gradlew :app:assembleRelease \
  -PversionName=2.0.0 -PversionCode=20000 \
  -PSIGNING_STORE_FILE=/path/to/release.jks \
  -PSIGNING_STORE_PASSWORD=*** \
  -PSIGNING_KEY_ALIAS=*** \
  -PSIGNING_KEY_PASSWORD=***
```

签名凭据也可用同名**环境变量**传入（`SIGNING_STORE_FILE` 等）。
不提供时 release 包仍可构建，但产物**未签名**、无法直接安装。

## 说明

bilispace 是一个基于官方 [BBSpace](https://github.com/naaammme/bbspace) 源码衍生的项目，
持续加入互动、动效与体验优化。与哔哩哔哩官方无关。
项目主要用于学习、研究和 Android 原生界面实现练习。仓库内涉及的接口信息均来自公开资料整理，
仅用于技术交流，不包含破解和付费内容分发。

如果这个项目帮你省下了找实现细节和踩坑的时间，欢迎点个 Star。

## 致谢

- [BBSpace（原版）](https://github.com/naaammme/bbspace) —— 本项目的一切起点，衷心感谢原作者的开源贡献
- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect)
- [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus)
- [BiliPai](https://github.com/jay3-yy/BiliPai) —— 刷视频与播放链路的对照参考
- [androidx/media](https://github.com/androidx/media)
- [DanmakuFlameMaster](https://github.com/naaammme/DanmakuFlameMaster)

## License

[GPL-3.0](LICENSE)

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=zgxaxdd/bilispace&type=Date)](https://star-history.com/#zgxaxdd/bilispace&Date)
