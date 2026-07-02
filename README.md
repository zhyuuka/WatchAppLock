# WatchAppLock

面向 **Android 8.1（API 27）手表**（2GB RAM + 32GB ROM，低性能）的应用锁。
原生 Kotlin 壳 + 按需 WebView 设置界面；锁核心为 `UsageStatsManager` 轮询，
不依赖无障碍（无障碍为可选近实时增强）。

> 设计依据：`2026-07-01-watch-applock-design.md`。UI 设计包采用
> [`zhyuuka/design-systems-collection`](https://github.com/zhyuuka/design-systems-collection)
> 仓库的 `minimalist.zip`（Minimal Dashboard Design System），官方原版 `colors_and_type.css`
> 直接复用，`components.css` 按 preview 真实模式用官方令牌重建，含 15 个官方 SVG 图标。

## 构建

需要 JDK 11+ 与 Android SDK（compileSdk 33）。首次执行会自动下载 Gradle 8.1.4：

```bash
cd WatchAppLock
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 当前环境无 Android SDK，故未在沙箱内实跑构建；源码与资源齐备，导入 Android Studio 即可编译。

## 安装与首启

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次运行需按引导授予（规格 §9）：
1. 使用情况访问权限（必须）— 锁核心
2. 通知渠道（8.1 前台 Service 必须）
3. 电池优化白名单
4. ROM 自启动白名单（文案引导）
5. 无障碍（可选增强）

## 架构

```
常驻层  LockGuardService(前台) ─ UsagePoller(1.5s 轮询) ─ Heartbeat(AlarmManager)
        └ (可选) LockAccessibilityService ─ TYPE_WINDOW_STATE_CHANGED 近实时
触发层  LockScreenActivity ─ PIN / 图案输入，校验通过写 TTL(30s) 放行
UI 层   SettingsActivity + WebView ─ minimalist 设置页，用完即毁
自启动  BootReceiver(BOOT_COMPLETED + LOCKED_BOOT_COMPLETED)
```

锁核心不依赖无障碍：关闭它，`UsagePoller` 仍正常工作。

## Minimal Dashboard 设计系统

采用官方 `minimalist.zip` 令牌体系（`colors_and_type.css` 原版复用）：

| 令牌 | 值 | 用途 |
|---|---|---|
| `--brand-600` | `#52525b` | 冷中性品牌色（primary/ring/charts） |
| `--font-sans` | Geist | UI 主字体（手表无 Geist 时回退 system-ui） |
| `--radius` / `--radius-md` | `0.75rem`(12px) | 单一软角 |
| `--spacing` / `--space-4` | `0.25rem`(4px) | 紧凑仪表盘间距 |
| `--shadow-*` | 近零 | 扁平，靠边框对比 |
| `.dark` | — | 手表暗色主题（html class="dark"） |

组件 class 遵循 preview 真实模式：`btn primary/secondary/ghost/danger`、
`card metric/detail`、`list__row`、`segmented__opt`、`navbar__item` 等。
15 个官方 SVG 图标（`currentColor` 继承）位于 `assets/ui/icons/`。

## 关键文件

| 文件 | 职责 |
|---|---|
| `LockGuardService.kt` | 前台常驻 + 轮询 + 被杀重启 |
| `UsagePoller.kt` | 每 1500ms 查 `queryEvents` 取前台包名 |
| `LockTrigger.kt` | 命中 + TTL + 3s 防抖 + 拉起锁屏（共用） |
| `LockScreenActivity.kt` | 全屏 PIN/图案，错误 5 次延时 30s |
| `SettingsActivity.kt` | WebView 宿主，注入 JsBridge + CSS 变量 |
| `JsBridge.kt` | WebView ↔ SharedPreferences 双向桥（规格 §10） |
| `DeviceProfile.kt` | 屏幕比例探测（圆/方/长条/宽），注入 CSS 变量 |
| `ServiceGuard.kt` | JobScheduler 15min + AlarmManager 心跳兜底 |
| `Prefs.kt` | SharedPreferences 封装，解锁 TTL LRU 缓存(≤50) |
| `PinHasher.kt` | 加盐 SHA-256 + 10k 轮拉伸 |

## 自适应屏幕（规格 §8）

`DeviceProfile` 探测 `w/h` 分类 `square/tall/wide/round`，圆屏底部留 `chin`，
通过 `--safe-inset-*` CSS 变量承载。`app.css` 用 flex 撑满 + `min-height:0` 防溢出，
字号 `clamp()`，触控目标 `min:48px`。测试矩阵：圆 454² / 方 400² / 长条 240×280 / 宽 280×240。

## 内存优化（规格 §7）

仅 `LockGuardService` 常驻（目标 8–12MB）；WebView 用完即毁并 `System.gc()`；
锁名单用 StringSet 持久化不占内存大列表；依赖仅 core-ktx（不引入 AppCompat）。

## 可选增强（隐藏开发者选项）

「关于」页连点版本号 7 次解锁开发者选项：ADB 模式（一键复制 `dumpsys deviceidle whitelist` +
`pm grant PACKAGE_USAGE_STATS`）、Root 模式（检测 su）。当前设备无 ADB/root，默认隐藏。
