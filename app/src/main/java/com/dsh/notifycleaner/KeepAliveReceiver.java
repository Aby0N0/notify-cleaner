package com.dsh.notifycleaner;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * 看门狗：由系统闹钟叫醒，检查该活着的东西还在不在。
 *
 * 为什么需要它：进程被杀之后，应用自己没有任何办法"自己醒过来"。
 * 但 AlarmManager 的闹钟是记在系统里的，进程死了闹钟还在 ——
 * 到点系统会把本进程拉起来执行这个广播，我们就有机会把前台服务和通知监听服务都要回来。
 *
 * 同时它也接管开机广播和应用更新广播：这两种情况下系统会清掉已排的闹钟，需要重排。
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    public static final String ACTION_TICK = "com.dsh.notifycleaner.action.TICK";
    /** 被划掉之后由闹钟触发，用来把自己重新拉起来。 */
    public static final String ACTION_RESTART = "com.dsh.notifycleaner.action.RESTART";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        String action = intent == null ? null : intent.getAction();

        // ⚠️ 顺序很重要：先把自己续上，再干活。
        // 看门狗已经不是"重复闹钟"了 —— 因为 setInexactRepeating 会被系统大幅推迟（见 KeepAlive.schedule 的注释）。
        // 现在改成"每次响完自己排下一次"，所以这一行必须放在最前面：
        // 万一后面的代码抛异常，至少下一次巡检还排着，不会彻底断掉。
        KeepAlive.schedule(context);

        // 开机 / 应用更新之后，系统会清掉已排的闹钟，上面的 schedule 已经补上了，这里只记一笔
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            LogStore.add(context, "⏰ 开机或更新完成，已重新排好看门狗");
        }

        // 每次被叫醒都确保常驻服务在跑（重复调用安全）
        KeepAliveService.start(context);

        // 通知监听服务没连上就主动请求重连
        NotifyCleanerService service = NotifyCleanerService.get();
        if (service != null && service.isConnected()) return;
        try {
            NotifyCleanerService.requestRebind(
                    new ComponentName(context, NotifyCleanerService.class));
            LogStore.add(context, "⏰ 看门狗：服务没连上，已请求重连");
        } catch (Throwable ignored) {
            // 拿不到就算了，下一轮巡检再试
        }
    }
}
