package com.dsh.notifycleaner;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 强行让系统重新注册本应用的通知监听。
 *
 * 为什么需要它
 * ------------
 * 国产 ROM（vivo OriginOS 实测）划掉多任务时，杀法接近 am force-stop：
 * 系统会把本应用的通知监听组件从**内部注册表**里摘掉，但
 * `enabled_notification_listeners` 这个设置项里仍然留着我们的名字。
 *
 * 结果就是界面显示「已授权」，但系统已经不认识这个监听了：
 *   - NotificationListenerService.requestRebind() 找不到对象，什么也不做
 *   - 只能手动去设置里把「通知使用权」关掉再打开，才会重新注册
 *
 * 这里做的就是那件手动的事：把这个设置项**改两次**（先摘掉自己，再写回去），
 * 让系统走一遍"移除 → 重新注册"的流程。
 *
 * 权限
 * ----
 * 改 Settings.Secure 需要 WRITE_SECURE_SETTINGS，这是签名级权限，普通应用拿不到，
 * 只能用电脑执行一次：
 *
 *     adb shell pm grant com.dsh.notifycleaner android.permission.WRITE_SECURE_SETTINGS
 *
 * 这个权限只需授予一次，之后永久有效，重启手机也不掉。
 */
public final class ListenerFix {

    private static final String SECURE_KEY = "enabled_notification_listeners";
    private static final String PREFS = "notify_cleaner_fix";
    /** 改之前先把原值存下来：万一中途进程挂了，下次启动能恢复，不会把权限弄丢。 */
    private static final String PENDING = "pendingOriginal";
    private static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";

    private ListenerFix() {
    }

    /** 有没有拿到 WRITE_SECURE_SETTINGS（只能靠 adb 授予）。 */
    public static boolean canFix(Context context) {
        try {
            return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 给用户看的 adb 命令。 */
    public static String adbCommand(Context context) {
        return "adb shell pm grant " + context.getPackageName() + " " + PERMISSION;
    }

    /**
     * 强行让系统重新注册通知监听。
     * 返回 false 表示没权限、或者我们的组件本来就不在列表里。
     */
    public static boolean forceReRegister(Context context, Handler handler) {
        if (!canFix(context)) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            String current = Settings.Secure.getString(cr, SECURE_KEY);
            if (current == null || current.isEmpty()) return false;

            String ours = new ComponentName(context, NotifyCleanerService.class).flattenToString();
            if (!current.contains(ours)) return false;

            // 把我们的组件从列表里摘掉，其余的应用原样保留
            List<String> parts = new ArrayList<>();
            for (String part : current.split(":")) {
                if (part.isEmpty() || part.equals(ours)) continue;
                parts.add(part);
            }
            String without = TextUtils.join(":", parts);

            // 先存原值，再改
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(PENDING, current)
                    .apply();

            Settings.Secure.putString(cr, SECURE_KEY, without);
            LogStore.add(context, "🔧 深度修复：已把本应用从通知监听列表摘掉，400 毫秒后写回");

            // 隔一会儿再写回去，让系统真的走一遍"移除 → 重新注册"
            handler.postDelayed(() -> restore(context), 400);
            return true;
        } catch (Throwable t) {
            // 出错也要尽力恢复，别把权限弄丢
            restore(context);
            LogStore.add(context, "⚠️ 深度修复失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 把设置项写回原值。幂等，随时可以调用。 */
    public static void restore(Context context) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String original = p.getString(PENDING, null);
            if (original == null) return;
            Settings.Secure.putString(context.getContentResolver(), SECURE_KEY, original);
            p.edit().remove(PENDING).apply();
            LogStore.add(context, "🔧 深度修复：通知监听列表已恢复");
        } catch (Throwable ignored) {
            // 下次启动 App 时会再试一次
        }
    }
}
