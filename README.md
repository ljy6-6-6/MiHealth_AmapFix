# MiHealth AmapFix (LSPosed module)

**修复点**：小米运动健康 (Mi Fitness) 3.44.0 引入 `NotificationFilterHelper.isMipmapNotification(StatusBarNotification)`，
对 `com.autonavi.minimap`（高德地图）导航常驻焦点通知 (id = `0x4d4`) 做了特判，导致实时导航信息不再被转发到手表/手环。
本模块在运行时 Hook 该方法，**强制返回 `false`**，从而不再拦截这条焦点通知。

### 注：高德地图的导航焦点通知理论只在 HyperOS 2.0 以上版本显示，且需高德地图的版本支持，请确保你在系统通知栏能在步行/骑行导航时看到导航的焦点通知信息后再使用本模块解锁限制。

- ### HyperOS 3 + A15 设备经测试可用，A16 需用户自行测试（本人没有便于测试的设备）

- 兼容 **LSPosed 新 API (api:100)**：现代入口 `io.github.mihealthamapfix.ModernEntry`
  > 部分框架存在使用现代入口但不支持现代最新语法的问题，将短路返回其它支持  
  > 因 LSPosed 官方做出破坏性 API 更变，后续将废弃 **api:100** 转向 **api:101** 支持
- 兼容 **旧 XposedBridge API**：传统入口 `io.github.mihealthamapfix.LegacyInit`
- 推荐在 LSPosed 中**只勾选**「小米运动健康」应用的作用域

## 构建

> 仓库已经包含 GitHub Actions 工作流，会自动：
> 1. 克隆并编译 `libxposed/api` → 发布到 `mavenLocal()`
> 2. 构建 `assembleRelease` 并产出 APK 工件

本地构建：

```bash
# 先安装 JDK 21 和 Gradle（或者用 Android Studio 打开）
git clone YOUR_REPO_URL
cd MiHealthAmapFix
# JDK 17 理论可用但不保证（外部项目 libxposed/api 要求使用 JAVA 21 构建）

# 准备 Gradle Wrapper（首次构建）
gradle wrapper --gradle-version 8.7

# 构建
./gradlew assembleRelease
# 输出: app/build/outputs/apk/release/app-release.apk
```

## 安装 & 使用

1. 在 LSPosed 管理器里安装并启用模块
   - 本模块未限定仅 LSPosed 框架可用但推荐使用该框架
   - 你亦可使用其它框架，含免 ROOT 等方式进行 HOOK
2. 作用域**仅勾选**：小米运动健康（Mi Fitness）
3. 使用强制停止的方式来重启应用（或重启手机）
4. 在小米运动健康内开启高德地图的消息通知同步
5. 打开高德地图开始导航，观察手表是否能收到导航信息

## 技术细节

- 测试设备：Redmi Watch 4 (理论通用但不保证其它穿戴设备能正确显示，你可以选择降级小米运动健康至`3.44.0`之前的版本测试，如`3.42.0`)
- 目标方法：`com.xiaomi.fitness.notify.util.NotificationFilterHelper#isMipmapNotification(StatusBarNotification)`
- 命中条件：`packageName == "com.autonavi.minimap"`（高德地图包名）且 `notificationId == 0x4d4`（焦点通知ID）
- 处理策略：无论原实现返回什么，最终都返回 `false`（即不命中过滤条件，使其正常转发）
- 旧版本 (<= 3.42.0) **没有该方法**，Hook 自然会跳过（如你不更新小米运动健康 APP ，就无需此模块）

---

仅供学习研究，请遵循相关法律法规与软件协议。
