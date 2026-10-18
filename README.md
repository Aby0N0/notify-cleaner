# 通知清理器（NotifyCleaner）

一个**只做一件事**的 Android 小应用：**收到指定应用的通知 → 等一会儿 → 把它清掉。**

用来替代 MacroDroid 那个「DSH助手」宏。

---

## MacroDroid 的「核心科技」到底是什么

一句话：**它一直挂着一个前台服务（foreground service），带着一条常驻通知。**

它的官方 wiki 写得很直接：

> "MacroDroid runs as a foreground service, which means it displays a persistent notification by default."
>
> —— [MacroDroid Wiki: Troubleshooting](https://wiki.macrodroid.com/wiki/index.php/Troubleshooting)

而且它自己承认那条通知就是命根子：

> "The 'force hide icon' option in the notification bar settings is known to cause issues on some devices with MacroDroid being killed in the background, so disable it if you have reliability problems."

**隐藏图标 → 就容易被杀。** 也就是说 MacroDroid 也做不到「既看不见又杀不死」，
它选的是「看得见、杀不死」。

### 本应用第一版为什么「划掉就死」

第一版刻意**不用前台服务**，只靠 `NotificationListenerService`（由系统绑定），
理由是「理论上进程被杀后系统会重新绑，所以不需要常驻通知」。

理论没错，但 vivo OriginOS、小米 HyperOS 这类系统**划掉多任务时会直接杀进程**，
而且不一定会立刻重新绑 —— 于是服务就断了。

**这是设计判断错了，不是系统太变态。** v5 起改成和 MacroDroid 同一招：前台服务。

### 但代价被压到了最小

| | MacroDroid | 本应用 v5 |
|---|---|---|
| 前台服务 | 有 | **有** |
| 常驻通知可见度 | 默认在状态栏显示图标 | **IMPORTANCE_MIN**：不出状态栏图标、不响、不震、不亮屏，只有下拉通知栏才在最底部看到一条折叠的静默条目 |
| 能不能完全藏起来 | 能（关掉那个通知渠道） | **能**，界面里有个「隐藏这条常驻通知」按钮直达该渠道 |

> 注意：把渠道彻底关掉之后，个别系统可能因此更容易杀掉本应用（MacroDroid 的 wiki 也这么说）。
> 真遇到又开始失效，回应用里重新打开那个渠道就行。

---

## 它是怎么工作的

### 清除逻辑：按状态判断，不记定时器

```
NotifyCleanerService extends NotificationListenerService

onListenerConnected()  → 立刻巡检一次（补清漏掉的）
onNotificationPosted() → 看一眼，该清的记下来，启动巡检
巡检（每秒一次，只在有待清通知时跑）
    → getActiveNotifications()  问系统"现在还有哪些通知"
    → 逐条判断：是不是监控的应用 / 关键字匹配 / 已存在超过 N 毫秒
    → 到点的就 cancelNotification()，下一轮复查到底清掉没有
```

为什么不用「排一个 10 秒后的定时任务」：那 10 秒里进程要是被杀，任务就跟着没了。
按状态判断则不需要保存任何东西 —— 重新连上时再问一次系统就行。

### 保活：三道防线

| 防线 | 做法 |
|---|---|
| 1. 前台服务 | `KeepAliveService`，`stopWithTask=false` + `START_STICKY`。**这是主力**，划掉多任务不会带走它 |
| 2. 看门狗闹钟 | 每 5 分钟醒一次，前台服务或通知监听服务没起来就重新拉起。闹钟由系统保管，进程死了还在 |
| 3. 系统白名单 | 一键申请电池优化白名单 + 一键打开自启动管理页（vivo / 小米 / 华为 / OPPO / 三星各自适配） |

其他细节：

- 只清**存在时间在 10 分钟以内**的通知 —— 免得一装上就把几天前的旧通知全清了。
- 常驻通知（音乐播放器那种）不动，因为系统本来就不让清。
- 同一条通知最多试 3 次，还清不掉就记一条警告，不死循环。
- 巡检期间持有一个 `PARTIAL_WAKE_LOCK`（带超时，绝不会漏放）。
- **零第三方依赖**：不用 AndroidX，不用 Kotlin 插件，只用 Android 框架自带的 API。

---

## 关于「标题」和「正文」两个框

**飞书这类应用的通知结构是：标题 = 应用名（「飞书」），机器人名字在正文里。**

所以关键字一般要填在**正文**框。

| | 默认值 | 说明 |
|---|---|---|
| 标题必须包含 | 空 | 留空 = 不限制 |
| 正文必须包含 | `DSH助手` | 留空 = 不限制 |

两个都填 = 必须同时满足；只填一个 = 只查那一个。
**升级不用重新设置**：老版本填错框的值会自动挪到正文框，只迁移一次。

> 想让某个应用的通知**全部**都在 N 秒后消失？把两个框都清空就行。

---

## 签名与「覆盖安装」

GitHub Actions 每次运行都是全新机器，Android 默认的 debug 签名密钥是每台机器现场生成的 ——
所以每次编出来的 APK 签名都不一样，手机上覆盖安装会失败。

现在仓库里放了一套**固定的签名密钥**（`keystore/notify-cleaner.p12` + `keystore.properties`），
每次编译都用它签，并打印证书指纹供核对。签名固定了，以后所有新版本都能直接覆盖安装。

> ⚠️ **装 v5 之前要先卸载一次旧的 v1 / v2**。旧版是随机签名，和新的固定签名对不上。
> **只有这一次**需要卸载，之后就不用了。
>
> ⚠️ 仓库是公开的，所以这套密钥和密码也是公开的。对一个自己用的工具 App 可以接受。
> 想换成私有的：删掉 `notify-cleaner.p12`，用 `keytool -genkeypair` 重新生成，改 `keystore.properties`。

---

## 怎么拿到 APK（走 GitHub 云编译，本地零安装）

### 第 1 步：在 GitHub 建一个空仓库

名字随便，比如 `notify-cleaner`。**建的时候不要勾选 README / .gitignore**。

### 第 2 步：把本目录推上去

```bash
git init
git add .
git commit -m "通知清理器：初版"
git branch -M main
git remote add origin https://github.com/你的用户名/notify-cleaner.git
git push -u origin main
```

> **以后改完代码只要这三条**：
> ```bash
> git add .
> git commit -m "改了什么"
> git push
> ```
> `git remote add` 报 `already exists` 就用 `git remote set-url origin <新地址>`。
> push 报 `schannel: AcquireCredentialsHandle failed` 就用 `git -c http.sslBackend=openssl push`。

### 第 3 步：等它编译

仓库页面 → **Actions** 标签 → `build-apk` 正在跑。约 **1～2 分钟**。绿勾 = 成功。

### 第 4 步：下载 APK

点进那次运行 → 页面底部 **Artifacts** → **`notify-cleaner-apk`** → 解压得到 **`notify-cleaner-v5.0.apk`**。

> 建议**在电脑上下载，再传到手机**（微信文件传输助手 / 数据线）。

### 第 5 步：装到手机

1. **先卸载旧的「通知清理器」**（只有这一次，原因见上面「签名与覆盖安装」）。
2. 传入 APK，点它安装。允许「未知来源应用」。
3. 装好后**打开一次**。

### 第 6 步：授权「通知使用权」（关键，只做一次）

1. 打开应用，顶部显示红色「**● 未授权，无法工作**」。
2. 点「**打开系统「通知使用权」设置**」。
3. 找到「**通知清理器**」并打开。系统弹「允许此应用读取所有通知？」选**允许**。
4. 返回应用，顶部应该变成绿色「**● 正在工作**」。

顶部那条状态是固定的、不跟着滚动：

| 显示 | 意思 |
|---|---|
| ● 正在工作 | 一切正常 |
| ● 已暂停 | 权限有，但「启用」开关被关了 |
| ● 正在连接服务… | 权限有，正在自动重连（最多 3 秒） |
| ● 未授权，无法工作 | 权限没给，什么都不会发生 |
| ● 已授权，但服务没连上 | 自动重连没成功 —— 点「重新连接」 |

### 第 7 步：把「后台常驻」那两招点一下（强烈建议）

在「后台常驻」卡片里：

1. **隐藏这条常驻通知**（可选）—— 不想在通知栏看到它的话点这个，跳到系统渠道设置把它关掉。
2. **把本应用设为「不限制后台」** —— 弹系统对话框，点「允许」。
3. **打开系统的「自启动管理」** —— 在列表里把「通知清理器」打开。

vivo OriginOS 上的路径大概是：

- 自启动：`i管家 → 应用管理 → 权限管理 → 自启动`，或 `设置 → 应用与权限 → 权限管理 → 自启动`
- 后台高耗电：`设置 → 电池 → 后台高耗电`，把「通知清理器」设为允许

### 第 8 步：自检

点「发一条测试通知」，等 10 秒看它有没有消失，应用会直接告诉你 ✅ / ❌。

### 第 9 步：验证保活

1. 划掉多任务（把本应用从最近任务里划掉）。
2. 让飞书发一条通知。
3. 10 秒后看它有没有自己消失。

**这一步才是真正的验收** —— 之前几版都是挂在这里。

---

## 出问题了怎么自己定位

「**最近发生了什么**」会一行一行记下来，最新在最上面：

| 日志 | 意思 | 怎么办 |
|---|---|---|
| `🔌 通知使用权已连接` | 服务起来了 | — |
| `👀 收到 飞书｜DSH助手: …` | 收到了，而且判定要清 | 正常 |
| `➖ 收到 飞书｜标题「飞书」正文「…」→ 不清（正文不含「DSH助手」）` | **被关键字挡住了**，日志会写清哪个框没过 | 照日志里的真实文字改，或清空 |
| `➖ 收到 飞书｜… → 不清（不在监控列表）` | 包名不对 | 照日志里的包名改 |
| `🧹 清除 飞书｜…` | 发起了清除 | 下一行应该是它消失 |
| `⚠️ 清不掉（试了 3 次）` | 系统拦了 | 检查本应用权限 |
| `⏰ 看门狗：服务没连上，已请求重连` | 服务被杀过，看门狗正在救 | 频繁出现就去做第 7 步 |
| 什么都没有 | **通知根本没送到本应用** | 通知使用权没生效，或服务没连上 |

「**当前通知**」卡片会列出系统里现在所有通知，每行显示 `标题「X」正文「Y」` ——
这是判断「关键字该填哪个框」的唯一可靠依据，也能一键把通知全清掉。

---

## 常见问题

**Q：为什么现在有常驻通知了？之前不是说不需要吗？**
之前判断错了。不用前台服务的话，国产系统划掉多任务会直接杀进程，服务就断了。
MacroDroid 能「划掉还活着」就是因为它一直挂着前台服务。v5 起改成同样的做法，
但通知用最低优先级，并且可以在系统设置里把它彻底关掉。

**Q：隐藏了常驻通知会怎样？**
前台服务照跑，通知不显示。但个别系统可能因此更容易杀掉本应用 —— MacroDroid 的 wiki 也这么说。
真遇到又开始失效，回应用里把那个渠道重新打开就行。

**Q：需要设置开机自启吗？**
不需要手动设。应用会在开机后自动重排看门狗并拉起前台服务。
不过国产 ROM 上还是建议把自启动打开。

**Q：为什么不用 Android Studio？**
整个应用只有 7 个 Java 文件 + 十几个小资源文件，命令行编译就够了。

**Q：以后想改功能怎么办？**
改完代码 `git push`，Actions 自动重新编译，去 Artifacts 下载新 APK **直接覆盖安装**。

**Q：想本地编译（不走云）？**
需要 JDK 17 + Android SDK 命令行工具（约 1.5–2 GB），然后 `gradle assembleRelease`，
产物在 `app/build/outputs/apk/release/app-release.apk`。

---

## 目录结构

```
通知清理器/
├── .github/workflows/build.yml        云编译流程（release + 固定签名 + 验证指纹）
├── settings.gradle / build.gradle / gradle.properties
├── keystore.properties                签名密钥的密码等配置
├── keystore/notify-cleaner.p12        固定签名密钥（保证能覆盖安装）
├── app/
│   ├── build.gradle                   应用模块配置 + 签名配置
│   └── src/main/
│       ├── AndroidManifest.xml        组件声明（Activity + 两个 Service + 看门狗 Receiver）
│       ├── java/com/dsh/notifycleaner/
│       │   ├── Config.java            规则配置读写（含老版本关键字迁移）
│       │   ├── LogStore.java          事件日志（环形缓冲，最多 200 条）
│       │   ├── KeepAliveService.java  前台服务 —— 「划掉还活着」的关键
│       │   ├── KeepAlive.java         看门狗闹钟 + 电池白名单 + 自启动页跳转
│       │   ├── KeepAliveReceiver.java 被闹钟/开机广播叫醒时把一切拉回来
│       │   ├── NotifyCleanerService.java   核心：监听 + 巡检 + 清除
│       │   └── SettingsActivity.java       界面：设置 + 自检 + 日志 + 当前通知
│       └── res/
│           ├── drawable/              图标 + 各种圆角背景
│           ├── layout/activity_settings.xml
│           └── values/                colors / styles / strings
└── README.md
```

## 用到的技术版本

| 组件 | 版本 | 为什么选它 |
|---|---|---|
| Gradle | 8.9 | 与 AGP 8.7.3 稳定搭配 |
| Android Gradle Plugin | 8.7.3 | 稳定版，Google Maven 上一定有 |
| compileSdk / targetSdk | 35 | Android 15 |
| minSdk | 26 | Android 8.0 起 |
| Java | 17 | AGP 8.x 要求 |
| 前台服务类型 | `specialUse` | Android 14+ 强制要求声明类型，`specialUse` 是「其他用途」的合法出口 |
| 签名密钥 | PKCS12 / RSA 2048 / 3DES | 3DES 是 Java 兼容性最好的组合 |
