package io.github.zhanxing.dshandroid;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Instrumentation-only provider; fixtures are not an end-to-end system picker. */
public final class SafFixtureProvider extends ContentProvider {
  public static final String AUTHORITY = "io.github.zhanxing.dshandroid.saf.fixture";
  public static final Uri FILE = Uri.parse("content://" + AUTHORITY + "/document/file");
  public static final Uri TREE = Uri.parse("content://" + AUTHORITY + "/tree/root");
  public static final Uri ROOT_DOCUMENT = Uri.parse("content://" + AUTHORITY + "/document/root");
  private File dir;

  @Override public boolean onCreate() {
    dir = new File(getContext().getCacheDir(), "saf-fixture");
    dir.mkdirs();
    try {
      write("file", "fixture-bytes".getBytes(StandardCharsets.UTF_8));
      write("nested", "nested-bytes".getBytes(StandardCharsets.UTF_8));
      write("bad", "bad".getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) { throw new AssertionError(e); }
    return true;
  }

  private void write(String name, byte[] bytes) throws IOException {
    try (FileOutputStream out = new FileOutputStream(new File(dir, name))) { out.write(bytes); }
  }

  private static String id(Uri uri) {
    String path = uri.getPath();
    if (path == null) return "";
    int slash = path.lastIndexOf('/');
    return slash < 0 ? path : path.substring(slash + 1);
  }

  private static String[] columns(String[] projection) {
    return projection == null ? new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS} : projection;
  }

  @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    String[] cols = columns(projection);
    MatrixCursor cursor = new MatrixCursor(cols);
    String path = uri.getPath() == null ? "" : uri.getPath();
    if (path.endsWith("/children")) {
      String parent = DocumentsContract.getDocumentId(uri);
      if (parent.equals("root")) {
        add(cursor, cols, "file", "hello.txt", "text/plain", 0);
        add(cursor, cols, "dir", "folder", DocumentsContract.Document.MIME_TYPE_DIR, 0);
      } else if (parent.equals("dir")) {
        add(cursor, cols, "nested", "nested.txt", "text/plain", 0);
      } else if (parent.equals("loop")) {
        add(cursor, cols, "loop", "loop", DocumentsContract.Document.MIME_TYPE_DIR, 0);
      }
      return cursor;
    }
    String document = id(uri);
    if ("root".equals(document)) add(cursor, cols, "root", "fixture-root", DocumentsContract.Document.MIME_TYPE_DIR, 0);
    else if ("dir".equals(document)) add(cursor, cols, "dir", "folder", DocumentsContract.Document.MIME_TYPE_DIR, 0);
    else if ("file".equals(document)) add(cursor, cols, "file", "hello.txt", "text/plain", 0);
    else if ("nested".equals(document)) add(cursor, cols, "nested", "nested.txt", "text/plain", 0);
    else if ("bad".equals(document)) add(cursor, cols, "bad", "../evil", "text/plain", 0);
    else if ("loop".equals(document)) add(cursor, cols, "loop", "loop", DocumentsContract.Document.MIME_TYPE_DIR, 0);
    return cursor;
  }

  private static void add(MatrixCursor c, String[] cols, String id, String name, String mime, int flags) {
    Object[] row = new Object[cols.length];
    for (int i = 0; i < cols.length; i++) {
      switch (cols[i]) {
        case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row[i] = id; break;
        case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row[i] = name; break;
        case DocumentsContract.Document.COLUMN_MIME_TYPE: row[i] = mime; break;
        case DocumentsContract.Document.COLUMN_FLAGS: row[i] = flags; break;
        default: row[i] = null;
      }
    }
    c.addRow(row);
  }

  @Override public String getType(Uri uri) {
    String document = id(uri);
    return ("root".equals(document) || "dir".equals(document) || "loop".equals(document))
        ? DocumentsContract.Document.MIME_TYPE_DIR : "text/plain";
  }

  @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
    String document = id(uri);
    if ("out".equals(document)) return ParcelFileDescriptor.open(new File(dir, "exported"),
        ParcelFileDescriptor.parseMode(mode));
    if ("file".equals(document)) return ParcelFileDescriptor.open(new File(dir, "file"), ParcelFileDescriptor.MODE_READ_ONLY);
    if ("nested".equals(document)) return ParcelFileDescriptor.open(new File(dir, "nested"), ParcelFileDescriptor.MODE_READ_ONLY);
    if ("bad".equals(document)) return ParcelFileDescriptor.open(new File(dir, "bad"), ParcelFileDescriptor.MODE_READ_ONLY);
    throw new java.io.FileNotFoundException("not a fixture file");
  }

  @Override public int delete(Uri uri, String s, String[] a) { return 0; }
  @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
  @Override public Uri insert(Uri uri, ContentValues v) { return null; }
}
