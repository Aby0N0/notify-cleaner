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
    /** 默认关键字。飞书通知的标题是「飞书」，机器人名字在正文里，所以关键字要能匹配正文。 */
    public static final String DEFAULT_KEYWORD = "DSH助手";

    /** 总开关。 */
    public final boolean enabled;
    /** 拆好、去空格的包名列表。空数组 = 不限制应用。 */
    public final String[] packages;
    /** 收到通知后等多久再清除（毫秒）。 */
    public final int delayMs;
    /**
     * 关键字：**标题或正文任一处包含它**就算匹配。空 = 不限制。
     *
     * 为什么合成一个字段：飞书通知的「标题」是应用名「飞书」，「DSH助手」在正文里。
     * 第一版分「标题必须包含」「正文必须包含」两个框，用户按直觉填在标题里，
     * 结果一条都匹配不上 —— 而且界面上完全看不出错在哪。合成一个就不会填错了。
     */
    public final String keyword;

    private Config(boolean enabled, String[] packages, int delayMs, String keyword) {
        this.enabled = enabled;
        this.packages = packages;
        this.delayMs = delayMs;
        this.keyword = keyword;
    }

    public static Config load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Config(
                p.getBoolean("enabled", true),
                split(p.getString("packages", DEFAULT_PACKAGES)),
                p.getInt("delayMs", DEFAULT_DELAY_MS),
                migrateKeyword(p));
    }

    /**
     * 取关键字。老版本存的是 titleKeyword / textKeyword 两个字段，这里做一次性迁移：
     * 谁有值就用谁，都没有就用默认值。用户不用重新设置。
     */
    private static String migrateKeyword(SharedPreferences p) {
        String keyword = p.getString("keyword", null);
        if (keyword != null) return keyword.trim();

        String title = p.getString("titleKeyword", "");
        String text = p.getString("textKeyword", "");
        if (title != null && !title.trim().isEmpty()) return title.trim();
        if (text != null && !text.trim().isEmpty()) return text.trim();
        return DEFAULT_KEYWORD;
    }

    public static void save(Context context, boolean enabled, String packages, int delayMs,
                            String keyword) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", enabled)
                .putString("packages", packages == null ? "" : packages.trim())
                .putInt("delayMs", delayMs)
                .putString("keyword", keyword == null ? "" : keyword.trim())
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

    /**
     * 关键字是否命中。**标题或正文任一处包含就算命中** —— 不再区分填在哪个字段。
     * 没填关键字 = 不限制，全部命中。
     */
    public boolean matchesText(CharSequence title, CharSequence text) {
        if (keyword.isEmpty()) return true;
        if (title != null && title.toString().contains(keyword)) return true;
        if (text != null && text.toString().contains(keyword)) return true;
        return false;
    }
}
