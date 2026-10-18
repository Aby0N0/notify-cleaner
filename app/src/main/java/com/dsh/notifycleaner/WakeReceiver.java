package com.dsh.notifycleaner;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * 靠系统广播把自己叫醒。
 *
 * 同样是从 MacroDroid 的清单里学来的：它的 crashguard 库注册了
 *     PowerReceiver   ← ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED
 *     NetworkReceiver ← CONNECTIVITY_CHANGE / WIFI_STATE_CHANGED
 *
 * 这类广播是**系统发的**，收到时系统会把我们的进程拉起来执行它 ——
 * 不需要「自启动」权限，也不需要闹钟。插一次充电器、切换一次网络，
 * 就多一次「把自己救回来」的机会。
 *
 * 顺带也接管开机广播。
 */
public class WakeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();

        KeepAliveService.start(context);
        KeepAlive.schedule(context);
        RestartJobService.schedule(context, 1000);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            RestartJobService.schedulePeriodic(context);
        }

        NotifyCleanerService listener = NotifyCleanerService.get();
        if (listener == null || !listener.isConnected()) {
            try {
                NotifyCleanerService.requestRebind(
                        new ComponentName(context, NotifyCleanerService.class));
            } catch (Throwable ignored) {
                // 下次广播再试
            }
        }
    }
}
