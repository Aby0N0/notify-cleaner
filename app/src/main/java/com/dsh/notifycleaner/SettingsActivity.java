package com.dsh.notifycleaner;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 唯一的界面：一个设置页。
 * 刻意用系统自带控件，不引入 AndroidX —— 依赖越少，云编译越不容易出问题。
 */
public class SettingsActivity extends Activity {

    private CheckBox enabledBox;
    private EditText packagesBox;
    private EditText delayBox;
    private EditText titleBox;
    private EditText textBox;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        enabledBox = findViewById(R.id.enabled);
        packagesBox = findViewById(R.id.packages);
        delayBox = findViewById(R.id.delay);
        titleBox = findViewById(R.id.titleKeyword);
        textBox = findViewById(R.id.textKeyword);
        statusView = findViewById(R.id.status);

        Config config = Config.load(this);
        enabledBox.setChecked(config.enabled);
        packagesBox.setText(TextUtils.join(",", config.packages));
        delayBox.setText(String.valueOf(config.delayMs));
        titleBox.setText(config.titleKeyword);
        textBox.setText(config.textKeyword);

        findViewById(R.id.save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });

        findViewById(R.id.openAccess).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openListenerSettings();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        statusView.setText(isListenerEnabled()
                ? "✅ 通知使用权：已授权"
                : "❌ 通知使用权：未授权\n点下面的按钮去系统设置里，找到「通知清理器」并打开");
    }

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
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
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
}
