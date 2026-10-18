package com.dsh.notifycleaner;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

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
}
