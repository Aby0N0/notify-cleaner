package com.dsh.notifycleaner;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

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

    /** 看门狗间隔。5 分钟：够快，而且一个只在没连上时才做事的广播，耗电可以忽略。 */
    public static final long INTERVAL_MS = 5 * 60 * 1000L;

    private static final int REQUEST_CODE = 4001;

    private KeepAlive() {
    }

    /** 排一个重复闹钟。重复调用是安全的：同一个 PendingIntent 会被替换，不会堆起来。 */
    public static void schedule(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.setInexactRepeating(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + INTERVAL_MS,
                    INTERVAL_MS,
                    pending(context));
        } catch (Throwable ignored) {
            // 排不上就算了，别让界面崩
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
            SharedPreferences p = context.getApplicationContext()
                    .getSharedPreferences("notify_cleaner", Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            long windowStart = p.getLong("restartWindowStart", 0L);
            int count = p.getInt("restartCount", 0);
            if (windowStart == 0L || now - windowStart > RESTART_WINDOW_MS) {
                windowStart = now;
                count = 0;
            }
            if (count >= MAX_RESTARTS_PER_WINDOW) {
                LogStore.add(context, "⚠️ 重启次数过多，暂停自动重启（系统可能在反复杀）");
                return;
            }
            p.edit()
                    .putLong("restartWindowStart", windowStart)
                    .putInt("restartCount", count + 1)
                    .apply();

            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = restartPending(context);
            long triggerAt = now + delayMs;
            try {
                // 有精确闹钟权限就用精确的，没有就退回普通闹钟（可能晚几秒，问题不大）
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } catch (Throwable ignored) {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } catch (Throwable t) {
            LogStore.add(context, "⚠️ 排重启闹钟失败：" + t.getClass().getSimpleName());
        }
    }

    private static PendingIntent restartPending(Context context) {
        Intent intent = new Intent(context, KeepAliveReceiver.class);
        intent.setAction(KeepAliveReceiver.ACTION_RESTART);
        return PendingIntent.getBroadcast(context, RESTART_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
