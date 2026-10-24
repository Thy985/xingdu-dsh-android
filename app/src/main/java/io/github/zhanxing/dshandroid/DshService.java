package io.github.zhanxing.dshandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns one local DSH process. Credentials and launch URLs never leave this process via IPC. */
public final class DshService extends Service {
  public static final String START = "start";
  public static final String STOP = "stop";
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final LocalBinder binder = new LocalBinder();
  private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
  private final StringBuilder logs = new StringBuilder();
  private volatile Process process;
  private volatile long processGeneration;
  private volatile long generation;
  private volatile boolean starting;
  private volatile boolean stopRequested;
  private volatile String launchUrl;
  private volatile EngineState state = EngineState.NOT_STARTED;
  private volatile String stateDetail;
  private FileChannel lockChannel;
  private FileLock instanceLock;
  private int latestStartId;

  public interface Listener { void onSnapshot(Snapshot snapshot); }

  public static final class Snapshot {
    public final EngineState state;
    public final String detail;
    public final String url;
    public final String logs;
    Snapshot(EngineState state, String detail, String url, String logs) {
      this.state = state;
      this.detail = detail;
      this.url = url;
      this.logs = logs;
    }
  }

  public final class LocalBinder extends Binder {
    public Snapshot snapshot() { return DshService.this.snapshot(); }
    public String logs() { return snapshot().logs; }
    public void addListener(Listener listener) {
      listeners.add(listener);
      listener.onSnapshot(snapshot());
    }
    public void removeListener(Listener listener) { listeners.remove(listener); }
  }

  @Override public void onCreate() {
    super.onCreate();
    getSystemService(NotificationManager.class).createNotificationChannel(
        new NotificationChannel("dsh", getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW));
  }

  @Override public synchronized int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) return START_NOT_STICKY;
    latestStartId = startId;
    if (STOP.equals(intent.getAction())) {
      stopRequested = true;
      generation++;
      launchUrl = null;
      setState(EngineState.STOPPING, null);
      publish();
      final long stopGeneration = generation;
      worker.execute(() -> stopInternal(stopGeneration, false));
    } else if (START.equals(intent.getAction())) {
      // Always meet the foreground-service deadline, including duplicate start intents.
      foreground();
      Process current = process;
      if (!ServiceLifecycle.shouldStart(stopRequested, starting, current != null && current.isAlive())) {
        publish();
        return START_NOT_STICKY;
      }
      stopRequested = false;
      starting = true;
      launchUrl = null;
      long ownedGeneration = ++generation;
      setState(EngineState.INSTALLING, null);
      publish();
      worker.execute(() -> startInternal(ownedGeneration));
    }
    return START_NOT_STICKY;
  }

  private void foreground() {
    Intent open = new Intent(this, MainActivity.class);
    PendingIntent content = PendingIntent.getActivity(this, 0, open,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop = PendingIntent.getService(this, 1,
        new Intent(this, DshService.class).setAction(STOP),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    startForeground(42, new Notification.Builder(this, "dsh")
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setContentIntent(content)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setOngoing(true)
        .addAction(new Notification.Action.Builder(null, getString(R.string.notification_stop_engine), stop).build())
        .build());
  }

  private boolean cancelled(long ownedGeneration) {
    return ServiceLifecycle.cancelled(ownedGeneration, generation, stopRequested,
        Thread.currentThread().isInterrupted());
  }

  private void startInternal(long ownedGeneration) {
    try {
      Path runtime = RuntimeInstaller.install(this, () -> cancelled(ownedGeneration));
      if (cancelled(ownedGeneration)) return;
      Path mobilePatch = null;
      try { mobilePatch = MobileShellInstaller.install(this); }
      catch (Exception ignored) { appendLog("手机导航扩展安装失败，已回退官方界面。"); }
      Path home = new File(getFilesDir(), "home").toPath();
      Path workspace = new File(getFilesDir(), "workspaces/default").toPath();
      Files.createDirectories(home);
      Files.createDirectories(workspace);
      closeLock();
      lockChannel = FileChannel.open(home.resolve(".android-engine.lock"),
          StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      instanceLock = lockChannel.tryLock();
      if (instanceLock == null) throw new IOException("已有本机引擎实例运行");
      List<String> command = new ArrayList<>(Arrays.asList(
          runtime.resolve("bin/node").toString(), "--expose-internals",
          runtime.resolve("lib/node_modules/@deepseek-ai/dsh/lib/bin.js").toString(), "web"));
      if (mobilePatch != null) command.addAll(Arrays.asList("--patch", mobilePatch.toString()));
      command.addAll(Arrays.asList("--no-open", "--host", "127.0.0.1", "--port", "3080"));
      ProcessBuilder builder = new ProcessBuilder(command)
          .directory(workspace.toFile()).redirectErrorStream(true);
      Map<String, String> env = builder.environment();
      env.put("HOME", home.toString());
      env.put("DSH_HOME", home.resolve(".dsh").toString());
      env.put("PREFIX", runtime.toString());
      env.put("LD_LIBRARY_PATH", runtime.resolve("lib").toString());
      env.put("PATH", runtime.resolve("bin") + ":/system/bin");
      env.put("TMPDIR", getCacheDir().toString());
      env.put("SSL_CERT_FILE", runtime.resolve("etc/tls/cert.pem").toString());
      env.put("NODE_EXTRA_CA_CERTS", runtime.resolve("etc/tls/cert.pem").toString());
      env.put("BASH", runtime.resolve("bin/bash").toString());
      env.put("SHELL", runtime.resolve("bin/bash").toString());
      env.put("TERM", "xterm-256color");
      // No permission override: official workspace-write/approval defaults remain in force.
      if (cancelled(ownedGeneration)) return;
      Process started = builder.start();
      boolean rejected;
      synchronized (this) {
        rejected = cancelled(ownedGeneration);
        if (!rejected) {
          process = started;
          processGeneration = ownedGeneration;
          setState(EngineState.WAITING_WEB, null);
          publish();
        }
      }
      if (rejected) { terminate(started); return; }
      new Thread(() -> readOutput(started, ownedGeneration), "dsh-output").start();
    } catch (Exception error) {
      synchronized (this) {
        if (!cancelled(ownedGeneration)) {
          setState(EngineState.FAILED, LogParser.redact(String.valueOf(error.getMessage())));
          closeLock();
          stopForeground(true);
          stopSelfResult(latestStartId);
          publish();
        }
      }
    } finally {
      synchronized (this) {
        if (ownedGeneration == generation) starting = false;
        if (cancelled(ownedGeneration) && process == null) closeLock();
      }
    }
  }

  private void readOutput(Process owned, long ownedGeneration) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(owned.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        synchronized (this) {
          if (generation != ownedGeneration || process != owned) continue;
          String candidate = LogParser.findLaunchUrl(line);
          if (candidate != null && UrlPolicy.allowed("http://127.0.0.1:3080/", candidate)) {
            launchUrl = candidate;
            setState(EngineState.URL_READY, null);
          }
          appendLog(line);
          publish();
        }
      }
    } catch (IOException error) {
      // Closing the output stream during a requested stop is expected.
      if (!stopRequested) appendLog("读取引擎日志失败：" + error.getMessage());
    }
    try {
      int code = owned.waitFor();
      worker.execute(() -> {
        synchronized (this) {
          if (generation == ownedGeneration && process == owned) {
            process = null;
            launchUrl = null;
            setState(EngineState.EXITED, String.valueOf(code));
            closeLock();
            stopForeground(true);
            stopSelfResult(latestStartId);
            publish();
          }
        }
      });
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    } catch (java.util.concurrent.RejectedExecutionException ignored) {
      // Service destruction has already taken ownership of process cleanup.
    }
  }

  private void stopInternal(long requestedGeneration, boolean destroying) {
    Process owned;
    synchronized (this) {
      // A STOP still owns the older process when a newer START is queued behind it.
      owned = process;
      if (owned != null && !ServiceLifecycle.stopOwnsProcess(requestedGeneration, generation,
          processGeneration, destroying)) return;
    }
    if (owned != null) terminate(owned);
    synchronized (this) {
      if (process == owned) process = null;
      processGeneration = 0;
      closeLock();
      // Never replace the status/foreground notification of a newer queued start.
      if (!destroying && requestedGeneration != generation) return;
      launchUrl = null;
      starting = false;
      setState(EngineState.STOPPED, null);
      appendLog("Node 主进程已停止；当前开发版不保证回收自行脱离的插件子进程。");
      stopForeground(true);
      if (!destroying) stopSelfResult(latestStartId);
      publish();
    }
  }

  private static void terminate(Process owned) {
    owned.destroy();
    try {
      if (!owned.waitFor(1500, TimeUnit.MILLISECONDS)) {
        owned.destroyForcibly();
        owned.waitFor(1500, TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException error) {
      owned.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }

  private synchronized void closeLock() {
    try { if (instanceLock != null) instanceLock.release(); }
    catch (IOException ignored) { /* Closing the channel also releases its lock. */ }
    try { if (lockChannel != null) lockChannel.close(); }
    catch (IOException ignored) { /* The process is already stopped. */ }
    instanceLock = null;
    lockChannel = null;
  }

  private void appendLog(String line) {
    synchronized (logs) {
      logs.append(LogParser.redact(line)).append('\n');
      if (logs.length() > 16000) logs.delete(0, logs.length() - 12000);
    }
  }

  private void setState(EngineState next, String detail) {
    state = next;
    stateDetail = detail;
  }

  private Snapshot snapshot() {
    synchronized (logs) { return new Snapshot(state, stateDetail, launchUrl, logs.toString()); }
  }

  private void publish() {
    Snapshot current = snapshot();
    for (Listener listener : listeners) listener.onSnapshot(current);
  }

  @Override public IBinder onBind(Intent intent) { return binder; }

  @Override public synchronized void onDestroy() {
    stopRequested = true;
    generation++;
    worker.execute(() -> stopInternal(generation, true));
    worker.shutdown();
    listeners.clear();
    super.onDestroy();
  }
}
