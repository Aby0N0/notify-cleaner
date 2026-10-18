package com.dsh.notifycleaner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 后台启动前台服务被拒之后的"再试一次"。
 *
 * 抄的是 MacroDroid 的 FGSRetryReceiver（反编译原文）：
 *     "FGSRetryReceiver: retrying foreground service start after background FGS rejection"
 * 它在 MacroDroidApplication 里被排成一个 4 秒后触发的闹钟，
 * 闹钟的排法就是 wo.b() —— RTC_WAKEUP + setExactAndAllowWhileIdle。
 *
 * 为什么这条重要：Android 12 起，应用在后台直接 startForegroundService()
 * 会抛 ForegroundServiceStartNotAllowedException。我们原来只是把这个异常记进日志
 * 就完了 —— 于是闹钟响过之后，前台服务依然没起来，表现就是「划掉之后彻底死掉」。
 */
public class FgsRetryReceiver extends BroadcastReceiver {

    public static final String ACTION_RETRY = "com.dsh.notifycleaner.action.RETRY_FGS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        if (KeepAliveService.isRunning()) {
            // 已经在跑了，不用重试，但顺手把看门狗续上
            KeepAlive.schedule(context);
            return;
        }
        LogStore.add(context, "🔁 前台服务重试启动");
        // 这次如果还被拒，KeepAliveService.start() 会自己再排一次重试
        KeepAliveService.start(context);
        KeepAlive.schedule(context);
    }
}
