# 广告快跳（AdAuto）

一个 Android 悬浮球助手：**常驻屏幕左上角的球形助手 + 无障碍自动跳过广告**。

- 应用以**悬浮球**形态常驻在屏幕左上角，可随意拖动。
- 通过**无障碍服务（Accessibility）**读取界面内容，无需 Root。
- 检测到界面进入广告状态（例如出现 **"上滑继续观看短剧"**、"跳过广告"、"关闭广告" 等），自动执行对应操作跳过广告。

> 🎯 **主要适配应用：红果免费短剧 App**（以及同类短剧 App），内置规则以其广告形态为准。
> 其它 App 的广告形态可能不同、无法全部识别——可在"规则设置"里自行追加关键词扩展，
> 或把**广告截图 + App 名称**提交到项目 Issues，我们会针对性补充规则。

---

## 功能特性

| 特性 | 说明 |
| --- | --- |
| 悬浮控制球 | 屏幕左上角显示球形助手，可拖动；点击快捷开关"自动跳过"，长按打开设置页 |
| 无障碍自动跳过 | 监听界面变化，检测到广告后自动点击"跳过/关闭"或执行上滑手势 |
| 短剧上滑识别 | 识别"上滑继续观看短剧 / 上滑继续"等关键字并自动上滑 |
| 自定义关键词 | 可在设置页追加"跳过按钮"关键词，支持中英文逗号/顿号分隔 |
| 倒计时广告识别 | 识别"X秒后可继续 / 倒计时 / 后继续观看"等文案，倒计时结束后自动点击"继续观看"按钮 |
| 防误触 | 点击类规则默认要求出现"广告/倒计时"上下文，降低误触风险；内置节流防重复触发 |

## 支持的广告类型（内置规则）

1. **上滑继续观看短剧**：页面出现"上滑继续观看/上滑继续/继续观看短剧" → 自动执行**上滑**手势
2. **跳过广告**：出现"跳过 / 跳过广告 / 跳过此广告 / skip" → 自动**点击**
3. **关闭广告**：出现"关闭 / 关闭广告 / ×"，且页面有广告上下文 → 自动**点击**
4. **知道了/确定**：广告弹窗提示 → 自动**点击**
5. **倒计时广告**：出现"X秒后可继续 / 倒计时 / 后继续观看"等 → 判定为广告页；倒计时结束出现"继续观看/继续播放"按钮 → 自动**点击**

> 广告上下文判定：页面出现"广告"字样，或出现倒计时（如 "5秒"、"3s"、"X秒后可继续"）。

## 工作原理

```
其他应用界面变化
      │  AccessibilityEvent (TYPE_WINDOW_STATE_CHANGED / CONTENT_CHANGED)
      ▼
AdSkipAccessibilityService（无障碍服务）
      ▼
AdDetector：遍历当前窗口节点树（BFS，仅保留可见节点）
      ▼
AdRules：关键字匹配
      ├─ 命中"上滑继续观看短剧"  → dispatchGesture 上滑
      ├─ 命中"跳过/关闭/知道了"  → 找到可点击节点 → performAction(ACTION_CLICK)
      └─ 命中用户自定义关键词     → 同上
```

悬浮球由 `FloatingBallService`（前台服务 + `WindowManager`）承载，与无障碍服务通过 `SettingsManager`（SharedPreferences）共享开关状态。

## 目录结构

```
ad_auto/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/wisight/adauto/
│       │   ├── App.kt                        # Application，初始化设置
│       │   ├── MainActivity.kt               # 设置页：权限、开关、规则、立即检测
│       │   ├── core/
│       │   │   ├── SettingsManager.kt        # 持久化设置
│       │   │   ├── AdRules.kt                # 广告识别规则（关键字→动作）
│       │   │   └── AdDetector.kt             # 遍历节点树、匹配规则、执行动作
│       │   └── service/
│       │       ├── FloatingBallService.kt    # 悬浮球前台服务（拖动/点击/长按）
│       │       └── AdSkipAccessibilityService.kt # 无障碍服务入口
│       └── res/
│           ├── layout/activity_main.xml      # 设置页布局
│           ├── layout/view_floating_ball.xml # 悬浮球视图
│           ├── drawable/bg_ball*.xml         # 球体渐变/置灰背景
│           ├── xml/accessibility_service_config.xml
│           └── values/{strings,colors,themes}.xml
├── build.gradle          # AGP 8.5.2 / Kotlin 1.9.24
├── settings.gradle
├── gradle.properties
└── gradlew(.bat)         # Gradle Wrapper (Gradle 8.7)
```

## 构建

环境要求：JDK 17+，Android SDK（compileSdk 34）。

```bash
# 在项目根目录
./gradlew assembleDebug        # 调试包
./gradlew assembleRelease      # 发布包（自动用 keystore/adauto.keystore 签名）
```

APK 输出：
- 调试包：`app/build/outputs/apk/debug/app-debug.apk`
- 发布包：`app/build/outputs/apk/release/app-release.apk`

安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

> ⚠️ **小米 / HyperOS 设备请务必安装 `app-release.apk`（release 签名包）**。
> 使用 debug 调试包会导致系统提示"未知来源应用，系统已拒绝此应用获取敏感权限"，从而**无法开启无障碍服务**（详见 FAQ Q1）。

## 使用步骤

1. **安装并打开应用**，进入设置页。
2. 依次完成三项授权（页面内点"去开启/去授权"即可跳转）：
   - **无障碍服务**（必开）：系统设置 → 无障碍 → 已安装的服务 → 广告快跳无障碍服务
   - **悬浮窗权限**（必开）：允许显示在其他应用上层
   - **通知权限**（Android 13+）：用于前台服务常驻通知
3. 确认"自动跳过广告"与"显示悬浮球"两个开关为开启状态。
4. 返回桌面，屏幕**左上角**即出现悬浮球，开始自动工作。

### 悬浮球操作

| 操作 | 作用 |
| --- | --- |
| 拖动 | 移动到任意位置 |
| 点击 | 快捷开关"自动跳过广告"（球体变灰 = 已关闭） |
| 长按 | 打开设置页 |

### 调试小技巧

- 设置页底部的 **"立即检测当前界面"**：手动触发一次检测，返回当前界面是否处于广告状态，方便验证规则。
- 规则设置里的自定义关键词可追加"跳过按钮"文字（默认已内置常见词）。

## 说明与限制

- 本应用**仅用于跳过应用内广告**，不会收集、上传任何个人数据；所有处理均在本地完成。
- **主要适配「红果免费短剧」App**（及同类短剧 App）；其它 App 可自行在"规则设置"追加关键词扩展，或将广告截图 + App 名提交到 Issues，由后续版本针对性补充规则。
- 无障碍服务仅在检测到广告关键字时才执行点击/手势，且带 1.5s 节流防重复触发。
- 某些广告（如全屏原生广告、无"跳过"文字的贴片广告）可能无法识别，可在"规则设置"中追加关键词增强识别。
- Android 12+ 限制后台启动前台服务：无障碍服务连接时若应用处于后台，可能无法自动拉起悬浮球，此时请在设置页手动打开"显示悬浮球"开关（或在应用内点击开启）。

## 常见问题与排障（FAQ）

### Q1：提示"未知来源应用，系统已拒绝此应用获取敏感权限"，无法开启无障碍

**原因**：安装的是 **debug 签名**的 APK（`./gradlew assembleDebug` 打的包）。MIUI / HyperOS 出于安全策略，会拒绝 debug 签名应用获取无障碍、悬浮窗等敏感权限。

**处理**：
1. 改装 **release 签名包**：`./gradlew assembleRelease`，产物为 `app/build/outputs/apk/release/app-release.apk`
2. 若设备上已装有旧包（debug 或不同签名），需**先卸载**再安装（签名不一致无法覆盖安装）：
   ```bash
   adb uninstall com.wisight.adauto
   adb install app/build/outputs/apk/release/app-release.apk
   ```
3. 卸载重装会清空应用数据，安装后需重新开启三项权限并恢复设置。

### Q2：无障碍开关明明开着，过一阵自动失效了（小米 / HyperOS 常见）

**原因**：MIUI / HyperOS 在以下场景会**静默清除无障碍设置**：
- 在设置/应用管理里**强行停止**了本应用
- 手机管家的**垃圾清理 / 电池优化**杀了后台
- **重启手机**（部分版本）
- 应用长期未使用被系统回收

**症状**：悬浮球可能还在，但不再自动跳过广告（因为无障碍服务已断开）。

**处理**：
1. 打开 **设置 → 无障碍 → 已下载的服务 → 广告快跳无障碍服务**，重新打开开关
2. 或使用 adb 一键恢复（设备已开启 USB 调试）：
   ```bash
   adb shell settings put secure enabled_accessibility_services com.wisight.adauto/.service.AdSkipAccessibilityService
   adb shell settings put secure accessibility_enabled 1
   ```
3. 确认是否生效：
   ```bash
   adb shell dumpsys accessibility | grep "Enabled services"
   ```
   输出应包含 `com.wisight.adauto/.service.AdSkipAccessibilityService`

**预防**（减少被撤销的概率）：
- 在**最近任务**里下拉**锁定**广告快跳
- 手机管家 → 应用管理 → 权限 → 打开**自启动**，省电策略设为**无限制**

### Q3：覆盖安装（升级）后悬浮球不见了

**原因**：覆盖安装瞬间系统可能短暂判定"显示在其他应用上层"权限未就绪，导致悬浮球未自动拉起（应用会弹出系统授权页，属正常现象）。

**处理**：重新打开一次应用主界面即可自动拉起悬浮球；若仍未出现，到 **设置 → 应用 → 广告快跳 → 显示在其他应用上层** 确认权限为"允许"。

### Q4：升级时报"安装失败 / 签名不一致"（INSTALL_FAILED_UPDATE_INCOMPATIBLE）

**原因**：新包与已安装包**签名不同**。常见于：debug 包与 release 包混装、或更换了 keystore 重新签名。

**处理**：
- 始终用**同一把 keystore** 签名（见下节"发布与签名"）
- 若签名确实已变，只能**卸载后重装**（会清空设置，需重新授权）

### Q5：某些广告跳不过去（无"跳过"文字、纯图片按钮）

**原因**：无障碍服务只能读取到带 `text` / `contentDescription` 的节点；**纯图片按钮读不到文字**，因此无法自动点击。

**处理**：
- 在设置页"规则设置"中追加该广告上的可见关键词
- 或在项目里为特定 App 增加针对性的规则（欢迎反馈截图）

## 发布与签名

- 签名文件：`keystore/adauto.keystore`（RSA 2048）
- 签名配置：`keystore.properties`（内含 keystore 密码与 keyAlias）
- 两者均已加入 `.gitignore`，**不会**提交到仓库

> ⚠️ **请务必备份 `keystore/adauto.keystore` 和 `keystore.properties`！**
> Android 升级安装要求新旧包**签名一致**。一旦丢失 keystore 或密码：
> - 无法对旧用户做无缝升级（只能卸载重装，丢失所有设置）
> - 只能重新生成 keystore，此时视为"全新应用"
>
> 建议：将这两个文件复制到安全的离线位置（网盘/加密盘）妥善保管。

## 版本

- compileSdk / targetSdk：34，minSdk：26（Android 8.0+）
- AGP 8.5.2 · Gradle 8.7 · Kotlin 1.9.24
