package io.github.zhanxing.dshandroid;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * SAF transfer coverage using an instrumentation-only ContentProvider fixture.
 * This is not an end-to-end system picker/UI test: private synchronous transfer methods are invoked.
 */
@RunWith(AndroidJUnit4.class)
public final class SafTransferTest {
  private ActivityScenario<MainActivity> scenario;
  private SafTransferController controller;
  private Path files;

  @Before public void setUp() throws Exception {
    scenario = ActivityScenario.launch(MainActivity.class);
    files = Files.createTempDirectory(app().getCacheDir().toPath(), "saf-test-");
    scenario.onActivity(a -> {
      try {
        controller = new SafTransferController(a, null);
        Field field = SafTransferController.class.getDeclaredField("files");
        field.setAccessible(true);
        field.set(controller, files);
      } catch (Exception e) { throw new AssertionError(e); }
    });
  }

  @After public void tearDown() throws Exception {
    if (controller != null) controller.destroy();
    if (scenario != null) scenario.close();
    if (files != null) delete(files);
  }

  @Test public void intentsDeclareNativePickerActionsCategoriesAndMinimalFlags() {
    Intent filesIntent = SafTransferController.importFilesIntent();
    assertEquals(Intent.ACTION_OPEN_DOCUMENT, filesIntent.getAction());
    assertTrue(filesIntent.hasCategory(Intent.CATEGORY_OPENABLE));
    assertTrue(filesIntent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
    assertTrue((filesIntent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    Intent folderIntent = SafTransferController.importFolderIntent();
    assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, folderIntent.getAction());
    assertTrue((folderIntent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    assertTrue((folderIntent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0);
    assertTrue((folderIntent.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0);
    Intent export = SafTransferController.exportIntent("x.txt");
    assertEquals(Intent.ACTION_CREATE_DOCUMENT, export.getAction());
    assertEquals("x.txt", export.getStringExtra(Intent.EXTRA_TITLE));
    assertTrue((export.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0);
    assertTrue(SafTransferController.contentUri(SafFixtureProvider.FILE));
    assertFalse(SafTransferController.contentUri(Uri.parse("file:///tmp/x")));
    assertFalse(SafTransferController.contentUri(Uri.parse("http://example/x")));
  }

  @Test public void providerRootMimeAndProjectionAreDocumentTreeCompatible() throws Exception {
    android.content.ContentResolver r = app().getContentResolver();
    assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, r.getType(SafFixtureProvider.ROOT_DOCUMENT));
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(SafFixtureProvider.TREE, "root");
    try (android.database.Cursor c = r.query(children,
        new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
      assertNotNull(c);
      assertEquals(2, c.getColumnCount());
      assertEquals(DocumentsContract.Document.COLUMN_DOCUMENT_ID, c.getColumnName(0));
      assertTrue(c.moveToFirst());
      assertEquals("file", c.getString(0));
    }
  }

  @Test public void pendingPickerAndExportPathRoundTripAndCancel() throws Exception {
    Field picker = field("pendingPicker");
    Field export = field("pendingExport");
    picker.setInt(controller, SafTransferController.EXPORT_FILE);
    export.set(controller, files.resolve("workspaces/default/x.txt").toString());
    Bundle saved = new Bundle();
    controller.save(saved);
    SafTransferController restored = new SafTransferController(activity(), saved);
    try {
      assertEquals(SafTransferController.EXPORT_FILE, picker.getInt(restored));
      assertEquals(files.resolve("workspaces/default/x.txt").toString(), export.get(restored));
      assertTrue(restored.result(SafTransferController.EXPORT_FILE, Activity.RESULT_CANCELED, null));
      assertEquals(0, picker.getInt(restored));
      assertEquals(null, export.get(restored));
    } finally { restored.destroy(); }
  }

  @Test public void abandonedPickerResultCannotMatchNewRequestGeneration() throws Exception {
    Field picker = field("pendingPicker");
    int newer = SafTransferController.IMPORT_FILES + 3;
    picker.setInt(controller, newer);
    assertTrue(controller.result(SafTransferController.IMPORT_FILES, Activity.RESULT_OK,
        new Intent().setData(SafFixtureProvider.FILE)));
    assertEquals(newer, picker.getInt(controller));
    assertNoPublishedImport();
    assertTrue(controller.result(newer, Activity.RESULT_CANCELED, null));
    assertEquals(0, picker.getInt(controller));
  }

  @Test public void importFilesCopiesExactBytesIntoPublishedSnapshot() throws Exception {
    Object result = invoke("importFiles", Arrays.asList(SafFixtureProvider.FILE), cancel());
    Path published = resultPath(result);
    assertTrue(Files.isDirectory(published));
    assertArrayEquals("fixture-bytes".getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(published.resolve("hello.txt")));
  }

  @Test public void importFolderCopiesBytesAndHierarchyIntoPublishedSnapshot() throws Exception {
    Object result = invoke("importFolder", SafFixtureProvider.TREE, cancel());
    Path published = resultPath(result);
    assertArrayEquals("fixture-bytes".getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(published.resolve("fixture-root/hello.txt")));
    assertArrayEquals("nested-bytes".getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(published.resolve("fixture-root/folder/nested.txt")));
  }

  @Test public void exportWritesExactBytesThroughContentResolver() throws Exception {
    Path source = files.resolve("workspaces/default/export.txt");
    Files.createDirectories(source.getParent());
    Files.write(source, "export-bytes".getBytes(StandardCharsets.UTF_8));
    invoke("exportFile", source, Uri.parse("content://" + SafFixtureProvider.AUTHORITY + "/document/out"), cancel());
    try (InputStream in = app().getContentResolver().openInputStream(
        Uri.parse("content://" + SafFixtureProvider.AUTHORITY + "/document/out"))) {
      assertArrayEquals("export-bytes".getBytes(StandardCharsets.UTF_8), readAll(in));
    }
  }

  @Test public void unsafeNameAndCycleFailWithoutPublishingSnapshot() throws Exception {
    expectFailure("importFiles", Arrays.asList(Uri.parse("content://" + SafFixtureProvider.AUTHORITY + "/document/bad")), cancel());
    assertNoPublishedImport();
    expectFailure("importFolder", Uri.parse("content://" + SafFixtureProvider.AUTHORITY + "/tree/loop"), cancel());
    assertNoPublishedImport();
  }

  @Test public void snapshotRecordRejectsForgedMappingWithoutPersistedGrant() throws Exception {
    Path folder = files.resolve("workspaces/default/import-folder");
    Files.createDirectories(folder);
    Files.write(folder.resolve("one.txt"), "one".getBytes(StandardCharsets.UTF_8));
    Method remember = SafTransferController.class.getDeclaredMethod("rememberSnapshot", String.class, Uri.class);
    remember.setAccessible(true);
    try {
      remember.invoke(controller, folder.toString(), SafFixtureProvider.TREE);
      fail("forged mapping must be rejected without a persisted read/write grant");
    } catch (InvocationTargetException expected) {
      assertTrue(expected.getCause() instanceof java.io.IOException);
    }
  }

  private WorkspaceTransfers.Cancellation cancel() { return () -> false; }
  private android.content.Context app() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
  private Activity activity() { final Activity[] out = new Activity[1]; scenario.onActivity(a -> out[0] = a); return out[0]; }

  @Test public void snapshotExportFailsBeforeProviderWritesWithoutPersistedGrant() throws Exception {
    Path folder = files.resolve("workspaces/default/import-123e4567-e89b-12d3-a456-426614174000");
    Files.createDirectories(folder);
    Files.write(folder.resolve("sample.txt"), "local".getBytes(StandardCharsets.UTF_8));
    try {
      invoke("exportSnapshot", folder, SafFixtureProvider.TREE, cancel());
      fail("no persisted read/write grant must prevent all write-back");
    } catch (SecurityException expected) { }
    try (InputStream input = app().getContentResolver().openInputStream(SafFixtureProvider.FILE)) {
      assertArrayEquals("fixture-bytes".getBytes(StandardCharsets.UTF_8), readAll(input));
    }
  }

  private Object invoke(String name, Object... args) throws Exception {
    Method method;
    if ("importFiles".equals(name)) method = SafTransferController.class.getDeclaredMethod(name, List.class, WorkspaceTransfers.Cancellation.class);
    else if ("importFolder".equals(name)) method = SafTransferController.class.getDeclaredMethod(name, Uri.class, WorkspaceTransfers.Cancellation.class);
    else method = SafTransferController.class.getDeclaredMethod(name, Path.class, Uri.class, WorkspaceTransfers.Cancellation.class);
    method.setAccessible(true);
    try { return method.invoke(controller, args); }
    catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) throw (Exception) cause;
      throw e;
    }
  }

  private void expectFailure(String name, Object... args) throws Exception {
    try { invoke(name, args); fail("expected transfer failure"); } catch (java.io.IOException expected) { }
  }

  private Path resultPath(Object result) throws Exception {
    Field path = result.getClass().getDeclaredField("path");
    path.setAccessible(true);
    return java.nio.file.Paths.get((String) path.get(result));
  }

  private Field field(String name) throws Exception {
    Field f = SafTransferController.class.getDeclaredField(name); f.setAccessible(true); return f;
  }

  private void assertNoPublishedImport() throws Exception {
    Path root = files.resolve("workspaces/default");
    if (!Files.exists(root)) return;
    try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(root, "import-*")) {
      assertFalse("failed batch must not publish", stream.iterator().hasNext());
    }
  }

  private static byte[] readAll(InputStream in) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] b = new byte[1024]; int n; while ((n = in.read(b)) >= 0) if (n > 0) out.write(b, 0, n);
    return out.toByteArray();
  }

  private static void delete(Path p) throws Exception {
    if (!Files.exists(p)) return;
    if (Files.isDirectory(p)) try (java.nio.file.DirectoryStream<Path> s = Files.newDirectoryStream(p)) { for (Path c : s) delete(c); }
    Files.deleteIfExists(p);
  }
}
