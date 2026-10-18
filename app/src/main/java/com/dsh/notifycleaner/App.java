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
    }
}
