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
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int rebindAttempts;
    /** 那个 1×1 的透明悬浮窗。有了它，系统才真的把本应用当成"有可见窗口"的应用。 */
    private View overlayView;

    /**
     * 挂一个 1×1 像素、完全透明的悬浮窗。
     *
     * 为什么要真的挂一个窗口：很多国产 ROM 判断"这应用是不是在前台/重要"时，
     * 看的是它有没有可见窗口，而不是只看权限。光有权限、不挂窗口，效果会打折。
     *
     * 这个窗口：1×1 像素、透明、不可点、不可聚焦、贴在左上角 —— 用户完全感知不到。
     * 这条做法来自 MacroDroid 清单里的 android.permission.SYSTEM_ALERT_WINDOW。
     */
    private void ensureOverlay() {
        if (overlayView != null) return;
        if (!KeepAlive.canDrawOverlays(this)) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            View view = new View(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            wm.addView(view, lp);
            overlayView = view;
            LogStore.add(this, "🪟 已挂上 1×1 透明悬浮窗（大幅提升存活优先级）");
        } catch (Throwable t) {
            LogStore.add(this, "⚠️ 悬浮窗挂载失败：" + shortMsg(t));
        }
    }

    private void removeOverlay() {
        if (overlayView == null) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) wm.removeView(overlayView);
        } catch (Throwable ignored) {
            // 窗口可能已经被系统回收
        }
        overlayView = null;
    }

    /**
     * 反复请求重绑通知监听，直到连上为止。
     *
     * 为什么不能只请求一次：日志实测，requestRebind() 是有效的，但系统要 10～20 秒才真正
     * 把服务绑起来（00:26:38 请求 → 00:26:57 连上；00:54:36 请求 → 00:54:46 连上）。
     * 之前只请求一次就放弃，所以经常等半天都连不上。
     */
    private final Runnable rebindLoop = new Runnable() {
        @Override
        public void run() {
            NotifyCleanerService listener = NotifyCleanerService.get();
            if (listener != null && listener.isConnected()) {
                if (rebindAttempts > 0) {
                    LogStore.add(KeepAliveService.this,
                            "✅ 监听服务已连上（共尝试 " + rebindAttempts + " 次）");
                }
                rebindAttempts = 0;
                return;
            }
            if (rebindAttempts >= 60) {
                // 3 秒一次，最多 3 分钟；之后交给看门狗闹钟重新拉起再试
                return;
            }
            rebindAttempts++;
            try {
                NotifyCleanerService.requestRebind(
                        new ComponentName(KeepAliveService.this, NotifyCleanerService.class));
            } catch (Throwable ignored) {
                // 下一轮再试
            }
            if (rebindAttempts == 1 || rebindAttempts % 10 == 0) {
                LogStore.add(KeepAliveService.this, "🔄 请求重绑通知监听（第 " + rebindAttempts + " 次）");
            }
            handler.postDelayed(this, 3000);
        }
    };

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
            // 关键补救：Android 12+ 在后台启动前台服务会被拒（ForegroundServiceStartNotAllowedException）。
            // 只记日志就放弃的话，服务就再也起不来了。这里 4 秒后再试一次 ——
            // 这是 MacroDroid 的 FGSRetryReceiver 干的事。
            KeepAlive.retryFgsStart(context);
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

        // 进程刚被拉起来时，通知监听服务往往还没绑上。启动一个反复重试的循环，
        // 每 3 秒请求一次，直到连上为止（系统实测要 10～20 秒才会真的绑）。
        handler.removeCallbacks(rebindLoop);
        rebindAttempts = 0;
        handler.postDelayed(rebindLoop, 500);

        // 有悬浮窗权限就挂一个 1×1 透明窗，让系统认为本应用"有可见窗口"
        ensureOverlay();

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
        LogStore.add(this, "♻️ 任务被划掉，正在把自己拉回来");
        // 双管齐下，两招都是 MacroDroid 在用的：
        //   1. 立刻起一个透明 Activity，让进程变成"前台应用"（这一招最快、最有效）；
        //   2. 再排一个 1 秒后的闹钟兜底，万一 Activity 被系统拦下来。
        KeepAlive.bringBack(this);
        KeepAlive.scheduleRestart(this, 1000);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        handler.removeCallbacks(rebindLoop);
        removeOverlay();
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
