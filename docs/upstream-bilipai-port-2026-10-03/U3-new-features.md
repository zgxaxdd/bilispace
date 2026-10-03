# U3 — BiliPai v0.2.4 四项新功能向 bbspace 同步的证据级评估

- 审计对象：`D:\111j\c\_ref\BiliPai-024`（只读）
  - `origin/main` = `6e6bdce36`，tag `v0.2.4`（2026-10-02 23:28:40 +0800）
  - 基线 `34d189620`（2026-10-01 15:47:58 +0800，位于 v0.2.4 之前，`merge-base --is-ancestor` 通过）
- 接收方：`D:\111j\c\_ref\bbspace`（HEAD `a5df3387`，**104 项未提交改动，全程未触碰、零 git 写操作**）
- 方法：`git log 34d189620..origin/main` 定位提交 → `git show --stat/-- <path>` 取改动 → 上游源码逐行读 → bbspace 侧 grep 命中/未命中核实。
- 约束：**无真机**。全文不出现任何帧率/内存/耗时数字；凡只能真机判死的一律进 ⑤「待真机」。

---

## 提交拓扑与功能归属（【事实】）

```
34d189620  (基线)
  └─ 6cac80f45  Release 0.2.4: QR scan authorization, device transfer with
                settings/plugins, user agreement, foldable hinge fixes   ← 功能 1/2/3
       └─ 2fc35a71d  Update CHANGELOG.md
            └─ a83598622  Foldable feed crosses soft crease; scrollable video
                          tab bar                                     ← 折痕放宽 + Tab栏滚动
                 └─ c1ff890df  Fix video tab clipping and background
                               playback recovery                      ← Tab栏裁切修正
                      └─ 483099f98  Make floating video cards land on
                                    release; bump versionCode 425     ← 浮动卡落位
                           └─ 30daebd0b  Extract shared data, playback and
                                         design modules; add TV app   ← 模块抽取
                                └─ 4a79d46bb → 6e6bdce36 (tag v0.2.4)
```

【事实】`30daebd0b` 把 `core-data` / `core-player` / `design-tokens` / `app-tv` 等模块抽离（由 `git log --diff-filter=A` 逐模块核实）；`miuix-navigation` 则早在 `483099f98` 已新增。所以 v0.2.4 tag 状态下的网络层文件在 `core-data/**`，而功能本体（`QrAuthorizationClient.kt`）在 `6cac80f45` 时位于 `app/src/main/java/com/android/purebilibili/core/network/`。本文引用以 **tag v0.2.4（origin/main）路径**为准。

---

## ① 四功能逐项评估表

| 功能 | 上游提交 / 关键文件 | 依赖（下游是否具备） | bbspace 现状 | 结论 | 成本 |
|---|---|---|---|---|---|
| **QR 扫码授权** | `6cac80f45`；`OfficialQrAuthorizationService.kt:39,43,89,127`、`OfficialQrAuthorizationViewModel.kt:29,37,68`、`BilibiliLoginQr.kt:27`、`BiliPaiTransferScanner.kt:46`、`core-data/.../QrAuthorizationClient.kt:11`、`ApiClient.kt:2468,2478,2489,2640,3430`、`AppSignUtils.kt:73,93,103` | ① CameraX——**未命中**；② zxing 解码 API——命中（但只用过生成）；③ android64 appkey/appsec——**命中**（`BiliConstants.kt:21-22`）；④ `AppSigner.sign`——命中；⑤ `access_token`——命中；⑥ passport REST 客户端——命中；⑦ 严格 TLS 白名单客户端——**未命中**；⑧ GET raw 变体——**未命中** | 仅有 TV 式二维码**生成+轮询**（`AuthRepository.kt:45-46`）；**无**扫码授权、**无**相机管线、**无** CAMERA 权限 | **需决策**（可同步，但须引入 CameraX） | 中：约 9–12 文件，1 个新依赖 + 1 个新权限 |
| **设备迁移**（带设置/插件） | `6cac80f45`；`BiliPaiTransferSession.kt:56,77,98,111`、`BiliPaiTransferCrypto.kt:18,24,33,37`、`BiliPaiTransferContent.kt:44,46,67,116`、`QrTransferModels.kt:13,22,37,65`、`BiliPaiTransferChunks.kt`、`AccountSessionStore.kt:185`、`LoginScreen.kt:422` | ① JS 插件运行时+仓储——**未命中**；② 外部 Kotlin 插件包——**未命中**；③ 皮肤包——**未命中**；④ `SettingsShareService`(471 行)+`SettingsManager`(8312 行)——**未命中**；⑤ kotlinx.serialization——部分命中；⑥ RSA/AES-GCM/签名——命中（JDK）；⑦ CameraX 扫码——**未命中**；⑧ 会话导入——等价命中 | `AuthStore.exportAllAccounts:226` / `importAccounts:232`，经 `PrivacyViewModel.kt:76-83` + `PrivacySettingsScreen.kt:229-259` 剪贴板导出导入，**明文 JSON**；无加密、无分块、无设置/插件携带 | **不建议同步**（当前形态）；**考虑单独做加密设备迁移** | 高：≥10 文件 + 需先建 3 套插件/皮肤仓储 |
| **用户协议** | `6cac80f45`；`UserAgreementGate.kt:42,58,128,173`、`UserAgreementStore.kt:6,10,16`、`UserAgreementText.kt:7`、`MainActivity.kt:1641`、入口 `LoginScreen.kt:342` / `SettingsSections.kt:823` / `OfficialQrAuthorizationContent.kt:103` | 仅 Compose + SharedPreferences——**全部命中** | grep `用户协议\|隐私政策\|UserAgreement\|agreement` → **0 命中**。仅有「隐私安全」页（`PrivacySettingsScreen.kt:50`），内容是风控开关/设备信息/清缓存，非协议门 | **可同步**（落地最轻，但文本须重写） | 低：3 新文件 + 3 处入口钩子 |
| **折痕修复 + Tab栏滚动 + 浮动卡落位** | 折痕放宽 `a83598622`；Tab栏滚动 `a83598622`+`c1ff890df`；浮动卡 `483099f98` | ① androidx.window + material3-adaptive Posture——声明未用；② 上游 F1–F6 铰链基建——**未命中**；③ `miuix-navigation`（4502 行 vendored）——**未命中**；④ 连续手势进度 substrate——**未命中** | `hinge/crease/折痕/铰链` → 0 命中；`WindowSizeClass/foldable/isTabletop` → 0 命中；视频详情页**无多 Tab 栏**；返回手势是离散策略 `SwipeBackPolicy.kt:15` | **折痕：不建议**（无 substrate，是"新增折叠适配"而非 bug fix）／**Tab栏：不建议**（无落点）／**浮动卡：不建议**（机制完全不同） | 极低（三项都是 0；见 ③ 合并评估） |

---

## ② 逐项详析

### 2.1 功能 1：QR 扫码授权

**上游实现定位（【事实】）**

链路为 `BiliPaiQrDecoder`/`BiliPaiTransferScanner` 取码 → `parseBilibiliLoginQr` 白名单校验 → `check` → `scene` → `confirm`：

- 扫码与解码：`app/src/main/java/com/android/purebilibili/feature/login/BiliPaiTransferScanner.kt:46-54`（CameraX：`ProcessCameraProvider`/`ImageAnalysis`/`PreviewView`，`STRATEGY_KEEP_ONLY_LATEST`，支持相册 `PickVisualMedia`）；`BiliPaiQrDecoder.kt`（88 行，`MultiFormatReader`+`PlanarYUVLuminanceSource`）。
- 取码白名单：`BilibiliLoginQr.kt:27-44`。仅接受 https:443、`account.bilibili.com/h5/account-h5/auth/scan-web`、`passport.bilibili.com/h5-app/passport/login/scan`、`passport.bilibili.com/x/passport-tv-login/h5/qrcode/auth` 三种 URL，且 key 必须匹配 `[0-9a-fA-F]{32}` 单值。
- 业务逻辑：`OfficialQrAuthorizationService.kt:39`；`:43 prepare()` 先 `validateCookieSession` 验本机登录态、再 GET `check`、GET `scene`；`:89 confirm()` POST `confirm`（WEB）或 `confirmTvQrCode`（TV，`auth_code` 字段 + `Buvid` 头）。
- 签名：`core-data/src/main/java/com/android/purebilibili/core/network/AppSignUtils.kt:73 signForTvApi`、`:93 signForAndroidHdLogin`、`:103 signForAndroid64Login`；`signedWebParams` 在 `OfficialQrAuthorizationService.kt:127-151`，注释明确 "An access_token only pairs with the appkey/appsec that issued it"。
- 网络隔离：`core-data/.../QrAuthorizationClient.kt:11-37`。自带 `X509TrustManager`、禁重定向、`followSslRedirects(false)`，拦截器把目标锁死在 4 个 path 白名单内，否则 `IOException`。
- 端点定义：`ApiClient.kt:2468/2474`（check）、`:2478/2484`（scene）、`:2489/2495`（confirm）、`:2640`（confirmTv）、`:3430`（`qrAuthorizationApi`）。
- UI：`OfficialQrAuthorizationContent.kt:31`；接线 `LoginScreen.kt:224`。
- 测试资产（可直接借鉴做验收）：`app/src/test/java/com/android/purebilibili/feature/login/BilibiliLoginQrTest.kt`、`OfficialQrAuthorizationServiceTest.kt`、`core/network/QrAuthorizationClientTest.kt`。

**依赖度评估（逐条 + bbsppace grep 证据）**

| # | 依赖项 | bbspace 是否存在 | 证据 |
|---|---|---|---|
| 1 | CameraX（core/lifecycle/view） | **未命中** | `gradle/libs.versions.toml` grep `camera` → 0；全仓 `*.kts` grep `camera` → 0 |
| 2 | CAMERA 权限 | **未命中** | `app/src/main/AndroidManifest.xml:5-11` 仅 INTERNET / ACCESS_NETWORK_STATE / FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK / POST_NOTIFICATIONS |
| 3 | zxing 解码能力 | 命中（仅用过生成） | `feature/auth/build.gradle.kts:29 implementation(libs.zxing.core)`；`LoginViewModel.kt:116-117` 只用了 `QRCodeWriter.encode`。zxing-core 同包含 `MultiFormatReader`，【推断】无需加依赖即可解码 |
| 4 | android64 appkey/appsec 对 | **命中** | `BiliConstants.kt:21-22` `APP_KEY_SMS="783bbb7264451d82"` / `APP_SEC_SMS="2653583c8873dea268ab9386918b1d65"`，与上游 `ANDROID64_APP_KEY/SEC`（`core-data/.../AppSignUtils.kt:31-32`）**逐字相同**，仅命名不同 |
| 5 | 参数签名器 | 命中 | `infra/crypto/AppSigner.kt:46 fun sign(params, appKey, appSec)` |
| 6 | `access_token` | 命中 | `core/auth/AuthStore.kt:54 val accessToken`（由短信登录 `token_info.access_token` 落库，`AuthRepository.kt:373`） |
| 7 | passport 端点 REST 客户端 | 命中 | `infra/network-http/BiliRestClient.kt:35 postSigned / :73 getSigned / :90 postSignedRaw`；`BiliRestProfile.kt:35 SMS`；`AuthRepository.kt:45-53` |
| 8 | 严格 TLS + 白名单专用客户端 | **未命中** | bbspace 全部走注入的共享 `OkHttpClient`（`BiliRestClient.kt:26`），无按用途新建 client 的先例 |
| 9 | GET 的 raw 变体（容忍 code≠0） | **未命中** | `BiliRestClient.kt:73 getSigned` 强制 `requireSuccess`；raw 形态只有 `:90 postSignedRaw` |

**bbspace 现状（【事实】）**

- 已有 TV 协议二维码**登录**（自己当被扫方）：`AuthRepository.kt:45 QR_AUTH_CODE_ENDPOINT`、`:46 QR_POLL_ENDPOINT`、`:64 getQrCode()`、`:90 pollQrCode()`；展示 `feature/auth/login/LoginScreen.kt:117 QrCodeContent`、`:142 "请使用 B 站 APP 扫描二维码"`。
- **无**"扫码授权"（本机已登录、去确认别人设备发起的登录请求）。grep `扫码\|二维码\|QR\|Qr\|qr_code\|qrcode` 共 62 命中，无一涉及 `web/qrcode/check|scene|confirm`。
- **无**相机扫码：grep `cameraX|ImageAnalysis|barcode|MLKit|analyzer` 仅命中 zxing 生成与 `ril.barcode` 无关串。
- 无用户协议门。

**同步结论：需决策。**

- 支持同步的理由：**签名、access_key、REST 客户端、TV 端点经验全部就位**，上游最硬的"android64 平台配对"问题在 bbspace 已经被 `BiliRestProfile`（`BiliRestProfile.kt:11-43`，注释"调用方必须显式选择 profile"）优雅解决——只需新增一个 profile 或复用 `SMS`。真正的业务逻辑（约 300 行：`BilibiliLoginQr`+`Service`+`ViewModel`）是纯 JVM 可测代码。
- 阻碍：必须引入 **CameraX + CAMERA 权限**。这是 bbspace 目前完全没有的一类依赖（相机/权限/预览生命周期），对"隐私优先"定位的第三方客户端是新面。

**若同步：落点 / 改动面 / 验收 / 风险**

① bbspace 侧落点
- `core/auth/.../AuthRepository.kt` 内新增 3 个端点常量与 2 个 suspend 方法（`prepareWebQrAuthorization` / `confirmWebQrAuthorization`），与 `AuthRepository.kt:45` 现有端点常量同风格；或新开 `core/auth/.../QrAuthorizationRepository.kt`（更干净，独立 Hilt 注入）。
- `infra/network-http/.../BiliRestProfile.kt:35` 之后新增 `QR_AUTH`（appKey/appSec 取 `BiliConstants.APP_KEY_SMS/APP_SEC_SMS`，build/mobi_app 取 `BiliConstants.BUILD_STR/MOBI_APP`）。
- `infra/network-http/.../BiliRestClient.kt` 新增 `getSignedRaw`（GET + 不检查 code），与 `:90 postSignedRaw` 对称。
- 扫码 UI：新路由 `QR_AUTH_ROUTE`（`feature/auth/.../navigation/LoginNavigation.kt:9` 旁），`SmsLoginScreen.kt:230-231` 已有的 "扫码登录" `TextButton` 旁边新增"扫码授权"入口；UI 放 `feature/auth/login/`。
- 相机：`feature/auth/build.gradle.kts` 加 CameraX；`app/src/main/AndroidManifest.xml` 加 `CAMERA` 权限与 `android.hardware.camera.any`（`required="false"`）。

② 改动面：约 **9–12 个文件**；新增依赖 **1 组（CameraX ~4 artifact）**；新增权限 1 项；无新增模块。

③ 静态可判死的验收（编译 / JVM 单测 / 源码结构断言）
- `parseBilibiliLoginQr` 等价实现 + 单测（正例 3 种 URL；反例：http scheme、端口≠443、URL 带 userinfo、key 非 32-hex、`qrcode_key` 出现多次、长度 >4096）。
- 签名黄金向量：`AppSigner.sign(固定参数, BiliConstants.APP_KEY_SMS, BiliConstants.APP_SEC_SMS)` 产出固定 MD5 串。
- profile 断言：`BiliRestProfile.QR_AUTH.appKey == BiliConstants.APP_KEY_SMS && appSec == APP_SEC_SMS`。
- 源码结构守卫（bbspace 已有此惯例，例：`app/src/test/java/com/naaammme/bbspace/navigation/NavBackHandlingGuardTest.kt:36`）：断言 `AndroidManifest.xml` 含 `android.permission.CAMERA`；`feature/auth/build.gradle.kts` 含 camera 依赖；`AuthRepository.kt`（或新仓储）含 4 个 `web/qrcode/*` 端点常量。
- 编译：`:app:assembleDebug` + `:app:testDebugUnitTest`。

④ 风险与回滚
- **端点是 B 站非公开契约**：上游注释自述 "Contract verified by PiliPlus issue #2933 against the live API"（`OfficialQrAuthorizationService.kt:36-37`）——契约随时可变，无静态验收能覆盖。
- **access_key 平台配对**：【推断】bbspace 的 `access_token` 由 `BiliRestProfile.SMS`（`BiliRestProfile.kt:35`）签发，而上游 `signedWebParams` 有 `accessTokenPlatform` 分支区分 tv / android-hd。若配对选错，服务端返回"鉴权失败"。**属待真机**。
- 严格 TLS 白名单客户端若不实现，退化为"共享 client + 白名单 path"，安全性下降；建议至少实现 `followRedirects(false)`。
- 回滚：删除 `QR_AUTH` profile / 新路由 / 新仓储 + 移除 CameraX 依赖与权限，3 步可逆，无数据迁移。

**安全/隐私含义**：不向任何第三方传输本机凭据——`OfficialQrAuthorizationService.kt:35` 注释 "Bilibili delivers credentials to the requesting device's poll; this client only authorizes"：授权只发签名参数给 `passport.bilibili.com`，凭据由 B 站服务端直接投递给请求方设备。【事实】。**需要网络后端配合 = 仅 B 站公开 passport 端点，无 BiliPai 自有后端**，因此不存在"上游私有后端导致 bbspace 无法复用"的问题。真正的自有钱是 `QrAuthorizationClient.kt:11` 的 TLS 白名单隔离（防 access_key 随重定向外泄）。

---

### 2.2 功能 2：设备迁移

**上游实现定位（【事实】）**

- 会话/状态机：`BiliPaiTransferSession.kt:56`；`:63 beginReceive()` 生成请求 QR；`:77 createEnvelope()` 收集内容→加密→编解码→分块；`:98 acceptEnvelope()` 聚合分块；`:111 confirmImport()` 导会话 + 逐项导入内容。
- 加密封包：`BiliPaiTransferCrypto.kt:18`；`:19-21` `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`（包 AES key）+ `AES/GCM/NoPadding`（128-bit tag）+ `SHA256withRSA`（签名）；`:50-79 encryptSerialized`、`:81-105 decryptSerialized`。
- 载荷模型：`QrTransferModels.kt:13 BiliPaiTransferRequest`、`:22 BiliPaiTransferEnvelope`、`:37 BILIPAI_TRANSFER_PAYLOAD_VERSION=2`、`:65 BiliPaiTransferPayload`（settingsJson / jsonRules / jsPlugins / skins / kotlinPlugins 五类内容）、`:78 BiliPaiTransferCodec`。
- 分块：`BiliPaiTransferChunks.kt`；容量常数 `QrTransferModels.kt:119-125`（单码 90_000 字符、聚合约 200_000）。
- **插件/皮肤/设置耦合点**：`BiliPaiTransferContent.kt:5-9` 三个 import 即耦合全貌——`core.plugin.kotlinpkg.ExternalKotlinPluginInstallStore`、`core.plugin.js.BiliPaiJsPluginInstallStore`、`core.plugin.skin.UiSkinInstallStore`、`core.store.SettingsManager`、`feature.settings.share.SettingsShareService`。
  - `:46 inventory()` 枚举可传项；`:67 collect()` 采集；`:116 apply()` 逐项落地（`:132` JS 插件、`:145` 皮肤、`:156` Kotlin 插件包、`:171 applyJsonRules` 直接写 `shared_prefs/json_plugins.xml` 与 `filesDir/json_plugins/`）。
  - `:219 BiliPaiTransferSettingsBridge` 是 `SettingsShareService`（471 行）的内存内薄适配。
- 会话落地：`core-data/.../core/store/AccountSessionStore.kt:185 importTransferredSession`。
- UI：`LoginScreen.kt:411 BiliPaiTransferEntry`、`:422 BiliPaiTransferDialog`（发送端翻页、接收端连续扫码聚合、逐项导入报告）。

**依赖度评估（逐条 + bbspace grep 证据）**

| # | 依赖项 | bbspace 是否存在 | 证据 |
|---|---|---|---|
| 1 | JS 插件系统（`BiliPaiJsPluginInstallStore.kt:19` + JS 运行时） | **未命中** | grep `QuickJS\|Rhino\|ScriptEngine\|PluginCapability\|皮肤\|skin` → 仅 1 条无关注释；grep `plugin\|Plugin` 23 命中全是 build-logic 约定插件 |
| 2 | 外部 Kotlin 插件包（`ExternalKotlinPluginInstallStore.kt:31`） | **未命中** | 同上 |
| 3 | 皮肤包系统（`UiSkinInstallStore.kt:10`） | **未命中** | 同上 |
| 4 | 设置分享服务（`SettingsShareService.kt:51`，471 行）+ `SettingsManager.getShareableSettingsEntryDefinitions():8321` | **未命中** | grep `SettingsShare\|ShareService\|createExportArtifact` → 0；bbspace 只有 `AppSettings.kt`（848 行，DataStore） |
| 5 | kotlinx.serialization 载荷 | 部分命中 | `infra/network-http/build.gradle.kts:5,27`、`core/swipe/build.gradle.kts:17` 已引 plugin+json；但 auth 层现状用 `org.json` |
| 6 | RSA/AES-GCM/签名/Base64 | 命中（JDK 内置） | — |
| 7 | 相机扫码 + CameraX | **未命中** | 同 2.1 依赖 #1 |
| 8 | 会话导入能力 | 等价命中 | `AuthStore.kt:141 saveCredential` / `:232 importAccounts` |
| 9 | 传输层 | N/A | 全程 QR 离线，无网络后端 |

**bbspace 现状（【事实】）**

- 账号迁移**已有原始等价物**：`AuthStore.kt:226 exportAllAccounts()` 产出 JSON（含 `access_token` / `refresh_token` / `cookies`，见 `:248-257 credentialToJson`）、`:232 importAccounts(json)`；入口 `PrivacyViewModel.kt:76-83` → `PrivacySettingsScreen.kt:229-259`「导出到剪切板」/「从剪切板导入」。
- **该实现是明文**，且落在系统剪贴板（可被其它应用读取）。这是 bbspace 现存的安全缺口。
- 无"设备迁移/设备传输"产品语义：grep `设备迁移\|设备传输\|deviceTransfer\|BiliPaiTransfer` → **0 命中**。
- 无任何设置/插件/皮肤的"可分享集合"概念。

**同步结论：不建议同步（按 v0.2.4 当前形态）。**

理由：
1. 上游这个功能的**一半以上价值绑定在 bbspace 不存在的插件生态**上——`BiliPaiTransferContent.kt:86-111` 五类内容里三类（JS 插件 / 皮肤 / Kotlin 插件包）在 bbspace 是空集合；同步过来只能传"登录态 + 设置"，那就是一个**缩水版**。
2. 为了这半功能去引入上游插件架构（`plugin-sdk` 模块 + 三类 install store + JS 运行时 + 能力授权模型），成本远超收益，且与 bbspace 现有整洁的 `core:* / feature:* / infra:*` 分层冲突。
3. 上游 `BiliPaiTransferContent.kt:171-188` 直接向 `shared_prefs` 写 XML、向 `filesDir` 写插件文件，这是**跨进程文件写入型导入**，安全面大；在 bbspace 现有的多账号/级联清理体系（`AuthStore.kt:198-224 perAccountCleaners`）里落地需要重新设计。

**若要保留"设备迁移"这个用户价值 → 建议的独立方案（需决策）**

- 只做"加密账号迁移"：把 `BiliPaiTransferCrypto.kt:18-117`（117 行，纯 JVM、无上游耦合）+ `QrTransferModels.kt:13-32,78-118` 的编解码搬过来，替换 bbspace 现在的**明文剪贴板**导出（`PrivacySettingsScreen.kt:229-259`）。
- 落点：`core/auth` 新增 `SecureAccountTransfer.kt`（crypto + codec）+ `PrivacyViewModel.kt:76-83` 两方法改写；UI 复用现有隐私页两个按钮。
- 改动面：**2–3 个文件**，0 新依赖。
- 静态验收：单测覆盖 `encrypt→decrypt` 往返、版本不匹配拒绝（对应上游 `BiliPaiTransferSession.kt:106`）、过期拒绝（`BiliPaiTransferCrypto.kt:89`）、签名篡改拒绝（`:94-96`）、分块 `split→join` 往返与乱序/缺失（`BiliPaiTransferChunks.kt`）；结构守卫断言 `PrivacyViewModel` 不再出现明文 `arr.toString(2)` 之类。
- 安全含义（重点）：**这是四项里唯一真正传输凭据的功能**。上游用 E2E 加密是必要设计；bbspace 现状明文剪贴板导出应予修复（独立于"是否同步上游"）。

---

### 2.3 功能 3：用户协议与隐私政策

**上游实现定位（【事实】）**

- 门：`feature/agreement/UserAgreementGate.kt:42 UserAgreementGate(content)`（未同意则只渲染协议页）；`:58 UserAgreementGateScreen`，`:63-65` 注释说明"同意按钮只有在滚到全文末尾后才解锁"（`LazyListState.layoutInfo` + `derivedStateOf`，`:67-79`）；`:118-121`「不同意并退出」`activity.finishAffinity()`。
- 持久化：`UserAgreementStore.kt:6`；`:10 isAccepted = acceptedVersion >= USER_AGREEMENT_VERSION`；`:16 accept()`。
- 版本与正文：`UserAgreementText.kt:7 const val USER_AGREEMENT_VERSION = 1`；`:5` 注释"文本更新时递增，所有用户需重新确认"。
- 正文渲染：`UserAgreementGate.kt:128 UserAgreementBody`；`:140 URL_REGEX` + `:144 LinkifiedText`（URL 高亮可点）。
- 常驻入口：`LoginScreen.kt:342`、`SettingsSections.kt:823`、`OfficialQrAuthorizationContent.kt:103`；`UserAgreementGate.kt:173 UserAgreementReviewDialog` 只读弹窗。
- 根挂载：`MainActivity.kt:1641` 包住 `AppNavigation`。
- 上游正文覆盖 GPL v3 开源性质、免费/反倒卖、隐私与数据处理（Crashlytics/Analytics 披露）、扫码登录风险、手机号/验证码/密码不存储。

**依赖度评估**：仅 Compose（`LazyColumn`/`Surface`/`AnnotatedString`）+ SharedPreferences。**bbspace 全部具备**（`MainActivity.kt:120` 已在用 `getSharedPreferences`）。

**bbspace 现状（【事实】）**

- grep `用户协议\|隐私政策\|UserAgreement\|agreement` → **0 命中**（首次启动无任何同意门）。
- 最近的相邻物是「隐私安全」页 `PrivacySettingsScreen.kt:50`（`TopAppBar title = "隐私安全"`），内容是风控上报开关、设备/会话信息、清缓存——**不是**协议。
- bbspace 无 Firebase/Crashlytics/Analytics（grep `firebase|crashlytics|analytics` in `*.kts` → 0；`*.kt` 中 `Firebase|Crashlytics` → 0）；但有 B 站侧 Gaia 应用列表上报与开关（`infra/network-grpc/.../GaiaReporter.kt:33-39`、`AppSettings.kt:502 block_gaia`、`PrivacySettingsScreen.kt:99-102`）。
- LICENSE 为 GPL v3（674 行）。

**同步结论：可同步**（成本最低、无架构冲突），但**正文必须为 bbspace 重写**——上游文本声明 Firebase，bbspace 没有；bbspace 需要声明的是 Gaia 设备应用列表上报。这属法律/合规判断，本文只指出事实差异。

**落点 / 改动面 / 验收 / 风险**

① 落点
- 新模块目录 `feature/settings/src/main/java/com/naaammme/bbspace/feature/settings/agreement/`（与既存 `about/`、`privacy/`、`appearance/` 平级），放 `UserAgreementGate.kt` / `UserAgreementStore.kt` / `UserAgreementText.kt`。
- 根挂载：`app/src/main/java/com/naaammme/bbspace/MainActivity.kt:261-269`——把 `AppNavHost(...)` 包进 gate（与上游 `MainActivity.kt:1641` 同构）。
- 入口：`feature/settings/.../about/AboutScreen.kt`（`:161-209` 一整片 `LinkCard` 模式）加一条「用户协议与隐私政策」；`feature/auth/login/LoginScreen.kt` 登录页脚注加一条。

② 改动面：**3 个新文件 + 3 处修改**，0 新依赖，0 新权限。

③ 静态可判死的验收
- 单测：`UserAgreementStore` 的 `isAccepted(版本 0→false / 1→true / 2→false)`、`accept()` 后 `acceptedVersion==USER_AGREEMENT_VERSION`。
- 单测：`USER_AGREEMENT_VERSION` 与 `UserAgreementText.SECTIONS` 非空、每条 `bodyLines` 非空、至少含一条 URL（对应 `LinkifiedText` 路径）。
- 源码结构守卫（bbspace 惯例，见 `NavBackHandlingGuardTest.kt:36-58`）：`MainActivity.kt` 中 `AppNavHost(` 必须位于 `UserAgreementGate` 组合调用之内（缩进/嵌套计数断言）；`AboutScreen.kt` 必须出现协议入口 `LinkCard`。
- 编译：`:app:assembleDebug` + `:app:testDebugUnitTest`。

④ 风险与回滚：无运行时风险（无网络、无权限、无数据迁移）。回滚 = 删 3 文件 + 还原 `MainActivity.kt` 一行包裹。唯一风险是**产品风险**：协议文本一旦上线即构成公开承诺，需法务确认；且老用户升级后不会重新弹门（`isAccepted` 只在版本号递增时才拦）——这与上游语义一致。

---

## ③ 折痕 / Tab 栏 / 浮动卡 三项"小改动"的合并评估

这三项上游自身改动量确实极小，但**三者在 bbspace 的落点均为 0**。分开说：

### (a) 折叠屏折痕放宽（`a83598622`）
- 上游改动：`AppHingeSafeContent.kt:33-45`。核心 6 行——`val hinges = adaptiveInfo.foldingFeature.layoutHinges()`，然后 `if (hinges.all { !hinge.isOccluding }) { BoxWithConstraints(modifier, savedContent); return }`，即"非遮挡铰链一律允许内容整体跨越"；`:38-41` 是一段 `Log.d` 诊断。
- 注释自述根因（`AppHingeSafeContent.kt:34-37`）：Jetpack 把半开的 FOLD 上报为 `isSeparating=true`，按 separating 避让会把信息流钳进单侧 pane。
- **但这是一个补丁，不是功能**：它修补的是上游在 `6cac80f45` 里新建的 F1–F6 铰链适配体系（`core/util/HingeLayoutPolicy.kt:6-12 AppHingeFeature`、`:40 resolveHingeSafeContentRegions`、`:140 resolvePreferredHingeSafePane`、`core/ui/adaptive/AppHingePaneLayout.kt`、`HingeSafeOverlayRegionsPolicy.kt`、`MainActivity.kt:1516-1519 LocalHingeSafeOverlayRegions`、测试 `HingePaneUiRegressionTest.kt`）。
- bbspace 侧核实（【事实】）：grep `hinge|Hinge|crease|Crease|折痕|铰链` → **0 命中**；grep `WindowSizeClass|foldable|Folding|isTabletop|windowAdaptiveInfo|WindowInfoTracker` → **0 命中**；`libs.versions.toml:114` 有 `androidx-window`，但仅 `core/designsystem/build.gradle.kts:22 api(libs.androidx.window)` 声明，`core/designsystem` 源码里 `androidx.window|WindowMetrics|isTabletop|folding` → **0 命中**（即声明未用）。
- **结论：不建议同步**。bbspace 没有"折痕避让"实现，也就没有"被折痕钳住"的 bug。要做等于从零引入折叠屏适配（依赖 `androidx.window.layout` + `androidx.compose.material3.adaptive.Posture`，见 `BiliPai-024/app/src/main/java/com/android/purebilibili/core/util/WindowSizeUtils.kt:320 Posture.toAppFoldingFeatureInfo`），并要在所有 feed/dialog/live-split/bangumi/video-detail 挂安全区——那是上游一整个 release 的工作量，不是 45 行 diff。

### (b) 视频 Tab 栏可滚动（`a83598622` + `c1ff890df`）
- 上游改动：`VideoContentSection.kt:2019 VideoContentTabBar`；`:2067 val tabBarScrollState = rememberScrollState()`；`:2069-2084 LaunchedEffect(selectedTabIndex, …)` 计算视口宽（`viewportPx = tabWidthPx * tabs.size - maxValue`）并把选中项 `animateScrollTo` 进可视区；`:2117 .horizontalScroll(tabBarScrollState)`；`:2116` 外层 `Modifier.weight(1f, fill=false)`。`c1ff890df` 再把 `weight(1f, fill=false)` 改为 `weight(1f)`、去掉 `Spacer(weight(1f))`，解决"与 Spacer 平分后裁切"。
- bbspace 侧核实（【事实】）：`feature/video` 全模块 grep `FilledTabRow\|tabs =` → **0 命中**；bbspace 的 `FilledTabRow.kt:53` 是固定分段控件（`:159 val segmentWidth = maxWidth / tabs.size`，按 tab 数**平分全宽**，天然不会溢出）；视频详情页没有"多 Tab + HorizontalPager"结构。
- **结论：不建议同步**。没有可溢出的 Tab 栏，修复无落点。若将来 bbspace 视频详情页要加 Tab，这段 `rememberScrollState` + 视口宽反推算法（`:2073`）可以直接照搬——作为**未来参考实现**留存，不作为本次同步项。

### (c) 浮动视频卡落位（`483099f98`）
- 上游改动：新增 `miuix-navigation` 本地模块（38 文件 / 4502 行，`settings.gradle.kts:84 include(":miuix-navigation")`，`app/build.gradle.kts` 从 `libs.miuix.navigation`（GitHub Packages Maven）切到 `project(":miuix-navigation")`）+ 20 行 `navigation3/predictiveback/VideoCardBackCompletionPolicy.kt:7`（`shouldCommit/shouldCommitOnCancel`，`FLOATING_CARD_PROGRESS=0.65f`、`RESTORE_DETAIL_PROGRESS=0.35f`）+ `BiliPaiNavDisplayHost.kt:760` 一行 `backCompletionPolicy = if (cardMorphAvailable) VideoCardBackCompletionPolicy else null`。附带 52 行 JVM 单测 `VideoCardBackCompletionPolicyTest.kt`。
- 机制本质：给 `NavDisplay` 的预测性返回手势加一个"进入浮动卡模式后，落位判据从速度切换为峰值进度"的策略。**它是一段纯函数**（20 行，可完整搬走且带测试）。
- bbspace 侧核实（【事实】）：无 `miuix-navigation`（`core/designsystem/build.gradle.kts:30` 只有 `libs.miuix.blur`）；grep `miuix|zxing|camera` in `*.kts` 仅 3 条，无导航库；无 `NavDisplay`/`NavBackCompletionPolicy` 对应物。bbspace 的返回链路是 androidx Navigation-Compose `NavHost` + `predictivePopEnterTransition/predictivePopExitTransition`（`AppNavHost.kt:488-495`）+ OEM 无缝动画（`core/designsystem/seamless/OplusViewSeamlessCompat.kt:33`）；返回手势本身是**离散策略** `core/swipe/SwipeBackPolicy.kt:15`（`resolve(anySheetOpen, inSheetCloseGrace) → CloseSheet/Swallow/PassThrough`，见 `PortraitSwipePager.kt:457-473`），**没有连续的 shrink progress 供策略挂载**。
- **结论：不建议同步**。策略函数本身可搬（`shouldCommit` 的 6 条单测也很有价值），但没有 substrate。除非哪天 bbspace 引入上游同款 miuix-navigation 手势管线，否则这是死代码。
- 【推断】bbspace 的卡片返回观感问题（若有）在 OEM `OplusViewSeamless` 体系内解决，与上游的 `NavBackCompletionPolicy` 是两条完全不同的技术路线。

---

## ④ 建议同步顺序（低成本高收益优先）

| 顺序 | 项 | 理由 | 预估改动面 |
|---|---|---|---|
| **1** | **用户协议门**（功能 3） | 0 依赖、0 权限、无架构冲突、合规价值直接；3 新文件 + 3 处钩子 | ~6 文件 |
| **2** | **账号迁移加密化**（功能 2 的瘦身版） | 顺手修掉 bbspace **明文剪贴板导出凭据**的现存缺口；搬上游 `BiliPaiTransferCrypto.kt`（117 行纯 JVM，自带往返/篡改/过期可测点） | 2–3 文件，0 依赖 |
| **3** | **QR 扫码授权**（功能 1） | 收益真实（手机确认网页/TV 登录），基础设施命中率最高；但要引入 CameraX + CAMERA 权限，需产品侧拍板 | 9–12 文件，1 依赖 + 1 权限 |
| **4** | **折痕 / Tab 栏 / 浮动卡** | 全部**无落点**。建议只把上游两个纯函数算法作为参考归档：`AppHingeSafeContent.kt:42` 的非遮挡铰链放行判据、`VideoContentSection.kt:2069-2084` 的 Tab 视口宽反推 + `VideoCardBackCompletionPolicy.kt:7` 的浮动卡落位判据 | 0（本次不同步） |

**明确不建议为了它们引入上游架构**：插件系统（`plugin-sdk` + JS 运行时 + Kotlin 插件包 + 皮肤包）、`miuix-navigation`、上游铰链适配体系。这三者在 bbspace 的对应物都是 0，引入即架构负债。

---

## ⑤ 只能真机判死（静态不可判）清单

| 项 | 为什么静态判不了 |
|---|---|
| QR 授权 4 个端点实际返回契约 | B 站非公开契约（上游注释自述对照 PiliPlus issue #2933 实测），`check/scene/confirm` 的 code 语义、`-400` 复现条件、`location/locationDiffers/transient` 字段真实性 |
| access_key 与 appkey/appsec 的平台配对 | `signedWebParams`（`OfficialQrAuthorizationService.kt:146-150`）按 `accessTokenPlatform` 分支；bbspace 的 `access_token` 由 `BiliRestProfile.SMS` 签发，配对是否正确只能实机验 |
| CameraX 预览在 bbspace 现有全屏/沉浸式/PIP 生态下的取码成功率 | 依赖 `VideoScreen.kt:388-392` 的 `WindowInsetsControllerCompat.hide(systemBars)` 等交互 |
| 相册大图解码耗时与内存 | 违反本次无真机约束，不评估 |
| 用户协议"必须滚到末尾"的大字号/横屏可用性 | `UserAgreementGate.kt:67-79` 依赖 `layoutInfo`，纯 Compose 运行时行为 |
| 折叠机 FOLD 半开被上报为 `isSeparating` 的实际规避效果 | bbspace 无 substrate，该问题不存在 |
| 加密账号迁移的 QR 分块扫码体验（发送端翻页、接收端聚合） | 多次扫码需真机 |

---

## ⑥ 安全/隐私专项结论（QR 授权 / 设备迁移 / 用户协议）

1. **QR 扫码授权**：不向第三方外传本机凭据（`OfficialQrAuthorizationService.kt:35` 注释为证），凭据由 B 站服务端直接投递给请求方设备。**无 BiliPai 自有后端依赖**——全部打 `passport.bilibili.com` 公开端点，因此不存在"上游私有后端导致 bbspace 不可复用"的阻断项。必须自建的是 `QrAuthorizationClient.kt:11-37` 的 TLS 白名单隔离（防 access_key 随 30x 重定向外泄）；bbspace 目前**没有**按用途隔离 OkHttpClient 的机制，若同步而不实现该隔离，会新增一个凭据外泄面。
2. **设备迁移**：四项中**唯一真正搬运凭据**的功能。上游用 RSA-OAEP 包 AES-256-GCM key + RSA 签名（`BiliPaiTransferCrypto.kt:19-21`），并做版本/过期/transferId/签名四重校验（`:88-96`）。bbspace 现状 `AuthStore.kt:226-246` 是**明文 JSON 走系统剪贴板**，含 `access_token`/`refresh_token`/`cookies`（含 SESSDATA）。**这是 bbspace 现存安全问题，与是否同步上游无关，建议优先修。**
3. **用户协议**：不涉凭据传输、无网络后端。但正文含隐私承诺文本：bbspace **没有** Firebase/Crashlytics/Analytics（grep 0 命中），**有** B 站 Gaia 设备应用列表上报与"禁止 Gaia 上报"开关（`GaiaReporter.kt:33-39`、`AppSettings.kt:502`、`PrivacySettingsScreen.kt:99-102`）。上游正文不可照抄，须按 bbspace 实际依赖重写。

---

## 附录 A：grep 证据汇总（bbspace）

| 模式 | 结果 | 关键命中 / 说明 |
|---|---|---|
| `用户协议\|隐私政策\|UserAgreement\|agreement` | **0** | 无协议门 |
| `扫码\|二维码\|QR\|Qr\|qr_code\|qrcode` | 62 | 全在 TV 二维码生成+轮询、QQ 群卡片、zxing 常量串 |
| `cameraX\|ImageAnalysis\|barcode\|Barcode\|MLKit\|analyzer` | 3 | 仅 zxing 生成（`LoginViewModel.kt:6,117`）与 `ril.barcode` |
| `设备迁移\|设备传输\|deviceTransfer\|BiliPaiTransfer` | **0** | 无 |
| `hinge\|Hinge\|crease\|Crease\|折痕\|铰链` | **0** | 无折叠适配 |
| `WindowSizeClass\|foldable\|Folding\|isTabletop\|windowAdaptiveInfo\|WindowInfoTracker` | **0** | 无 |
| `QuickJS\|quickjs\|Rhino\|ScriptEngine\|PluginCapability\|UiSkin\|皮肤\|skin` | 1 | 仅一条无关注释（`VideoPlayerPane.kt:2223`） |
| `plugin\|Plugin` | 23 | 全部是 `build-logic/**` Gradle 约定插件 |
| `camera\|zxing`（`*.kts`） | 2 | `libs.versions.toml:60,162` zxing；`feature/auth/build.gradle.kts:29` |
| `firebase\|crashlytics\|analytics\|umeng\|bugly`（`*.kts`） | **0** | 无统计 SDK |
| `SettingsShare\|ShareService\|createExportArtifact` | **0** | 无设置分享服务 |
| `miuix`（`*.kts`） | 1 | `core/designsystem/build.gradle.kts:30 api(libs.miuix.blur)` |

## 附录 B：上游关键文件行数（改动面估算依据）

| 文件 | 行数 |
|---|---|
| `feature/login/BiliPaiTransferScanner.kt` | 377 |
| `feature/login/BiliPaiTransferContent.kt` | 229 |
| `feature/login/QrTransferModels.kt` | 126 |
| `feature/login/BiliPaiTransferCrypto.kt` | 117 |
| `feature/login/OfficialQrAuthorizationService.kt` | 173 |
| `feature/login/LoginScreen.kt` | 1229（本次 +335） |
| `feature/agreement/UserAgreementGate.kt` | 184 |
| `feature/agreement/UserAgreementText.kt` | 77 |
| `core/plugin/js/BiliPaiJsPluginInstallStore.kt` | 112 |
| `core/plugin/kotlinpkg/ExternalKotlinPluginInstallStore.kt` | 180 |
| `core/plugin/skin/UiSkinInstallStore.kt` | 219 |
| `feature/settings/share/SettingsShareService.kt` | 471 |
| `miuix-navigation`（整模块） | ~4502 |

---

*本报告为只读取证产物，未修改 `_ref/BiliPai-024`、`_ref/BiliPai`、`_ref/bbspace` 任何文件，未对 bbspace 执行任何 git 写操作。*
