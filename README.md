# MiHealth AmapFix

适用于 **小米运动健康 / Mi Fitness** 的 **LSPosed 模块**。

这个模块当前主要解决两类问题：

- **高德地图导航焦点通知** 被小米运动健康过滤，导致导航信息无法正常同步到手表 / 手环
- **Android 15 / SDK 35** 环境下被隐藏的 **“勿扰模式同步”** 入口，以及对应的 **ROOT 写入链路**

> [!IMPORTANT]
> 当前版本 `v2.0` 对于不同版本的 LSPosed 兼容性有待验证
>
> - **高德地图通知修复** 是正式功能
> - **勿扰模式同步** 目前仅支持 **`ROOT`** 环境 (需授权小米运动健康权限)
> - **`Shizuku`** 适配存在问题，相关代码仍保留，但**功能屏蔽**等待优化

## 适用场景

### 1. 高德地图通知转发修复

小米运动健康自 **`3.44.0`** 起，对高德地图导航焦点通知增加了专门过滤。

常见表现：

- **高德地图在步行 / 骑行模式下导航**
- **手机通知栏能看到焦点通知 / 灵动岛**
- **穿戴设备开启相关通知转发但收不到对应导航信息**

本模块会在运行时短路这段过滤逻辑，让高德导航通知恢复正常转发。

> [!NOTE]
> 高德导航焦点通知通常依赖系统本身支持显示，需要 **HyperOS 2.0** 及以上版本。  
> 请先确认你在手机通知栏里本来就能看到高德的导航焦点通知，再使用本模块验证穿戴侧同步。

### 2. 勿扰模式同步修复

小米运动健康自 **`3.46.0`** 起更新到 **`SDK 35`** 后，因原生相关 DND 权限行为发生收紧，应用内会直接隐藏 **“勿扰模式同步”** 入口。

当前版本的修复范围：

- **仅针对 Android 15 / `SDK 35` 及以上环境**
- **仅在宿主应用具备可用 `ROOT` 能力时放开入口**
- 通过 **Hook + ROOT 写入链路** 恢复功能
- **仅测试米系**手机正常工作，其余品牌系统等待反馈

如果你的小米运动健康版本较旧，或者本来就没有升级到 **`SDK 35`**，通常不需要这部分修复。

## 使用说明

### 通用步骤

1. 在 **LSPosed** 中安装并启用模块
2. 作用域勾选小米运动健康对应包
3. **强制停止并重新打开** 小米运动健康，或重启手机

**推荐作用域：**

- `xom.mi.health` （主线支持）
- `com.xiaomi.wearable`
- `com.xiaomi.hm.health` （静态入口保留，未测试）

### 高德地图通知修复

1. 完成上面的通用步骤
2. 打开高德地图开始 **步行 / 骑行导航**
3. 确认手机通知栏里已经出现 **导航焦点通知**
4. 观察穿戴设备是否恢复接收导航信息

### 勿扰模式同步

1. 设备本身已具备可用 **`ROOT`**
2. 给 **小米运动健康宿主应用** 授予可用的 `ROOT` 能力
3. 完成上面的通用步骤
4. 进入设备设置页，确认 **“勿扰模式同步”** 入口已经显示
5. 打开该开关后测试手机与手表 / 手环之间的勿扰联动

> [!IMPORTANT]
> 勿扰同步能力认的是 **宿主应用的 `ROOT`**（即**小米运动健康** APP）。  
> **并非模块自身需获取 `ROOT` 权限，提权行为由宿主应用进行。**  
> **蓝牙连接稳定性可能影响**最终**同步**表现，这不是模块自身问题

## 当前实现说明

### 1. 高德地图通知修复

**目标方法：**

- `com.xiaomi.fitness.notify.util.NotificationFilterHelper#isMipmapNotification(StatusBarNotification)`

**命中条件：**

- `packageName == "com.autonavi.minimap"`
- `notificationId == 0x4d4`

**处理策略：**

- 无论原始实现返回什么，最终都返回 **`false`**
- 让这条通知继续参与小米运动健康到穿戴设备的正常同步

如果你使用的是更旧的小米运动健康版本，这个方法本身不存在， Hook 自然会跳过。

### 2. 勿扰模式同步修复

当前正式实现重点包括：

- 精确 Hook `ZenUtils.isSupportZenMode(...)`
- 条件放行 `NotificationManager.isNotificationPolicyAccessGranted()`
- 接管 `NotificationManager.setInterruptionFilter(int)`
- DND 写入优先走 `cmd notification set_dnd`
- 失败时回退 `cmd notification set_zen_mode`
- `settings put global zen_mode` 仅作为最后兜底
- 运行时校验以宿主 `NotificationManager.getCurrentInterruptionFilter()` 为准
- 设备重连后会执行一次 **headless 状态恢复**，尽量减少重启后功能失效的问题
  > 如依然发生，请手动进入勿扰模式同步的设置界面后，功能自动恢复  
  > 为避免发生，请尽可能保证小米运动健康 APP 的后台留存；重启手机后手动测试
  > 如日志中有明确发生错误未能状态恢复，请提供日志及设备信息反馈

> [!NOTE]
> 此功能依赖宿主应用获取 ROOT 提权，**请勿授予模块 ROOT 权限**  
> 当模块检测到宿主应用**无权限时**将停止相关 HOOK，**不解锁被隐藏的开关**选项

## 兼容性

- **`minSdk`**: 24
- **`targetSdk`**: 35
- **Java**: 17

模块当前保留 **单 APK 三段入口兼容**：

- **LSPosed Modern API `101`**
- **LSPosed Modern API `100`**
- **低于 `100` 的 Legacy 入口**

> 由于上游 LSPosed API:101 新 Hook 方式发生了重大变化  
> 目前无法达成三种 API 模式同时都处于最优状态下运行  
> 单包体多兼容可能难以避免存在问题，尽管我已经大量重构调试  
> 如果你仍遇到兼容性问题，请务必提供相关版本及日志信息反馈

当前 APK 同时包含：

- `META-INF/xposed/java_init.list`
- `META-INF/xposed/module.prop`
- `META-INF/xposed/scope.list`
- `assets/xposed_init`

## 日志

**主要日志标签：**

- `AmapFix`
- `AmapFix-DND`

其中 **`AmapFix-DND`** 主要会输出：

- 当前 DND 路由模式摘要
- 宿主应用 ROOT 能力探测结果
- 入口放行与权限放行日志
- DND 写入路径与读回校验结果
- 启动恢复与设置页诊断信息

## 构建

**本地构建环境：**

- JDK 17
- Android SDK 35
- Gradle 8.7

**本地构建示例：**

```bash
gradle wrapper --gradle-version 8.7
./gradlew assembleRelease
```

**输出位置：**

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

## GitHub Actions

仓库已包含 **GitHub Actions** 工作流，会自动完成：

- 使用 JDK 17
- 安装 Android SDK 35 / Build Tools 35.0.0
- 执行 `assembleRelease`
- 校验 APK 内 Xposed 元数据是否完整
- Tag 构建时执行签名并发布 Release

**工作流文件：**

- `.github/workflows/android.yml`

## 免责声明

本项目仅用于学习、研究与兼容性修复测试，请自行评估使用风险，并遵守相关法律法规与软件协议。
