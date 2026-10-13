package com.dsh.notifycleaner;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 规则配置。
 *
 * 只负责读写，不碰界面 —— 这样通知服务（没有界面）和设置界面能共用同一份逻辑。
 * 数据存在 SharedPreferences 里，跨进程重启也不会丢。
 */
public final class Config {

    private static final String PREFS = "notify_cleaner";

    /** 飞书（国内版）和 Lark（国际版）的包名。 */
    public static final String DEFAULT_PACKAGES = "com.ss.android.lark,com.larksuite.suite";
    public static final int DEFAULT_DELAY_MS = 10000;

    /** 总开关。 */
    public final boolean enabled;
    /** 拆好、去空格的包名列表。空数组 = 不限制应用。 */
    public final String[] packages;
    /** 收到通知后等多久再清除（毫秒）。 */
    public final int delayMs;
    /** 标题必须包含的关键字，空 = 不限制。 */
    public final String titleKeyword;
    /** 正文必须包含的关键字，空 = 不限制。 */
    public final String textKeyword;

    private Config(boolean enabled, String[] packages, int delayMs,
                   String titleKeyword, String textKeyword) {
        this.enabled = enabled;
        this.packages = packages;
        this.delayMs = delayMs;
        this.titleKeyword = titleKeyword;
        this.textKeyword = textKeyword;
    }

    public static Config load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Config(
                p.getBoolean("enabled", true),
                split(p.getString("packages", DEFAULT_PACKAGES)),
                p.getInt("delayMs", DEFAULT_DELAY_MS),
                p.getString("titleKeyword", ""),
                p.getString("textKeyword", ""));
    }

    public static void save(Context context, boolean enabled, String packages, int delayMs,
                            String titleKeyword, String textKeyword) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", enabled)
                .putString("packages", packages == null ? "" : packages.trim())
                .putInt("delayMs", delayMs)
                .putString("titleKeyword", titleKeyword == null ? "" : titleKeyword.trim())
                .putString("textKeyword", textKeyword == null ? "" : textKeyword.trim())
                .apply();
    }

    /** 按逗号（中英文）、空白切分，顺便把空项丢掉。 */
    private static String[] split(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new String[0];
        String[] parts = raw.split("[,，;；\\s]+");
        int count = 0;
        for (String part : parts) {
            if (!part.isEmpty()) count++;
        }
        String[] out = new String[count];
        int i = 0;
        for (String part : parts) {
            if (!part.isEmpty()) out[i++] = part;
        }
        return out;
    }

    /** 这个包名的通知要不要处理。没填包名 = 所有应用都处理。 */
    public boolean matchesPackage(String packageName) {
        if (packages.length == 0) return true;
        for (String allowed : packages) {
            if (allowed.equals(packageName)) return true;
        }
        return false;
    }

    /** 标题/正文关键字都满足才返回 true；没填的项视为不限制。 */
    public boolean matchesText(CharSequence title, CharSequence text) {
        if (!titleKeyword.isEmpty()) {
            if (title == null || !title.toString().contains(titleKeyword)) return false;
        }
        if (!textKeyword.isEmpty()) {
            if (text == null || !text.toString().contains(textKeyword)) return false;
        }
        return true;
    }
}
