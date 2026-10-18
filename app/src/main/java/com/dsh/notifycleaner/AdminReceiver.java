package com.dsh.notifycleaner;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 设备管理器组件。
 *
 * 为什么需要它：这是 MacroDroid 清单里最后一个我们没做的组件
 * （com.arlosoft.macrodroid.triggers.receivers.MacroDroidDeviceAdminReceiver）。
 * 成为设备管理器的应用，系统会把它当"特权应用"看待，后台清理时明显更宽容；
 * 而且卸载必须先手动取消激活，等于多了一道保护。
 *
 * 注意：device_admin.xml 里【没有声明任何策略】，所以本应用
 * 拿不到锁屏、清数据、改密码这些能力，只是一个"身份"。
 */
public class AdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        LogStore.add(context, "🔐 设备管理器已激活");
        // 立刻把常驻服务拉起来，让它以新身份重新运行
        KeepAliveService.start(context);
        RestartJobService.schedule(context, 1000);
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        LogStore.add(context, "🔐 设备管理器已停用");
    }
}
