package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.font.GlyphInk;
import com.muoniumplayer.core.rendering.ui.widgets.IconWidget;

/**
 * 播放器里用图标字体绘制的几枚控件图标。
 *
 * <p>「下一首播放」来自 {@code muonium/fonts/player-queue-icons.ttf}（圆角播放三角 + 加号角标，
 * 码位 U+E309）；「添加到歌单」与「下一首播放列表」来自另一批 fontello 导出
 * {@code muonium/fonts/player-action-icons.ttf}（U+E113 方框加号、U+E144 播放三角 + 列表）。
 * 三枚共用同一套"按实测墨迹挑字号 + 按墨迹居中"的构造逻辑，所以放在同一个工厂里。</p>
 *
 * <p>为什么用字体而不是贴图：「下一首播放」此前是把同一个字形烘成 96×96 PNG 再按 20×20 画出来的，
 * 而那张位图把字形撑到了画布的 76%×87.5%（相邻贴图图标一律是 54%×50%）并在右下把三角裁掉一角，
 * 实际显示就是一个又大又缺角的图形。字体路径没有这一步离线栅格化，字形由运行时按实际字号生成，
 * 也和项目里其它图标字体（icomoon / music / music-brand-icons / qq-music-icons）走同一条渲染链、
 * 同样可以跟随主题染色。</p>
 *
 * <p>码位放在独立字体里而不是并入既有的 {@code fontello.ttf}：那份字体已经占用了
 * U+E001..U+E004、U+E800..U+E802、U+F127、U+F234，混进同一个文件迟早撞码。这也是
 * {@code music-brand-icons} 与 {@code qq-music-icons} 各自独立成包的原因。</p>
 *
 * <p>三枚的对齐与大小都是量出来的，不是估的，见 {@link #newPlayNextButton(double)} 的说明。</p>
 */
public final class PlayerQueueIcons {

    /** 圆角播放三角 + 加号角标（歌曲行的「下一首播放」）。 */
    public static final String PLAY_NEXT = "\ue309";
    /** 方框加号 + 上方一横（歌曲行的「添加到歌单」）。 */
    public static final String ADD_TO_PLAYLIST = "\ue113";
    /** 播放三角 + 三条横线（播放条右下角的「下一首播放列表」抽屉开关）。 */
    public static final String PLAY_QUEUE = "\ue144";

    /**
     * 墨迹高度占按钮边长的目标比例。
     *
     * <p>取自相邻的贴图图标：{@code favorite.png} 与 {@code playlist.png} 都是 96×96 画布、墨迹
     * 分别是 52×48 与 52×50，也就是 54.2% 宽、50.0%–52.1% 高，并且在画布里严格居中。图标行里视觉
     * 上对齐的是高度，所以按高度对标；宽度随字形自身的长宽比走（播放三角加角标本来就比它高得少）。</p>
     */
    private static final double TARGET_INK_RATIO = .52;

    private PlayerQueueIcons() {
    }

    /**
     * 造一枚已经对齐好的「下一首播放」图标按钮。下面两条同样适用于本类里另外两枚图标。
     *
     * <p>两件事是这里做的，两个调用点都不必各抄一份常数：</p>
     * <ul>
     *   <li><b>选字号。</b>字形是按字号栅格化的，同一个渲染器画进 20 像素和 18 像素的按钮会一大
     *       一小。这里在可用字号里挑墨迹高度最接近 {@code boxSize * TARGET_INK_RATIO} 的那一档。</li>
     *   <li><b>按墨迹居中。</b>{@link IconWidget} 默认按字距框（{@code getStringWidth()} /
     *       {@code getFontHeight()}）居中，那是排版度量而不是墨迹范围，对单个图标会偏——这枚图标
     *       此前就偏高约 2 像素。挂上 {@link GlyphInk} 之后落点由实测墨迹包围盒决定。</li>
     * </ul>
     *
     * <p>度量拿不到时（AWT 不可用、字体缺失）退回默认的字距框居中并使用最大的那一档字号：位置会
     * 差一点，但按钮照样能画、能点。</p>
     *
     * @param boxSize 命中框与悬浮圆的边长（逻辑像素）
     */
    public static IconWidget newPlayNextButton(double boxSize) {
        return newGlyphButton(PLAY_NEXT, queueRenderers(), boxSize);
    }

    /** 歌曲行的「添加到歌单」按钮（方框加号）。 */
    public static IconWidget newAddToPlaylistButton(double boxSize) {
        return newGlyphButton(ADD_TO_PLAYLIST, actionRenderers(), boxSize);
    }

    /** 播放条右下角的「下一首播放列表」按钮（播放三角 + 列表）。 */
    public static IconWidget newPlayQueueButton(double boxSize) {
        return newGlyphButton(PLAY_QUEUE, actionRenderers(), boxSize);
    }

    private static CFontRenderer[] queueRenderers() {
        return new CFontRenderer[]{FontManager.queueIcon34, FontManager.queueIcon38};
    }

    private static CFontRenderer[] actionRenderers() {
        return new CFontRenderer[]{FontManager.actionIcon34, FontManager.actionIcon38};
    }

    private static IconWidget newGlyphButton(String glyph, CFontRenderer[] candidates, double boxSize) {
        CFontRenderer renderer = pickRenderer(glyph, candidates, boxSize);
        IconWidget icon = new IconWidget(glyph, renderer, 0, 0, boxSize, boxSize);
        icon.inkMetrics = GlyphInk.measure(renderer, glyph.charAt(0));
        return icon;
    }

    /** 在可用字号里挑墨迹高度最贴近目标的那一档。 */
    private static CFontRenderer pickRenderer(String glyph, CFontRenderer[] candidates, double boxSize) {
        double target = Math.max(1.0, boxSize * TARGET_INK_RATIO);

        CFontRenderer best = null;
        double bestError = Double.MAX_VALUE;
        for (CFontRenderer candidate : candidates) {
            if (candidate == null) continue;
            GlyphInk ink = GlyphInk.measure(candidate, glyph.charAt(0));
            if (ink == null) continue;
            // 位图像素到逻辑像素是 0.5（drawString 整体缩放一半）。
            double error = Math.abs(ink.inkHeight() * .5 - target);
            if (error < bestError) {
                bestError = error;
                best = candidate;
            }
        }
        if (best != null) {
            return best;
        }
        // 度量整体拿不到（AWT 不可用 / 字体缺失）：退回最大的那一档，位置差一点但按钮照样能画能点。
        for (int i = candidates.length - 1; i >= 0; i--) {
            if (candidates[i] != null) {
                return candidates[i];
            }
        }
        return null;
    }
}
