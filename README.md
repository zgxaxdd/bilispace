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
  <a href="https://github.com/zgxaxdd/bilispace/releases/latest">
    <img src="https://img.shields.io/github/downloads/zgxaxdd/bilispace/latest/total?style=flat" alt="latest release downloads" />
  </a>
  <a href="https://developer.android.com/about/versions/13">
    <img src="https://img.shields.io/badge/Android-13%2B-3DDC84?style=flat&logo=android&logoColor=white" alt="android 13+" />
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/github/license/zgxaxdd/bilispace?style=flat" alt="license" />
  </a>
</p>

<p align="center">
  一个强大的第三方哔哩哔哩 Android 客户端。持续施工中...
</p>

<p align="center">
  <a href="https://github.com/zgxaxdd/bilispace/releases"><strong>下载 APK</strong></a>
  ·
  <a href="https://github.com/zgxaxdd/bilispace/issues"><strong>反馈问题</strong></a>
</p>


## 截图

<p align="center">
  <img src="images/shot.png" alt="bilispace screenshot home" width="240" />
  <img src="images/shot2.png" alt="bilispace screenshot player" width="240" />
</p>
<p align="center">
  <img src="images/shot3.png" alt="bilispace screenshot tablet" width="640" />
</p>

## 当前功能

### 账号

- [x] 扫码登录
- [x] 短信登录
- [x] 多账号管理

### 内容

- [x] 首页推荐视频流
- [x] 视频搜索
- [x] 视频详情页
- [x] 评论区
- [x] 动态
- [x] 个人空间
- [x] 稍后再看

### 播放

- [x] 视频播放
- [x] 弹幕
- [x] 听视频
- [x] 直播
- [x] 应用内小窗
- [x] 应用内缓存
- [x] 视频音频一键下载导出

### 互动

- [x] 视频点赞 / 投币 / 收藏 / 一键三连
- [x] 双击点赞
- [x] 评论点赞 / 点踩
- [x] 动态点赞 / 转发

### 社交

- [x] 即时消息

### 其他

- [x] 空降助手（SponsorBlock）
- [x] 发评反诈
- [x] 评论弹幕记录
- [x] 评论历史查询
- [x] 用户关系查询

### 正在补全

- [ ] 字幕
- [ ] 分类搜索
- [ ] 其他细节完善

## 更新日志

### v1.8.04（2026-09-06，bilispace 首个发布）

> 版本口径说明（C-1，2026-10-04 审查报告）：本节标题原先写 `v1.9.0`，与
> `app/build.gradle.kts` 的 `versionName = "1.8.04"`、`git tag`（最新 `v1.8.04`）
> 以及 `dist/` 里的产物名三处都不一致。以 **`app/build.gradle.kts` 的
> `versionCode` / `versionName` 为唯一真相**，此处已对齐。

- **OPPO ColorOS「View 无缝动画」适配**：视频详情页 Activity 化（`VideoDetailActivity`），接入 `OplusViewSeamless` 系统级无缝过渡——首页卡片封面从卡片矩形丝滑展开到全屏，支持跟手、阻断、回弹，返回精准回到卡片当前位置；非 ColorOS 16.1+ 设备自动降级为应用内封面展开动画
- **互动闭环**：视频点赞 / 投币 / 收藏 / 一键三连 / 双击点赞，状态回显与失败回滚
- **评论互动**：评论点赞 / 点踩
- **动态互动**：动态点赞 / 转发
- **空降助手**：SponsorBlock 完整接入与设置页
- **播放器**：CDN 故障切换（`CdnFailoverDataSource`）
- **UI 美化**：卡片组件体系（`BiliCardSurface` / `BiliImagePlaceholder`）、主题间距 token、阻尼滚动（`DampedFlingBehavior`）、全界面视觉打磨
- **基础设施**：分享工具、图片加载 DI、无缝动画兼容层与卡片锚点桥接

## 技术实现

- `Kotlin` + `Jetpack Compose` + `Material 3`
- `Media3` 负责播放，`DanmakuFlameMaster` 负责弹幕渲染
- `Room` 和 `DataStore` 管理本地数据
- `OkHttp` `Retrofit` `Protobuf` 负责网络和协议层
- 自行实现 B 站 gRPC over HTTP/1.1 请求封装
- `OplusViewSeamless`（ColorOS 系统能力，compileOnly）负责系统级无缝过渡

## 下载与运行

- 用户安装：前往 [Releases](https://github.com/zgxaxdd/bilispace/releases) 下载最新 APK
- 系统要求：**Android 13（API 33）及以上** —— 与 `AndroidApplicationConventionPlugin` /
  `AndroidLibraryConventionPlugin` 的 `minSdk = 33` 一致（Liquid Glass 的 AGSL 从 API 33 起可用）
- 开发环境：JDK 17、Android SDK 37（compileSdk 37.2：`haze 2.0.0-beta03` 的 AAR 元数据要求 `minCompileMinorSdk=2`）
- 发布签名：release 构建需通过环境变量注入 `SIGNING_STORE_FILE` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_ALIAS` / `SIGNING_KEY_PASSWORD`

## 说明

bilispace 是一个基于官方 [BBSpace](https://github.com/naaammme/bbspace) 源码衍生的新项目，持续加入互动、动效与体验优化。与哔哩哔哩官方无关。项目主要用于学习、研究和 Android 原生界面实现练习。仓库内涉及的接口信息均来自公开资料整理，仅用于技术交流，不包含破解和付费内容分发。

如果这个项目帮你省下了找实现细节和踩坑的时间，欢迎点个 Star。

## 致谢

- [BBSpace（官方原版）](https://github.com/naaammme/bbspace) —— bilispace 基于官方 BBSpace 源码衍生维护，衷心感谢原作者的开源贡献
- [bilibili-API-collect](https://github.com/SocialSisterYi/bilibili-API-collect)
- [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus)
- [androidx/media](https://github.com/androidx/media)
- [DanmakuFlameMaster](https://github.com/naaammme/DanmakuFlameMaster)

## License

[GPL-3.0](LICENSE)


## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=zgxaxdd/bilispace&type=Date)](https://star-history.com/#zgxaxdd/bilispace&Date)
