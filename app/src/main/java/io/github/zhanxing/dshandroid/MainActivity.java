package io.github.zhanxing.dshandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Local lifecycle controls; remote sessions use a separate activity and WebView process. */
public final class MainActivity extends Activity {
  private static final String PREF_MOBILE_LAYOUT = "mobile-layout-enabled";
  private LinearLayout root;
  private LinearLayout content;
  private WebView web;
  private TextView status;
  private TextView introduction;
  private TextView homeEngineStatus;
  private TextView homeRuntimeStatus;
  private TextView homeDeviceStatus;
  private Button homeStart;
  private Button homeStop;
  private Button homeRetry;
  private boolean homeBuilt;
  private String homeSnapshotState = "未启动";
  private String cachedRuntimeStatus;
  private DshService.LocalBinder binder;
  private boolean bound;
  private boolean bindingRequested;
  private boolean destroyed;
  private String desiredUrl;
  private String pendingUrl;
  private String loadedUrl;
  private String pageError;
  private SafTransferController fileTransfers;
  private final ConnectionAttempt connectionAttempt = new ConnectionAttempt();
  private final DshService.Listener listener = snapshot -> runOnUiThread(() -> apply(snapshot));
  private final ServiceConnection connection = new ServiceConnection() {
    @Override public void onServiceConnected(ComponentName name, IBinder service) {
      if (destroyed) return;
      binder = (DshService.LocalBinder) service;
      bound = true;
      binder.addListener(listener);
    }
    @Override public void onServiceDisconnected(ComponentName name) {
      bound = false;
      binder = null;
      connectionAttempt.invalidate();
      desiredUrl = null;
      pendingUrl = null;
      loadedUrl = null;
      pageError = null;
      clearWeb();
      showIntroduction();
      status.setText("本机服务已断开，可重新启动");
      updateHome("本机服务已断开，可重新启动");
    }
  };

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    fileTransfers = new SafTransferController(this, saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    if (android.os.Build.VERSION.SDK_INT >= 28) {
      WindowManager.LayoutParams attributes = getWindow().getAttributes();
      attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
      getWindow().setAttributes(attributes);
    }

    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xfff7f8fc);
    setContentView(root);

    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(12), 0, dp(4), 0);
    bar.setBackgroundColor(0xfff7f8fc);
    root.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));
    TextView title = text("星渡", 18);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    bar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
    status = text("未启动", 12);
    status.setSingleLine(true);
    status.setEllipsize(TextUtils.TruncateAt.END);
    status.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
    status.setContentDescription("本机引擎状态，点击查看完整提示");
    status.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("本机引擎状态")
        .setMessage(status.getText()).setPositiveButton("知道了", null).show());
    bar.addView(status, new LinearLayout.LayoutParams(0, -1, 1));
    Button menu = new Button(this);
    menu.setText("⋮");
    menu.setTextSize(24);
    menu.setContentDescription("打开操作菜单");
    menu.setAllCaps(false);
    menu.setMinWidth(dp(48));
    menu.setMinHeight(dp(48));
    menu.setPadding(0, 0, 0, 0);
    menu.setOnClickListener(view -> showMenu());
    bar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));

    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
    showIntroduction();
    bindingRequested = bindService(new Intent(this, DshService.class), connection, BIND_AUTO_CREATE);
  }

  private int dp(int value) {
    return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
  }

  private TextView text(String value, int size) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(0xff20283d);
    view.setPadding(dp(8), dp(5), dp(8), dp(5));
    return view;
  }

  private void showIntroduction() {
    if (web != null) return;
    if (homeBuilt) {
      updateHome(homeSnapshotState);
      return;
    }
    content.removeAllViews();
    ScrollView scroll = new ScrollView(this);
    LinearLayout page = new LinearLayout(this);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(16), dp(8), dp(16), dp(20));
    TextView eyebrow = text("本机 / 远程工作台  ·  开发测试版", 12);
    eyebrow.setTextColor(0xff52617a);
    page.addView(eyebrow, new LinearLayout.LayoutParams(-1, -2));
    TextView heading = text("星渡 · DSH", 26);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    heading.setPadding(0, dp(2), 0, dp(2));
    page.addView(heading, new LinearLayout.LayoutParams(-1, -2));
    introduction = text("本机运行，或连接你自己的远程 DSH 主机。在手机上管理本机工作区，也可通过局域网 / Tailscale 连接电脑。模型 API 在官方设置中配置；首次启动需要解包运行时，建议预留至少 2 GB 空间。", 14);
    introduction.setTextColor(0xff52617a);
    introduction.setTextIsSelectable(true);
    page.addView(introduction, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout engine = card();
    addCardTitle(engine, "本机引擎", "服务状态与启停控制");
    homeEngineStatus = cardText("未启动");
    engine.addView(homeEngineStatus);
    LinearLayout controls = new LinearLayout(this);
    controls.setGravity(Gravity.CENTER_VERTICAL);
    homeStart = actionButton("启动");
    homeStart.setOnClickListener(view -> start());
    homeStop = actionButton("停止引擎");
    homeStop.setOnClickListener(view -> stop());
    homeRetry = actionButton("重试");
    homeRetry.setOnClickListener(view -> retry());
    controls.addView(homeStart, new LinearLayout.LayoutParams(0, dp(48), 1));
    controls.addView(homeStop, new LinearLayout.LayoutParams(0, dp(48), 1));
    controls.addView(homeRetry, new LinearLayout.LayoutParams(0, dp(48), 1));
    engine.addView(controls);
    page.addView(engine);

    LinearLayout remote = card();
    addCardTitle(remote, "远程连接", "局域网 / Tailscale · 与本机工作区分离");
    remote.addView(cardText("粘贴主机打印的完整启动 URL，进入主机上的官方 DSH 页面。先在系统中连接同一局域网或 Tailscale；本应用不创建 VPN、不更改主机审批策略。本机引擎可继续运行。远程模式需要 Android 9+。"));
    Button connectRemote = actionButton("连接远程主机");
    connectRemote.setOnClickListener(view -> openRemote());
    remote.addView(connectRemote, new LinearLayout.LayoutParams(-1, dp(46)));
    page.addView(remote);

    LinearLayout runtime = card();
    addCardTitle(runtime, "运行时与安装", "随包版本与本机安装标记");
    homeRuntimeStatus = cardText(refreshRuntimeStatus());
    runtime.addView(homeRuntimeStatus);
    page.addView(runtime);

    LinearLayout device = card();
    addCardTitle(device, "本机信息", "用于排查兼容性，不读取凭证");
    homeDeviceStatus = cardText(android.os.Build.MODEL + "  ·  Android " + android.os.Build.VERSION.RELEASE
        + "  ·  " + android.os.Build.SUPPORTED_ABIS[0]);
    device.addView(homeDeviceStatus);
    page.addView(device);

    LinearLayout files = card();
    addCardTitle(files, "手机文件", "系统选择授权 · 无需 Root · 不覆盖原件");
    files.addView(cardText("将手机文件或文件夹快照复制到默认工作区。可单文件另存为，或将已导入文件夹导出回原授权文件夹中的新子目录，不覆盖原件。不是实时同步；本入口仅操作本机工作区，不是远程上传/下载。"));
    Button transfer = actionButton("导入 / 导出手机文件");
    transfer.setOnClickListener(view -> fileTransfers.showMenu());
    files.addView(transfer, new LinearLayout.LayoutParams(-1, dp(46)));
    page.addView(files);

    LinearLayout safe = card();
    addCardTitle(safe, "安全快捷入口", "只读、脱敏");
    Button logs = actionButton("查看脱敏日志");
    logs.setOnClickListener(view -> showLog());
    Button diagnostics = actionButton("只读诊断");
    diagnostics.setOnClickListener(view -> showDiagnostics());
    safe.addView(logs, new LinearLayout.LayoutParams(-1, dp(46)));
    safe.addView(diagnostics, new LinearLayout.LayoutParams(-1, dp(46)));
    page.addView(safe);

    LinearLayout note = card();
    addCardTitle(note, "插件与终端", "官方页面内使用；本机首页不代操作");
    note.addView(cardText("插件、终端和命令能力必须在引擎启动后由官方 DSH 页面提供。点击下方了解入口；这里不会自动安装插件、执行命令或修改审批策略。"));
    Button plugins = actionButton("插件使用说明");
    plugins.setOnClickListener(view -> showOfficialGuide("官方插件入口",
        "启动引擎并进入官方 DSH 页面后：打开官方会话栏 → 账号菜单 → 设置 → 内置插件。插件是否可用以官方页面实际显示为准。"));
    Button terminal = actionButton("终端使用说明");
    terminal.setOnClickListener(view -> showOfficialGuide("官方终端入口",
        "启动引擎并进入官方 DSH 页面后：选择工作区 → 工具 → 终端。终端命令由你在官方页面内确认执行；本应用不会代为输入、执行命令或读取凭证。"));
    note.addView(plugins, new LinearLayout.LayoutParams(-1, dp(46)));
    note.addView(terminal, new LinearLayout.LayoutParams(-1, dp(46)));
    page.addView(note);
    scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
    content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    homeBuilt = true;
    updateHome(homeSnapshotState);
  }

  private LinearLayout card() {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(14), dp(12), dp(14), dp(10));
    android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
    background.setColor(0xffffffff);
    background.setCornerRadius(dp(14));
    card.setBackground(background);
    card.setElevation(dp(1));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(10), 0, 0);
    card.setLayoutParams(lp);
    return card;
  }

  private void addCardTitle(LinearLayout card, String title, String subtitle) {
    TextView heading = text(title, 16);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    card.addView(heading);
    TextView sub = text(subtitle, 12);
    sub.setTextColor(0xff65728b);
    card.addView(sub);
  }

  private TextView cardText(String value) {
    TextView view = text(value, 14);
    view.setTextColor(0xff26324a);
    view.setPadding(0, dp(8), 0, dp(8));
    return view;
  }

  private Button actionButton(String label) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setMinHeight(dp(44));
    return button;
  }

  private String refreshRuntimeStatus() {
    try {
      JSONObject manifest;
      try (InputStream input = getAssets().open(RuntimeInstaller.MANIFEST)) {
        byte[] bytes = new byte[8192];
        int count = input.read(bytes);
        manifest = new JSONObject(new String(bytes, 0, Math.max(0, count), StandardCharsets.UTF_8));
      }
      String version = manifest.optString("dshVersion", "未知版本");
      String expected = manifest.optString("sha256", "");
      File root = getFilesDir();
      File marker = new File(root, "runtime.sha256");
      File node = new File(root, "runtime/bin/node");
      boolean installed = node.isFile() && marker.isFile() && expected.equals(readSmall(marker));
      cachedRuntimeStatus = "官方 DSH " + version + "  ·  " + (installed ? "已安装（安装标记匹配；未执行完整文件哈希校验）" : "尚未安装");
      return cachedRuntimeStatus;
    } catch (Exception error) {
      cachedRuntimeStatus = "运行时信息不可读，启动时将重新校验安装";
      return cachedRuntimeStatus;
    }
  }

  private String runtimeStatus() {
    return cachedRuntimeStatus == null ? refreshRuntimeStatus() : cachedRuntimeStatus;
  }

  private String readSmall(File file) throws java.io.IOException {
    try (InputStream input = new java.io.FileInputStream(file)) {
      byte[] bytes = new byte[256];
      int count = input.read(bytes);
      return new String(bytes, 0, Math.max(0, count), StandardCharsets.UTF_8).trim();
    }
  }

  private void updateHome(String state) {
    String nextState = state == null ? "未启动" : state;
    boolean stageChanged = !java.util.Objects.equals(homeSnapshotState, nextState);
    homeSnapshotState = nextState;
    if (!homeBuilt || homeEngineStatus == null) return;
    homeEngineStatus.setText(homeSnapshotState);
    homeStart.setEnabled(HomeStatePolicy.canStart(homeSnapshotState));
    homeStop.setEnabled(HomeStatePolicy.canStop(homeSnapshotState));
    homeRetry.setEnabled(HomeStatePolicy.canRetry(homeSnapshotState));
    if (homeRuntimeStatus != null) {
      if (stageChanged) refreshRuntimeStatus();
      homeRuntimeStatus.setText(runtimeStatus());
    }
  }

  private void showDiagnostics() {
    String service = bound && binder != null ? binder.snapshot().state : "服务绑定中";
    new AlertDialog.Builder(this).setTitle("只读诊断")
        .setMessage("引擎：" + service + "\n运行时：" + runtimeStatus()
            + "\n地址策略：仅允许 127.0.0.1:3080\n凭证：不读取、不展示\n权限：不新增、不提升")
        .setPositiveButton("关闭", null).show();
  }

  private void showMenu() {
    boolean enabled = getPreferences(0).getBoolean(PREF_MOBILE_LAYOUT, true);
    String layoutAction = enabled ? "关闭手机布局" : "启用手机布局";
    String[] actions = {"启动", "停止", "重试", "日志", "使用说明", layoutAction, "手机文件（导入 / 导出）", "连接远程主机"};
    new AlertDialog.Builder(this).setTitle("操作").setItems(actions, (dialog, which) -> {
      if (which == 0) start();
      else if (which == 1) stop();
      else if (which == 2) retry();
      else if (which == 3) showLog();
      else if (which == 4) showInstructions();
      else if (which == 5) toggleMobileLayout(!enabled);
      else if (which == 6) fileTransfers.showMenu();
      else openRemote();
    }).show();
  }

  private void openRemote() {
    if (android.os.Build.VERSION.SDK_INT < 28) {
      new AlertDialog.Builder(this).setTitle("远程模式需要 Android 9+")
          .setMessage("为隔离本机与远程 WebView 会话，远程模式使用独立进程及数据目录。Android 8 上本机模式和手机文件功能仍可用。")
          .setPositiveButton("知道了", null).show();
      return;
    }
    startActivity(new Intent(this, RemoteActivity.class));
  }
  private void retry() {
    connectionAttempt.invalidate();
    pageError = null;
    pendingUrl = null;
    loadedUrl = null;
    clearWeb();
    showIntroduction();
    if (bound && binder.snapshot().url != null) apply(binder.snapshot());
    else start();
  }

  private void showInstructions() {
    new AlertDialog.Builder(this).setTitle("使用说明")
        .setMessage("点击“启动本机 DSH”启动服务；服务连接后，页面会占满顶部栏以下的区域。\n\n菜单可停止、重试、查看脱敏日志，或切换手机布局。模型请求仍使用你在 DSH 中配置的 API；请勿导入重要数据。")
        .setPositiveButton("知道了", null).show();
  }

  private void showOfficialGuide(String title, String message) {
    new AlertDialog.Builder(this).setTitle(title).setMessage(message)
        .setNegativeButton("关闭", null)
        .setPositiveButton("启动本机引擎", (dialog, which) -> start()).show();
  }

  private void toggleMobileLayout(boolean enabled) {
    getPreferences(0).edit().putBoolean(PREF_MOBILE_LAYOUT, enabled).apply();
    if (web != null) MobileWebAdapter.setEnabled(web, enabled);
  }

  private void apply(DshService.Snapshot snapshot) {
    if (destroyed) return;
    boolean changed = !java.util.Objects.equals(desiredUrl, snapshot.url);
    desiredUrl = snapshot.url;
    updateHome(snapshot.state);
    if (changed) {
      connectionAttempt.invalidate();
      pageError = null;
    }
    if (desiredUrl == null) {
      pendingUrl = null;
      loadedUrl = null;
      clearWeb();
      showIntroduction();
      status.setText(snapshot.state);
      updateHome(snapshot.state);
      return;
    }
    if (!UrlPolicy.allowed("http://127.0.0.1:3080/", desiredUrl)) {
      status.setText("拒绝非本机服务地址");
      updateHome(snapshot.state);
      return;
    }
    if (pageError != null) {
      status.setText(pageError);
      updateHome(snapshot.state);
      return;
    }
    if (desiredUrl.equals(loadedUrl)) {
      status.setText("本机页面已加载 · 127.0.0.1:3080");
      updateHome(snapshot.state);
      return;
    }
    if (!desiredUrl.equals(pendingUrl)) checkAndLoad(desiredUrl);
  }

  private void start() {
    // On the welcome/error screen the primary action must also recover a running engine's UI.
    if (bound && binder != null && binder.snapshot().url != null) {
      retry();
      return;
    }
    if (!getPreferences(0).getBoolean("risk-accepted", false)) {
      new AlertDialog.Builder(this)
          .setTitle("首次使用：开发测试版")
          .setMessage("这会在手机内运行 Node 与 DSH。模型请求可能发送工作区内容到你配置的 API；插件与本机命令可访问本 App 的数据。无需 Root，但不代表插件隔离安全。当前版本未完成 ARM64 真机验收，请勿导入重要数据。")
          .setNegativeButton("取消", null)
          .setPositiveButton("理解并启动", (dialog, which) -> {
            getPreferences(0).edit().putBoolean("risk-accepted", true).apply();
            start();
          }).show();
      return;
    }
    startForegroundService(new Intent(this, DshService.class).setAction(DshService.START));
  }

  private void stop() {
    connectionAttempt.invalidate();
    pageError = null;
    desiredUrl = null;
    pendingUrl = null;
    loadedUrl = null;
    clearWeb();
    showIntroduction();
    startService(new Intent(this, DshService.class).setAction(DshService.STOP));
  }

  private void showLog() {
    new AlertDialog.Builder(this).setTitle("本机引擎日志（已脱敏）")
        .setMessage(bound ? binder.logs() : "正在连接本机服务…")
        .setPositiveButton("关闭", null).show();
  }

  private void checkAndLoad(String url) {
    pendingUrl = url;
    final long request = connectionAttempt.begin();
    status.setText("正在验证本机 Web 服务…");
    new Thread(() -> {
      String failure = "本机服务暂不可达，请从菜单重试或查看日志";
      for (int probe = 0; probe < ConnectionAttempt.MAX_PROBES; probe++) {
        if (!connectionAttempt.current(request)) return;
        HttpURLConnection connection = null;
        try {
          // Keep launch credentials out of readiness probes; WebView owns authentication.
          connection = (HttpURLConnection) new URL("http://127.0.0.1:3080/").openConnection();
          connection.setInstanceFollowRedirects(false);
          connection.setConnectTimeout(1500);
          connection.setReadTimeout(2000);
          int code = connection.getResponseCode();
          if (ConnectionAttempt.ready(code)) {
            runOnUiThread(() -> {
              if (!destroyed && connectionAttempt.current(request)
                  && url.equals(desiredUrl) && url.equals(pendingUrl)) load(url);
            });
            return;
          }
          failure = "本机服务返回 HTTP " + code + "，请从菜单重试或查看日志";
        } catch (Exception error) {
          failure = "本机服务暂不可达，请从菜单重试或查看日志";
        } finally {
          if (connection != null) connection.disconnect();
        }
        if (probe + 1 < ConnectionAttempt.MAX_PROBES) try { Thread.sleep(500); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
      }
      final String message = failure;
      runOnUiThread(() -> {
        if (!destroyed && connectionAttempt.current(request) && url.equals(desiredUrl)) {
          pageError = message;
          pendingUrl = null;
          status.setText(message);
        }
      });
    }, "dsh-local-health").start();
  }

  private void load(String url) {
    if (destroyed || !url.equals(desiredUrl)
        || !UrlPolicy.allowed("http://127.0.0.1:3080/", url)) return;
    clearWeb();
    loadedUrl = null;
    pendingUrl = url;
    pageError = null;
    content.removeAllViews();
    homeBuilt = false;
    web = new WebView(this);
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setAllowFileAccessFromFileURLs(false);
    settings.setAllowUniversalAccessFromFileURLs(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setUseWideViewPort(true);
    settings.setLoadWithOverviewMode(true);
    settings.setSupportZoom(true);
    settings.setBuiltInZoomControls(true);
    settings.setDisplayZoomControls(false);
    settings.setTextZoom(Math.round(getResources().getConfiguration().fontScale * 100));
    web.setWebViewClient(new WebViewClient() {
      private boolean failed;
      @Override public void onPageStarted(WebView view, String address, android.graphics.Bitmap icon) {
        if (view != web) return;
        if (!UrlPolicy.allowed("http://127.0.0.1:3080/", address)) {
          view.stopLoading();
          pageFailed(view, "已阻止非本机页面跳转");
          return;
        }
        failed = false;
        loadedUrl = null;
        pageError = null;
        status.setText("正在加载本机 DSH 页面…");
      }
      private void pageFailed(WebView view, String message) {
        if (view != web) return;
        failed = true;
        loadedUrl = null;
        pendingUrl = null;
        pageError = message;
        status.setText(message);
      }
      @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request.isForMainFrame()) pageFailed(view, "页面加载失败，请从菜单重试或查看日志");
      }
      @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
        if (request.isForMainFrame()) pageFailed(view, "页面返回 HTTP " + response.getStatusCode() + "，请从菜单重试");
      }
      @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        if (view == web) {
          pageFailed(view, "页面进程已退出，请从菜单重试（未保存草稿可能丢失）");
          clearWeb();
          showIntroduction();
        }
        return true;
      }
      @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri target = request.getUrl();
        if (UrlPolicy.allowed("http://127.0.0.1:3080/", target.toString())) return false;
        if (request.isForMainFrame() && request.hasGesture()
            && ("https".equals(target.getScheme()) || "http".equals(target.getScheme()))) {
          try { startActivity(new Intent(Intent.ACTION_VIEW, target)); }
          catch (android.content.ActivityNotFoundException ignored) { /* No external browser installed. */ }
        }
        return true;
      }
      @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        // Android's shared network config permits LAN HTTP for the separate remote process.
        // The local WebView must not inherit permission to send cleartext to other origins.
        String address = request.getUrl().toString();
        if ("http".equalsIgnoreCase(request.getUrl().getScheme())
            && !UrlPolicy.allowed("http://127.0.0.1:3080/", address)) {
          return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
              java.util.Collections.emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
        }
        return null;
      }
      @Override public void onPageFinished(WebView view, String finishedUrl) {
        super.onPageFinished(view, finishedUrl);
        if (view != web || failed || !UrlPolicy.allowed("http://127.0.0.1:3080/", finishedUrl)) return;
        loadedUrl = url;
        pendingUrl = null;
        pageError = null;
        status.setText("本机页面已加载 · 127.0.0.1:3080");
        boolean enabled = getPreferences(0).getBoolean(PREF_MOBILE_LAYOUT, true);
        MobileWebAdapter.install(view);
        MobileWebAdapter.setEnabled(view, enabled);
      }
    });
    content.addView(web, new LinearLayout.LayoutParams(-1, -1));
    web.loadUrl(url);
    status.setText("正在加载本机 DSH 页面…");
  }

  private void clearWeb() {
    if (web != null) {
      WebView old = web;
      web = null;
      content.removeView(old);
      old.stopLoading();
      old.destroy();
    }
  }

  @Override protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    fileTransfers.save(outState);
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (fileTransfers != null) fileTransfers.result(requestCode, resultCode, data);
  }

  @Override protected void onDestroy() {
    destroyed = true;
    if (fileTransfers != null) fileTransfers.destroy();
    connectionAttempt.invalidate();
    if (bound && binder != null) binder.removeListener(listener);
    if (bindingRequested) unbindService(connection);
    clearWeb();
    super.onDestroy();
  }
}
