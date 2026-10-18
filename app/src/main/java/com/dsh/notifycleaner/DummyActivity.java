package com.dsh.notifycleaner;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * 一个完全透明的空窗口。用户永远看不到它，它的唯一作用是：
 * **让进程重新变成"有界面的应用"。**
 *
 * 为什么需要它（这是从 MacroDroid 反编译出来的做法）：
 *
 * MacroDroidService.onTaskRemoved() 里写着
 *     "MacroDroid service task removed - attempting restart mechanism"
 * 然后它 new 了一个 DummyActivity 并 startActivity()。也就是说，
 * 被从多任务里划掉的那一刻，它**不是先去排闹钟，而是先把自己变成一个可见应用**。
 *
 * 原因在于 Android 12+ 的一条硬限制：应用在后台时不允许启动前台服务
 * （ForegroundServiceStartNotAllowedException）。国产 ROM 还会额外收紧。
 * 于是"排闹钟 → 闹钟响 → 启动前台服务"这条路经常在最后一步被拒。
 * 而一旦有 Activity 起来了，进程就是"前台应用"，这条限制不再适用，
 * 前台服务和通知监听的重绑都能正常做。
 *
 * 细节照抄 MacroDroid 的清单配置：透明主题、noHistory、不进最近任务、
 * 独立 taskAffinity —— 保证它不会在用户的多任务列表里留下任何痕迹。
 */
public class DummyActivity extends Activity {

    /** 停留多久再自己关掉。太短可能来不及把服务拉起来，太长会白占资源。 */
    private static final long STAY_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 借用这次"前台身份"把该做的事做完
        KeepAliveService.start(this);
        KeepAlive.schedule(this);
        try {
            NotifyCleanerService.requestRebind(
                    new android.content.ComponentName(this, NotifyCleanerService.class));
        } catch (Throwable ignored) {
            // 重绑失败不要紧，KeepAliveService 里的重试循环会继续试
        }
        LogStore.add(this, "🪟 透明窗口已就位，正在恢复后台服务");

        // 停留 1.5 秒再消失，给系统足够时间认可"这是前台应用"
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, STAY_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
