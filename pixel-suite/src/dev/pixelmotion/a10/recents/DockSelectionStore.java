package dev.pixelmotion.a10.recents;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import dev.pixelsuite.ModulePreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** The module owns the editable list; injected code reads it through the provider. */
public final class DockSelectionStore {
    static final String AUTHORITY = "dev.pixelmotion.a10.recents.selection";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/apps");
    private static final int MAX_APPS = 512;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private DockSelectionStore() { }

    static final class Selection {
        final boolean enabled;
        final long revision;
        final List<String> packages;
        Selection(boolean enabled, long revision, List<String> packages) {
            this.enabled = enabled;
            this.revision = revision;
            this.packages = Collections.unmodifiableList(new ArrayList<>(packages));
        }
    }

    static synchronized Selection read(Context context) {
        SharedPreferences prefs = ModulePreferences.dock(context);
        boolean enabled = prefs.getBoolean("enabled", false);
        long revision = prefs.getLong("revision", 0);
        List<String> packages = new ArrayList<>();
        String encoded = prefs.getString("packages", "");
        if (!encoded.isEmpty()) {
            for (String name : encoded.split("\\n")) packages.add(name);
        }
        return new Selection(enabled, revision, normalized(packages));
    }

    static synchronized Selection save(Context context, List<String> values) {
        return write(context, true, normalized(values));
    }

    static synchronized Selection useNative(Context context) {
        return write(context, false, read(context).packages);
    }

    public static synchronized Selection setEnabled(Context context, boolean enabled) {
        return write(context, enabled, read(context).packages);
    }

    public static synchronized boolean isEnabled(Context context) {
        return read(context).enabled;
    }

    private static Selection write(Context context, boolean enabled, List<String> packages) {
        SharedPreferences prefs = ModulePreferences.dock(context);
        long revision = Math.max(System.currentTimeMillis(), prefs.getLong("revision", 0) + 1);
        StringBuilder encoded = new StringBuilder();
        for (String name : packages) {
            if (encoded.length() > 0) encoded.append('\n');
            encoded.append(name);
        }
        if (!prefs.edit().putBoolean("enabled", enabled).putLong("revision", revision)
                .putString("packages", encoded.toString()).commit()) {
            throw new IllegalStateException("无法保存应用列表，请重试");
        }
        context.getContentResolver().notifyChange(URI, null);
        return new Selection(enabled, revision, packages);
    }

    private static List<String> normalized(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && value.length() <= 255 && PACKAGE.matcher(value).matches()) {
                    result.add(value);
                    if (result.size() == MAX_APPS) break;
                }
            }
        }
        return new ArrayList<>(result);
    }
}
