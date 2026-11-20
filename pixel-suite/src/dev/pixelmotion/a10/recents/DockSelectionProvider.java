package dev.pixelmotion.a10.recents;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

/** Read-only IPC: no third-party app can read or change the user's selected list. */
public final class DockSelectionProvider extends ContentProvider {
    private static final String LAUNCHER = "com.google.android.apps.nexuslauncher";
    private static final String[] COLUMNS = { "enabled", "revision", "package_name", "position" };

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        enforceReader();
        enforceUri(uri);
        DockSelectionStore.Selection state = DockSelectionStore.read(getContext());
        MatrixCursor cursor = new MatrixCursor(COLUMNS, Math.max(1, state.packages.size()));
        int enabled = state.enabled ? 1 : 0;
        if (state.packages.isEmpty()) {
            cursor.addRow(new Object[] { enabled, state.revision, null, -1 });
        } else {
            for (int i = 0; i < state.packages.size(); i++) {
                cursor.addRow(new Object[] { enabled, state.revision, state.packages.get(i), i });
            }
        }
        cursor.setNotificationUri(getContext().getContentResolver(), DockSelectionStore.URI);
        return cursor;
    }

    @Override public String getType(Uri uri) {
        enforceReader();
        enforceUri(uri);
        return "vnd.android.cursor.dir/vnd.pixelmotion.dock-selection";
    }

    private void enforceReader() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String name : packages) if (LAUNCHER.equals(name)) return;
        }
        throw new SecurityException("Only Pixel Launcher may read Dock selections");
    }

    private void enforceUri(Uri uri) {
        if (!"content".equals(uri.getScheme()) || !DockSelectionStore.AUTHORITY.equals(uri.getAuthority())
                || !"/apps".equals(uri.getPath())) {
            throw new IllegalArgumentException("Unknown selection URI");
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw readOnly(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw readOnly(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw readOnly(); }
    private UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("Dock selections are read-only outside module settings");
    }
}
