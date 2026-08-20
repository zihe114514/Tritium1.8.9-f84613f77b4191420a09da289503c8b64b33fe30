package com.muoniumplayer.core.ncm.customsource;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the mandatory LX-style header without running untrusted JavaScript. */
final class CustomSourceMetadataParser {

    private static final Pattern HEADER = Pattern.compile("^\\s*/\\*([\\s\\S]{0,16384}?)\\*/");
    private static final Pattern FIELD = Pattern.compile("^\\s*\\*?\\s*@([A-Za-z][A-Za-z0-9_]*)\\s*(.*)$");

    private CustomSourceMetadataParser() {
    }

    static void apply(String script, CustomSourceInfo info) {
        if (script == null || script.trim().isEmpty()) {
            throw new IllegalArgumentException("音源脚本为空");
        }
        Matcher header = HEADER.matcher(script);
        if (!header.find()) {
            throw new IllegalArgumentException("音源缺少 LX metadata 注释头");
        }
        String[] lines = header.group(1).split("\\r?\\n");
        for (String line : lines) {
            Matcher field = FIELD.matcher(line);
            if (!field.matches()) continue;
            String key = field.group(1).toLowerCase(Locale.ROOT);
            String value = trim(field.group(2), limitFor(key));
            if ("name".equals(key)) info.name = emptyFallback(value, "未命名音源");
            else if ("description".equals(key)) info.description = value;
            else if ("version".equals(key)) info.version = value;
            else if ("author".equals(key)) info.author = value;
            else if ("homepage".equals(key)) info.homepage = value;
        }
        if (info.name == null || info.name.trim().isEmpty()) {
            info.name = "未命名音源";
        }
    }

    private static int limitFor(String key) {
        if ("name".equals(key)) return 24;
        if ("description".equals(key)) return 72;
        if ("author".equals(key)) return 56;
        if ("homepage".equals(key)) return 1024;
        if ("version".equals(key)) return 36;
        return 256;
    }

    private static String trim(String value, int limit) {
        String safe = value == null ? "" : value.trim();
        return safe.length() <= limit ? safe : safe.substring(0, limit);
    }

    private static String emptyFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}