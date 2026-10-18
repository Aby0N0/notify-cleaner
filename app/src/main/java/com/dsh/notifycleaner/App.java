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

        // 每次进程启动都重新排一次看门狗。
        // 看门狗不是"重复闹钟"（那种会被系统大幅推迟，见 KeepAlive.schedule 的注释），
        // 而是"响一次、排下一次"的接力，所以必须有一个地方把它续上 —— 进程启动是最可靠的时机。
        try {
            KeepAlive.schedule(this);
        } catch (Throwable ignored) {
            // 排不上还有 JobScheduler 那条路
        }
    }
}
