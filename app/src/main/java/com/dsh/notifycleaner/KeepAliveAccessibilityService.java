package com.dsh.notifycleaner;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 一个"什么都不做"的无障碍服务。
 *
 * 【为什么需要它 —— 这是目前最有希望的一条路】
 *
 * 前面十几个版本我们一直在做「被杀了之后怎么复活」：前台服务、START_STICKY、
 * 精确闹钟、JobScheduler、电源/网络广播、设备管理器、透明窗口拉回前台……
 * 全部照抄了 MacroDroid 的做法，日志也证明进程确实在重启、前台服务确实回来了。
 * 但真正的瓶颈始终是：**通知监听被系统解绑后，系统不肯再绑回去。**
 * 进程活着，可它拿不到通知，等于白活。
 *
 * 而 MacroDroid 在同样的机器上不出现这个问题。它清单里有两样我们一直没有的东西：
 *     MacroDroidAccessibilityServiceJellyBean
 *     UIInteractionAccessibilityService
 * 两个无障碍服务。
 *
 * 无障碍服务在国产 ROM 上几乎是"免死金牌"：系统必须让它持续工作，
 * 一旦被清理，依赖它的辅助功能就废了，用户会投诉。所以厂商普遍对它手下留情。
 * 如果 MD 的无障碍是开着的，那它的进程**根本就没进入过"该杀"的名单** ——
 * 也就从来没被解绑过，自然不存在"绑不回去"的问题。
 * 这也解释了为什么它不需要自启动、不需要电池白名单。
 *
 * 【这个服务做什么】
 * 不做任何事。不读屏幕（canRetrieveWindowContent=false）、不模拟点击、
 * 只订阅通知状态变化事件（开销可以忽略）。它的全部价值就是"存在"。
 *
 * 连上的时候顺手把前台服务和看门狗拉起来，算是白捡的一次复活机会。
 */
public class KeepAliveAccessibilityService extends AccessibilityService {

    /** 服务当前是否连着。设置界面要显示这个状态。 */
    private static volatile boolean sConnected;

    public static boolean isConnected() {
        return sConnected;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sConnected = true;
        LogStore.add(this, "♿ 无障碍服务已连接 —— 进程优先级已提升，不容易被清理");
        // 白捡的一次复活机会：无障碍连上时通常也是进程刚起来的时候
        KeepAliveService.start(this);
        KeepAlive.schedule(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 故意什么都不做。
        // 订阅这个事件只是为了满足"无障碍服务必须声明一个事件类型"的要求，
        // 而且通知状态变化本身就很频繁 —— 等于顺带让系统觉得我们"在工作"。
    }

    @Override
    public void onInterrupt() {
        // 系统要求实现，用不到
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sConnected = false;
        LogStore.add(this, "♿ 无障碍服务已断开");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sConnected = false;
        super.onDestroy();
    }
}
