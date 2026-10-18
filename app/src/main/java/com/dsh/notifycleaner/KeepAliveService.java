package com.dsh.notifycleaner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * 前台服务：这个应用能"划掉还活着"的唯一原因。
 *
 * 为什么之前不行
 * --------------
 * 上一版刻意不用前台服务，只靠 NotificationListenerService（由系统绑定）。
 * 理论上进程被杀后系统会重新绑，但 vivo OriginOS 这类系统划掉多任务时会直接把进程杀掉，
 * 而且不一定会立刻重新绑 —— 表现就是"一退出就失效"。
 *
 * MacroDroid 为什么划掉还活着
 * --------------------------
 * 它的官方 wiki 写得很清楚：
 *   "MacroDroid runs as a foreground service, which means it displays a persistent
 *    notification by default."
 * 就是前台服务 + 一条常驻通知，没有别的魔法。它自己也承认
 *   "The 'force hide icon' option ... is known to cause issues on some devices with
 *    MacroDroid being killed in the background."
 * 也就是说：那条通知就是它的命根子，藏了图标就容易被杀。
 *
 * 所以这里也走同一条路，但把代价压到最小
 * --------------------------------------
 * 通知渠道用 IMPORTANCE_MIN：不出状态栏图标、不响、不震、不亮屏，
 * 只有你主动下拉通知栏时才会在最底部看到一条折叠的静默条目。
 * 如果你连这个都不想要，界面里有个「隐藏这条通知」按钮，
 * 可以在系统设置里把这个渠道关掉 —— 通知消失，前台服务照跑。
 */
public class KeepAliveService extends Service {

    /** 常驻通知的渠道 id。界面上那个「隐藏这条通知」就是跳到这里。 */
    public static final String CHANNEL_ID = "keepalive";

    private static final int NOTIFICATION_ID = 1001;

    /** 启动（或确保已在运行）常驻服务。重复调用是安全的。 */
    public static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, KeepAliveService.class));
        } catch (Throwable ignored) {
            // Android 12+ 在后台启动前台服务可能被拒；这时只能靠看门狗闹钟兜底
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignored) {
            // 极少数情况下系统拒绝把服务升为前台；服务本身还活着，只是优先级低一些
        }
        // 被系统杀掉后自动重启
        return START_STICKY;
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

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_keepalive),
                    NotificationManager.IMPORTANCE_MIN);   // 最低优先级：无图标、无声、不亮屏
            channel.setDescription(getString(R.string.channel_keepalive_desc));
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(channel);
        } catch (Throwable ignored) {
            // 建不出渠道就用系统默认的，服务照样跑
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, SettingsActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 注意：系统原生的 Notification.Builder 没有 setSilent()（那是 AndroidX 才有的）。
        // 静音是靠上面的渠道做到的：IMPORTANCE_MIN + 无声音 + 无振动。
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
}
