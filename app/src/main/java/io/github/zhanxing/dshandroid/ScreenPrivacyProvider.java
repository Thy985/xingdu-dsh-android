package io.github.zhanxing.dshandroid;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Single-process preference owner; never rely on multiprocess SharedPreferences caches. */
public final class ScreenPrivacyProvider extends ContentProvider {
    private boolean persistenceFailed;
    static final String PREFS = "screen-privacy";
    static final String ALLOWED = "capture-allowed";
    static final String OK = "ok";

    static Uri uri(Context context) {
        return Uri.parse("content://" + context.getPackageName() + ".screen-privacy");
    }

    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("Private setting");
        android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean ok = true;
        if ("set".equals(method)) {
            if (extras == null || !extras.containsKey(ALLOWED)) throw new IllegalArgumentException("Missing choice");
            // Persist before notifying another process or removing FLAG_SECURE.
            ok = prefs.edit().putBoolean(ALLOWED, extras.getBoolean(ALLOWED, false)).commit();
            persistenceFailed = !ok;
            getContext().getContentResolver().notifyChange(uri(getContext()), null);
        } else if (!"get".equals(method)) {
            throw new IllegalArgumentException("Unknown operation");
        }
        Bundle result = new Bundle();
        result.putBoolean(ALLOWED, !persistenceFailed && prefs.getBoolean(ALLOWED, false));
        result.putBoolean(OK, ok && !persistenceFailed);
        return result;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
