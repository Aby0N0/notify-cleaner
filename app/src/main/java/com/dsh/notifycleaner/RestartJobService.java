package com.dsh.notifycleaner;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * 用 JobScheduler 把自己拉起来。
 *
 * 这是从 MacroDroid 的清单里学来的：它带了一个叫 crashguard 的库，里面有
 *     crashguard.android.library.SystemJobService
 * 就是靠 JobScheduler 在系统层面排任务。
 *
 * 为什么这条比闹钟靠谱：
 *   - 任务由系统（JobScheduler）保管和调度，进程被杀也还在
 *   - **不需要「自启动」权限** —— 国产 ROM 的自启动开关管的是"应用自己起来"，
 *     系统派任务给它执行是另一条路
 *   - 被系统杀掉后，系统会按计划重新拉起进程来执行任务
 *
 * 我们用它做三件事：拉起前台服务、请求重绑通知监听、顺手再排下一次。
 */
public class RestartJobService extends JobService {

    private static final int ONE_SHOT_JOB = 5001;
    private static final int PERIODIC_JOB = 5002;

    /** 排一个「过 minLatencyMs 毫秒后执行」的一次性任务。 */
    public static void schedule(Context context, long minLatencyMs) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            long latency = Math.max(0L, minLatencyMs);
            JobInfo job = new JobInfo.Builder(ONE_SHOT_JOB,
                    new ComponentName(context, RestartJobService.class))
                    .setMinimumLatency(latency)
                    .setOverrideDeadline(latency + 5000L)
                    .build();
            js.schedule(job);
        } catch (Throwable ignored) {
            // 排不上就靠别的机制
        }
    }

    /** 排一个周期任务做兜底（系统要求最短 15 分钟）。 */
    public static void schedulePeriodic(Context context) {
        try {
            JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo job = new JobInfo.Builder(PERIODIC_JOB,
                    new ComponentName(context, RestartJobService.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(job);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        LogStore.add(this, "🧩 系统任务唤醒（JobScheduler）");

        // 把看门狗续上。JobScheduler 周期任务最快也要 15 分钟，
        // 而看门狗是 1 分钟级的，两者互补：任务唤醒时顺手把 1 分钟那条接力排好。
        KeepAlive.schedule(this);

        // 拉起前台服务
        KeepAliveService.start(this);

        // 通知监听没连上就请求一次
        NotifyCleanerService listener = NotifyCleanerService.get();
        if (listener == null || !listener.isConnected()) {
            try {
                NotifyCleanerService.requestRebind(
                        new ComponentName(this, NotifyCleanerService.class));
            } catch (Throwable ignored) {
                // 下次再来
            }
        }

        // 再排一次，形成接力（周期任务太慢，靠这个保持响应速度）
        if (params.getJobId() == ONE_SHOT_JOB) {
            schedule(this, 60 * 1000L);
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 返回 true 表示"这次没做完，请重新排"
        return true;
    }
}
