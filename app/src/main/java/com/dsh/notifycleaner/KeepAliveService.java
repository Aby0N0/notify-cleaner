package com.dsh.notifycleaner;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：这个应用能"划掉还活着"的唯一原因。
 *
 * MacroDroid 的官方 wiki 写得很清楚：
 *   "MacroDroid runs as a foreground service, which means it displays a persistent
 *    notification by default."
 * 并且明确警告：
 *   "The 'force hide icon' option ... is known to cause issues on some devices with
 *    MacroDroid being killed in the background."
 *
 * 也就是说：**那条通知必须是"看得见"的**，藏得太干净反而会被系统当成没有前台服务而杀掉。
 * v5 把它设成了 IMPORTANCE_MIN（不出状态栏图标），结果在 vivo OriginOS 上
 * 一退出就被杀 —— 正好复现了 MacroDroid 文档里说的那个坑。
 *
 * 所以这里改成 IMPORTANCE_LOW：
 *   - 有状态栏小图标（这是保命的关键）
 *   - 但静音：不响、不震、不亮屏、不弹横幅
 *   - 不显示角标
 * 界面里仍提供「隐藏这条通知」，但会明确警告可能因此被杀。
 */
public class KeepAliveService extends Service {

    /**
     * 常驻通知的渠道 id。界面上那个「隐藏这条通知」就是跳到这里。
     *
     * ⚠️ 为什么叫 keepalive_v2 而不是 keepalive：
     * Android 有一条硬规则 —— **渠道一旦创建，重要级别就不能再改**，只能由用户手动改。
     * v5 时这个渠道是用 IMPORTANCE_MIN 创建的（不显示），后来代码改成 IMPORTANCE_LOW
     * 对已存在的渠道**完全无效**，用户手机上它一直是"不显示"，等于没有前台服务通知。
     * 唯一解法是换一个新 id，让系统重新创建一个渠道。
     */
    public static final String CHANNEL_ID = "keepalive_v2";
    /** 旧的错误渠道，创建时顺手删掉。 */
    private static final String OLD_CHANNEL_ID = "keepalive";

    private static final int NOTIFICATION_ID = 1001;

    /** 前台服务当前是否在跑。设置界面要显示这个状态。 */
    private static volatile boolean sRunning;

    public static boolean isRunning() {
        return sRunning;
    }

    /** 启动（或确保已在运行）常驻服务。重复调用是安全的。 */
    public static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, KeepAliveService.class));
        } catch (Throwable t) {
            // 不再静默吞掉：失败原因要能被用户看到
            LogStore.add(context, "⚠️ 启动常驻服务失败：" + shortMsg(t));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LogStore.add(this, "🛡️ 常驻服务 onCreate");
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
            if (!sRunning) {
                LogStore.add(this, "🛡️ 常驻服务已进入前台，开始保命");
            }
            sRunning = true;
        } catch (Throwable t) {
            sRunning = false;
            LogStore.add(this, "⚠️ 升为前台服务失败：" + shortMsg(t));
        }

        // 进程刚被拉起来时，通知监听服务往往还没绑上，主动请求一次
        NotifyCleanerService listener = NotifyCleanerService.get();
        if (listener == null || !listener.isConnected()) {
            try {
                NotifyCleanerService.requestRebind(
                        new ComponentName(this, NotifyCleanerService.class));
            } catch (Throwable ignored) {
                // 请求不到就靠后面的看门狗
            }
        }

        // 精确闹钟能不能用，直接决定"1 秒后复活"到底准不准
        try {
            boolean exact = true;
            if (Build.VERSION.SDK_INT >= 31) {
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                exact = am != null && am.canScheduleExactAlarms();
            }
            LogStore.add(this, "🛡️ 精确闹钟可用 = " + exact);
        } catch (Throwable ignored) {
            // 查不到就算了
        }

        // 被系统杀掉后自动重启（国产系统上不靠谱，主要靠下面的闹钟）
        return START_STICKY;
    }

    /**
     * 用户把任务从最近列表里划掉了。
     *
     * 这是整个保活机制里最关键的一个回调 —— MacroDroid 的日志里那行
     * "KeepAlive service task removed - attempting to restart mechanism"
     * 就是收到它之后立刻启动重启。
     *
     * 做法：排一个 1 秒后触发的系统闹钟。闹钟记在 system_server 里，
     * 本进程被杀掉也照样会响，响的时候系统会把我们重新拉起来。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        LogStore.add(this, "♻️ 任务被划掉，1 秒后自动重启");
        KeepAlive.scheduleRestart(this, 1000);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        LogStore.add(this, "🛡️ 常驻服务被销毁，1 秒后自动重启");
        // 不管是被谁销毁的，都排一个重启闹钟 —— 宁可多起一次，也不要彻底死掉
        KeepAlive.scheduleRestart(this, 1000);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            // 删掉 v5 那个建错了的渠道（MIN 优先级、不显示）
            try {
                nm.deleteNotificationChannel(OLD_CHANNEL_ID);
            } catch (Throwable ignored) {
                // 删不掉也不影响
            }

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_keepalive),
                    NotificationManager.IMPORTANCE_LOW);   // 有图标、但不响不震
            channel.setDescription(getString(R.string.channel_keepalive_desc));
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(channel);

            // 把渠道的实际优先级记下来：如果显示的是 1，说明通知不会显示，前台服务等于白挂
            NotificationChannel actual = nm.getNotificationChannel(CHANNEL_ID);
            if (actual != null) {
                int importance = actual.getImportance();
                LogStore.add(this, "🛡️ 常驻通知渠道优先级 = " + importance
                        + (importance <= NotificationManager.IMPORTANCE_MIN
                        ? "（=1，通知不会显示！这就是被杀的根源）" : "（正常，会显示小图标）"));
            }
        } catch (Throwable t) {
            LogStore.add(this, "⚠️ 创建通知渠道失败：" + shortMsg(t));
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, SettingsActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 注意：系统原生的 Notification.Builder 没有 setSilent()（那是 AndroidX 才有的）。
        // 静音是靠上面的渠道做到的：IMPORTANCE_LOW + 无声音 + 无振动。
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
