package io.github.zhanxing.dshandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Switch;
import android.widget.Toast;

/** Native-only opt-in; applies to the Activity Window, not a particular WebView or screen. */
final class ScreenPrivacy {
    static final String TITLE = "截图与录屏";
    static final String EXPLANATION = "默认关闭，保护本机和远程模式。开启后允许系统截图、录屏及投屏，最近任务列表也可能显示页面预览。会话、文件、启动 URL 或密钥等内容可能被记录，请只在需要时开启，结束后关闭。\n\n此选择在本机与局域网 / Tailscale 远程模式间共用，重启应用后保留；清除应用数据或重装后恢复关闭。关闭不能删除已经保存的截图、录像或系统已有的预览，系统及厂商限制也可能影响实际效果。";
    private final Activity activity;
    private AlertDialog dialog;
    private Switch toggle;
    private boolean updating;
    private final ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };

    ScreenPrivacy(Activity activity) {
        this.activity = activity;
        // Secure even if the provider is unavailable, before any content is attached.
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        activity.getContentResolver().registerContentObserver(ScreenPrivacyProvider.uri(activity), false, observer);
        refresh();
    }

    boolean allowed() {
        try {
            Bundle value = activity.getContentResolver().call(ScreenPrivacyProvider.uri(activity), "get", null, null);
            return value != null && value.getBoolean(ScreenPrivacyProvider.OK, false)
                && value.getBoolean(ScreenPrivacyProvider.ALLOWED, false);
        } catch (RuntimeException error) { return false; }
    }

    boolean setAllowed(boolean allowed) {
        boolean ok = false;
        try {
            Bundle args = new Bundle();
            args.putBoolean(ScreenPrivacyProvider.ALLOWED, allowed);
            Bundle result = activity.getContentResolver().call(ScreenPrivacyProvider.uri(activity), "set", null, args);
            ok = result != null && result.getBoolean(ScreenPrivacyProvider.OK, false);
        } catch (RuntimeException error) { /* Stay secure if storage or IPC fails. */ }
        if (ok) refresh();
        else {
            apply(activity.getWindow(), false);
            if (dialog != null) apply(dialog.getWindow(), false);
            Toast.makeText(activity, "设置未能保存，已保持屏幕保护，请重试", Toast.LENGTH_LONG).show();
        }
        return ok;
    }

    void refresh() {
        boolean allowed = allowed();
        apply(activity.getWindow(), allowed);
        if (dialog != null) apply(dialog.getWindow(), allowed);
        if (toggle != null) {
            updating = true;
            toggle.setChecked(allowed);
            updating = false;
        }
    }

    private static void apply(Window window, boolean allowed) {
        if (window == null) return;
        if (allowed) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    void showSettings() {
        if (dialog != null && dialog.isShowing()) return;
        toggle = new Switch(activity);
        toggle.setText("允许截图与录屏");
        toggle.setTextSize(16);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density + .5f);
        toggle.setPadding(pad, pad / 2, pad, pad / 2);
        toggle.setChecked(allowed());
        dialog = new AlertDialog.Builder(activity).setTitle(TITLE).setMessage(EXPLANATION)
            .setView(toggle).setPositiveButton("完成", null).create();
        // A dialog is a separate Window: never leave this warning itself unprotected by accident.
        apply(dialog.getWindow(), allowed());
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            if (!checked) { setAllowed(false); refresh(); return; }
            updating = true;
            toggle.setChecked(false);
            updating = false;
            AlertDialog confirm = new AlertDialog.Builder(activity).setTitle("允许记录屏幕内容？")
                .setMessage("本机与远程页面都将允许截图、录屏及投屏；最近任务可能显示页面内容。请确认画面中没有不希望泄露的会话、文件或凭证。")
                .setNegativeButton("取消", null)
                .setPositiveButton("理解风险并开启", (d, w) -> { setAllowed(true); refresh(); }).create();
            apply(confirm.getWindow(), false);
            confirm.show();
        });
        dialog.setOnDismissListener(d -> { dialog = null; toggle = null; });
        dialog.show();
        refresh();
    }

    void destroy() {
        activity.getContentResolver().unregisterContentObserver(observer);
        if (dialog != null) dialog.dismiss();
    }
}
