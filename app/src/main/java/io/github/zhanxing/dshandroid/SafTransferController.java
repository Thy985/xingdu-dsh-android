package io.github.zhanxing.dshandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-initiated native file transfers only: no WebView bridge, no broad storage permission. */
final class SafTransferController {
  static final int IMPORT_FILES = 7101;
  static final int IMPORT_FOLDER = 7102;
  static final int EXPORT_FILE = 7103;
  private static final String STATE_EXPORT = "saf-export-path";
  private static final String STATE_PICKER = "saf-picker-request";
  private static final String STATE_SEQUENCE = "saf-picker-sequence";
  private static final int PICKER_GENERATIONS = 19000;
  private final Activity activity;
  private final ContentResolver resolver;
  private final Path files;
  private final android.content.SharedPreferences snapshotPrefs;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private volatile boolean destroyed;
  private boolean busy;
  private int pendingPicker;
  private int nextPicker;
  private String pendingExport;
  private AtomicBoolean cancellation;
  private volatile Thread transferThread;
  private AlertDialog progress;

  SafTransferController(Activity activity, Bundle saved) {
    this(activity, saved, activity.getApplicationContext().getSharedPreferences("saf-snapshot-records", Context.MODE_PRIVATE));
  }

  SafTransferController(Activity activity, Bundle saved, android.content.SharedPreferences preferences) {
    this.activity = activity;
    resolver = activity.getApplicationContext().getContentResolver();
    files = activity.getFilesDir().toPath();
    snapshotPrefs = preferences;
    if (saved != null) {
      pendingExport = saved.getString(STATE_EXPORT);
      pendingPicker = saved.getInt(STATE_PICKER, 0);
      nextPicker = saved.getInt(STATE_SEQUENCE, pendingPicker == 0 ? 0
          : (pendingPicker - IMPORT_FILES) / 3 + 1);
      if (pendingPicker != 0 && !pickerRequest(pendingPicker)) {
        pendingPicker = 0;
        pendingExport = null;
      }
    }
  }

  static Intent importFilesIntent() {
    return new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }

  static Intent importFolderIntent() {
    return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
  }

  static Intent exportIntent(String name) {
    return new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  }

  void save(Bundle out) {
    out.putString(STATE_EXPORT, pendingExport);
    out.putInt(STATE_PICKER, pendingPicker);
    out.putInt(STATE_SEQUENCE, nextPicker);
  }

  void showMenu() {
    if (busy) {
      message("文件传输", "已有文件传输进行中，请先完成或取消。", null);
      return;
    }
    if (pendingPicker != 0) {
      new AlertDialog.Builder(activity).setTitle("有未完成的系统文件选择")
          .setMessage("若系统选择器已关闭但没有返回结果，可清除本次等待再重新选择。清除后，旧选择结果不会触发传输。")
          .setNegativeButton("继续等待", null).setPositiveButton("清除等待", (d, w) -> {
            pendingPicker = 0;
            pendingExport = null;
            showMenu();
          }).show();
      return;
    }
    String[] items = {"导入手机文件（复制）", "导入手机文件夹（快照）", "已导入文件夹导出回原文件夹（新目录）",
        "导出默认工作区文件", "工作区位置与限制"};
    new AlertDialog.Builder(activity).setTitle("手机文件 · 无需 Root")
        .setItems(items, (dialog, which) -> {
          if (which == 0) launch(importFilesIntent(), IMPORT_FILES);
          else if (which == 1) launch(importFolderIntent(), IMPORT_FOLDER);
          else if (which == 2) browseSnapshots();
          else if (which == 3) browse(files.resolve("workspaces/default"));
          else showHelp();
        }).setNegativeButton("关闭", null).show();
  }

  private void showHelp() {
    message("默认工作区与手机文件", "默认工作区：\n" + files.resolve("workspaces/default")
        + "\n\n导入复制到其中新的 import-* 子目录，不覆盖现有文件、不改手机原件。文件夹也是一次性快照，不是实时挂载或同步。"
        + "\n\n原生导入不自动切换官方 DSH 当前工作区；若你已切换到其他目录，请在官方工作区选择器中打开上面的默认目录或导入完成显示的路径。"
        + "\n\n导出仅支持默认工作区内的单个普通文件；在系统选择器另存为。单文件最多 128 MiB，每批最多 256 MiB、1000 文件、2000 条目、16 层。"
        + "\n\n文件夹快照会保存原文件夹的持久读写授权；可在‘已导入文件夹导出回原文件夹’中导出到原授权目录下全新的 DSH-导出-快照-* 子目录，不覆盖或删除原件。授权被撤销或变为只读时会提示重新授权。"
        + "不支持其他 App 私有目录、系统受限目录、符号链接或 content:// 直接作为终端/Git 工作区。"
        + "\n\n模型请求可能将导入内容发往你配置的 API；请先用非敏感文件测试。", null);
  }

  private void launch(Intent intent, int request) {
    if (busy || pendingPicker != 0 || destroyed) return;
    // A fresh request code prevents an abandoned picker's late result from matching a new task.
    int generation = Math.floorMod(nextPicker++, PICKER_GENERATIONS);
    pendingPicker = IMPORT_FILES + generation * 3 + (request - IMPORT_FILES);
    try { activity.startActivityForResult(intent, pendingPicker); }
    catch (RuntimeException error) {
      pendingPicker = 0;
      pendingExport = null;
      message("无法打开系统文件选择器", "此设备未提供可用的文件选择器，或系统拒绝了请求。", null);
    }
  }

  boolean result(int request, int result, Intent data) {
    if (!pickerRequest(request)) return false;
    // Do not accept unexpected or duplicate callbacks, including a restored stale export.
    if (pendingPicker != request || destroyed) return true;
    pendingPicker = 0;
    String exportPath = pendingExport;
    pendingExport = null;
    if (result != Activity.RESULT_OK || data == null) return true;
    int kind = IMPORT_FILES + (request - IMPORT_FILES) % 3;
    if (kind == EXPORT_FILE) {
      Uri uri = data.getData();
      if (exportPath == null || !contentUri(uri)) {
        message("无法导出", "导出来源或目标授权已失效，请重新选择。", null);
        return true;
      }
      transfer("正在导出文件…", cancel -> exportFile(Paths.get(exportPath), uri, cancel));
    } else if (kind == IMPORT_FOLDER) {
      Uri uri = data.getData();
      if (!contentUri(uri) || !DocumentsContract.isTreeUri(uri)) {
        message("无法导入", "系统没有返回可读取的文件夹授权。", null);
        return true;
      }
      int grants = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      if ((grants & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
        message("无法导入", "系统没有返回可读取的文件夹授权。", null);
        return true;
      }
      // A read-only provider is still useful for a snapshot. Persist only the
      // permissions actually granted, and never release an older grant here.
      try {
        if ((grants & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
          resolver.takePersistableUriPermission(uri,
              Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } else {
          resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
      } catch (RuntimeException ignored) { /* Snapshot copying can still use the temporary grant. */ }
      final boolean replayable = hasPersistedReadWrite(uri);
      transfer("正在复制文件夹快照…", cancel -> {
        TransferResult imported = importFolder(uri, cancel);
        if (!replayable) return new TransferResult(imported.text
            + "\n\n当前授权为只读或无法持久保存；快照已导入，但不具备回原文件夹能力。", imported.path);
        try {
          if (!hasPersistedReadWrite(uri)) throw new IOException("授权已撤销");
          rememberSnapshot(imported.path, uri);
          return imported;
        } catch (Exception recordFailure) {
          return new TransferResult(imported.text
              + "\n\n快照已导入，但来源授权未能记录，无法回原文件夹；如需回写请重新导入并保留持久读写授权。", imported.path);
        }
      });
    } else {
      List<Uri> uris = new ArrayList<>();
      ClipData clip = data.getClipData();
      int count = clip == null ? (data.getData() == null ? 0 : 1) : clip.getItemCount();
      if (count == 0 || count > WorkspaceTransfers.MAX_FILES) {
        message("无法导入", "请选择 1–1000 个文件。", null);
        return true;
      }
      for (int i = 0; i < count; i++) {
        Uri uri = clip == null ? data.getData() : clip.getItemAt(i).getUri();
        if (!contentUri(uri)) {
          message("无法导入", "只接受系统文件提供器返回的 content:// 授权。", null);
          return true;
        }
        if (!uris.contains(uri)) uris.add(uri);
      }
      transfer("正在复制所选文件…", cancel -> importFiles(uris, cancel));
    }
    return true;
  }

  private static boolean pickerRequest(int request) {
    return request >= IMPORT_FILES && request < IMPORT_FILES + PICKER_GENERATIONS * 3;
  }

  static boolean contentUri(Uri uri) { return uri != null && "content".equals(uri.getScheme()); }

  private interface Operation { TransferResult run(WorkspaceTransfers.Cancellation cancel) throws Exception; }
  private static final class TransferResult {
    final String text;
    final String path;
    TransferResult(String text, String path) { this.text = text; this.path = path; }
  }

  private void transfer(String title, Operation operation) {
    if (destroyed || busy) return;
    busy = true;
    AtomicBoolean cancelled = new AtomicBoolean(false);
    cancellation = cancelled;
    progress = new AlertDialog.Builder(activity).setTitle(title)
        .setMessage("后台复制中。取消会请求中止；若已提交或提供器阻塞，可能仍完成或延迟响应。导出目标由系统选择器决定。")
        .setNegativeButton("取消", (d, w) -> cancel()).setCancelable(false).create();
    progress.show();
    submit(() -> {
      transferThread = Thread.currentThread();
      TransferResult outcome = null;
      String failure = null;
      try { outcome = operation.run(cancelled::get); }
      catch (Exception error) {
        failure = (cancelled.get() ? "文件传输已取消。" : "传输未完成：") + safeError(error);
      }
      finally { transferThread = null; }
      final TransferResult completed = outcome;
      final String error = failure;
      activity.runOnUiThread(() -> {
        busy = false;
        cancellation = null;
        if (progress != null) { progress.dismiss(); progress = null; }
        if (destroyed) return;
        if (error != null) message("文件传输未完成", error
            + "\n\n失败的导入不会发布到工作区。失败或取消的导出可能在目标位置留下不完整文件，请检查后删除。", null);
        else message("文件传输完成", (cancelled.get()
            ? "取消请求到达时传输已提交完成，以下结果已经保存。\n\n" : "")
            + completed.text, completed.path);
      });
    });
  }

  private void submit(Runnable work) {
    try { worker.submit(work); }
    catch (java.util.concurrent.RejectedExecutionException error) {
      busy = false;
      cancellation = null;
      if (progress != null) { progress.dismiss(); progress = null; }
      if (!destroyed) message("文件传输未开始", "页面已关闭，请重新打开后再试。", null);
    }
  }

  private static String safeError(Exception error) {
    if (error instanceof SecurityException) return "系统未授予读取/写入权限，请重新选择。";
    String text = error.getMessage();
    return text == null ? "文件提供器或本地存储不可用。" : text.substring(0, Math.min(300, text.length()));
  }

  private TransferResult importFiles(List<Uri> uris, WorkspaceTransfers.Cancellation cancel) throws Exception {
    try (WorkspaceTransfers.Batch batch = new WorkspaceTransfers.Batch(files)) {
      for (Uri uri : uris) {
        WorkspaceTransfers.check(cancel);
        String name = displayName(uri);
        Path target = batch.destination(batch.staging, name, false, 1);
        try (InputStream input = input(uri)) { batch.write(target, input, cancel); }
      }
      Path destination = batch.publish(cancel);
      return imported(batch, destination);
    }
  }

  private TransferResult importFolder(Uri tree, WorkspaceTransfers.Cancellation cancel) throws Exception {
    try (WorkspaceTransfers.Batch batch = new WorkspaceTransfers.Batch(files)) {
      String id = DocumentsContract.getTreeDocumentId(tree);
      Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
      if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(resolver.getType(document))) {
        throw new IOException("所选授权不是可读取的目录");
      }
      Path folder = batch.destination(batch.staging, displayName(document), true, 1);
      Set<String> visited = new HashSet<>();
      visited.add(id);
      copyChildren(tree, id, folder, batch, visited, 2, cancel);
      Path destination = batch.publish(cancel);
      return imported(batch, destination);
    }
  }

  private void copyChildren(Uri tree, String parentId, Path parent, WorkspaceTransfers.Batch batch,
      Set<String> visited, int depth, WorkspaceTransfers.Cancellation cancel) throws Exception {
    WorkspaceTransfers.check(cancel);
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
    String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS};
    try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
      if (cursor == null) throw new IOException("无法列出所选文件夹");
      while (cursor.moveToNext()) {
        WorkspaceTransfers.check(cancel);
        String id = cursor.getString(0);
        String name = cursor.getString(1);
        String mime = cursor.getString(2);
        int flags = cursor.getInt(3);
        if (id == null || !visited.add(id)) throw new IOException("文件提供器返回了重复或循环的目录条目");
        if (mime == null || mime.isEmpty()) throw new IOException("文件提供器未返回有效的文档类型");
        if ((flags & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0) {
          throw new IOException("暂不支持虚拟文档，请先在原应用导出为普通文件");
        }
        boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
        Path destination = batch.destination(parent, name, directory, depth);
        if (directory) copyChildren(tree, id, destination, batch, visited, depth + 1, cancel);
        else {
          Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
          try (InputStream input = input(document)) { batch.write(destination, input, cancel); }
        }
      }
    }
  }

  private TransferResult imported(WorkspaceTransfers.Batch batch, Path destination) {
    return new TransferResult("已复制 " + batch.budget.files + " 个文件（" + batch.budget.bytes
        + " 字节）到默认工作区：\n" + destination
        + "\n\n手机原件未改动。文件夹是快照，不自动同步，也不会自动切换官方 DSH 当前工作区。"
        + "\n如官方文件列表未更新，请重新打开默认工作区；也可复制此路径，在官方工作区选择器打开。", destination.toString());
  }

  private void rememberSnapshot(String path, Uri tree) throws IOException {
    Path root = WorkspaceTransfers.workspace(files);
    Path candidate = Paths.get(path);
    if (!isImportChild(root, candidate) || !contentTree(tree) || !hasPersistedReadWrite(tree))
      throw new IOException("拒绝记录无效或非持久读写快照映射");
    String value = android.util.Base64.encodeToString(candidate.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)
        + ":" + android.util.Base64.encodeToString(tree.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    Set<String> records = new HashSet<>(snapshotPrefs.getStringSet("records", new HashSet<>()));
    records.add(value);
    snapshotPrefs.edit().putStringSet("records", records).apply();
  }

  private boolean isImportChild(Path root, Path candidate) {
    try {
      Path normalized = candidate.toAbsolutePath().normalize();
      if (!normalized.getParent().equals(root.toAbsolutePath().normalize())) return false;
      String name = normalized.getFileName().toString();
      return name.matches("import-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
          && Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS);
    } catch (RuntimeException e) { return false; }
  }

  private boolean contentTree(Uri tree) {
    try {
      return contentUri(tree) && DocumentsContract.isTreeUri(tree)
          && DocumentsContract.Document.MIME_TYPE_DIR.equals(resolver.getType(
              DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))));
    } catch (RuntimeException e) { return false; }
  }

  private List<String[]> snapshots() {
    List<String[]> result = new ArrayList<>();
    for (String value : snapshotPrefs.getStringSet("records", new HashSet<>())) {
      try {
        String[] parts = value.split(":", 2);
        if (parts.length != 2) continue;
        String path = new String(android.util.Base64.decode(parts[0], android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
        String uriText = new String(android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
        Uri uri = Uri.parse(uriText);
        Path root = WorkspaceTransfers.workspace(files);
        if (isImportChild(root, Paths.get(path)) && contentTree(uri) && hasPersistedReadWrite(uri))
          result.add(new String[]{path, uriText, uriText});
      } catch (Exception ignored) { }
    }
    return result;
  }

  private void browseSnapshots() {
    if (destroyed || busy || pendingPicker != 0) return;
    busy = true;
    submit(() -> {
      List<String[]> found = null; String failure = null;
      try { found = snapshots(); } catch (Exception e) { failure = safeError(e); }
      final List<String[]> records = found; final String error = failure;
      activity.runOnUiThread(() -> {
        busy = false;
        if (destroyed) return;
        if (error != null) { message("无法读取快照列表", error, null); return; }
        if (records == null || records.isEmpty()) {
          message("没有可回写的文件夹快照", "请先导入一个具备持久读写授权的文件夹快照。只读提供器仍可导入，但不会记录回原文件夹映射。", null); return;
        }
        String[] labels = new String[records.size()];
        for (int i = 0; i < records.size(); i++) labels[i] = Paths.get(records.get(i)[0]).getFileName() + "  ← 原目录：" + records.get(i)[2];
        new AlertDialog.Builder(activity).setTitle("选择要回原文件夹的快照")
            .setItems(labels, (d, which) -> confirmSnapshotExport(records.get(which)))
            .setNegativeButton("取消", null).show();
      });
    });
  }

  private void confirmSnapshotExport(String[] record) {
    new AlertDialog.Builder(activity).setTitle("导出回原文件夹？")
        .setMessage("快照：\n" + record[0] + "\n原目录：" + record[2] + "\n\n将在原授权目录中创建全新的唯一子目录（xingdu-export-*）。不会覆盖、删除或合并原文件。")
        .setNegativeButton("取消", null).setPositiveButton("开始导出", (d, w) -> {
          transfer("正在导出回原文件夹…", cancel -> exportSnapshot(Paths.get(record[0]), Uri.parse(record[1]), cancel));
        }).show();
  }

  private TransferResult exportSnapshot(Path snapshot, Uri tree, WorkspaceTransfers.Cancellation cancel) throws Exception {
    Path root = WorkspaceTransfers.workspace(files);
    snapshot = WorkspaceTransfers.confined(root, snapshot);
    WorkspaceTransfers.requireDirectory(snapshot);
    if (!isImportChild(root, snapshot) || !contentTree(tree) || !hasPersistedReadWrite(tree)
        || !hasSnapshotRecord(snapshot, tree))
      throw new SecurityException("快照映射无效、原文件夹授权已撤销或为只读");
    SnapshotExportPlan plan = SnapshotExportPlan.plan(root, snapshot, cancel);
    WorkspaceTransfers.check(cancel);
    Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    Uri destination = DocumentsContract.createDocument(resolver, parent,
        DocumentsContract.Document.MIME_TYPE_DIR, "xingdu-export-" + UUID.randomUUID());
    if (destination == null) throw new IOException("原文件夹不支持创建新目录");
    try { copySnapshotPlan(plan, destination, cancel); }
    catch (Exception error) { throw new IOException("导出部分失败；目标新目录 URI：" + destination + "；原件未修改，请检查并删除目标中的不完整新目录", error); }
    return new TransferResult("已导出到原授权目录下的新目录（未覆盖或删除原件）：\n" + destination
        + "\n\n如导出中途取消或失败，请检查并删除目标中的不完整目录。", null);
  }

  private void copySnapshotPlan(SnapshotExportPlan plan, Uri target,
      WorkspaceTransfers.Cancellation cancel) throws Exception {
    java.util.Map<String, Uri> directories = new java.util.HashMap<>();
    directories.put("", target);
    WorkspaceTransfers.Budget budget = new WorkspaceTransfers.Budget();
    for (SnapshotExportPlan.Entry entry : plan.entries()) {
      WorkspaceTransfers.check(cancel);
      Path source = WorkspaceTransfers.confined(plan.snapshot(), entry.path());
      String relative = entry.relativePath();
      int slash = relative.lastIndexOf('/');
      String parentName = slash < 0 ? "" : relative.substring(0, slash);
      String name = slash < 0 ? relative : relative.substring(slash + 1);
      Uri parent = directories.get(parentName);
      if (parent == null) throw new IOException("导出计划的父目录不可用");
      budget.entry(relative.split("/").length);
      if (entry.isDirectory()) {
        WorkspaceTransfers.requireDirectory(source);
        WorkspaceTransfers.check(cancel);
        Uri directory = DocumentsContract.createDocument(resolver, parent,
            DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (directory == null) throw new IOException("无法创建目标目录");
        directories.put(relative, directory);
      } else {
        // Reopen NOFOLLOW after preflight; changed/oversized/special sources still fail closed.
        try (InputStream input = WorkspaceTransfers.openExport(plan.snapshot(), source)) {
          WorkspaceTransfers.check(cancel);
          Uri file = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name);
          if (file == null) throw new IOException("无法创建目标文件");
          try (OutputStream output = resolver.openOutputStream(file, "wt")) {
            if (output == null) throw new IOException("目标文件不可写");
            WorkspaceTransfers.copy(input, output, budget, cancel);
            output.flush();
          }
        }
      }
    }
    WorkspaceTransfers.check(cancel);
  }
  private boolean hasPersistedReadWrite(Uri tree) {
    for (android.content.UriPermission permission : resolver.getPersistedUriPermissions()) {
      if (tree.equals(permission.getUri()) && permission.isReadPermission() && permission.isWritePermission()) return true;
    }
    return false;
  }

  private boolean hasSnapshotRecord(Path snapshot, Uri tree) {
    try {
      String path = snapshot.toAbsolutePath().normalize().toString();
      for (String value : snapshotPrefs.getStringSet("records", new HashSet<>())) {
        String[] parts = value.split(":", 2);
        if (parts.length != 2) continue;
        String savedPath = new String(android.util.Base64.decode(parts[0], android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
        String savedTree = new String(android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8);
        if (path.equals(Paths.get(savedPath).toAbsolutePath().normalize().toString()) && tree.toString().equals(savedTree)) return true;
      }
    } catch (RuntimeException ignored) { }
    return false;
  }

  private TransferResult exportFile(Path source, Uri destination, WorkspaceTransfers.Cancellation cancel)
      throws Exception {
    Path root = WorkspaceTransfers.workspace(files);
    try (InputStream input = WorkspaceTransfers.openExport(root, source)) {
      WorkspaceTransfers.check(cancel);
      try (OutputStream output = resolver.openOutputStream(destination, "wt")) {
        if (output == null) throw new IOException("系统没有提供可写入的目标");
        long count = WorkspaceTransfers.copy(input, output, new WorkspaceTransfers.Budget(), cancel);
        output.flush();
        return new TransferResult("已另存为 " + source.getFileName() + "（" + count
            + " 字节）。\n\n默认工作区原文件未改动。保存位置以系统文件选择器中你的选择为准。", null);
      }
    }
  }

  private InputStream input(Uri uri) throws IOException {
    InputStream input = resolver.openInputStream(uri);
    if (input == null) throw new IOException("系统没有提供可读取的文件内容");
    return input;
  }

  private String displayName(Uri uri) throws IOException {
    try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cursor == null || !cursor.moveToFirst()) throw new IOException("无法读取文件名");
      return WorkspaceTransfers.safeName(cursor.getString(0));
    }
  }

  private void browse(Path directory) {
    if (destroyed || busy || pendingPicker != 0) return;
    busy = true;
    submit(() -> {
      List<Path> entries = null;
      Path root = null;
      String error = null;
      try {
        root = WorkspaceTransfers.workspace(files);
        entries = WorkspaceTransfers.list(root, directory);
      } catch (Exception e) { error = safeError(e); }
      final List<Path> paths = entries;
      final Path workspace = root;
      final String failure = error;
      activity.runOnUiThread(() -> {
        busy = false;
        if (destroyed) return;
        if (failure != null) { message("无法浏览默认工作区", failure, null); return; }
        boolean nested = !workspace.equals(directory);
        List<String> labels = new ArrayList<>();
        if (nested) labels.add("↑ 上一级");
        for (Path path : paths) labels.add((Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "[目录] " : "[文件] ") + path.getFileName());
        new AlertDialog.Builder(activity).setTitle("导出：默认工作区/" + workspace.relativize(directory))
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
              if (nested && which == 0) { browse(directory.getParent()); return; }
              Path selected = paths.get(which - (nested ? 1 : 0));
              if (Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) browse(selected);
              else confirmExport(selected);
            }).setMessage(labels.isEmpty() ? "此目录没有可导出的普通文件。" : null)
            .setNegativeButton("取消", null).show();
      });
    });
  }

  private void confirmExport(Path selected) {
    new AlertDialog.Builder(activity).setTitle("导出此文件？")
        .setMessage("来源：\n" + selected + "\n\n下一步由系统选择另存为位置，不自动回写导入来源。目标重名及覆盖提示由系统文件提供器处理，请核对后确认。")
        .setNegativeButton("取消", null).setPositiveButton("选择保存位置", (dialog, which) -> {
          pendingExport = selected.toString();
          launch(exportIntent(selected.getFileName().toString()), EXPORT_FILE);
        }).show();
  }

  private void message(String title, String text, String copyPath) {
    if (destroyed || activity.isFinishing()) return;
    TextView body = new TextView(activity);
    int padding = (int) (20 * activity.getResources().getDisplayMetrics().density);
    body.setPadding(padding, padding / 2, padding, padding / 2);
    body.setText(text);
    body.setTextIsSelectable(true);
    android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
    scroll.addView(body);
    AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(title).setView(scroll)
        .setPositiveButton("知道了", null);
    if (copyPath != null) builder.setNeutralButton("复制工作区路径", (d, w) ->
        ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText("DSH 导入目录", copyPath)));
    builder.show();
  }

  private void cancel() {
    if (cancellation != null) cancellation.set(true);
    // Do not cancel a queued Future: its body must run to settle busy state and cleanup.
    Thread running = transferThread;
    if (running != null) running.interrupt();
  }

  void destroy() {
    destroyed = true;
    cancel();
    if (progress != null) { progress.dismiss(); progress = null; }
    worker.shutdownNow();
  }
}
