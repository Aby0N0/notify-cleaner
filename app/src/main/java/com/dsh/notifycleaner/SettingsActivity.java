package com.dsh.notifycleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
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
    private TextView deepFixHint;
    private TextView fgsState;
    private TextView batteryBtn;
    private TextView autostartBtn;
    private TextView keepAliveHint;
    private Switch enabledBox;
    private EditText packagesBox;
    private EditText delayBox;
    private EditText titleBox;
    private EditText textBox;
    private TextView testResult;
    private TextView logView;
    private TextView activeCount;
    private LinearLayout activeList;

    /** 界面在前台时每隔 1 秒刷新一次状态。 */
    private final Runnable liveTick = new Runnable() {
        @Override
        public void run() {
            refreshPermission();
            refreshFgsState();

            // 没连上就持续催，每 2 秒一次，最多 30 次（约 1 分钟）
            if (isListenerEnabled() && !connectedNow() && rebindTries < 30) {
                rebindTries++;
                if (rebindTries % 2 == 1) kickService();
            }
            handler.postDelayed(this, 1000);
        }
    };

    private int rebindTries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        headerChip = findViewById(R.id.headerChip);
        permState = findViewById(R.id.permState);
        permHint = findViewById(R.id.permHint);
        reconnect = findViewById(R.id.reconnect);
        deepFixHint = findViewById(R.id.deepFixHint);
        fgsState = findViewById(R.id.fgsState);
        batteryBtn = findViewById(R.id.batteryBtn);
        autostartBtn = findViewById(R.id.autostartBtn);
        keepAliveHint = findViewById(R.id.keepAliveHint);
        enabledBox = findViewById(R.id.enabled);
        packagesBox = findViewById(R.id.packages);
        delayBox = findViewById(R.id.delay);
        titleBox = findViewById(R.id.titleKeyword);
        textBox = findViewById(R.id.textKeyword);
        testResult = findViewById(R.id.testResult);
        logView = findViewById(R.id.logView);
        activeCount = findViewById(R.id.activeCount);
        activeList = findViewById(R.id.activeList);

        Config config = Config.load(this);
        enabledBox.setChecked(config.enabled);
        packagesBox.setText(TextUtils.join(",", config.packages));
        delayBox.setText(String.valueOf(config.delayMs));
        titleBox.setText(config.titleKeyword);
        textBox.setText(config.textKeyword);

        findViewById(R.id.save).setOnClickListener(v -> save());
        findViewById(R.id.openAccess).setOnClickListener(v -> openListenerSettings());
        reconnect.setOnClickListener(v -> forceReconnect());
        findViewById(R.id.deepFixBtn).setOnClickListener(v -> deepFix());
        findViewById(R.id.testBtn).setOnClickListener(v -> sendTest());
        findViewById(R.id.viewLog).setOnClickListener(v -> showFullLog());
        findViewById(R.id.refreshActive).setOnClickListener(v -> refreshActive());
        findViewById(R.id.clearActive).setOnClickListener(v -> clearAllActive());

        findViewById(R.id.hideNotifBtn).setOnClickListener(v -> {
            openChannelSettings();
            findViewById(R.id.hideNotifWarn).setVisibility(View.VISIBLE);
            LogStore.add(this, "🔧 打开了常驻通知的渠道设置");
        });

        batteryBtn.setOnClickListener(v -> {
            if (KeepAlive.isIgnoringBatteryOptimizations(this)) {
                Toast.makeText(this, R.string.keepalive_battery_ok, Toast.LENGTH_SHORT).show();
                return;
            }
            KeepAlive.requestIgnoreBatteryOptimizations(this);
            // 回来之后复查一次：vivo 上这个系统对话框经常点了也没生效
            handler.postDelayed(this::checkBatteryResult, 2500);
        });

        autostartBtn.setOnClickListener(v -> {
            boolean opened = KeepAlive.openAutostartSettings(this);
            if (opened) {
                keepAliveHint.setVisibility(View.GONE);
                LogStore.add(this, "🔧 打开了系统自启动管理页");
            } else {
                keepAliveHint.setText(R.string.keepalive_autostart_fail);
                keepAliveHint.setVisibility(View.VISIBLE);
                LogStore.add(this, "🔧 没找到自启动页，已打开本应用的详情页");
            }
        });

        // 前台服务：这是"划掉多任务还活着"的关键。
        // 它的通知必须有状态栏图标，否则系统会当成没有前台服务而杀掉。
        KeepAliveService.start(this);
        KeepAlive.schedule(this);

        // 前台服务的通知要能显示出来，否则系统可能不把它当回事
        ensurePostPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebindTries = 0;
        KeepAliveService.start(this);

        // 一进来就把通知监听服务要回来
        if (isListenerEnabled() && !connectedNow()) {
            kickService();
        }

        refreshKeepAlive();
        refreshPermission();
        refreshFgsState();
        refreshLog();
        refreshActive();

        handler.removeCallbacks(liveTick);
        handler.postDelayed(liveTick, 1000);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(liveTick);
        super.onPause();
    }

    /**
     * 用户离开界面时（切到别的应用、按返回、或者直接把任务划掉），
     * 都先排一个「1 秒后把自己拉起来」的闹钟。
     *
     * 为什么要在这里排：日志证明 onTaskRemoved() 从来没被系统调用过
     * （vivo 是直接 SIGKILL，不给回调机会）。onStop 是我们能拿到的、
     * 最接近"用户要走了"的信号。
     *
     * 副作用很小：如果应用本来就活着，这个闹钟只是把已经在跑的服务再 start 一次。
     */
    @Override
    protected void onStop() {
        KeepAlive.scheduleRestart(this, 1000);
        super.onStop();
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
        } catch (Throwable t) {
            LogStore.add(this, "⚠️ 请求重连失败：" + t.getClass().getSimpleName());
        }
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
        if (rebindTries <= 4) {
            // 刚开始自动重连，先别报警
            setChip(R.drawable.bg_chip_neutral, R.color.text_sub,
                    getString(R.string.chip_connecting));
            reconnect.setVisibility(View.GONE);
            deepFixHint.setVisibility(View.GONE);
        } else {
            setChip(R.drawable.bg_chip_bad, R.color.danger, getString(R.string.chip_no_service));
            reconnect.setVisibility(View.VISIBLE);
            deepFixHint.setVisibility(View.VISIBLE);
        }
    }

    private void refreshFgsState() {
        boolean running = KeepAliveService.isRunning();
        fgsState.setText(running ? R.string.fgs_running : R.string.fgs_stopped);
        fgsState.setTextColor(getColor(running ? R.color.primary : R.color.danger));
    }

    private void setChip(int bgRes, int colorRes, String text) {
        headerChip.setBackgroundResource(bgRes);
        headerChip.setTextColor(getColor(colorRes));
        headerChip.setText(text);
    }

    private void refreshKeepAlive() {        if (KeepAlive.isIgnoringBatteryOptimizations(this)) {
            batteryBtn.setText(R.string.keepalive_battery_ok);
            batteryBtn.setTextColor(getColor(R.color.primary_dark));
            batteryBtn.setBackgroundResource(R.drawable.bg_button_soft);
            batteryBtn.setAlpha(0.7f);
        } else {
            // 没进白名单时做成醒目的实心按钮 —— 这一项不做，前面所有机制都可能白费
            batteryBtn.setText(R.string.keepalive_battery_urgent);
            batteryBtn.setTextColor(getColor(R.color.white));
            batteryBtn.setBackgroundResource(R.drawable.bg_button_primary);
            batteryBtn.setAlpha(1f);
        }
    }

    /**
     * 点了「不限制后台」之后复查结果。
     * vivo 上那个系统对话框经常点了也不生效（它的「智能控制」是另一套东西），
     * 所以这里要给出手动设置的路径，不能让用户以为已经设好了。
     */
    private void checkBatteryResult() {
        refreshKeepAlive();
        if (KeepAlive.isIgnoringBatteryOptimizations(this)) {
            LogStore.add(this, "✅ 已加入电池优化白名单");
            return;
        }
        LogStore.add(this, "⚠️ 系统没接受白名单请求（vivo 上常见），需要手动设置");
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_manual_title)
                .setMessage(R.string.battery_manual_msg)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.open_app_details,
                        (d, w) -> KeepAlive.openAppDetails(this))
                .show();
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
                titleBox.getText().toString(),
                textBox.getText().toString());

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

    /**
     * 跳到「后台常驻」这个通知渠道的系统设置页。
     * 注意：关掉它会显著增加被系统杀掉的风险，界面上有警告。
     */
    private void openChannelSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, KeepAliveService.CHANNEL_ID);
            startActivity(intent);
            return;
        } catch (Throwable ignored) {
            // 有些 ROM 不认渠道级的设置页，退到应用的通知设置页
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Throwable ignored) {
            Toast.makeText(this,
                    "打不开通知设置，请手动到 设置 → 通知 里找到本应用",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void forceReconnect() {
        kickService();
        LogStore.add(this, "🔁 手动请求重新连接");
        Toast.makeText(this, "已请求重连", Toast.LENGTH_SHORT).show();
        rebindTries = 0;
        refreshLog();
    }

    /**
     * 深度修复：把本应用从「通知使用权」列表里摘掉再写回，逼系统重新注册监听。
     * 这就是用户手动"关开一次通知使用权"做的同一件事。
     * 需要 WRITE_SECURE_SETTINGS（adb 授予一次），没有就给出命令。
     */
    private void deepFix() {
        if (!ListenerFix.canFix(this)) {
            showAdbDialog();
            return;
        }
        boolean started = ListenerFix.forceReRegister(this, handler);
        if (started) {
            Toast.makeText(this, R.string.deep_fix_done, Toast.LENGTH_SHORT).show();
            rebindTries = 0;
            handler.postDelayed(this::kickService, 1200);
        } else {
            Toast.makeText(this, "修复没能执行，看日志里的原因", Toast.LENGTH_SHORT).show();
        }
        refreshLog();
    }

    private void showAdbDialog() {
        String cmd = ListenerFix.adbCommand(this);
        new AlertDialog.Builder(this)
                .setTitle(R.string.deep_fix_dialog_title)
                .setMessage(getString(R.string.deep_fix_dialog_msg) + "\n\n" + cmd)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.copy_cmd, (d, which) -> copyToClipboard(cmd))
                .show();
    }

    // ==================== 自检 ====================

    private void sendTest() {
        if (!ensurePostPermission()) {
            Toast.makeText(this, "先允许发通知，再点一次自检", Toast.LENGTH_SHORT).show();
            return;
        }
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
                .setNeutralButton(R.string.log_share, (d, which) -> shareLog())
                .setNegativeButton(R.string.log_clear, (d, which) -> {
                    LogStore.clear(this);
                    refreshLog();
                })
                .show();
    }

    /** 把日志用系统分享面板发出去 —— 直接发给我最省事，不用截图也不用复制粘贴。 */
    private void shareLog() {
        try {
            String all = LogStore.getAll(this);
            if (all.trim().isEmpty()) all = "（暂无记录）";
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "通知清理器日志");
            send.putExtra(Intent.EXTRA_TEXT, all);
            startActivity(Intent.createChooser(send, getString(R.string.log_share)));
        } catch (Throwable t) {
            Toast.makeText(this, "分享失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    /** 一键把日志复制到剪贴板 —— 比截图方便，也方便直接粘给我。 */
    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("通知清理器日志", text));
            Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t, Toast.LENGTH_SHORT).show();
        }
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
        CharSequence rawText = null;
        if (sbn.getNotification() != null && sbn.getNotification().extras != null) {
            rawTitle = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
            rawText = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
        }

        long ageSec = Math.max(0, (System.currentTimeMillis() - sbn.getPostTime()) / 1000);
        // 标题和正文都列出来 —— 这是判断"关键字该填哪个框"的唯一可靠依据
        return label + "  ·  " + ageSec + " 秒前\n"
                + "标题「" + shortText(rawTitle, 14) + "」正文「" + shortText(rawText, 18) + "」";
    }

    private String shortText(CharSequence s, int max) {
        if (s == null) return "空";
        String t = s.toString().replace('\n', ' ').trim();
        if (t.isEmpty()) return "空";
        return t.length() <= max ? t : t.substring(0, max) + "…";
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
