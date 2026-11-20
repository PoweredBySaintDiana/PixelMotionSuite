package dev.pixelsuite;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

public final class ModulePreferences {
    private static final String LEGACY_FEATURES = "features";
    private static final String DOCK = "dock_selection";

    private ModulePreferences() { }

    static synchronized SharedPreferences features(Context context, boolean forWrite) {
        SharedPreferences target;
        try {
            target = context.getSharedPreferences(FeatureConfig.PREFS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException unavailable) {
            if (forWrite) throw unavailable;
            return context.getSharedPreferences(LEGACY_FEATURES, Context.MODE_PRIVATE);
        }
        return importIfEmpty(context, target, LEGACY_FEATURES);
    }

    public static synchronized SharedPreferences dock(Context context) {
        return importIfEmpty(context, context.getSharedPreferences(DOCK, Context.MODE_PRIVATE), DOCK);
    }

    private static SharedPreferences importIfEmpty(Context context, SharedPreferences target, String name) {
        if (!target.getAll().isEmpty()) return target;
        File directory = new File(context.getDataDir(), "shared_prefs");
        File legacy = new File(directory, name + ".xml");
        File backup = new File(directory, name + ".xml.bak");
        File source = backup.isFile() ? backup : legacy;
        if (!source.isFile()) return target;
        try (FileInputStream input = new FileInputStream(source)) {
            DocumentBuilder parser = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            parser.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            Element root = parser.parse(input).getDocumentElement();
            if (!"map".equals(root.getTagName())) throw new IllegalStateException("Invalid preference file");
            SharedPreferences.Editor editor = target.edit();
            boolean imported = false;
            for (Node item = root.getFirstChild(); item != null; item = item.getNextSibling()) {
                if (!(item instanceof Element)) continue;
                Element entry = (Element) item;
                String key = entry.getAttribute("name");
                String value = entry.getAttribute("value");
                String type = entry.getTagName();
                if ((LEGACY_FEATURES.equals(name) ? FeatureConfig.isMotionKey(key) : "enabled".equals(key))
                        && "boolean".equals(type) && ("true".equals(value) || "false".equals(value))) {
                    editor.putBoolean(key, Boolean.parseBoolean(value));
                } else if (DOCK.equals(name) && "long".equals(type) && "revision".equals(key)) {
                    editor.putLong(key, Long.parseLong(value));
                } else if (DOCK.equals(name) && "string".equals(type) && "packages".equals(key)) {
                    editor.putString(key, entry.getTextContent());
                } else continue;
                imported = true;
            }
            if (imported && !editor.commit()) throw new IllegalStateException("Migration commit failed");
            return target;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot import " + name, failure);
        }
    }
}
