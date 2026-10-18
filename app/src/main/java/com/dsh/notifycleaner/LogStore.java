package com.dsh.notifycleaner;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 一行一行的事件记录，存在 SharedPreferences 里。
 *
 * 为什么要有它：这个应用在后台工作，出问题时用户什么都看不到。
 * 有了这个日志，就能分清到底是「根本没收到通知」「收到了但关键字不匹配」
 * 还是「该清但清不掉」—— 这三种情况的修法完全不同。
 *
 * 最新的在最前面，最多留 MAX_LINES 行，超了就丢最老的。
 */
public final class LogStore {

    private static final String PREFS = "notify_cleaner_log";
    private static final String KEY = "lines";
    private static final int MAX_LINES = 200;

    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    /** 上一条正文，用来压掉连续重复（飞书一次会连发好几条一模一样的）。 */
    private static String lastBody;

    private LogStore() {
    }

    public static synchronized void add(Context context, String body) {
        if (context == null || body == null) return;
        if (body.equals(lastBody)) return;
        lastBody = body;

        String line = TIME.format(new Date()) + "  " + body;

        // 同时也写一份到 logcat。
        // 为什么：应用自己的日志存在 SharedPreferences 里，只有打开界面才看得到；
        // 而排查"后台到底发生了什么"时，用电脑接 adb 看 logcat 是唯一能看到实时真相的办法。
        //     adb logcat -s NotifyCleaner
        try {
            Log.i("NotifyCleaner", body);
        } catch (Throwable ignored) {
            // 打不出来不影响存盘
        }

        // 再写一份到外部存储，并且**同步刷盘**。
        //
        // 这一条是专门为"进程被瞬间杀掉"这种情况加的：
        //   · SharedPreferences 用的是 apply()，异步落盘，进程马上死就丢了；
        //   · logcat 在 vivo 上被系统关掉了，普通应用打不出日志；
        //   · 外部存储这个位置用 adb 可以直接读：
        //         adb shell cat /sdcard/Android/data/com.dsh.notifycleaner/files/log.txt
        // 有了它，就能知道 onTaskRemoved 这种"死前最后一刻"的回调到底有没有被调用。
        appendExternal(context, line);

        SharedPreferences prefs = prefs(context);
        String old = prefs.getString(KEY, "");
        String merged = old == null || old.isEmpty() ? line : line + "\n" + old;

        // 只留最前面的 MAX_LINES 行
        int newlineCount = 0;
        int cut = -1;
        for (int i = 0; i < merged.length(); i++) {
            if (merged.charAt(i) == '\n') {
                newlineCount++;
                if (newlineCount == MAX_LINES) {
                    cut = i;
                    break;
                }
            }
        }
        if (cut >= 0) merged = merged.substring(0, cut);

        prefs.edit().putString(KEY, merged).apply();
    }

    public static synchronized String getAll(Context context) {
        String value = prefs(context).getString(KEY, "");
        return value == null ? "" : value;
    }

    public static synchronized void clear(Context context) {
        lastBody = null;
        prefs(context).edit().remove(KEY).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 把一行日志追加到外部存储的 log.txt，并且强制刷到磁盘。
     *
     * 关键在 out.getFD().sync()：它保证这一行真的写进了闪存才返回。
     * 不加它的话，进程被 SIGKILL 时缓冲区里的内容会整段丢失 ——
     * 而"进程被杀的最后一刻发生了什么"恰恰是我们最需要看到的。
     */
    private static void appendExternal(Context context, String line) {
        FileOutputStream out = null;
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists() && !dir.mkdirs()) return;
            File f = new File(dir, "log.txt");
            // 防止无限增长：超过 512KB 就清空重来
            if (f.length() > 512 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            out = new FileOutputStream(f, true);
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Throwable ignored) {
            // 外部存储不可用（没挂载/没权限）就算了，不能因为日志把应用搞崩
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
