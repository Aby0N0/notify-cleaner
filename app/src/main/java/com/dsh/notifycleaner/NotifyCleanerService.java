package com.dsh.notifycleaner;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 整个应用的本体：监听通知 → 等一会儿 → 把它清掉。
 *
 * 关键设计：这里【没有前台服务】、【没有常驻通知】。
 *
 * 为什么这样就够了：
 *   NotificationListenerService 是由系统（system_server）主动绑定的服务。
 *   进程一旦被杀，系统会在需要时把它重新绑起来 —— 拉起它的责任在系统，不在应用自己。
 *   所以不需要像 MacroDroid / Automation 那样挂一个看得见的常驻通知来"续命"。
 *
 * 这也是为什么这个 app 能同时做到「看不见」和「难杀」，而那类自动化应用做不到。
 */
public class NotifyCleanerService extends NotificationListenerService {

    private static final String TAG = "NotifyCleaner";
    private static final String WAKE_LOCK_TAG = "NotifyCleaner:dismiss";

    /** 主线程 Handler：延迟任务挂在它上面。 */
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 每个通知 key 对应一个待执行的清除任务。 */
    private final Map<String, Runnable> pending = new HashMap<>();
    /** 每个待执行任务持有的唤醒锁。 */
    private final Map<String, PowerManager.WakeLock> locks = new HashMap<>();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        Config config = Config.load(this);
        if (!config.enabled) return;
        // 常驻通知本来就关不掉（比如音乐播放器），别白排一个任务
        if (sbn.isOngoing()) return;
        if (!config.matchesPackage(sbn.getPackageName())) return;

        Notification notification = sbn.getNotification();
        CharSequence title = null;
        CharSequence text = null;
        if (notification != null && notification.extras != null) {
            title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
            text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        }
        if (!config.matchesText(title, text)) return;

        scheduleDismiss(sbn.getKey(), config.delayMs);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        // 用户自己划掉了，就不用再排着了
        cancelPending(sbn.getKey());
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        clearAll();
        // 被系统断开时主动请求重连，别干等
        try {
            requestRebind(new ComponentName(this, NotifyCleanerService.class));
        } catch (Throwable t) {
            Log.w(TAG, "requestRebind failed: " + t);
        }
    }

    @Override
    public void onDestroy() {
        clearAll();
        super.onDestroy();
    }

    /**
     * 排一个「delayMs 毫秒后清除这条通知」的任务。
     * 同一条通知被更新（重复 post）时会先取消旧任务，只留最后一个 —— 免得清早了。
     */
    private void scheduleDismiss(final String key, int delayMs) {
        cancelPending(key);

        PowerManager.WakeLock lock = null;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            lock.setReferenceCounted(false);
            // 只在这几秒内持有，保证延迟任务能准时跑到；acquire 带超时，到点自动释放，不会漏放
            lock.acquire(delayMs + 5000L);
        }

        Runnable task = new Runnable() {
            @Override
            public void run() {
                pending.remove(key);
                try {
                    cancelNotification(key);
                    Log.i(TAG, "已清除通知: " + key);
                } catch (Throwable t) {
                    Log.w(TAG, "cancelNotification 失败: " + t);
                } finally {
                    releaseLock(key);
                }
            }
        };

        pending.put(key, task);
        if (lock != null) locks.put(key, lock);
        handler.postDelayed(task, delayMs);
        Log.i(TAG, "已排定 " + delayMs + "ms 后清除: " + key);
    }

    private void cancelPending(String key) {
        Runnable task = pending.remove(key);
        if (task != null) handler.removeCallbacks(task);
        releaseLock(key);
    }

    private void releaseLock(String key) {
        PowerManager.WakeLock lock = locks.remove(key);
        if (lock != null && lock.isHeld()) {
            try {
                lock.release();
            } catch (Throwable ignored) {
                // 释放失败无所谓，acquire 的超时兜底
            }
        }
    }

    private void clearAll() {
        for (Iterator<Map.Entry<String, Runnable>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Runnable> entry = it.next();
            handler.removeCallbacks(entry.getValue());
            it.remove();
        }
        for (Iterator<Map.Entry<String, PowerManager.WakeLock>> it = locks.entrySet().iterator(); it.hasNext(); ) {
            PowerManager.WakeLock lock = it.next().getValue();
            if (lock != null && lock.isHeld()) {
                try {
                    lock.release();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            it.remove();
        }
    }
}
