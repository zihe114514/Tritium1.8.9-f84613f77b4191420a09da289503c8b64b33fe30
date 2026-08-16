package tritium.screens.ncm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** 播放器主题：读取主题色时实时生效，切换时使用平滑颜色过渡。 */
public final class NCMTheme {

    private static final File FILE = new File("tritium_player_theme.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long TRANSITION_DURATION_NANOS = 950_000_000L;

    private static ThemePreset current = ThemePreset.OBSIDIAN_RED;
    private static boolean loaded;
    private static int[] transitionFromColors;
    private static float transitionFromGlass;
    private static long transitionStartedAt = -1L;

    private NCMTheme() {
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        if (!FILE.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(FILE), StandardCharsets.UTF_8)) {
            JsonObject object = GSON.fromJson(reader, JsonObject.class);
            if (object != null && object.has("theme")) {
                current = ThemePreset.fromId(object.get("theme").getAsString());
            }
        } catch (Throwable ignored) {
            current = ThemePreset.OBSIDIAN_RED;
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

        NCMScreen.ColorType[] colorTypes = NCMScreen.ColorType.values();
        int[] visualSnapshot = new int[colorTypes.length];
        for (NCMScreen.ColorType type : colorTypes) {
            visualSnapshot[type.ordinal()] = getAnimatedColorLocked(type, System.nanoTime());
        }
        float glassSnapshot = getLiquidGlassAmountLocked(System.nanoTime());

        ThemePreset[] presets = ThemePreset.values();
        current = presets[(current.ordinal() + 1) % presets.length];
        transitionFromColors = visualSnapshot;
        transitionFromGlass = glassSnapshot;
        transitionStartedAt = System.nanoTime();
        save();
        return current;
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

    private static int getAnimatedColorLocked(NCMScreen.ColorType colorType, long now) {
        int target = current.getColor(colorType);
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
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(FILE), StandardCharsets.UTF_8)) {
                writer.write(GSON.toJson(object));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 五套强调氛围和阅读舒适度各不相同的播放器主题。 */
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
        PEARL_LIGHT("pearl_light", "珍珠晨曦", 0xEEF2F6, 0xF9FBFD, 0xDCE5EF, 0x202832, 0x647386, 0x5E83C6, 0x789DDF, 0xE7EDF4, 0xDDE5EE, 0xB8C8D9, false);

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
