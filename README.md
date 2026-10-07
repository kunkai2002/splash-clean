# 开屏净 SplashClean

安卓手机上自动跳过开屏广告、关掉弹窗广告与更新提示的开源应用。**不需要 root。**

[English](#english)

## 下载

[Releases](https://github.com/kunkai2002/splash-clean/releases/latest) 里选一个：

| 文件 | 适用 |
|---|---|
| `…-arm64-v8a.apk` | 绝大多数手机（2017 年以后） |
| `…-armeabi-v7a.apk` | 更老的 32 位手机 |
| `…-universal.apk` | 不确定时用这个（较大） |

新版本直接覆盖安装，设置和规则都会保留。

## 它做什么

打开应用后的头几秒，开屏净会依次用下面几层方法找到「跳过」并点掉：

| 层 | 做法 | 需要 |
|---|---|---|
| 1. 规则 | GKD 格式的规则（自带通用规则＋可订阅社区规则，约 1000 个应用的专属规则） | 无障碍服务 |
| 2. 截图认字 | 按钮没有文字信息时（画在画布、视频、Flutter 上），截图后用 PaddleOCR 在手机上找「跳过」 | Android 11+ |
| 3. 教它 | 漏网时点「抓取广告」，在截图上点一下跳过按钮，存成规则 | — |
| 4. 拦截广告网址 | 本机 DNS 过滤（只拦域名查询），让第三方广告联盟的广告载不进来 | 占用 VPN 位置 |
| 5. 防「摇一摇」 | 对选定应用关掉动作感应器，晃手机不再跳转 | 一次授权 |

另外：「一次授权、长期不掉」——在手机上用无线调试配对一次，应用取得 `WRITE_SECURE_SETTINGS`，之后无障碍服务被系统关掉时会自己开回来；重启、更新都不会掉，卸载才会消失。

### 点错了怎么办

- **各应用设置**（规则页或设置页进入）：每个应用一个总开关；点进去可以逐条开关这个应用用到的专属规则、通用规则和截图认字，只影响这个应用。
- **记录里点一下**：哪条记录点到了不是广告的东西，点它 →「关掉」，只在那个应用停用那条规则。
- **自动发现**：同一条规则 10 秒内在同一个应用点了 3 次以上，会先暂停并弹出「是否错误识别广告？」——选「是」就在这个应用关掉它，选「不是」就继续、以后不再问。
- 内置通用规则与截图认字固定不碰常被误认的应用（拼多多、支付宝、美团、TapTap）。

### 更新

设置 → 更新：从 GitHub Releases 检查新版本（打开应用时每天最多自动检查一次），下载后先核对安装包的签名与本应用相同，再交给系统安装；任何一步失败都可以改用浏览器下载。

## 做不到的

- 广告会先闪一下（约 0.2～1 秒）才被点掉；要完全不出现需要 root。
- 淘宝、京东、知乎、B 站等大应用的自家广告，拦截网址拦不住（和正常内容同一个域名），只能靠跳过。
- 开着任何无障碍服务就拒绝运行的银行类应用：请放进「不处理的应用」。
- 微信一律不处理（微信会扰乱第三方无障碍服务，且 2026 年有因点击类工具被封号的报道）。
- 应用被「强行停止」时，Android 会关掉它的无障碍服务；下次打开应用（或开机）会自动恢复。
- 纯血鸿蒙（HarmonyOS NEXT／5 以上）无法安装 APK。

## 安装与设置

1. 安装 APK（一般手机用 `arm64-v8a` 版）。
2. 打开应用 → 首页「设置检查」逐项完成：开启无障碍服务、长期不掉（授权）、社区规则、电池不限制、本机保活设置；小米、OPPO、vivo 等会多一项「应用列表权限」。
   语言在「设置 → 语言」（简体／繁體／English，或跟随系统）。
3. Android 13 以上开启无障碍时提示「受限制的设置」：先试着开一次，再到「应用信息」右上角 ⋮ →「允许受限制的设置」。完成「长期不掉」授权就不需要这一步。

一次授权的两种方式：

- **只用手机（Android 11+）**：设置 → 长期不掉 → 开始 → 开发者选项开启「无线调试」（勾「一律允许使用这个网络」）→「使用配对码配对设备」→ 在通知里输入 6 位配对码。
- **用电脑**：
  ```
  adb shell pm grant io.github.kunkai2002.splashclean android.permission.WRITE_SECURE_SETTINGS
  adb shell appops set io.github.kunkai2002.splashclean ACCESS_RESTRICTED_SETTINGS allow
  ```

小米需另开「USB 调试（安全设置）」；OPPO／一加需开启「禁止权限监控」。

## 兼容性

| Android | 可用功能 |
|---|---|
| 7.0–10 | 规则跳过、教它（无截图）、拦截网址；授权需用电脑 |
| 11+ | 全部功能 |

## 规则

- 规则格式与 [GKD](https://gkd.li/api/) 相同，选择器引擎直接使用 GKD 的 `gkd-selector`（未修改）。
- 内置通用规则在 `app/src/main/assets/builtin_rules.json5`；当启用的社区订阅提供同类全局规则时，内置规则自动让位。
- 社区订阅（例如 [Lin-arm/GKD_subscription](https://github.com/Lin-arm/GKD_subscription)）由作者维护，应用只在你点「添加」时从作者的链接下载，不打包进 APK。

## 隐私

没有广告、没有统计、没有账号。屏幕内容、截图、规则都只在手机上处理；网络只用于下载你添加的规则订阅和拦截清单。

## 从源码构建

需要 JDK 17+、Android SDK（platform 37、build-tools 37.0.0）。

```
./gradlew :app:assembleRelease          # 输出在 app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest        # 单元测试
```

正式签名：`-PsplashCleanSigning=/路径/signing.properties`（内容：`storeFile`、`storePassword`、`keyAlias`、`keyPassword`）。不提供时用 debug 签名。

GitHub Actions（`.github/workflows/build.yml`）每次推送都会跑单元测试并打包；推 `v*` 标签且仓库设了签名密钥（`SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`）时，自动把签好名的 APK 发到 Releases。

`testbed/` 是模拟各种开屏广告的测试应用（穿山甲式、「5s | 跳过」、画在画布上的按钮、更新弹窗、不该点的「跳过片头」、无文字的关闭图标、摇一摇感应器计数），只用于在模拟器上验证，不发布。

## 授权

GPL-3.0（见 `LICENSE`）。使用的第三方代码与数据见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## English

SplashClean automatically skips splash-screen ads and closes pop-up ads and update prompts on Android, **without root**.

Download: [Releases](https://github.com/kunkai2002/splash-clean/releases/latest) — `arm64-v8a` for most phones, `armeabi-v7a` for old 32-bit phones, `universal` if unsure.
UI languages: English, 简体中文, 繁體中文 (Settings → Language).

- **Rules**: GKD-format rules (built-in generic rules + optional community subscriptions covering ~1000 apps), executed through an accessibility service. The selector engine is GKD's own `gkd-selector`.
- **OCR fallback** (Android 11+): when a skip button has no text in the accessibility tree, a screenshot is read on-device with PaddleOCR (PP-OCRv4, ONNX Runtime).
- **Teach it**: capture a missed ad, tap its skip button, save a rule.
- **Ad-server blocking**: a DNS-only local VPN answers ad-network domains with 0.0.0.0 (AWAvenue Ads Rule list).
- **No "shake to open"**: switches off motion sensors for chosen apps (`cmd sensorservice set-uid-state … idle`).
- **Stays on**: a one-time wireless-debugging pairing (no computer needed on Android 11+) grants `WRITE_SECURE_SETTINGS`, so the app turns its accessibility service back on by itself.

Limits: the ad still flashes briefly before it is skipped; in-house ads of big apps cannot be blocked by DNS; WeChat is never touched; HarmonyOS NEXT cannot install APKs.

Build: JDK 17+, Android SDK 37, `./gradlew :app:assembleRelease`. License: GPL-3.0.
