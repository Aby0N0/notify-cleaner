package com.dsh.notifycleaner;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * 让通知监听服务"活过来"的几件事。
 *
 * 背景：通知监听服务是系统绑定的，理论上进程被杀后系统会自己重新绑。
 * 但国产 ROM（小米 HyperOS / MIUI 最明显）划掉多任务时会把进程直接杀掉，
 * 而且不一定会立刻重新绑定 —— 表现就是「一退出就失效，再打开还要点重新连接」。
 *
 * 这里做三件事兜底：
 *   1. 看门狗闹钟：每 5 分钟醒一次，发现服务没连上就主动 requestRebind。
 *      闹钟由系统保管，进程被杀也还在。
 *   2. 电池优化白名单：引导用户把本应用设成"不限制"，这是最有效的一招。
 *   3. 自启动设置：给个直达入口（小米/华为/OPPO/vivo 各家页面不一样，挨个试）。
 */
public final class KeepAlive {

    /**
     * 看门狗间隔。
     *
     * 原来 5 分钟太慢，2 分钟也还是慢：被划掉之后最坏要等这么久才恢复，那期间的通知全漏了。
     * 现在 1 分钟。一次广播只是查两个布尔值，耗电可以忽略。
     */
    public static final long INTERVAL_MS = 60 * 1000L;

    /**
     * 「不健康」时的巡检间隔。
     *
     * 服务没在跑、或者通知监听没连上，说明应用正处于残废状态，
     * 这时候 1 分钟才看一次太慢 —— 中间来的通知全漏。
     * 改成 10 秒一次，把"发现自己是残废的"延迟压到和 MacroDroid 同一个量级。
     * 健康的时候仍然是 1 分钟，不会平白耗电。
     */
    public static final long RETRY_MS = 10 * 1000L;

    private static final int REQUEST_CODE = 4001;

    private KeepAlive() {
    }

    /**
     * 排下一次看门狗（默认 1 分钟后）。
     *
     * ⚠️ 这里原来有一个**致命**的写法错误，是从 MacroDroid 反编译代码对照出来的：
     *
     * 原来用的是 setInexactRepeating(AlarmManager.RTC, ...) ——
     *   · RTC 而不是 RTC_WAKEUP：设备睡着时**不唤醒**，只在下次自己醒的时候补发；
     *   · setInexactRepeating：这是"非精确重复"闹钟，系统会**大幅批处理、往后推**。
     *     Android 6 的 Doze、加上国产 ROM 的后台管控，会把它推到几小时以后。
     * 结果就是：我们以为"每分钟巡检一次"，实际上几乎**从来没按时响过**。
     * 日志能印证这一点 —— 真正把进程叫醒的全是 JobScheduler（15 分钟一次），
     * 所以中间才有 5 分钟以上的空洞。
     *
     * MacroDroid 的做法（反编译 wo.smali 里的 b() 方法得到）：
     *     canScheduleExactAlarms()
     *         ? setExactAndAllowWhileIdle(RTC_WAKEUP, ...)
     *         : setAndAllowWhileIdle(RTC_WAKEUP, ...)
     * 而且它**根本不用重复闹钟** —— 每次响完，由接收器自己排下一次。
     *
     * 这里照抄。重复调用是安全的：同一个 PendingIntent 会被覆盖，不会堆起来。
     */
    public static void schedule(Context context) {
        schedule(context, INTERVAL_MS);
    }

    /** 排一个 delayMs 之后触发的看门狗（非重复；响完由 KeepAliveReceiver 再排下一次）。 */
    public static void schedule(Context context, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = pending(context);
            // 必须先 cancel：老版本排的是 setInexactRepeating，
            // 不取消的话那个"僵尸重复闹钟"会一直留着，和新的精确闹钟打架。
            try {
                am.cancel(pi);
            } catch (Throwable ignored) {
                // 取消失败也继续，顶多多响一次
            }
            setWakeup(am, SystemClock.elapsedRealtime() + delayMs, pi);
        } catch (Throwable ignored) {
            // 排不上就算了，别让界面崩
        }
    }

    /** 能不能排"精确闹钟"。声明了 USE_EXACT_ALARM 时通常恒为 true，但个别 ROM 会返回 false。 */
    static boolean canExact(AlarmManager am) {
        try {
            if (Build.VERSION.SDK_INT >= 31) return am.canScheduleExactAlarms();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 排一个"会唤醒设备、能穿透 Doze"的闹钟。
     *
     * 两个要点：
     *   · RTC_WAKEUP：到点会把 CPU 叫醒，而不是等设备自己醒；
     *   · AllowWhileIdle：在 Doze 深度省电状态下也允许触发（系统限制最多每 9 分钟一次，
     *     但比被推迟几小时强太多）。
     */
    static void setWakeup(AlarmManager am, long triggerElapsedRealtime, PendingIntent pi) {
        if (canExact(am)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerElapsedRealtime, pi);
        } else {
            // 没有精确闹钟权限：至少保证"会唤醒 + 能穿透 Doze"，只是时间点可能被系统挪动
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerElapsedRealtime, pi);
        }
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, KeepAliveReceiver.class);
        intent.setAction(KeepAliveReceiver.ACTION_TICK);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ==================== 被划掉后立刻复活 ====================

    /** 重启用的请求码，和看门狗的错开，免得互相覆盖。 */
    private static final int RESTART_REQUEST_CODE = 4002;
    /** 重启频率限制：5 分钟内最多重启 12 次，防止被杀→重启→被杀的死循环把电耗光。 */
    private static final long RESTART_WINDOW_MS = 5 * 60 * 1000L;
    private static final int MAX_RESTARTS_PER_WINDOW = 12;

    /**
     * 排一个「过 delayMs 毫秒把自己重新拉起来」的闹钟。
     *
     * 这是本应用能不能像 MacroDroid 一样「划掉还活着」的关键一步。
     * MacroDroid 的日志里有一行：
     *     "KeepAlive service task removed - attempting to restart mechanism"
     * 就是收到 onTaskRemoved（任务被划掉）之后，立刻启动重启机制。
     *
     * 闹钟记在系统（system_server）里，进程被杀掉也还在 —— 这是唯一能
     * 「自己把自己叫醒」的办法。START_STICKY 在国产系统上不靠谱。
     */
    public static void scheduleRestart(Context context, long delayMs) {
        try {
            // 服务还活着时排的这个闹钟只是"预防性"的（比如界面 onStop 时顺手排一个），
            // 不算一次真正的重启，不占配额 —— 否则频繁开关界面会把配额耗光。
            boolean preventive = KeepAliveService.isRunning();

            SharedPreferences p = context.getApplicationContext()
                    .getSharedPreferences("notify_cleaner", Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            long windowStart = p.getLong("restartWindowStart", 0L);
            int count = p.getInt("restartCount", 0);
            if (windowStart == 0L || now - windowStart > RESTART_WINDOW_MS) {
                windowStart = now;
                count = 0;
            }
            if (!preventive) {
                if (count >= MAX_RESTARTS_PER_WINDOW) {
                    LogStore.add(context, "⚠️ 重启次数过多，暂停自动重启（系统可能在反复杀）");
                    return;
                }
                count++;
            }
            p.edit()
                    .putLong("restartWindowStart", windowStart)
                    .putInt("restartCount", count)
                    .apply();

            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = restartPending(context);
            // 和看门狗用同一套：RTC_WAKEUP + 精确（有权限时），能穿透 Doze。
            // 原来这里靠 try/catch 兜底，但 Android 12+ 在没有精确闹钟权限时
            // 不一定会抛异常，而是悄悄把闹钟挪走 —— 所以改成显式判断。
            setWakeup(am, SystemClock.elapsedRealtime() + delayMs, pi);

            // 同时排一个 JobScheduler 任务。这条路不依赖「自启动」权限 ——
            // 闹钟在国产 ROM 上会被自启动开关挡住，系统任务不会。
            RestartJobService.schedule(context, delayMs);
        } catch (Throwable t) {
            LogStore.add(context, "⚠️ 排重启闹钟失败：" + t.getClass().getSimpleName());
        }
    }

    private static PendingIntent restartPending(Context context) {
        Intent intent = new Intent(context, KeepAliveService.class);
        // 关键：用 getForegroundService，让"系统"来启动这个前台服务。
        // Android 12+ 限制"应用自己在后台启动前台服务"，走 PendingIntent 由系统代劳可以绕开。
        // 之前用的是 getBroadcast + 在广播里自己 startForegroundService，很可能就是被这条限制挡掉的。
        return PendingIntent.getForegroundService(context, RESTART_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ==================== 被划掉后「立刻拉回前台」 ====================

    /**
     * 用一个透明窗口把进程拉回前台。
     *
     * 这是 MacroDroid 真正的手段，反编译 MacroDroidService.onTaskRemoved() 得到的原文：
     *
     *     "MacroDroid service task removed - attempting restart mechanism"
     *     new Intent(this, DummyActivity.class)
     *     addFlags(FLAG_ACTIVITY_NEW_TASK)
     *     startActivity(...)
     *
     * 为什么这招比"排闹钟"管用：
     *   · 排闹钟要等系统把进程重新拉起来，而国产 ROM 在"后台"状态下会拒绝启动前台服务
     *     （Android 12+ 的 ForegroundServiceStartNotAllowedException），于是闹钟响了也没用；
     *   · 直接起一个 Activity，进程立刻变成"有界面的应用"，这条限制就不适用了，
     *     前台服务、通知监听的重绑都变得合法。
     *
     * DummyActivity 是透明、无历史、不进最近任务的，用户完全看不见。
     * 另外：申请过「显示在其他应用上层」（SYSTEM_ALERT_WINDOW）的应用，
     * 系统会额外豁免"后台启动 Activity"的限制 —— 这也是 MD 要那个权限的原因。
     */
    public static void bringBack(Context context) {
        try {
            Intent intent = new Intent(context, DummyActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            context.startActivity(intent);
            LogStore.add(context, "🪟 已用透明窗口把进程拉回前台");
        } catch (Throwable t) {
            // Android 10+ 可能拦下"后台启动 Activity"，拦下也不要紧，还有闹钟那条路
            LogStore.add(context, "⚠️ 透明窗口拉起被拒：" + t.getClass().getSimpleName());
        }
    }

    // ==================== 后台启动前台服务被拒后的重试 ====================

    /** 重试请求码，和看门狗、重启闹钟都错开。 */
    private static final int FGS_RETRY_REQUEST_CODE = 4003;
    /** MD 里这个延迟是 4000 毫秒（反编译看到常量 0xfa0）。 */
    static final long FGS_RETRY_DELAY_MS = 4000L;
    /**
     * 连续失败多少次之后放慢到 1 分钟一次。
     *
     * MD 是"无次数上限、无退避"地每 4 秒重试一次。我们加一个刹车：
     * 万一这台机器永远不允许（比如用户把应用冻结了），每 4 秒一次会一直耗电。
     * 20 次 ≈ 80 秒，正常情况早该成功了；超过就退回 1 分钟节奏，但仍然一直试。
     */
    private static final int FGS_RETRY_FAST_LIMIT = 20;

    /**
     * 4 秒后重试启动前台服务。
     *
     * 抄的是 MacroDroid 的 FGSRetryReceiver。Android 12 起，应用在后台直接
     * startForegroundService() 会抛 ForegroundServiceStartNotAllowedException，
     * 抛了以后如果就此放弃，前台服务就再也起不来了 —— 表现就是「划掉之后彻底死掉」。
     * MD 的做法是：捕获到之后排一个 4 秒后的广播，再试一次，一直试。
     */
    public static void retryFgsStart(Context context) {
        try {
            SharedPreferences p = context.getApplicationContext()
                    .getSharedPreferences("notify_cleaner", Context.MODE_PRIVATE);
            int count = p.getInt("fgsRetryCount", 0) + 1;
            p.edit().putInt("fgsRetryCount", count).apply();

            long delay = count <= FGS_RETRY_FAST_LIMIT ? FGS_RETRY_DELAY_MS : 60_000L;

            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(context, FgsRetryReceiver.class);
            intent.setAction(FgsRetryReceiver.ACTION_RETRY);
            PendingIntent pi = PendingIntent.getBroadcast(context, FGS_RETRY_REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            setWakeup(am, SystemClock.elapsedRealtime() + delay, pi);
            LogStore.add(context, "⏰ 前台服务启动被拒，" + (delay / 1000) + " 秒后重试（第 "
                    + count + " 次）");
        } catch (Throwable t) {
            LogStore.add(context, "⚠️ 排前台服务重试闹钟失败：" + t.getClass().getSimpleName());
        }
    }

    /** 前台服务成功起来了，把重试计数清零。 */
    public static void resetFgsRetry(Context context) {
        try {
            context.getApplicationContext()
                    .getSharedPreferences("notify_cleaner", Context.MODE_PRIVATE)
                    .edit().putInt("fgsRetryCount", 0).apply();
        } catch (Throwable ignored) {
            // 清不掉只是下次节奏慢一点，不影响功能
        }
    }

    // ==================== 无障碍服务（目前最有希望的一张牌） ====================

    /**
     * 本应用的无障碍服务是不是已经开启。
     *
     * 判断方式：问系统的无障碍管理器要"已启用的服务列表"，看里面有没有我们的组件。
     * 注意不能只查 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 这个字符串 ——
     * 有些 ROM 会写进多个条目、或者带空格的变体，直接比对字符串容易误判。
     */
    public static boolean isAccessibilityEnabled(Context context) {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            List<AccessibilityServiceInfo> enabled =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            if (enabled == null) return false;
            String target = context.getPackageName() + "/"
                    + KeepAliveAccessibilityService.class.getName();
            for (AccessibilityServiceInfo info : enabled) {
                if (info == null) continue;
                String id = info.getId();
                if (id != null && id.equals(target)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开系统的无障碍设置页（各家的类名基本一致，直接跳官方 action 最稳）。 */
    public static void openAccessibilitySettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        } catch (Throwable ignored) {
            // 极少数 ROM 改了入口，退回应用详情页
        }
        openAppDetails(activity);
    }

    // ==================== 设备管理器（保活的最后一张牌） ====================

    /**
     * 本应用是不是已经激活为设备管理器。
     *
     * 这条来自 MacroDroid 的清单：它有一个 MacroDroidDeviceAdminReceiver。
     * 成为设备管理器的应用会被系统当成"特权应用"，后台清理时明显更宽容，
     * 而且卸载必须先手动取消激活 —— 等于多了一道保护。
     */
    public static boolean isDeviceAdminActive(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isAdminActive(new ComponentName(context, AdminReceiver.class));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 弹出系统的「激活设备管理器」确认框。返回 false 表示这台机器不支持。 */
    public static boolean requestDeviceAdmin(Activity activity) {
        try {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    new ComponentName(activity, AdminReceiver.class));
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "只用来让系统在清理后台时保留本应用。"
                            + "本应用没有申请任何设备管理能力，不能锁屏、不能清除数据、不能改密码。");
            activity.startActivity(intent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 悬浮窗（保活的关键一环） ====================

    /**
     * 有没有「显示在其他应用上层」权限。
     *
     * 为什么这个重要：国产 ROM 对"有悬浮窗权限"的应用明显更宽容 —— 不容易被杀。
     * 这条是从 MacroDroid 的权限清单里发现的：它的 AndroidManifest 里就有
     *     android.permission.SYSTEM_ALERT_WINDOW
     * 而我们之前一直没有。
     */
    public static boolean canDrawOverlays(Context context) {
        try {
            return Settings.canDrawOverlays(context);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开系统的「显示在其他应用上层」授权页。 */
    public static void requestOverlay(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        } catch (Throwable ignored) {
            // 有些 ROM 不认带 package 的写法
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        } catch (Throwable ignored) {
            // 只能靠文字提示
        }
    }

    // ==================== 电池优化 ====================

    /** 当前是否已经在电池优化白名单里（在的话后台被杀的几率小很多）。 */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 弹系统对话框，请用户允许本应用在后台不被限制。 */
    public static void requestIgnoreBatteryOptimizations(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            return;
        } catch (Throwable ignored) {
            // 有些 ROM 不认这个 action，退回到列表页
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable ignored) {
            // 都不行就只能靠文字提示了
        }
    }

    // ==================== 自启动 ====================

    /**
     * 打开系统的"自启动管理"页面。各家 ROM 的类名都不一样，挨个试，第一个能打开的就用。
     *
     * 返回 true = 真的打开了自启动页；false = 没找到，已退回到"本应用的详情页"
     * （用户在那里能找到权限 / 电池 / 后台管理的入口）。
     */
    public static boolean openAutostartSettings(Activity activity) {
        String[][] candidates = {
                // vivo / iQOO（OriginOS、Funtouch OS）—— 放在最前面，这是最常见的机型
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BackgroundStartUpActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                // 小米 / Redmi（HyperOS、MIUI）
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                // 华为 / 荣耀
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                // OPPO / 一加 / realme
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                // 三星
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        };
        for (String[] candidate : candidates) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(candidate[0], candidate[1]));
                activity.startActivity(intent);
                return true;
            } catch (Throwable ignored) {
                // 这一家不适用，试下一家
            }
        }
        // 全都不行：退到本应用的应用详情页，至少给个能走到权限/电池设置的入口
        openAppDetails(activity);
        return false;
    }

    /** 打开本应用的系统详情页（权限、电池、存储都从这里进）。 */
    public static void openAppDetails(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Throwable ignored) {
            // 连详情页都打不开就只能靠界面上的文字提示了
        }
    }
}
