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

    /** 标题默认不限制。 */
    public static final String DEFAULT_TITLE_KEYWORD = "";
    /**
     * 正文默认预填这个。
     *
     * 为什么预填在正文而不是标题：飞书这类应用的通知，标题是应用名（「飞书」），
     * 机器人名字在正文里。填在标题框里永远匹配不上。
     */
    public static final String DEFAULT_TEXT_KEYWORD = "DSH助手";

    /** 标记：关键字迁移已经做过了，只做一次。 */
    private static final String KEY_MIGRATED = "keywordMigratedV4";

    /** 总开关。 */
    public final boolean enabled;
    /** 拆好、去空格的包名列表。空数组 = 不限制应用。 */
    public final String[] packages;
    /** 收到通知后等多久再清除（毫秒）。 */
    public final int delayMs;
    /** 标题必须包含。空 = 不限制。 */
    public final String titleKeyword;
    /** 正文必须包含。空 = 不限制。 */
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
        String[] keywords = loadKeywords(p);
        return new Config(
                p.getBoolean("enabled", true),
                split(p.getString("packages", DEFAULT_PACKAGES)),
                p.getInt("delayMs", DEFAULT_DELAY_MS),
                keywords[0],
                keywords[1]);
    }

    /**
     * 取标题/正文关键字，顺便把历史版本留下的数据一次性迁移干净。
     *
     * 要处理的三种历史情况：
     *   1. 全新安装 —— 正文预填 DEFAULT_TEXT_KEYWORD。
     *   2. 曾经被合并成一个 keyword 字段的版本 —— 还原到正文。
     *   3. 更早的版本把关键字填进了「标题」框 —— 飞书的标题永远是应用名，
     *      填在那儿永远匹配不上，挪到「正文」才有意义。
     *
     * 迁移只做一次（用 KEY_MIGRATED 标记），之后用户怎么改都不会再被覆盖。
     */
    private static String[] loadKeywords(SharedPreferences p) {
        String title = safe(p.getString("titleKeyword", ""));
        String text = safe(p.getString("textKeyword", ""));

        if (p.getBoolean(KEY_MIGRATED, false)) {
            return new String[]{title, text};
        }

        String merged = safe(p.getString("keyword", null));
        boolean untouched = title.isEmpty() && text.isEmpty() && merged.isEmpty();

        if (untouched) {
            // 全新安装：正文预填，标题留空
            text = DEFAULT_TEXT_KEYWORD;
        } else {
            // 被合并过的版本：还原到正文
            if (!merged.isEmpty() && text.isEmpty()) text = merged;
            // 填错框的老数据：挪到正文
            if (!title.isEmpty() && text.isEmpty()) {
                text = title;
                title = "";
            }
        }

        p.edit()
                .putString("titleKeyword", title)
                .putString("textKeyword", text)
                .putBoolean(KEY_MIGRATED, true)
                .apply();

        return new String[]{title, text};
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static void save(Context context, boolean enabled, String packages, int delayMs,
                            String titleKeyword, String textKeyword) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", enabled)
                .putString("packages", packages == null ? "" : packages.trim())
                .putInt("delayMs", delayMs)
                .putString("titleKeyword", safe(titleKeyword))
                .putString("textKeyword", safe(textKeyword))
                .putBoolean(KEY_MIGRATED, true)
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
     * 标题和正文各自独立判断：填了的那个必须包含，没填的不限制。
     * 两个都填 = 必须同时满足。
     */
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
