package com.dsh.notifycleaner;

import android.app.Application;

/**
 * 只做一件事：在进程启动时记一行日志。
 *
 * 为什么需要它：如果日志里反复出现「进程启动」，就说明应用进程被系统反复杀掉了 ——
 * 这是判断"到底是进程被杀，还是只是服务没连上"的唯一直接证据。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        LogStore.add(this, "🚀 进程启动");
        // 上一次「深度修复」如果没走完（进程中途被杀），这里把设置项恢复回去，
        // 免得把通知使用权弄丢。
        ListenerFix.restore(this);
        // 上一次如果"禁用组件"没走完（进程中途被杀），这里补上，
        // 免得监听组件一直被禁用 —— 那种状态表现为"授权看着是开的，但收不到任何通知"。
        ListenerFix.restoreComponent(this);

        // ===== 复活链的起点（照抄 MacroDroid 的 MacroDroidApplication.onCreate）=====
        // MD 的做法是：进程一启动，第一件事就是 startForegroundService。
        // 被系统拒（Android 12+ 后台不允许启动前台服务）也不要紧 ——
        // KeepAliveService.start() 会捕获异常并排一个 4 秒的精确闹钟重试，一直试到成功为止。
        // 这条链是"被划掉后秒级复活"的全部来源：只要进程被任何原因拉起来过，
        // 它就会自己把前台服务也拉起来。
        KeepAliveService.start(this);

        // 每次进程启动都重新排一次看门狗。
        // 看门狗不是"重复闹钟"（那种会被系统大幅推迟，见 KeepAlive.schedule 的注释），
        // 而是"响一次、排下一次"的接力，所以必须有一个地方把它续上 —— 进程启动是最可靠的时机。
        try {
            KeepAlive.schedule(this);
        } catch (Throwable ignored) {
            // 排不上还有 JobScheduler 那条路
        }

        // 记一笔无障碍状态：这是判断"到底哪条保活路在起作用"的关键信息。
        // 如果日志里一直是「未开启」，那保活就只能靠重启，效果必然差一截。
        try {
            LogStore.add(this, KeepAlive.isAccessibilityEnabled(this)
                    ? "♿ 无障碍服务：已开启"
                    : "♿ 无障碍服务：未开启（建议开启，这是最强的一项）");
        } catch (Throwable ignored) {
            // 查不到就算了
        }
    }
}
