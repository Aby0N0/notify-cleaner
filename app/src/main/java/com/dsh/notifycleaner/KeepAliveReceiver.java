package com.dsh.notifycleaner;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * 看门狗：由系统闹钟叫醒，检查通知监听服务还在不在。
 *
 * 为什么需要它：进程被杀之后，应用自己没有任何办法"自己醒过来"。
 * 但 AlarmManager 的闹钟是记在系统里的，进程死了闹钟还在 ——
 * 到点系统会把本进程拉起来执行这个广播，我们就有机会把服务要回来。
 *
 * 同时它也接管开机广播和应用更新广播：这两种情况下系统会清掉已排的闹钟，需要重排。
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    public static final String ACTION_TICK = "com.dsh.notifycleaner.action.TICK";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();

        // 开机 / 应用更新之后，闹钟会被系统清掉，重新排一个
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            KeepAlive.schedule(context);
            LogStore.add(context, "⏰ 开机或更新完成，已重新排好看门狗");
        }

        // 不管哪种触发，只要服务没连上就主动请求重连
        NotifyCleanerService service = NotifyCleanerService.get();
        if (service != null && service.isConnected()) return;
        try {
            NotifyCleanerService.requestRebind(
                    new ComponentName(context, NotifyCleanerService.class));
            LogStore.add(context, "⏰ 看门狗：服务没连上，已请求重连");
        } catch (Throwable ignored) {
            // 拿不到就算了，5 分钟后再试
        }
    }
}
