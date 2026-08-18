package com.muoniumplayer.core.rendering;

import lombok.Getter;
import lombok.experimental.UtilityClass;
import org.lwjgl.opengl.GL11;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.animation.Animation;
import com.muoniumplayer.core.rendering.animation.Easing;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.utils.Lazy;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.math.Mth;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * @author IzumiiKonata
 * Date: 2025/11/16 12:04
 */
@UtilityClass
public class MusicToast implements SharedRenderingConstants, SharedConstants {

    @Getter
    private final Map<String, String> locationToName = new HashMap<>();

    final Lazy<AnimatedTexture> musicNotes = Lazy.of(() -> {
        try {
            return new AnimatedTexture(Location.of("/tritium/textures/hud/music_notes.png"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    });

    public void pushMusicToast(String name) {
        text = name;
        waitStart = -1L;
        forward = true;

        double musicNotesSize = 16;
        double spacing = 4;
        double contentWidth = musicNotesSize + spacing + FontManager.pf18.getWidth(text);

        offset = -Math.max(120, contentWidth + 12) * 1.25;
        animation.setValue(offset);
    }

    private int musicNoteColorTick;
    private long lastMusicNoteColorChange;
    private int musicNoteColor;
    private String text = null;
    private boolean forward = true;
    private double offset = -240;
    private long waitStart = -1L;
    private final Animation animation = new Animation(Easing.EASE_OUT_QUART, Duration.ofMillis(750));

    public void tickMusicNotes() {
        long now;
        if ((now = System.currentTimeMillis()) > lastMusicNoteColorChange + 25L) {
            lastMusicNoteColorChange = now;
            musicNoteColor = getLerpedColor(++musicNoteColorTick);
        }
    }


    public void render() {
        if (text != null) {
            // 用 mod 自带的 CJK 字体（CFontRenderer）而非原项目的 getVanillaFont()：
            // Minecraft 原版 FontRenderer 无 CJK 字形，中文歌名/歌手会渲染成“小方框”。
            CFontRenderer font = FontManager.pf18;

            double musicNotesSize = 16;
            double spacing = 4;
            double contentWidth = musicNotesSize + spacing + font.getWidth(text);

            double toastWidth = Math.max(120, contentWidth + 12), toastHeight = 24;

            offset = animation.run(forward ? 0 : -toastWidth * 1.25);

            double offsetX = offset + 1;
            double offsetY = 1;

            if (!forward && offset + toastWidth < 0)
                return;

            if (forward && offset >= -.5) {

                if (waitStart == -1L) {
                    waitStart = System.currentTimeMillis();
                } else {
                    if (System.currentTimeMillis() - waitStart > 5000L) {
                        forward = false;
                    }
                }

            }

            if (!forward && offset <= -toastWidth * 1.2) {
                text = null;
                return;
            }

            api.getGLStateManager().enableTexture2D();

            if (toastWidth > 120) {
                TextureManager.getInstance().bindTexture(Location.of("/tritium/textures/hud/now_playing.png"));

                RenderSystem.color(-1);

                api.getGLStateManager().disableAlpha();
                api.getGLStateManager().enableBlend();
                api.getGLStateManager().tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);

                Image.drawModalRectWithCustomSizedTexture(offsetX, offsetY, 0, 0, 4, toastHeight, 120, 24);
                Image.drawModalRectWithCustomSizedTexture(offsetX + toastWidth - 4, offsetY, 116, 0, 4, toastHeight, 120, 24);

                int count = (int) ((toastWidth - 8) / 112);
                for (int i = 0; i < count; i ++) {
                    Image.drawModalRectWithCustomSizedTexture(offsetX + 4 + i * 112, offsetY, 4, 0, 112, toastHeight, 120, 24);
                }

                Image.drawModalRectWithCustomSizedTexture(offsetX + 4 + count * 112, offsetY, 4, 0, toastWidth - (8 + count * 112), toastHeight, 120, 24);

            } else {
                Image.draw(Location.of("/tritium/textures/hud/now_playing.png"), offsetX, offsetY, toastWidth, toastHeight, Image.Type.Normal);
            }


            AnimatedTexture value = musicNotes.getValue();

            if (value != null) {
                tickMusicNotes();
                RenderSystem.color(musicNoteColor);
                value.render(offsetX + toastWidth * .5 - contentWidth * .5, offsetY + toastHeight * .5 - musicNotesSize * .5, musicNotesSize, musicNotesSize, true);
//                    Rect.draw(offset + toastWidth * .5 - contentWidth * .5, toastHeight * .5 - musicNotesSize * .5, musicNotesSize, musicNotesSize, -1);
//                    value.render(offset + toastWidth * .5 - musicNotesSize * .5, toastHeight * .5 - musicNotesSize * .5, musicNotesSize, musicNotesSize, true);
            }

            font.drawString(text, offsetX + toastWidth * .5 - contentWidth * .5 + musicNotesSize + spacing, offsetY + toastHeight * .5 - font.getHeight() * .5, -1);
        }
    }

    private final DyeColor[] MUSIC_NOTE_COLORS = new DyeColor[]{DyeColor.WHITE, DyeColor.LIGHT_GRAY, DyeColor.LIGHT_BLUE, DyeColor.BLUE, DyeColor.CYAN, DyeColor.GREEN, DyeColor.LIME, DyeColor.YELLOW, DyeColor.ORANGE, DyeColor.PINK, DyeColor.RED, DyeColor.MAGENTA};

    public int getLerpedColor(float tick) {
        int colorDuration = 30;
        int tickCount = Mth.floor(tick);
        int value = tickCount / colorDuration;
        int colorCount = MUSIC_NOTE_COLORS.length;
        int c1 = value % colorCount;
        int c2 = (value + 1) % colorCount;
        float subStep = ((float)(tickCount % colorDuration) + Mth.frac(tick)) / (float)colorDuration;
        int color1 = getModifiedColor(MUSIC_NOTE_COLORS[c1], 1.25f);
        int color2 = getModifiedColor(MUSIC_NOTE_COLORS[c2], 1.25f);
        return RGBA.srgbLerp(subStep, color1, color2);
    }

    private int getModifiedColor(DyeColor color, float brightness) {
        if (color == DyeColor.WHITE) {
            return -1644826;
        }
        int src = color.getTextureDiffuseColor();
        return RGBA.color(Mth.floor((float) RGBA.red(src) * brightness), Mth.floor((float) RGBA.green(src) * brightness), Mth.floor((float) RGBA.blue(src) * brightness), 255);
    }

    public enum DyeColor
    {
        WHITE(0, "white", 0xF9FFFE, 0xF0F0F0, 0xFFFFFF),
        ORANGE(1, "orange", 16351261, 15435844, 16738335),
        MAGENTA(2, "magenta", 13061821, 12801229, 0xFF00FF),
        LIGHT_BLUE(3, "light_blue", 3847130, 6719955, 10141901),
        YELLOW(4, "yellow", 16701501, 14602026, 0xFFFF00),
        LIME(5, "lime", 8439583, 4312372, 0xBFFF00),
        PINK(6, "pink", 15961002, 14188952, 16738740),
        GRAY(7, "gray", 4673362, 0x434343, 0x808080),
        LIGHT_GRAY(8, "light_gray", 0x9D9D97, 0xABABAB, 0xD3D3D3),
        CYAN(9, "cyan", 1481884, 2651799, 65535),
        PURPLE(10, "purple", 8991416, 8073150, 10494192),
        BLUE(11, "blue", 3949738,  2437522, 255),
        BROWN(12, "brown", 8606770, 5320730, 9127187),
        GREEN(13, "green", 6192150, 3887386, 65280),
        RED(14, "red", 11546150, 11743532, 0xFF0000),
        BLACK(15, "black", 0x1D1D21, 0x1E1B1B, 0);

        private final int id;
        private final String name;
        private final int textureDiffuseColor;
        private final int fireworkColor;
        private final int textColor;

        DyeColor(int id, String name, int textureDiffuseColor, int fireworkColor, int textColor) {
            this.id = id;
            this.name = name;
            this.textColor = RGBA.opaque(textColor);
            this.textureDiffuseColor = RGBA.opaque(textureDiffuseColor);
            this.fireworkColor = fireworkColor;
        }

        public int getId() {
            return this.id;
        }

        public String getName() {
            return this.name;
        }

        public int getTextureDiffuseColor() {
            return this.textureDiffuseColor;
        }

        public int getFireworkColor() {
            return this.fireworkColor;
        }

        public int getTextColor() {
            return this.textColor;
        }


        public String toString() {
            return this.name;
        }

        public String getSerializedName() {
            return this.name;
        }

    }

}
