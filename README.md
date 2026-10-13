# 通知清理器（NotifyCleaner）

一个**只做一件事**的 Android 小应用：**收到指定应用的通知 → 等一会儿 → 把它清掉。**

用来替代 MacroDroid 那个「DSH助手」宏。

## 它和 MacroDroid / Automation 的根本区别

| | 前台服务类应用（MacroDroid、Automation） | 本应用 |
|---|---|---|
| 要挂常驻通知吗 | **必须**（Android 强制要求前台服务带通知） | **不需要** |
| 谁负责在进程被杀后拉起 | 应用自己（靠 START_STICKY、KeepAlive 服务、辅助应用、保活折腾） | **系统**（`system_server` 持有通知监听服务的绑定，进程没了系统会重新绑） |
| 抗杀能力 | 普通前台服务，靠厂商白名单续命 | 被系统绑定的服务，**难杀得多** |
| 需要授权什么 | 电池优化、自启动、后台运行、厂商白名单一大串 | 只需一次「**通知使用权**」 |
| 界面 | 复杂 | 一个设置页 |

**结论**：MacroDroid 的官方文档自己承认 ——「强制隐藏图标（force hide icon）在某些设备上**已知会导致 MacroDroid 在后台被杀**」。也就是说，它也没法同时做到「看不见」和「杀不死」。

本应用绕开了这个矛盾：**它压根不是前台服务，所以不需要通知来换命。**

## 它是怎么工作的（约 200 行）

```
NotifyCleanerService extends NotificationListenerService
    onNotificationPosted()  → 检查包名/关键字 → 排一个延迟任务
    延迟到点                → cancelNotification(key)
```

- 用 `Handler.postDelayed` 做延迟，期间持有一个 `PARTIAL_WAKE_LOCK`，保证这 10 秒里 CPU 不睡、任务准时跑（`acquire` 带超时，绝不会漏放）。
- 同一条通知被更新时，先取消旧任务，只留最后一个。
- 用户自己划掉了通知 → `onNotificationRemoved` 里取消任务。
- 被系统断开连接 → `onListenerDisconnected` 里主动 `requestRebind`。
- **零第三方依赖**：不用 AndroidX，不用 Kotlin 插件，只用 Android 框架自带的 API。依赖越少，云编译越不容易出岔子。

## 怎么拿到 APK（走 GitHub 云编译，本地零安装）

### 第 1 步：在 GitHub 建一个空仓库

名字随便，比如 `notify-cleaner`。**建的时候不要勾选 README / .gitignore**（勾了会跟下面的推送冲突）。

### 第 2 步：把本目录推上去

在本目录（就是有这个 README 的那一层）打开终端，执行：

```bash
git init
git add .
git commit -m "通知清理器：初版"
git branch -M main
git remote add origin https://github.com/你的用户名/notify-cleaner.git
git push -u origin main
```

> 如果 push 时提示要密码：GitHub 现在不接受账号密码，要用 **Personal Access Token** 当密码。
> 生成位置：GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens，
> 给这个仓库的 **Contents: Read and write** 权限即可。

### 第 3 步：等它编译

推上去之后，打开仓库页面 → 上方 **Actions** 标签 → 会看到 `build-apk` 正在跑。大约 **2～4 分钟**。

绿勾 = 成功。红叉 = 失败，把报错截图发我。

### 第 4 步：下载 APK

编译完成后，点进那次运行，页面底部 **Artifacts** 区域 → 点 **`notify-cleaner-apk`** 下载。

下载得到的是一个 **zip**，解压出来就是 **`app-debug.apk`**。

> ⚠️ 手机浏览器登录 GitHub 下载有时很别扭。建议**在电脑上下载，再传到手机**（微信文件传输助手 / 数据线都行）。

### 第 5 步：装到手机

1. 把 `app-debug.apk` 传到手机，点它安装。
2. 系统会提示「未知来源应用」——允许即可（这是你自己编的，正常）。
3. 装好后先**打开一次**。

### 第 6 步：授权「通知使用权」（关键，只做一次）

1. 打开应用，顶部会显示「❌ 通知使用权：未授权」。
2. 点「**打开「通知使用权」设置**」按钮。
3. 在列表里找到「**通知清理器**」，打开它。
4. 系统可能弹一个「允许此应用读取所有通知？」——选**允许**。
5. 返回应用，顶部应该变成「✅ 通知使用权：已授权」。

> 如果列表里找不到它：确认第 5 步真的装上并打开过一次。
> 有些 ROM 的路径是 设置 → 通知与状态栏 → 通知使用权。

### 第 7 步：按需要调一下

默认配置已经就是你要的效果：

| 项 | 默认值 | 说明 |
|---|---|---|
| 启用 | 开 | 总开关 |
| 只处理这些应用 | `com.ss.android.lark,com.larksuite.suite` | 飞书国内版 + Lark 国际版 |
| 等待毫秒 | `10000` | 就是 MacroDroid 里那个「等待 10 秒」 |
| 标题必须包含 | 空 | 如果你想只清「DSH助手」那个机器人的通知，可以填 `DSH助手` |
| 正文必须包含 | 空 | 一般不用填 |

改完点「**保存**」。

## 常见问题

**Q：划掉多任务会不会失效？**
不会。通知监听服务由系统绑定，不依赖应用自己保活。但如果你的 ROM 特别激进（小米/华为/OPPO/vivo），建议还是顺手把它加进「自启动」白名单，更稳。

**Q：需要设置开机自启吗？**
不需要。系统会在开机后自动重新绑定已授权的通知监听服务。

**Q：为什么不用 Android Studio？**
因为整个应用只有 3 个 Java 文件 + 3 个资源文件，用命令行编译就够了，没必要为此下载几个 GB 的 IDE。

**Q：以后想改功能怎么办？**
改完代码 `git push`，Actions 会自动重新编译，再去 Artifacts 下载新的 APK 覆盖安装即可。

**Q：想本地编译（不走云）？**
需要自己装 JDK 17 + Android SDK 命令行工具（约 1.5–2 GB），然后在工程目录执行：

```bash
gradle assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

## 目录结构

```
通知清理器/
├── .github/workflows/build.yml        云编译流程
├── settings.gradle                    工程声明
├── build.gradle                       根构建脚本（只声明 AGP 版本）
├── gradle.properties
├── app/
│   ├── build.gradle                   应用模块配置
│   └── src/main/
│       ├── AndroidManifest.xml        组件声明（一个 Activity + 一个 Service）
│       ├── java/com/dsh/notifycleaner/
│       │   ├── Config.java            规则配置读写
│       │   ├── NotifyCleanerService.java   核心：监听 + 延迟 + 清除
│       │   └── SettingsActivity.java       唯一的设置界面
│       └── res/
│           ├── drawable/ic_launcher.xml    图标（纯矢量）
│           ├── layout/activity_settings.xml
│           └── values/strings.xml
└── README.md
```

## 用到的技术版本

| 组件 | 版本 | 为什么选它 |
|---|---|---|
| Gradle | 8.9 | 与 AGP 8.7.3 稳定搭配 |
| Android Gradle Plugin | 8.7.3 | 稳定版，Google Maven 上一定有 |
| compileSdk / targetSdk | 35 | Android 15 |
| minSdk | 26 | Android 8.0 起；这样图标不需要准备 PNG |
| Java | 17 | AGP 8.x 要求 |
