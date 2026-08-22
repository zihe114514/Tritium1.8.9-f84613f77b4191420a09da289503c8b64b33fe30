package com.muoniumplayer.core.screens.ncm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import com.muoniumplayer.core.settings.ConfigPaths;
import com.muoniumplayer.core.settings.JsonConfigStorage;

/** 播放器主题：读取主题色时实时生效，切换时使用平滑颜色过渡。 */
public final class NCMTheme {

    private static final File FILE = ConfigPaths.THEME;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long TRANSITION_DURATION_NANOS = 950_000_000L;

    private static ThemePreset current = ThemePreset.OBSIDIAN_RED;
    private static boolean loaded;
    private static int[] transitionFromColors;
    private static float transitionFromGlass;
    private static long transitionStartedAt = -1L;
    /** 每套预设各自保存一份用户调色覆盖，切换主题时不会互相污染。 */
    private static final Map<String, EnumMap<NCMScreen.ColorType, Integer>> CUSTOM_BY_THEME = new HashMap<String, EnumMap<NCMScreen.ColorType, Integer>>();

    private NCMTheme() {
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!FILE.exists()) return;
        try {
            JsonObject object = JsonConfigStorage.readObject(FILE, GSON);
            if (object != null && object.has("theme")) {
                current = ThemePreset.fromId(object.get("theme").getAsString());
            }
            if (object != null && object.has("custom") && object.get("custom").isJsonObject()) {
                readCustomColors(object.getAsJsonObject("custom"));
            }
        } catch (Throwable ignored) {
            current = ThemePreset.OBSIDIAN_RED;
            CUSTOM_BY_THEME.clear();
        }
    }

    public static synchronized ThemePreset getCurrent() {
        load();
        return current;
    }

    public static synchronized String getCurrentName() {
        return getCurrent().getDisplayName();
    }

    /** 切换至下一款预设主题，并保存当前视觉状态作为动画起点。 */
    public static synchronized ThemePreset next() {
        load();
        ThemePreset[] presets = ThemePreset.values();
        return setCurrent(presets[(current.ordinal() + 1) % presets.length]);
    }

    /** 直接切换到指定预设主题；沿用与轮换一致的平滑过渡与持久化。 */
    public static synchronized ThemePreset setCurrent(ThemePreset preset) {
        load();
        if (preset == null || preset == current) {
            return current;
        }
        beginTransitionLocked();
        current = preset;
        save();
        return current;
    }

    /** 以当前实际显示的颜色为起点开启一次过渡动画。 */
    private static void beginTransitionLocked() {
        long now = System.nanoTime();
        NCMScreen.ColorType[] colorTypes = NCMScreen.ColorType.values();
        int[] visualSnapshot = new int[colorTypes.length];
        for (NCMScreen.ColorType type : colorTypes) {
            visualSnapshot[type.ordinal()] = getAnimatedColorLocked(type, now);
        }
        float glassSnapshot = getLiquidGlassAmountLocked(now);
        transitionFromColors = visualSnapshot;
        transitionFromGlass = glassSnapshot;
        transitionStartedAt = System.nanoTime();
    }

    public static synchronized int getColor(NCMScreen.ColorType colorType) {
        load();
        return getAnimatedColorLocked(colorType, System.nanoTime());
    }

    /** 液态玻璃材质权重；切入和切出主题时同样平滑变化。 */
    public static synchronized float getLiquidGlassAmount() {
        load();
        return getLiquidGlassAmountLocked(System.nanoTime());
    }

    /** Theme accent exposed without requiring global HUD elements to depend on NCMScreen. */
    public static int getAccentColor() {
        return getColor(NCMScreen.ColorType.ACCENT);
    }

    /** 当前预设在忽略用户调色覆盖时的原始颜色。 */
    public static synchronized int getPresetColor(NCMScreen.ColorType colorType) {
        load();
        return current.getColor(colorType);
    }

    /** 颜色的最终目标值：存在用户调色时返回调色结果，否则返回预设色。 */
    public static synchronized int getTargetColor(NCMScreen.ColorType colorType) {
        load();
        return effectiveColorLocked(current, colorType);
    }

    public static synchronized boolean hasCustomColor(NCMScreen.ColorType colorType) {
        load();
        EnumMap<NCMScreen.ColorType, Integer> map = CUSTOM_BY_THEME.get(current.getId());
        return map != null && colorType != null && map.containsKey(colorType);
    }

    public static synchronized boolean hasAnyCustomColor() {
        load();
        EnumMap<NCMScreen.ColorType, Integer> map = CUSTOM_BY_THEME.get(current.getId());
        return map != null && !map.isEmpty();
    }

    /**
     * 写入一项用户调色。为了让调色盘做到实时预览，这里不启动过渡动画，
     * 颜色立即生效；{@code persist} 为 {@code false} 时只改内存，供拖动过程使用。
     */
    public static synchronized void setCustomColor(NCMScreen.ColorType colorType, int rgb, boolean persist) {
        load();
        if (colorType == null) {
            return;
        }
        EnumMap<NCMScreen.ColorType, Integer> map = CUSTOM_BY_THEME.get(current.getId());
        if (map == null) {
            map = new EnumMap<NCMScreen.ColorType, Integer>(NCMScreen.ColorType.class);
            CUSTOM_BY_THEME.put(current.getId(), map);
        }
        map.put(colorType, Integer.valueOf(rgb & 0xFFFFFF));
        if (persist) {
            save();
        }
    }

    /** 撤销单项调色，回到预设色。 */
    public static synchronized void resetCustomColor(NCMScreen.ColorType colorType) {
        load();
        EnumMap<NCMScreen.ColorType, Integer> map = CUSTOM_BY_THEME.get(current.getId());
        if (map == null || colorType == null || map.remove(colorType) == null) {
            return;
        }
        if (map.isEmpty()) {
            CUSTOM_BY_THEME.remove(current.getId());
        }
        save();
    }

    /** 撤销当前主题的所有调色。 */
    public static synchronized void resetAllCustomColors() {
        load();
        if (CUSTOM_BY_THEME.remove(current.getId()) != null) {
            save();
        }
    }

    private static int effectiveColorLocked(ThemePreset preset, NCMScreen.ColorType colorType) {
        EnumMap<NCMScreen.ColorType, Integer> map = CUSTOM_BY_THEME.get(preset.getId());
        if (map != null) {
            Integer custom = map.get(colorType);
            if (custom != null) {
                return custom.intValue() & 0xFFFFFF;
            }
        }
        return preset.getColor(colorType);
    }

    private static void readCustomColors(JsonObject root) {
        CUSTOM_BY_THEME.clear();
        for (Map.Entry<String, com.google.gson.JsonElement> themeEntry : root.entrySet()) {
            if (!themeEntry.getValue().isJsonObject()) {
                continue;
            }
            EnumMap<NCMScreen.ColorType, Integer> map = new EnumMap<NCMScreen.ColorType, Integer>(NCMScreen.ColorType.class);
            for (Map.Entry<String, com.google.gson.JsonElement> colorEntry
                    : themeEntry.getValue().getAsJsonObject().entrySet()) {
                NCMScreen.ColorType type = parseColorType(colorEntry.getKey());
                if (type == null) {
                    continue;
                }
                try {
                    String raw = colorEntry.getValue().getAsString();
                    if (raw == null) {
                        continue;
                    }
                    raw = raw.trim();
                    if (raw.startsWith("#")) {
                        raw = raw.substring(1);
                    }
                    map.put(type, Integer.valueOf((int) (Long.parseLong(raw, 16) & 0xFFFFFFL)));
                } catch (Throwable ignored) {
                }
            }
            if (!map.isEmpty()) {
                CUSTOM_BY_THEME.put(themeEntry.getKey(), map);
            }
        }
    }

    private static JsonObject writeCustomColors() {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, EnumMap<NCMScreen.ColorType, Integer>> themeEntry : CUSTOM_BY_THEME.entrySet()) {
            if (themeEntry.getValue() == null || themeEntry.getValue().isEmpty()) {
                continue;
            }
            JsonObject colors = new JsonObject();
            for (Map.Entry<NCMScreen.ColorType, Integer> colorEntry : themeEntry.getValue().entrySet()) {
                colors.addProperty(colorEntry.getKey().name(),
                        String.format("#%06X", Integer.valueOf(colorEntry.getValue().intValue() & 0xFFFFFF)));
            }
            root.add(themeEntry.getKey(), colors);
        }
        return root;
    }

    private static NCMScreen.ColorType parseColorType(String name) {
        if (name == null) {
            return null;
        }
        for (NCMScreen.ColorType type : NCMScreen.ColorType.values()) {
            if (type.name().equalsIgnoreCase(name.trim())) {
                return type;
            }
        }
        return null;
    }

    private static int getAnimatedColorLocked(NCMScreen.ColorType colorType, long now) {
        int target = effectiveColorLocked(current, colorType);
        if (transitionFromColors == null) return target;

        float progress = getTransitionProgressLocked(now);
        int from = transitionFromColors[colorType.ordinal()];
        if (progress >= 1.0f) {
            transitionFromColors = null;
            transitionStartedAt = -1L;
            return target;
        }
        return lerpColor(from, target, smoothstep(progress));
    }

    private static float getLiquidGlassAmountLocked(long now) {
        float target = current.isLiquidGlass() ? 1.0f : 0.0f;
        if (transitionFromColors == null || transitionStartedAt < 0L) return target;
        return lerp(transitionFromGlass, target, smoothstep(getTransitionProgressLocked(now)));
    }

    private static float getTransitionProgressLocked(long now) {
        if (transitionStartedAt < 0L) return 1.0f;
        return Math.max(0.0f, Math.min(1.0f,
                (float) (now - transitionStartedAt) / (float) TRANSITION_DURATION_NANOS));
    }

    private static float smoothstep(float value) {
        return value * value * (3.0f - 2.0f * value);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static int lerpColor(int from, int to, float amount) {
        int r = Math.round(lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, amount));
        int g = Math.round(lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, amount));
        int b = Math.round(lerp(from & 0xFF, to & 0xFF, amount));
        return (r << 16) | (g << 8) | b;
    }

    private static void save() {
        try {
            JsonObject object = new JsonObject();
            object.addProperty("theme", current.getId());
            JsonObject custom = writeCustomColors();
            if (custom.entrySet().size() > 0) {
                object.add("custom", custom);
            }
            JsonConfigStorage.writeObject(FILE, GSON, object);

        } catch (Throwable ignored) {
        }
    }

    /** 深浅与氛围各不相同的播放器预设主题。 */
    public enum ThemePreset {
        OBSIDIAN_RED("obsidian_red", "曜石红", 0x1E1E1E, 0x252525, 0x383838, 0xFFFFFF, 0x9B9B9B, 0xD60017, 0xF04455, 0x292727, 0x191919, 0x454545, false),
        AURORA_TIDE("aurora_tide", "极光海", 0x0D1720, 0x142631, 0x203D4A, 0xEFFBFF, 0x91AAB5, 0x54D6C1, 0x7DE8D6, 0x10212B, 0x091118, 0x2B5160, false),
        VELVET_ROSE("velvet_rose", "丝绒玫瑰", 0x211821, 0x30212C, 0x493342, 0xFFF7FB, 0xC2AAB7, 0xE48AAB, 0xF4A9C4, 0x2A1D27, 0x1A1219, 0x604252, false),
        PORCELAIN_INK("porcelain_ink", "瓷白墨", 0xF4F0EA, 0xFFFCF7, 0xE7DDD1, 0x2A2723, 0x817970, 0xB85C38, 0xD37B54, 0xEEE7DF, 0xE7DED5, 0xD4C6B8, false),
        LIQUID_GLASS("liquid_glass", "液态玻璃", 0x182331, 0x26384B, 0x49637C, 0xF7FBFF, 0xB7C6D8, 0x64A8FF, 0x8BC1FF, 0x203044, 0x111C2A, 0x91A8C2, true),
        AURORA_FROST("aurora_frost", "极光霜蓝", 0x101827, 0x17263A, 0x2C4660, 0xF3F8FF, 0xAAB9CF, 0x7B9CFF, 0xA6B8FF, 0x1B2C44, 0x0C1423, 0x5576A6, true),
        MIDNIGHT_GRAPHITE("midnight_graphite", "午夜石墨", 0x111419, 0x1B2028, 0x323B49, 0xF5F7FA, 0xA3ACBA, 0x8FA8C7, 0xB4C8E5, 0x202731, 0x0C0F14, 0x4C5869, false),
        SUNSET_EMBER("sunset_ember", "暮色琥珀", 0x20151B, 0x2B1D25, 0x4B2D35, 0xFFF7F0, 0xCDB3A5, 0xF59A62, 0xFFC078, 0x352128, 0x171014, 0x70424A, false),
        SAGE_MIST("sage_mist", "鼠尾草雾", 0x121C1B, 0x1C2B29, 0x304742, 0xF1FAF5, 0xA8BDB4, 0x72C9A2, 0x9BE1BF, 0x203632, 0x0D1413, 0x4C7267, false),
        PEARL_LIGHT("pearl_light", "珍珠晨曦", 0xEEF2F6, 0xF9FBFD, 0xDCE5EF, 0x202832, 0x647386, 0x5E83C6, 0x789DDF, 0xE7EDF4, 0xDDE5EE, 0xB8C8D9, false),
        XIANGYUN_QINGCHUAN("xiangyun_qingchuan", "缃云晴川", 0xF7ECC0, 0xFDF7E2, 0xF0DFA8, 0x3A3320, 0x857A55, 0x2E7CA8, 0x79BEDF, 0xF3E7BF, 0xEFDFA6, 0xDCC580, false),
        CANGMING_YUYOU("cangming_yuyou", "沧溟玉釉", 0xFAF2E0, 0xFFFBF1, 0xF0E4CB, 0x16283C, 0x6E7C8B, 0x0E61AC, 0x1D7ACE, 0xF5EBD5, 0xF2E7CF, 0xDCCFB4, false),
        SHUANGWAN_XIUYAN("shuangwan_xiuyan", "霜纨岫烟", 0xFCF9E8, 0xFFFEF6, 0xF1EDD8, 0x1E2A2B, 0x6F7C7D, 0x00808C, 0x00B7C7, 0xF7F2DE, 0xF5F1DC, 0xDCD8C0, false),
        MEIGUI_YUEBAI("meigui_yuebai", "玫瑰月白", 0xE8F0F2, 0xF6FAFB, 0xDCE7EA, 0x262E31, 0x6B7A7F, 0xB44E60, 0xD87888, 0xE1EBEE, 0xDEE9EC, 0xC3D3D7, false),
        QINGLAN_YUNZHI("qinglan_yunzhi", "青岚云脂", 0xFBF1D7, 0xFFFAEA, 0xF0E4C4, 0x232A1D, 0x6F7A60, 0x4E7C36, 0x73AE52, 0xF6ECD0, 0xF4EACD, 0xDCD0AE, false);

        private final String id, displayName;
        private final int genericBackground, elementBackground, elementHover, primaryText, secondaryText;
        private final int accent, accentHover, inputBackground, navigationBackground, border;
        private final boolean liquidGlass;

        ThemePreset(String id, String displayName, int genericBackground, int elementBackground, int elementHover,
                    int primaryText, int secondaryText, int accent, int accentHover, int inputBackground,
                    int navigationBackground, int border, boolean liquidGlass) {
            this.id = id;
            this.displayName = displayName;
            this.genericBackground = genericBackground;
            this.elementBackground = elementBackground;
            this.elementHover = elementHover;
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            this.accent = accent;
            this.accentHover = accentHover;
            this.inputBackground = inputBackground;
            this.navigationBackground = navigationBackground;
            this.border = border;
            this.liquidGlass = liquidGlass;
        }

        public String getId() { return id; }
        public String getDisplayName() { return displayName; }
        public boolean isLiquidGlass() { return liquidGlass; }

        public int getColor(NCMScreen.ColorType type) {
            switch (type) {
                case GENERIC_BACKGROUND: return genericBackground;
                case ELEMENT_BACKGROUND: return elementBackground;
                case ELEMENT_HOVER: return elementHover;
                case PRIMARY_TEXT: return primaryText;
                case SECONDARY_TEXT: return secondaryText;
                case ACCENT: return accent;
                case ACCENT_HOVER: return accentHover;
                case INPUT_BACKGROUND: return inputBackground;
                case NAVIGATION_BACKGROUND: return navigationBackground;
                case BORDER: return border;
                default: return primaryText;
            }
        }

        private static ThemePreset fromId(String id) {
            if (id != null) {
                for (ThemePreset preset : values()) {
                    if (preset.id.equalsIgnoreCase(id)) return preset;
                }
            }
            return OBSIDIAN_RED;
        }
    }
}
