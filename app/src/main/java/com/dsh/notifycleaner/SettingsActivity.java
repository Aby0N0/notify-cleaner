package com.dsh.notifycleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 唯一的界面。
 *
 * 刻意只用系统自带控件、不引入 AndroidX —— 依赖越少，云编译越不容易出问题。
 *
 * 页面结构从上到下：
 *   1. 顶部：现在到底在不在工作（固定不滚动，不用点进去猜）
 *   2. 权限：通知使用权授了没有；没连上会自动重连，不用手动点
 *   3. 保活：引导用户把本应用设成"不限制后台"，减少被系统杀掉
 *   4. 规则：监控哪些应用、等多久、关键字
 *   5. 自检：自己发一条通知，看它会不会自动消失
 *   6. 日志：最近发生了什么
 *   7. 当前通知：系统里现在有哪些通知，可以一键清掉
 */
public class SettingsActivity extends Activity {

    private static final int TEST_NOTIFICATION_ID = 90001;
    private static final String TEST_CHANNEL_ID = "selftest";
    private static final int REQ_POST_NOTIFICATIONS = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView headerChip;
    private TextView permState;
    private TextView permHint;
    private TextView reconnect;
    private TextView batteryBtn;
    private TextView autostartBtn;
    private TextView keepAliveHint;
    private Switch enabledBox;
    private EditText packagesBox;
    private EditText delayBox;
    private EditText keywordBox;
    private TextView testResult;
    private TextView logView;
    private TextView activeCount;
    private LinearLayout activeList;

    /** 还在等服务的连接结果。> 0 表示"正在连接"，== 0 表示"确实没连上"。 */
    private int pollTicks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        headerChip = findViewById(R.id.headerChip);
        permState = findViewById(R.id.permState);
        permHint = findViewById(R.id.permHint);
        reconnect = findViewById(R.id.reconnect);
        batteryBtn = findViewById(R.id.batteryBtn);
        autostartBtn = findViewById(R.id.autostartBtn);
        keepAliveHint = findViewById(R.id.keepAliveHint);
        enabledBox = findViewById(R.id.enabled);
        packagesBox = findViewById(R.id.packages);
        delayBox = findViewById(R.id.delay);
        keywordBox = findViewById(R.id.keyword);
        testResult = findViewById(R.id.testResult);
        logView = findViewById(R.id.logView);
        activeCount = findViewById(R.id.activeCount);
        activeList = findViewById(R.id.activeList);

        Config config = Config.load(this);
        enabledBox.setChecked(config.enabled);
        packagesBox.setText(TextUtils.join(",", config.packages));
        delayBox.setText(String.valueOf(config.delayMs));
        keywordBox.setText(config.keyword);

        findViewById(R.id.save).setOnClickListener(v -> save());
        findViewById(R.id.openAccess).setOnClickListener(v -> openListenerSettings());
        reconnect.setOnClickListener(v -> forceReconnect());
        findViewById(R.id.testBtn).setOnClickListener(v -> sendTest());
        findViewById(R.id.viewLog).setOnClickListener(v -> showFullLog());
        findViewById(R.id.refreshActive).setOnClickListener(v -> refreshActive());
        findViewById(R.id.clearActive).setOnClickListener(v -> clearAllActive());

        batteryBtn.setOnClickListener(v -> {
            if (KeepAlive.isIgnoringBatteryOptimizations(this)) {
                Toast.makeText(this, R.string.keepalive_battery_ok, Toast.LENGTH_SHORT).show();
                return;
            }
            KeepAlive.requestIgnoreBatteryOptimizations(this);
        });

        autostartBtn.setOnClickListener(v -> {
            boolean opened = KeepAlive.openAutostartSettings(this);
            if (opened) {
                keepAliveHint.setVisibility(View.GONE);
                LogStore.add(this, "🔧 打开了系统自启动管理页");
            } else {
                keepAliveHint.setText(R.string.keepalive_autostart_fail);
                keepAliveHint.setVisibility(View.VISIBLE);
                LogStore.add(this, "🔧 这台手机没有通用的自启动页面");
            }
        });

        // 看门狗闹钟：进程被杀后，靠它每 5 分钟把服务要回来
        KeepAlive.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 一进来就把服务要回来，不用用户点「重新连接」。
        // 划掉多任务后系统可能还没重新绑定，所以请求完再观察几秒。
        if (isListenerEnabled() && !connectedNow()) {
            kickService();
            pollTicks = 5;
            pollConnection();
        } else {
            pollTicks = 0;
        }

        refreshKeepAlive();
        refreshPermission();
        refreshLog();
        refreshActive();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ==================== 连接状态 ====================

    private boolean connectedNow() {
        NotifyCleanerService service = NotifyCleanerService.get();
        return service != null && service.isConnected();
    }

    private void kickService() {
        try {
            NotifyCleanerService.requestRebind(new ComponentName(this, NotifyCleanerService.class));
        } catch (Throwable ignored) {
            // 请求不到就等看门狗
        }
    }

    /** 每 600 毫秒看一眼连上没有，最多看 5 次（约 3 秒）。 */
    private void pollConnection() {
        if (pollTicks <= 0) {
            refreshPermission();
            return;
        }
        if (connectedNow()) {
            pollTicks = 0;
            refreshPermission();
            refreshActive();
            return;
        }
        pollTicks--;
        handler.postDelayed(() -> {
            pollConnection();
            refreshPermission();
        }, 600);
    }

    private void refreshPermission() {
        boolean granted = isListenerEnabled();
        Config config = Config.load(this);

        if (!granted) {
            setChip(R.drawable.bg_chip_bad, R.color.danger, getString(R.string.chip_no_perm));
            permState.setText(R.string.perm_bad);
            permState.setTextColor(getColor(R.color.danger));
            permHint.setText(R.string.perm_hint_bad);
            reconnect.setVisibility(View.GONE);
            return;
        }

        permState.setText(R.string.perm_ok);
        permState.setTextColor(getColor(R.color.primary));

        if (connectedNow()) {
            reconnect.setVisibility(View.GONE);
            permHint.setText(R.string.perm_hint_ok);
            setChip(R.drawable.bg_chip_ok, R.color.primary_dark,
                    getString(config.enabled ? R.string.chip_ok : R.string.chip_off));
            return;
        }

        permHint.setText(R.string.perm_hint_no_service);
        if (pollTicks > 0) {
            // 还在自动重连，先别报警
            setChip(R.drawable.bg_chip_neutral, R.color.text_sub,
                    getString(R.string.chip_connecting));
            reconnect.setVisibility(View.GONE);
        } else {
            setChip(R.drawable.bg_chip_bad, R.color.danger, getString(R.string.chip_no_service));
            reconnect.setVisibility(View.VISIBLE);
        }
    }

    private void setChip(int bgRes, int colorRes, String text) {
        headerChip.setBackgroundResource(bgRes);
        headerChip.setTextColor(getColor(colorRes));
        headerChip.setText(text);
    }

    private void refreshKeepAlive() {
        if (KeepAlive.isIgnoringBatteryOptimizations(this)) {
            batteryBtn.setText(R.string.keepalive_battery_ok);
            batteryBtn.setAlpha(0.65f);
        } else {
            batteryBtn.setText(R.string.keepalive_battery);
            batteryBtn.setAlpha(1f);
        }
    }

    /**
     * 判断本应用的通知使用权有没有被授予。
     * 读的是系统那个 "enabled_notification_listeners" 设置项，里面存的是扁平的组件名列表。
     */
    private boolean isListenerEnabled() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 动作 ====================

    private void save() {
        int delay;
        try {
            delay = Integer.parseInt(delayBox.getText().toString().trim());
        } catch (Throwable t) {
            delay = Config.DEFAULT_DELAY_MS;
        }
        if (delay < 100) delay = 100;            // 太短没意义
        if (delay > 600000) delay = 600000;      // 上限 10 分钟

        Config.save(this,
                enabledBox.isChecked(),
                packagesBox.getText().toString(),
                delay,
                keywordBox.getText().toString());

        delayBox.setText(String.valueOf(delay));
        Toast.makeText(this, R.string.save_done, Toast.LENGTH_SHORT).show();
        LogStore.add(this, "⚙️ 规则已保存（延迟 " + delay + " 毫秒）");
        refreshPermission();
        refreshLog();
    }

    private void openListenerSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Throwable t) {
            Toast.makeText(this,
                    "打不开通知使用权设置，请手动到 系统设置 → 通知 → 通知使用权 里找",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void forceReconnect() {
        kickService();
        LogStore.add(this, "🔁 手动请求重新连接");
        Toast.makeText(this, "已请求重连", Toast.LENGTH_SHORT).show();
        pollTicks = 5;
        pollConnection();
        refreshLog();
    }

    // ==================== 自检 ====================

    private void sendTest() {
        if (!ensurePostPermission()) return;

        Config config = Config.load(this);
        int delay = config.delayMs;
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            Toast.makeText(this, "拿不到通知管理器", Toast.LENGTH_SHORT).show();
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                TEST_CHANNEL_ID, "自检", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("应用自己发的测试通知，用来验证清理链路");
        nm.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, TEST_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("自测通知")
                .setContentText("如果一切正常，" + (delay / 1000.0) + " 秒后它会自己消失")
                .setWhen(System.currentTimeMillis())
                .build();

        try {
            nm.notify(TEST_NOTIFICATION_ID, notification);
        } catch (Throwable t) {
            testResult.setText("发不出去：" + t);
            return;
        }

        LogStore.add(this, "🧪 发出自测通知，等 " + delay + " 毫秒");
        testResult.setText("已发出，等 " + (delay / 1000.0) + " 秒后看结果…");
        refreshLog();

        handler.postDelayed(() -> checkTest(delay), delay + 2500L);
    }

    private void checkTest(int delay) {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean stillThere = false;
        try {
            StatusBarNotification[] mine = nm.getActiveNotifications();
            if (mine != null) {
                for (StatusBarNotification sbn : mine) {
                    if (sbn != null && sbn.getId() == TEST_NOTIFICATION_ID) {
                        stillThere = true;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            testResult.setText("自测结果读不到：" + t);
            return;
        }

        if (stillThere) {
            testResult.setText("❌ 自测失败：通知还在。把下面「最近发生了什么」里的内容发我");
            testResult.setTextColor(getColor(R.color.danger));
            LogStore.add(this, "❌ 自测失败：等了 " + delay + " 毫秒，自测通知还在");
            try {
                nm.cancel(TEST_NOTIFICATION_ID);
            } catch (Throwable ignored) {
                // 清不掉就算了，用户自己划掉
            }
        } else {
            testResult.setText("✅ 自测通过：通知到点自己消失了，链路是通的");
            testResult.setTextColor(getColor(R.color.primary));
            LogStore.add(this, "✅ 自测通过：自测通知已被自动清除");
        }
        refreshLog();
        refreshActive();
    }

    private boolean ensurePostPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                        REQ_POST_NOTIFICATIONS);
                Toast.makeText(this, "先允许发通知，再点一次自检", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
        return true;
    }

    // ==================== 日志 ====================

    private void refreshLog() {
        String all = LogStore.getAll(this);
        if (all.trim().isEmpty()) {
            logView.setText(R.string.log_empty);
            return;
        }
        String[] lines = all.split("\n");
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(10, lines.length);
        for (int i = 0; i < shown; i++) {
            sb.append(lines[i]);
            if (i < shown - 1) sb.append('\n');
        }
        if (lines.length > shown) {
            sb.append("\n…共 ").append(lines.length).append(" 条，点右上角「查看全部」");
        }
        logView.setText(sb.toString());
    }

    private void showFullLog() {
        String all = LogStore.getAll(this);
        if (all.trim().isEmpty()) all = getString(R.string.log_empty);

        TextView tv = new TextView(this);
        tv.setText(all);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextColor(getColor(R.color.text));
        tv.setTextIsSelectable(true);
        int pad = dp(14);
        tv.setPadding(pad, pad, pad, pad);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(4), dp(8), 0);
        container.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(400)));

        new AlertDialog.Builder(this)
                .setTitle(R.string.log_title)
                .setView(container)
                .setPositiveButton(R.string.log_close, null)
                .setNeutralButton(R.string.log_clear, (d, which) -> {
                    LogStore.clear(this);
                    refreshLog();
                })
                .show();
    }

    // ==================== 当前通知 ====================

    private void refreshActive() {
        activeList.removeAllViews();
        NotifyCleanerService service = NotifyCleanerService.get();
        StatusBarNotification[] arr = service == null ? null : service.activeSafe();
        if (arr == null) {
            activeCount.setText("—");
            addRow(getString(R.string.active_no_service));
            return;
        }

        int total = 0;
        for (StatusBarNotification sbn : arr) {
            if (sbn == null || sbn.isOngoing()) continue;
            total++;
            if (total <= 6) addRow(describe(sbn));
        }
        activeCount.setText(String.valueOf(total));

        if (total == 0) {
            addRow(getString(R.string.active_none));
        } else if (total > 6) {
            addRow(getString(R.string.active_more, total - 6));
        }
    }

    private String describe(StatusBarNotification sbn) {
        String label = sbn.getPackageName();
        try {
            PackageManager pm = getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(sbn.getPackageName(), 0)).toString();
        } catch (Throwable ignored) {
            // 查不到就用包名
        }

        CharSequence rawTitle = null;
        if (sbn.getNotification() != null && sbn.getNotification().extras != null) {
            rawTitle = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
        }
        String title = rawTitle == null ? "（无标题）" : rawTitle.toString().replace('\n', ' ').trim();
        if (title.isEmpty()) title = "（无标题）";
        if (title.length() > 18) title = title.substring(0, 18) + "…";

        long ageSec = Math.max(0, (System.currentTimeMillis() - sbn.getPostTime()) / 1000);
        return label + "  ·  " + title + "  ·  " + ageSec + " 秒前";
    }

    private void addRow(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(getColor(R.color.text));
        tv.setBackgroundResource(R.drawable.bg_row);
        int p = dp(10);
        tv.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        activeList.addView(tv);
    }

    private void clearAllActive() {
        NotifyCleanerService service = NotifyCleanerService.get();
        if (service == null) {
            Toast.makeText(this, R.string.active_no_service, Toast.LENGTH_SHORT).show();
            return;
        }
        int count = service.clearAllActive();
        if (count < 0) {
            Toast.makeText(this, "清除失败，服务可能没连上", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "已清掉 " + count + " 条", Toast.LENGTH_SHORT).show();
        }
        handler.postDelayed(this::refreshActive, 400);
        refreshLog();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
