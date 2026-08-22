package com.muoniumplayer.core.rendering.font;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * 一个字形在 {@link GlyphGenerator} 烘出来的那张位图里的墨迹包围盒。
 *
 * <p>为什么需要这个：{@link CFontRenderer} 报出来的 {@code getStringWidth()} 与
 * {@code getFontHeight()} 都是排版度量（字距框 / 行高），不是墨迹范围。按它们居中对正文是对的
 * （每一行的基线要对齐），但对单个图标字形就不对——图标要的是"墨迹落在按钮正中间"。两者的差距
 * 在 20 像素的按钮里能到 2 像素，肉眼就是明显偏上或偏下。</p>
 *
 * <p>这里的做法是照着 {@link GlyphGenerator} 那一套一模一样地再烘一次位图（同样的字体、同样的
 * {@code width = ceil(advance + max(0, -lsb))}、同样的 {@code height = ascent + descent}、同样的
 * 基线落点），然后扫 alpha 求包围盒。因为是同一套参数，量出来的坐标可以直接换算到屏幕上，不需要
 * 任何经验常数；字号、字体、甚至 {@code getFontHeight()} 里那个 8.5 的经验值以后怎么变，结果都
 * 会自己跟着变。</p>
 *
 * <p>结果按 (字体, 字符) 缓存。测一次是一张几十像素见方的 {@code BufferedImage}，只在首次构造
 * 图标时发生；失败（字体不可用、AWT 抛异常、字形是空白）会被记成"测不出来"，调用方退回原本的
 * 字距框居中，不会每帧重试。</p>
 */
public final class GlyphInk {

    /** 低于这个 alpha 的像素算背景：抗锯齿会在边缘留下一圈几乎看不见的杂点。 */
    private static final int ALPHA_THRESHOLD = 8;

    private static final AffineTransform TRANSFORM = new AffineTransform();
    private static final FontRenderContext CONTEXT = new FontRenderContext(TRANSFORM, true, true);
    private static final BufferedImage METRICS_IMAGE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    private static final Graphics2D METRICS_GRAPHICS = (Graphics2D) METRICS_IMAGE.getGraphics();

    /** 测不出来的哨兵，用来把失败也缓存住。 */
    private static final GlyphInk UNMEASURABLE = new GlyphInk(0, 0, 0, 0, -1, -1);

    private static final Map<Font, Map<Character, GlyphInk>> CACHE = new HashMap<>();

    /** {@link GlyphGenerator} 烘出的位图尺寸，单位是位图像素。 */
    public final int bitmapWidth, bitmapHeight;
    /** 墨迹包围盒，闭区间，单位是位图像素。 */
    public final int minX, minY, maxX, maxY;

    private GlyphInk(int bitmapWidth, int bitmapHeight, int minX, int minY, int maxX, int maxY) {
        this.bitmapWidth = bitmapWidth;
        this.bitmapHeight = bitmapHeight;
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    /** 墨迹中心相对位图左边沿的距离（位图像素）。 */
    public double centerX() {
        return (minX + maxX + 1) * .5;
    }

    /** 墨迹中心相对位图上边沿的距离（位图像素）。 */
    public double centerY() {
        return (minY + maxY + 1) * .5;
    }

    /** 墨迹宽度（位图像素）。 */
    public int inkWidth() {
        return maxX - minX + 1;
    }

    /** 墨迹高度（位图像素）。 */
    public int inkHeight() {
        return maxY - minY + 1;
    }

    /**
     * 量出 {@code fr} 渲染 {@code ch} 时墨迹在位图里的位置。
     *
     * @return 量不出来时返回 {@code null}，调用方应保持原有的字距框居中行为
     */
    public static GlyphInk measure(CFontRenderer fr, char ch) {
        if (fr == null || fr.font == null) return null;

        Font font = fontForGlyph(fr, ch);
        if (font == null) return null;

        synchronized (CACHE) {
            Map<Character, GlyphInk> perFont = CACHE.get(font);
            if (perFont != null) {
                GlyphInk cached = perFont.get(ch);
                if (cached != null) return cached == UNMEASURABLE ? null : cached;
            }

            GlyphInk measured = measureUncached(fr, font, ch);
            if (perFont == null) {
                perFont = new HashMap<>();
                CACHE.put(font, perFont);
            }
            perFont.put(ch, measured == null ? UNMEASURABLE : measured);
            return measured;
        }
    }

    private static GlyphInk measureUncached(CFontRenderer fr, Font font, char ch) {
        try {
            FontMetrics metrics = METRICS_GRAPHICS.getFontMetrics(font);
            int ascent = metrics.getAscent();
            int descent = metrics.getDescent();
            // GlyphGenerator 的位图高度取的是主字体与所有回退字体里最高的那一个，回退字形要能放得下。
            int height = maxFontHeight(fr, ascent + descent);
            if (height <= 0) return null;

            TRANSFORM.setToIdentity();
            GlyphVector vector = font.createGlyphVector(CONTEXT, String.valueOf(ch));
            float lsb = vector.getGlyphMetrics(0).getLSB();
            int drawOffsetX = (int) Math.max(0, -lsb);
            int width = (int) Math.ceil(vector.getGlyphMetrics(0).getAdvance() + Math.max(0, -lsb));
            if (width <= 0) return null;

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.setComposite(AlphaComposite.Src);
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                        RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics.setFont(font);
                // 与 GlyphGenerator 完全一致的基线落点，否则量出来的纵向位置对不上真正画出来的那一张。
                graphics.drawString(String.valueOf(ch), drawOffsetX,
                        (height + ascent - descent) / 2);
            } finally {
                graphics.dispose();
            }

            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    if (((image.getRGB(x, y) >> 24) & 0xFF) <= ALPHA_THRESHOLD) continue;
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
            image.flush();

            // 空白字形（空格、字体里没画东西的码位）没有墨迹可言。
            if (maxX < minX || maxY < minY) return null;
            return new GlyphInk(width, height, minX, minY, maxX, maxY);
        } catch (Throwable ignored) {
            // 度量只是为了对得更准，任何意外都不该让界面画不出来。
            return null;
        }
    }

    private static int maxFontHeight(CFontRenderer fr, int baseHeight) {
        int max = baseHeight;
        if (fr.fallBackFonts != null) {
            for (Font fallback : fr.fallBackFonts) {
                if (fallback == null) continue;
                FontMetrics metrics = METRICS_GRAPHICS.getFontMetrics(fallback);
                max = Math.max(max, metrics.getAscent() + metrics.getDescent());
            }
        }
        return max;
    }

    /** 与 {@code GlyphGenerator.getFontForGlyph} 同一条选择逻辑：主字体画不出来才轮到回退字体。 */
    private static Font fontForGlyph(CFontRenderer fr, char ch) {
        Font main = fr.font;
        if (main.canDisplay(ch)) return main;
        if (fr.fallBackFonts != null) {
            for (Font fallback : fr.fallBackFonts) {
                if (fallback != null && fallback.canDisplay(ch)) return fallback;
            }
        }
        return main;
    }
}
