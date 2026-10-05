package com.cue.simplebrowser;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.TextView;

import java.util.List;

/** Runs in a separate app process so GeckoView can be re-created with a new -profile argument. */
public final class ProfileRestartActivity extends Activity {
    static final String EXTRA_OLD_PID = "com.cue.simplebrowser.extra.OLD_GECKO_PID";
    static final String EXTRA_RESTART_STATE = "com.cue.simplebrowser.extra.RESTART_STATE";
    static final String EXTRA_DEBUG_RESTART = "com.cue.simplebrowser.extra.DEBUG_RESTART";
    private static final long MAX_WAIT_MS = 15000L;
    private static final long POLL_INTERVAL_MS = 100L;
    private static final long SETTLE_DELAY_MS = 500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startedAt;
    private int oldPid;
    private boolean launched;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(17);
        status.setPadding(32, 32, 32, 32);
        status.setText(getIntent().getBooleanExtra(EXTRA_DEBUG_RESTART, false)
                ? "正在重启浏览引擎以应用调试设置…"
                : "正在安全关闭旧浏览会话并切换环境…");
        setContentView(status);
        oldPid = getIntent().getIntExtra(EXTRA_OLD_PID, -1);
        startedAt = SystemClock.elapsedRealtime();
        handler.post(checkOldProcess);
    }

    private final Runnable checkOldProcess = new Runnable() {
        @Override public void run() {
            if (launched || isFinishing()) return;
            if (oldPid <= 0 || !isProcessRunning(oldPid)) {
                launched = true;
                handler.postDelayed(ProfileRestartActivity.this::launchBrowser, SETTLE_DELAY_MS);
                return;
            }
            if (SystemClock.elapsedRealtime() - startedAt > MAX_WAIT_MS) {
                status.setText("旧浏览会话尚未退出。请返回并重试环境切换；当前环境数据未删除。");
                return;
            }
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private boolean isProcessRunning(int pid) {
        ActivityManager manager = getSystemService(ActivityManager.class);
        if (manager == null) return false;
        List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
        if (processes == null) return false;
        String mainProcessName = getPackageName();
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.pid == pid && mainProcessName.equals(process.processName)) return true;
        }
        return false;
    }

    private void launchBrowser() {
        if (isFinishing()) return;
        Intent intent = new Intent(this, MainActivity.class);
        Bundle restartState = getIntent().getBundleExtra(EXTRA_RESTART_STATE);
        if (restartState != null) {
            intent.putExtra(EXTRA_RESTART_STATE, restartState);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
