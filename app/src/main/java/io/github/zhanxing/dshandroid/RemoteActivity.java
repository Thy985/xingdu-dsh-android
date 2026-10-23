package io.github.zhanxing.dshandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Remote-only WebView, declared in the private :remote process. No local service binding. */
public final class RemoteActivity extends Activity {
    private ScreenPrivacy screenPrivacy;
    private static final int BLUE = 0xff3268d9, INK = 0xff18243a;
    private static final int MUTED = 0xff65738a, BG = 0xfff4f6fa;
    private static final int MAX_FILES = 16;
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final boolean DATA_DIRECTORY_READY = initWebViewDataDirectory();
    // Main-thread only, process-wide ownership prevents an old Activity's cleanup clearing a new one.
    private static long profileOwner;
    private long owner;
    private LinearLayout root;
    private WebView web;
    private EditText address;
    private TextView state;
    private ProgressBar progress;
    private URI origin;
    private boolean allowHttp, pageFailed, destroyed;
    private volatile int generation;
    private int nextPicker = 1901, pendingPicker;
    private ValueCallback<Uri[]> fileCallback;
    private final ExecutorService fileChecks = Executors.newSingleThreadExecutor();

    private static boolean initWebViewDataDirectory() {
        if (Build.VERSION.SDK_INT < 28) return false;
        // Executed once, before CookieManager/WebView initializes Chromium in :remote.
        WebView.setDataDirectorySuffix("remote");
        WebView.setWebContentsDebuggingEnabled(false);
        return true;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        screenPrivacy = new ScreenPrivacy(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (!DATA_DIRECTORY_READY) {
            new AlertDialog.Builder(this).setTitle("星渡 · DSH")
                .setMessage("远程模式需要 Android 9+，以隔离本机和远程 WebView 会话。本机模式不受影响。")
                .setOnDismissListener(d -> finish()).show();
            return;
        }
        owner = ++profileOwner;
        CookieManager.getInstance().setAcceptCookie(true);
        shell();
        root.addView(text("正在清理旧远程会话…", 16, MUTED));
        clearSession(true);
    }

    private boolean alive() { return !destroyed && !isFinishing() && owner == profileOwner; }
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(BLUE);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void shell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets x = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(x.left, x.top, x.right, x.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    private void showConnect() {
        if (!alive()) return;
        shell();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(26), dp(24), dp(24));
        scroll.addView(page);
        root.addView(scroll);
        TextView mark = text("星渡  /  DSH", 14, BLUE);
        mark.setLetterSpacing(.16f);
        page.addView(mark);
        page.addView(text("你的主机，\n随身连接。", 32, INK));
        page.addView(text("远程客户端 · 与本机独立", 14, MUTED));
        page.addView(text("连接自建 DeepSeek Harness。会话、工作区和操作审批由主机官方页面提供；本模式不会停止或修改手机本机引擎。", 16, MUTED));
        page.addView(text("主机完整启动 URL", 14, INK));
        address = new EditText(this);
        address.setSingleLine(false);
        address.setMaxLines(4);
        address.setTextSize(16);
        address.setHint("https://主机/ui/?…");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_URI
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        address.setSaveEnabled(false);
        address.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        page.addView(address, new LinearLayout.LayoutParams(-1, dp(100)));
        page.addView(text("粘贴官方打印的完整 URL。启动令牌不写入应用配置。请先在系统连接同一局域网或 Tailscale；本应用不配置 VPN 或主机。", 13, MUTED));
        CheckBox http = new CheckBox(this);
        http.setText("允许 HTTP（仅可信 LAN / 已建立 VPN/Tailscale）");
        http.setTextColor(INK);
        page.addView(http);
        page.addView(button("连接主机 →", () -> {
            try {
                URI target = RemoteUrlPolicy.parse(address.getText().toString(), http.isChecked());
                if ("http".equalsIgnoreCase(target.getScheme())) {
                    new AlertDialog.Builder(this).setTitle("连接未加密的主机？")
                        .setMessage("HTTP 会明文传输启动令牌、Cookie 与任务内容。仅在你确认的隔离 LAN/VPN/Tailscale 网络继续。应用不验证 VPN 是否已建立。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("仍然连接", (d, w) -> connect(target, true)).show();
                } else connect(target, false);
            } catch (IllegalArgumentException error) { address.setError(error.getMessage()); }
        }));
        page.addView(text("安全边界\n拒绝证书错误及跨主机页面/HTTP(S)资源；这不是网页或同 UID 插件的完整网络沙箱。请只连接你自己的可信主机。", 14, MUTED));
        page.addView(text("官方页面上传：最多16个文件，按已知大小预检100 MiB；未知大小并非硬限。远程下载尚未实现。", 13, MUTED));
        page.addView(button(ScreenPrivacy.TITLE, () -> screenPrivacy.showSettings()));
        page.addView(button("返回本机工作台", this::finish));
        page.addView(text("非 DeepSeek 官方应用 · 星渡", 12, MUTED));
    }

    private void connect(URI target, boolean http) {
        if (!alive() || web != null || address == null) return;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.setText("");
        address = null;
        origin = URI.create(RemoteUrlPolicy.displayOrigin(target));
        allowHttp = http;
        pageFailed = false;
        final int current = ++generation;
        final URI selectedOrigin = origin;
        shell();
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(8), 0, dp(8), 0);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(button("断开", this::confirmDisconnect));
        bar.addView(text("星渡 · DSH", 17, INK), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button("刷新", () -> { if (web != null) web.reload(); }));
        Button privacy = button("隐私", () -> screenPrivacy.showSettings());
        privacy.setContentDescription(ScreenPrivacy.TITLE);
        privacy.setMinWidth(dp(48));
        privacy.setMinimumWidth(dp(48));
        privacy.setPadding(0, 0, 0, 0);
        bar.addView(privacy, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(bar);
        state = text((http ? "HTTP · " : "HTTPS · ") + RemoteUrlPolicy.displayOrigin(origin), 12, MUTED);
        state.setPadding(dp(16), 0, dp(16), dp(5));
        root.addView(state);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        web.setSaveEnabled(false);
        web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setGeolocationEnabled(false);
        s.setSafeBrowsingEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(Math.round(getResources().getConfiguration().fontScale * 100));
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebViewClient(new WebViewClient() {
            private boolean active(WebView v) { return current == generation && v == web && alive(); }
            private boolean allowed(String url) {
                try { return RemoteUrlPolicy.allowedResource(selectedOrigin, URI.create(url), http); }
                catch (RuntimeException error) { return false; }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!active(v)) return true;
                if (allowed(r.getUrl().toString())) return false;
                if (r.isForMainFrame()) toast("已阻止跨主机或不安全跳转");
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                // Callback is on Chromium's I/O thread: use immutable per-connection origin.
                if (current == generation && allowed(r.getUrl().toString())) return null;
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                    java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                if (!active(v)) return;
                if (!allowed(url)) { v.stopLoading(); showLoadError("已阻止跨主机或无效页面地址。"); return; }
                pageFailed = false;
                progress.setVisibility(View.VISIBLE);
                state.setText("正在载入 · " + RemoteUrlPolicy.displayOrigin(selectedOrigin));
            }
            @Override public void onPageFinished(WebView v, String url) {
                if (!active(v) || !allowed(url)) return;
                progress.setVisibility(View.GONE);
                if (!pageFailed) state.setText((http ? "HTTP 未加密" : "HTTPS") + " · 页面已载入（不代表已认证）");
                v.clearHistory();
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError error) {
                if (active(v) && r.isForMainFrame()) showLoadError("无法连接主机，请检查地址、网络 / VPN 和主机服务。");
            }
            @Override public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse response) {
                if (active(v) && r.isForMainFrame()) showLoadError("主机返回 HTTP " + response.getStatusCode() + "；请检查启动 URL 和可信主机配置。");
            }
            @Override public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (active(v)) showLoadError("证书验证失败，已拒绝连接；不会绕过 TLS。");
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                if (v != web) { v.destroy(); return true; }
                ++generation;
                cancelFileSelection();
                root.removeView(v);
                web = null;
                v.destroy();
                if (alive()) new AlertDialog.Builder(RemoteActivity.this).setTitle("页面进程已退出")
                    .setMessage("远程页面意外退出，未保存草稿可能丢失。请重新粘贴完整启动 URL 连接。")
                    .setCancelable(false).setPositiveButton("回到连接", (d, w) -> clearSession(true)).show();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int value) {
                if (current == generation && v == web && alive()) progress.setProgress(value);
            }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public void onGeolocationPermissionsShowPrompt(String o, GeolocationPermissions.Callback callback) {
                callback.invoke(o, false, false);
            }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (v != web || current != generation || !alive()) { callback.onReceiveValue(null); return true; }
                if (pendingPicker != 0) { callback.onReceiveValue(null); return true; }
                cancelFileSelection();
                fileCallback = callback;
                pendingPicker = nextPicker++;
                // Never recycle a picker request code within this Activity's lifetime.
                if (nextPicker > 60000) { cancelFileSelection(); toast("请断开后重新打开远程页面再选择文件"); return true; }
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(pick, pendingPicker); }
                catch (ActivityNotFoundException error) { cancelFileSelection(); toast("系统未提供文件选择器"); }
                return true;
            }
        });
        web.setDownloadListener((url, ua, cd, mime, length) -> toast("远程下载暂未实现，请在可信浏览器处理"));
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        web.loadUrl(target.toASCIIString());
    }

    private void showLoadError(String message) {
        if (web == null || !alive()) return;
        boolean alreadyFailed = pageFailed;
        pageFailed = true;
        state.setText(message);
        progress.setVisibility(View.GONE);
        if (!alreadyFailed) new AlertDialog.Builder(this).setTitle("连接提示")
            .setMessage(message + "\n可刷新重试；令牌失效时请断开后粘贴新的完整 URL。")
            .setPositiveButton("知道了", null).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != pendingPicker || fileCallback == null) return;
        pendingPicker = 0;
        final ValueCallback<Uri[]> callback = fileCallback;
        final int current = generation;
        if (result != RESULT_OK || data == null) { cancelFileSelection(); return; }
        List<Uri> chosen = new ArrayList<>();
        if (data.getClipData() != null) {
            if (data.getClipData().getItemCount() > MAX_FILES) {
                toast("最多选择16个文件"); cancelFileSelection(); return;
            }
            for (int i = 0; i < data.getClipData().getItemCount(); i++) chosen.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) chosen.add(data.getData());
        try {
            fileChecks.execute(() -> {
                Set<String> seen = new HashSet<>();
                long total = 0;
                boolean valid = !chosen.isEmpty();
                for (Uri uri : chosen) {
                    if (Thread.currentThread().isInterrupted() || uri == null
                        || !"content".equals(uri.getScheme()) || !seen.add(uri.toString())) { valid = false; break; }
                    long size = sizeOf(uri);
                    if (size > MAX_BYTES || size >= 0 && total > MAX_BYTES - size) { valid = false; break; }
                    if (size >= 0) total += size;
                }
                final boolean accepted = valid;
                runOnUiThread(() -> {
                    if (fileCallback != callback) return; // Already cancelled exactly once.
                    fileCallback = null;
                    if (current != generation || !alive()) { callback.onReceiveValue(null); return; }
                    if (!accepted) toast("文件无效、重复或已知总大小超过100 MiB");
                    callback.onReceiveValue(accepted ? chosen.toArray(new Uri[0]) : null);
                });
            });
        } catch (RejectedExecutionException error) { cancelFileSelection(); }
    }

    private long sizeOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            return c != null && c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : -1;
        } catch (RuntimeException error) { return -1; }
    }

    private void cancelFileSelection() {
        pendingPicker = 0;
        if (fileCallback != null) { ValueCallback<Uri[]> callback = fileCallback; fileCallback = null; callback.onReceiveValue(null); }
    }

    private void toast(String message) { if (alive()) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void confirmDisconnect() {
        new AlertDialog.Builder(this).setTitle("断开并清除远程会话？")
            .setMessage("不会停止主机任务，也不会停止本机服务。重新连接可能需要新的启动 URL。")
            .setNegativeButton("取消", null).setPositiveButton("断开", (d, w) -> disconnect()).show();
    }

    private void destroyWeb() {
        ++generation;
        cancelFileSelection();
        address = null;
        origin = null;
        if (web != null) {
            WebView old = web;
            web = null;
            old.stopLoading();
            old.clearHistory();
            old.clearCache(true);
            if (root != null) root.removeView(old);
            old.destroy();
        }
    }

    private void disconnect() {
        destroyWeb();
        shell();
        root.addView(text("正在清理远程会话…", 16, MUTED));
        clearSession(true);
    }

    private void clearSession(boolean returnToConnect) {
        if (!DATA_DIRECTORY_READY || owner != profileOwner) return;
        final long sessionOwner = owner;
        final int current = generation;
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(done -> {
            if (sessionOwner != profileOwner || current != generation) return;
            CookieManager.getInstance().flush();
            if (returnToConnect && alive()) showConnect();
        });
    }

    @Override public void onBackPressed() { if (web != null) confirmDisconnect(); else super.onBackPressed(); }

    @Override protected void onStart() {
        super.onStart();
        screenPrivacy.refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        screenPrivacy.refresh();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && screenPrivacy != null) screenPrivacy.refresh();
    }

    @Override protected void onDestroy() {
        if (screenPrivacy != null) screenPrivacy.destroy();
        destroyed = true;
        destroyWeb();
        clearSession(false);
        fileChecks.shutdownNow();
        super.onDestroy();
    }
}
