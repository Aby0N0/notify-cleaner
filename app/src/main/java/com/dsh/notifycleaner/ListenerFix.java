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
    /** 记录"本应用曾经真的连上过通知监听"，用于设置项被意外清空时的自救。 */
    private static final String EVER_GRANTED = "everGranted";
    /** 组件禁用/启用过程中途被打断的标记，下次启动要补上，免得监听器一直被禁用。 */
    private static final String TOGGLE_PENDING = "togglePending";

    private ListenerFix() {
    }

    /** 通知监听成功连上时调用，记下"我们确实有过这个权限"。 */
    public static void markGranted(Context context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(EVER_GRANTED, true).apply();
        } catch (Throwable ignored) {
            // 记不上只是少了这一层自救，不影响别的
        }
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
            if (!current.contains(ours)) {
                // 我们的名字不见了 —— 正常情况下不该发生，但如果真发生了，
                // 而且我们确认过"曾经连上过"，就把它加回去。这是最后一道自救。
                SharedPreferences p0 = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (!p0.getBoolean(EVER_GRANTED, false)) return false;
                String rebuilt = current.isEmpty() ? ours : current + ":" + ours;
                Settings.Secure.putString(cr, SECURE_KEY, rebuilt);
                LogStore.add(context, "🔧 深度修复：通知监听设置里找不到本应用，已重新写回");
                return true;
            }

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
    public static void restore(Context context) {        try {
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

    // ==================== 策略 A：禁用再启用组件 ====================

    /**
     * 把自己这个通知监听组件「禁用 → 1.2 秒 → 启用」。
     *
     * 为什么这可能有用（真机实测出来的）：
     *   adb shell cmd notification allow_listener <组件>  单独执行就能把监听器救活
     *   （Live notification listeners 从 21 变成 22）。也就是说系统随时愿意重绑，
     *   只是需要一个"状态变化"去惊动它。
     *   而直接改 Settings.Secure 里那串 enabled_notification_listeners **完全无效**
     *   （实测：值确实变了，但 Live 计数纹丝不动）—— 说明系统根本不监听那个设置项了。
     *
     * 应用自己改不了系统的授权状态，但可以改**自己的组件启用状态**。
     * 组件状态一变，系统会收到包变化通知，进而重新整理监听器列表 —— 这是
     * 应用侧能触发的最强的一次"状态变化"。
     *
     * 风险与防护：万一在"禁用"和"启用"之间进程被杀，监听组件会一直处于禁用状态，
     * 表现为"通知使用权看起来是开的，但完全收不到通知"。
     * 所以这里先落一个 TOGGLE_PENDING 标记，App 每次启动都会调 restoreComponent() 兜底恢复。
     */
    public static boolean toggleComponent(Context context, Handler handler) {
        try {
            final PackageManager pm = context.getPackageManager();
            final ComponentName cn = new ComponentName(context, NotifyCleanerService.class);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(TOGGLE_PENDING, true).apply();

            pm.setComponentEnabledSetting(cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            LogStore.add(context, "🔧 策略A：已禁用监听组件，1.2 秒后重新启用");

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        pm.setComponentEnabledSetting(cn,
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                PackageManager.DONT_KILL_APP);
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .edit().remove(TOGGLE_PENDING).apply();
                        LogStore.add(context, "🔧 策略A：组件已重新启用");
                    } catch (Throwable t) {
                        LogStore.add(context, "⚠️ 重新启用组件失败：" + t.getClass().getSimpleName());
                    }
                }
            }, 1200);
            return true;
        } catch (Throwable t) {
            LogStore.add(context, "⚠️ 禁用组件失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 上一次"禁用组件"如果没走完，启动时补上 —— 免得监听器一直被禁用。 */
    public static void restoreComponent(Context context) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!p.getBoolean(TOGGLE_PENDING, false)) return;
            PackageManager pm = context.getPackageManager();
            pm.setComponentEnabledSetting(new ComponentName(context, NotifyCleanerService.class),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            p.edit().remove(TOGGLE_PENDING).apply();
            LogStore.add(context, "🔧 已恢复上次被禁用的监听组件");
        } catch (Throwable ignored) {
            // 下次启动再试
        }
    }
}
