package tritium.management;

import lombok.SneakyThrows;
import tritium.rendering.font.CFontRenderer;
import tritium.rendering.font.FontKerning;

import java.awt.*;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author IzumiiKonata
 * @since 4/8/2023 11:09 AM
 */
public class FontManager extends AbstractManager {

    public FontManager() {
        super("FontManager");
    }

    public static CFontRenderer pf12bold, pf14bold, pf16bold, pf18bold, pf20bold, pf25bold, pf28bold, pf34bold, pf40bold, pf65bold, pf50bold;
    public static CFontRenderer pf12, pf14, pf18, pf20, pf25, pf32;
    public static CFontRenderer icon30;
    public static CFontRenderer music18, music40;

    public static List<CFontRenderer> getAllFontRenderers() {

        return Arrays.stream(FontManager.class.getDeclaredFields())
                .filter(field -> field.getType() == CFontRenderer.class)
                .map(method -> {
            try {
                return (CFontRenderer) method.get(null);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());

    }

    public static void deleteLoadedTextures() {
        getAllFontRenderers().forEach(c -> {
            if (c != null) {
                c.close();
            }
        });
    }

    public static void loadFonts() {
        String normalName = "pf_normal";
        String boldName = "pf_middleblack";

        pf12 = create(12, normalName);
        pf14 = create(14, normalName);
        pf20 = create(20, normalName);
        pf25 = create(25, normalName);
        pf32 = create(32, normalName);
        pf18 = create(18, normalName);

        pf12bold = create(12, boldName);
        pf14bold = create(14, boldName);
        pf16bold = create(16, boldName);
        pf18bold = create(18, boldName);
        pf20bold = create(20, boldName);
        pf25bold = create(25, boldName);
        pf28bold = create(28, boldName);
        pf34bold = create(34, boldName);
        pf40bold = create(40, boldName);
        pf50bold = create(50, boldName);
        pf65bold = create(65, boldName);

        icon30 = create(30, "icomoon");

        music18 = create(18, "music");
        music40 = create(40, "music");
    }

    @SneakyThrows
    public static void waitUntilAllLoaded() {

        while (true) {
            List<CFontRenderer> list = getAllFontRenderers();

            Thread.sleep(100);

            long count = list.stream().filter(Objects::isNull).count();

            if (count == 0)
                break;

            System.out.println("Waiting for " + count + " font renderers to be initialized.");
        }

    }

    @Override
    public void init() {
        loadFonts();
        waitUntilAllLoaded();
    }
    
    private static final HashMap<String, Font> fonts = new HashMap<>();
    private static final HashMap<String, FontKerning> fontKernings = new HashMap<>();
    
    private static Font readFont(String path) {
        return fonts.computeIfAbsent(path, p -> {
            try {
                InputStream resourceAsStream = FontManager.class.getResourceAsStream(p);
                Font font = Font.createFont(Font.TRUETYPE_FONT, resourceAsStream);
                resourceAsStream.close();
                return font;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }
    
    private static FontKerning readFontKerning(String path) {

        return fontKernings.computeIfAbsent(path, p -> {
            try {
                return new FontKerning(path);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    @SneakyThrows
    public static CFontRenderer create(float size, InputStream fontStream) {
        Font font = Font.createFont(Font.TRUETYPE_FONT, fontStream);

        Font fallback = readFont("/tritium/fonts/pf_normal.ttf");
        FontKerning fallbackKerning = readFontKerning("/tritium/fonts/pf_normal.ttf");
        return new CFontRenderer(font, size * 0.5f, fallbackKerning, fallback);
    }

    @SneakyThrows
    public static CFontRenderer create(float size, InputStream fontStream, InputStream fallBackStream) {
        Font font = Font.createFont(Font.TRUETYPE_FONT, fontStream);
        Font fallBack = Font.createFont(Font.TRUETYPE_FONT, fallBackStream);

        FontKerning fontKerning = readFontKerning("/tritium/fonts/pf_normal.ttf");
        return new CFontRenderer(font, size * 0.5f, fontKerning, fallBack);
    }

    @SneakyThrows
    public static CFontRenderer create(float size, String name) {

        Font font = readFont("/tritium/fonts/" + name + ".ttf");
        FontKerning kerning = readFontKerning("/tritium/fonts/" + name + ".ttf");

        // 中文字体默认使用 SF Pro 作为主字体
        // 因为它们的英文字母太他妈难看了
        // 丑陋不堪，，
        if ("googlesans".equals(name) || "product".equals(name) || "tahoma".equals(name)) {
            Font fallback = readFont("/tritium/fonts/pf_normal.ttf");
            FontKerning fallbackKerning = readFontKerning("/tritium/fonts/pf_normal.ttf");
            return new CFontRenderer(font, size * 0.5f, kerning, fallback);
        } else if ("googlesansbold".equals(name)) {
            Font fallback = readFont("/tritium/fonts/pf_middleblack.ttf");
            FontKerning fallbackKerning = readFontKerning("/tritium/fonts/pf_middleblack.ttf");
            return new CFontRenderer(font, size * 0.5f, kerning, fallback);
        } else if ("pf_normal".equals(name)) {
            Font main = readFont("/tritium/fonts/sfregular.otf");
            FontKerning mainKerning = readFontKerning("/tritium/fonts/sfregular.otf");
            return new CFontRenderer(main, size * 0.5f, mainKerning, font);
        } else if ("pf_middleblack".equals(name)) {
            Font main = readFont("/tritium/fonts/sfbold.otf");
            FontKerning mainKerning = readFontKerning("/tritium/fonts/sfbold.otf");
            return new CFontRenderer(main, size * 0.5f, mainKerning, font);
        }
        return new CFontRenderer(font, size * 0.5f, kerning, font);

    }

    @Override
    public void stop() {

    }
}
