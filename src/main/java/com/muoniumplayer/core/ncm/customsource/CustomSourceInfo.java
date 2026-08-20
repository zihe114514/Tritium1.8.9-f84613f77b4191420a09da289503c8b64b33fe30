package com.muoniumplayer.core.ncm.customsource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persisted, non-secret metadata for one imported LX-compatible JavaScript source. */
public final class CustomSourceInfo {

    public String id = "";
    public String name = "未命名音源";
    public String description = "";
    public String version = "";
    public String author = "";
    public String homepage = "";
    public String origin = "";
    public String scriptFile = "";
    public boolean enabled = true;
    /** Exactly one enabled source may be explicitly selected for playback URL fallback. */
    public boolean selected;
    /** User-selected platform key within this source, for example wy, tx, kw, kg or mg. */
    public String selectedPlatform = "";
    public int priority;
    public long importedAt;
    public long updatedAt;
    public List<String> declaredSources = new ArrayList<>();
    public String runtimeStatus = "未初始化";
    public String runtimeMessage = "";

    public List<String> getDeclaredSources() {
        return declaredSources == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(declaredSources));
    }

    public String getDisplayCapabilities() {
        List<String> values = getDeclaredSources();
        return values.isEmpty() ? "尚未声明来源" : join(values, " / ").toUpperCase(java.util.Locale.ROOT);
    }

    private static String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(value.trim());
        }
        return builder.length() == 0 ? "尚未声明来源" : builder.toString();
    }
}