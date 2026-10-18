package com.dsh.notifycleaner;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 整个应用的本体：盯着通知栏，看到该清的就清掉。
 *
 * 【为什么没有前台服务、没有常驻通知】
 *   NotificationListenerService 是系统（system_server）主动绑定的服务。
 *   进程一旦被杀，系统会在需要时把它重新绑起来 —— 拉起它的责任在系统，不在应用自己。
 *   所以不需要像 MacroDroid / Automation 那样挂一个看得见的常驻通知来"续命"。
 *
 * 【为什么不记"10 秒后的闹钟"】
 *   第一版是"收到通知 → postDelayed(10 秒) → 清除"。这有个要命的缺陷：
 *   那 10 秒里进程要是被系统杀掉，闹钟就跟着没了，通知永远留着。
 *   现在改成【按状态判断】：每秒看一眼"现在还有哪些通知、哪条已经超时了"，
 *   超时就清。进程被杀也没关系 —— 重新连上时会把该清的补清掉。
 */
public class NotifyCleanerService extends NotificationListenerService {

    private static final String TAG = "NotifyCleaner";

    /** 巡检间隔。1 秒足够准，而且只在有待清通知时才跑。 */
    private static final long TICK_MS = 1000L;

    /**
     * 超过这个年龄的通知不动 —— 免得一装上就把几天前的旧通知全清了。
     *
     * 取 10 分钟而不是更短，是为了配合 5 分钟的看门狗：
     * 服务被杀之后，看门狗最晚 5 分钟才把它要回来，那时通知已经放了 5 分钟，
     * 阈值要是也设 5 分钟就正好卡在边界上，清不掉。
     */
    private static final long MAX_AGE_MS = 10 * 60 * 1000L;

    /** 同一条通知最多尝试清几次。 */
    private static final int MAX_TRIES = 3;

    /** 让设置界面能拿到这个服务实例（同一个进程，直接用静态引用最省事）。 */
    private static volatile NotifyCleanerService sInstance;

    public static NotifyCleanerService get() {
        return sInstance;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 系统回调在 binder 线程，巡检在主线程，两个线程都会碰它，所以用并发容器。 */
    private final Map<String, Integer> attempts = new ConcurrentHashMap<>();

    private volatile boolean connected;
    private boolean ticking;
    private PowerManager.WakeLock wakeLock;

    /** 应用名缓存，省得每条通知都去查一次包管理器。 */
    private final Map<String, String> labelCache = new ConcurrentHashMap<>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            try {
                sweep();
            } catch (Throwable t) {
                LogStore.add(NotifyCleanerService.this, "⚠️ 巡检出错：" + shortMsg(t));
            }
        }
    };

    // ==================== 生命周期 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        // 这行很重要：如果系统根本没绑定过这个服务，日志里就不会有它 ——
        // 那就说明系统的通知监听注册表里已经没有我们了（只能靠「深度修复」救）。
        LogStore.add(this, "🔧 通知监听服务已被系统绑定");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = this;
        connected = true;
        attempts.clear();
        LogStore.add(this, "🔌 通知使用权已连接，开始工作");
        // 刚连上时立刻补一次巡检：万一之前进程被杀，有漏掉的通知现在补清
        scheduleTick(300);
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        connected = false;
        LogStore.add(this, "🔌 通知使用权断开，2 秒后尝试重连");
        // 不要立刻重连：系统正在拆绑定的过程中请求重绑，往往会被直接忽略。
        // 等两秒，让系统先把它自己的状态收拾干净。
        handler.removeCallbacks(rebindTask);
        handler.postDelayed(rebindTask, 2000);
    }

    private final Runnable rebindTask = new Runnable() {
        @Override
        public void run() {
            try {
                requestRebind(new ComponentName(NotifyCleanerService.this, NotifyCleanerService.class));
            } catch (Throwable t) {
                Log.w(TAG, "requestRebind failed", t);
            }
        }
    };

    @Override
    public void onDestroy() {
        connected = false;
        handler.removeCallbacks(tick);
        ticking = false;
        releaseWake();
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }

    // ==================== 系统回调 ====================

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        // 本应用自己的常驻通知（前台服务那条）不用看，也不用记 —— 免得日志被它刷屏
        if (getPackageName().equals(sbn.getPackageName()) && sbn.isOngoing()) return;

        Config config = Config.load(this);
        Judgement j = judge(config, sbn);

        if (j.watched) {
            String name = label(sbn.getPackageName());
            if (j.clear) {
                // 命中了：正文比标题更有信息量，优先显示正文
                CharSequence shown = j.text != null && j.text.length() > 0 ? j.text : j.title;
                LogStore.add(this, "👀 收到 " + name + "｜" + clip(shown, 20));
            } else {
                // 没命中：标题和正文都打出来，用户一看就知道该改哪个框
                LogStore.add(this, "➖ 收到 " + name
                        + "｜标题「" + clip(j.title, 12) + "」正文「" + clip(j.text, 14) + "」"
                        + " → 不清（" + j.reason + "）");
            }
        }

        if (j.clear) {
            attempts.remove(sbn.getKey());
            ensureWake(config.delayMs + 20000L);
            scheduleTick(Math.max(100L, Math.min(config.delayMs, 500L)));
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        attempts.remove(sbn.getKey());
    }

    // ==================== 巡检 ====================

    private void sweep() {
        Config config = Config.load(this);
        if (!config.enabled) {
            releaseWake();
            return;
        }

        StatusBarNotification[] active = activeSafe();
        if (active == null) {
            // 没连上就别空转，等 onListenerConnected 自己来
            if (connected) scheduleTick(TICK_MS);
            return;
        }

        long now = System.currentTimeMillis();
        boolean again = false;

        for (StatusBarNotification sbn : active) {
            if (sbn == null) continue;
            Judgement j = judge(config, sbn);
            if (!j.clear) continue;

            long age = now - sbn.getPostTime();
            if (age < 0) age = 0;

            if (age < config.delayMs) {
                again = true;                       // 还没到点，继续盯
                continue;
            }
            if (age > MAX_AGE_MS) continue;          // 太老的不管

            String key = sbn.getKey();
            int tried = attempts.containsKey(key) ? attempts.get(key) : 0;
            if (tried >= MAX_TRIES) continue;        // 清不掉，别死循环

            attempts.put(key, tried + 1);
            doCancel(sbn);

            if (tried == 0) {
                LogStore.add(this, "🧹 清除 " + label(sbn.getPackageName()) + "｜" + clip(j.title));
            }
            if (tried + 1 >= MAX_TRIES) {
                LogStore.add(this, "⚠️ 清不掉（试了 " + MAX_TRIES + " 次）"
                        + label(sbn.getPackageName()) + "｜" + clip(j.title)
                        + " —— 系统可能拦了，去设置里看本应用的权限");
                releaseWake();
            } else {
                again = true;                        // 下一轮复查到底清掉没有
            }
        }

        if (again) {
            scheduleTick(TICK_MS);
        } else {
            attempts.clear();
            releaseWake();
        }
    }

    private void scheduleTick(long delayMs) {
        if (ticking) return;
        ticking = true;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, Math.max(100L, delayMs));
    }

    @SuppressWarnings("deprecation")
    private void doCancel(StatusBarNotification sbn) {
        // 两个接口都调一遍：新版 key 接口最靠谱，老的 pkg/tag/id 接口在某些 ROM 上反而有效
        try {
            cancelNotification(sbn.getKey());
        } catch (Throwable t) {
            Log.w(TAG, "cancelNotification(key) failed", t);
        }
        try {
            cancelNotification(sbn.getPackageName(), sbn.getTag(), sbn.getId());
        } catch (Throwable t) {
            Log.w(TAG, "cancelNotification(pkg,tag,id) failed", t);
        }
    }

    // ==================== 判定 ====================

    /** 一次判定的结果，顺便把标题/正文带出来记日志。 */
    private static final class Judgement {
        boolean clear;
        boolean watched;
        String reason;
        CharSequence title;
        CharSequence text;
    }

    private Judgement judge(Config config, StatusBarNotification sbn) {
        Judgement j = new Judgement();
        Notification n = sbn.getNotification();
        if (n != null && n.extras != null) {
            j.title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            j.text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        }

        String pkg = sbn.getPackageName();
        // 本应用自己发的通知（自检用）永远算"要处理"，不受包名和关键字限制
        boolean self = getPackageName().equals(pkg);
        j.watched = self || config.matchesPackage(pkg);

        if (!config.enabled) {
            j.reason = "总开关关着";
            return j;
        }
        if (sbn.isOngoing()) {
            j.reason = "常驻通知，系统不让清";
            return j;
        }
        if (!self && !config.matchesPackage(pkg)) {
            j.reason = "不在监控列表";
            return j;
        }
        if (!self && !config.matchesText(j.title, j.text)) {
            // 说清楚是哪个框没过，不然用户只能瞎猜
            if (!config.titleKeyword.isEmpty()
                    && (j.title == null || !j.title.toString().contains(config.titleKeyword))) {
                j.reason = "标题不含「" + config.titleKeyword + "」";
            } else {
                j.reason = "正文不含「" + config.textKeyword + "」";
            }
            return j;
        }
        j.clear = true;
        return j;
    }

    // ==================== 给界面用的 ====================

    public boolean isConnected() {
        return connected;
    }

    public StatusBarNotification[] activeSafe() {
        try {
            return getActiveNotifications();
        } catch (Throwable t) {
            Log.w(TAG, "getActiveNotifications failed", t);
            return null;
        }
    }

    /** 界面上那个「清掉当前所有通知」按钮：不管规则，非常驻的全清。 */
    public int clearAllActive() {
        StatusBarNotification[] active = activeSafe();
        if (active == null) return -1;
        int count = 0;
        for (StatusBarNotification sbn : active) {
            if (sbn == null || sbn.isOngoing()) continue;
            doCancel(sbn);
            count++;
        }
        LogStore.add(this, "🧹 手动清除当前通知：" + count + " 条");
        return count;
    }

    // ==================== 杂项 ====================

    private void ensureWake(long ms) {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotifyCleaner:tick");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire(ms);
        } catch (Throwable t) {
            Log.w(TAG, "acquire wake lock failed", t);
        }
    }

    private void releaseWake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
            // acquire 带了超时，漏放也会自己到期
        }
    }

    private String label(String pkg) {
        String cached = labelCache.get(pkg);
        if (cached != null) return cached;
        String result = pkg;
        try {
            PackageManager pm = getPackageManager();
            result = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable ignored) {
            // 查不到就用包名
        }
        labelCache.put(pkg, result);
        return result;
    }

    private static String clip(CharSequence s) {
        return clip(s, 24);
    }

    private static String clip(CharSequence s, int max) {
        if (s == null) return "（空）";
        String t = s.toString().replace('\n', ' ').trim();
        if (t.isEmpty()) return "（空）";
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}
