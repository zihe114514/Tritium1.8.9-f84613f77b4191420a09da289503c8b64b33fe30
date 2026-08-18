package com.muoniumplayer.core.screens.ncm;

import lombok.Getter;
import lombok.SneakyThrows;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.Project;
import today.opai.api.features.ExtensionScreen;
import com.muoniumplayer.core.interfaces.SharedConstants;
import com.muoniumplayer.core.interfaces.SharedRenderingConstants;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.ncm.music.dto.Album;
import com.muoniumplayer.core.ncm.music.dto.Music;
import com.muoniumplayer.core.ncm.music.dto.PlayList;
import com.muoniumplayer.core.rendering.*;
import com.muoniumplayer.core.rendering.Image;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.rendering.entities.impl.TextField;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.shader.Shaders;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.utils.KeyboardUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;
import com.muoniumplayer.core.utils.timing.Timer;

import java.awt.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * @author IzumiiKonata
 * Date: 2025/3/14 20:31
 */
public class CoverflowOverlay extends ExtensionScreen implements SharedConstants, SharedRenderingConstants {

    @Getter
    private static final CoverflowOverlay instance = new CoverflowOverlay();

    public boolean closing = false;
    public float alpha = 0.0f;

    public void display() {
        closing = false;
    }

    Map<Album, List<Music>> albumList = new ConcurrentHashMap<>()/*, renderList = new CopyOnWriteArrayList<>()*/;
    Map<Album, AlbumRenderingData> albumRenderingData = new HashMap<>();
    Map<Music, MusicRenderingData> musicRenderingData = new HashMap<>();
    List<Album> renderList = new CopyOnWriteArrayList<>();

    boolean lmbPressed, rmbPressed;

    TextField textBox = new TextField(0, 0, 0, 0, 0);

    boolean reloadOnClosed = false;

    Timer clickResistTimer = new Timer();

    private static class AlbumRenderingData {
        public boolean flipped, coverLoaded;
        public float rotateDeg = 0f;
        public double scale = .75, scrollTarget = 0, scrollOffset = 0;
    }

    private static class MusicRenderingData {
        public float hoverAlpha = 0f;
    }

    @Override
    public void onGuiClosed() {

        if (this.reloadOnClosed) {
            this.albumList.clear();
            this.reloadOnClosed = false;
            api.displayScreen(NCMScreen.getInstance());
        }

    }

    private void loadAlbumData(List<PlayList> list) {
        MultiThreadingUtil.runAsync(() -> {

            for (PlayList pl : list) {

                if (pl == null)
                    continue;

                List<Music> musics = pl.getMusics();

                if (musics == null)
                    continue;

                for (Music m : musics) {

                    if (m == null) {
                        continue;
                    }

                    Album album = m.getAlbum();
                    if (album == null) {
                        continue;
                    }

                    List<Music> playLists = albumList.computeIfAbsent(album, k -> new CopyOnWriteArrayList<>());
                    playLists.add(m);
                }

            }

            renderList.addAll(this.albumList.keySet());
//            albumList.sort(Comparator.comparing(o -> o.name));
        });
    }

    @Override
    public void initGui() {

//        albumList.clear();

        if (!this.reloadOnClosed && this.albumList.isEmpty())
            this.loadAlbumData(CloudMusic.playLists);

    }

    private void setupProjectionTransformation() {
        double aspectRatio = RenderSystem.getWidth() / RenderSystem.getHeight();

        api.getGLStateManager().matrixMode(GL11.GL_PROJECTION);
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().loadIdentity();
        Project.gluPerspective(45.0f, (float) aspectRatio, 1.0F, 3000.0F);
        api.getGLStateManager().matrixMode(GL11.GL_MODELVIEW);
        api.getGLStateManager().pushMatrix();
        api.getGLStateManager().loadIdentity();

        // translate z
        api.getGLStateManager().translate(0, 0, -200.0f);
        api.getGLStateManager().scale(1, -1, 1);
    }

    public void stopProjectionTransformation() {
        api.getGLStateManager().popMatrix();
        api.getGLStateManager().matrixMode(GL11.GL_PROJECTION);
        api.getGLStateManager().popMatrix();
        api.getGLStateManager().matrixMode(GL11.GL_MODELVIEW);
    }

    int index = 0;
    double scrollOffset = 0;


    @Override
    @SneakyThrows
    public void drawScreen(int mouseX, int mouseY) {

        // 响应式布局：与 NCMScreen 一致，GuiScreen 打开期间每帧刷新 ScaledResolution，
        // 避免窗口 resize / GUI Scale 变化后 getWidth()/getHeight() 停留在旧值。
        RenderSystem.refreshResolution();

        alpha = Interpolations.interpolate(alpha, closing ? .0f : 1f, 0.2f);

        if (alpha <= 0.05)
            return;

        if (!Mouse.isButtonDown(0) && lmbPressed)
            lmbPressed = false;

        if (!Mouse.isButtonDown(1) && rmbPressed)
            rmbPressed = false;

        Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), RGBA.color(0, 0, 0, alpha * 0.5f));

        if (albumList.isEmpty())
            return;

        textBox.setPosition(8, 8);
        // 搜索框按原 UI 比例缩小：字号 pf40bold(40px) → pf18bold(18px)，框高 34 → 24。
        // 原 40px 字号对 34px 高的搜索框严重偏大，占位文本向右溢出并遮挡封面。
        textBox.width = 220;
        textBox.height = 24;
        textBox.setFontRenderer(FontManager.pf18bold);
        textBox.setTextColor(-1);
        textBox.setDisabledTextColour(NCMScreen.getColor(NCMScreen.ColorType.SECONDARY_TEXT));
        textBox.setPlaceholder("Search (Ctrl + F)");
        textBox.setCallback(text -> {
            renderList.clear();

            if (text.isEmpty()) {
                renderList.addAll(albumList.keySet());
            } else {
                for (Album album : albumList.keySet()) {
                    if (album.getName().toLowerCase().contains(text.toLowerCase()))
                        renderList.add(album);
                }

                for (List<Music> m : albumList.values()) {
                    for (Music music : m) {
                        if (music.getName().toLowerCase().contains(text.toLowerCase())) {
                            renderList.add(music.getAlbum());
                            break;
                        }
                        if (music.getTranslatedNames() != null) {
                            if (music.getTranslatedNames().toLowerCase().contains(text.toLowerCase())) {
                                renderList.add(music.getAlbum());
                            }
                        }
                    }
                }

                // distinct the list
                renderList = renderList.stream().distinct().collect(Collectors.toList());
            }
        });
//        textBox.yOffset = -4f;

        textBox.drawTextBox((int) mouseX, (int) mouseY);

        this.setupProjectionTransformation();

        api.getGLStateManager().clear(GL11.GL_DEPTH_BUFFER_BIT);
        api.getGLStateManager().disableDepth();
        api.getGLStateManager().depthMask(false);
//        api.getGLStateManager().disableDepth();
//        api.getGLStateManager().clearDepth(1.0D);
//        api.getGLStateManager().disableCull();

        double coverSize = 96;
        double spacing = 24;

        float rotDegTarget = 45;

        scrollOffset = Interpolations.interpolate(scrollOffset, index * (coverSize * 0.5 + spacing), 0.2f);

        double offsetX = -coverSize * 0.5 - scrollOffset;

        int dWheel = Mouse.getDWheel();

        if (dWheel != 0 && !renderList.isEmpty() && !albumRenderingData.computeIfAbsent(renderList.get(index), k -> new AlbumRenderingData()).flipped) {

            int amount = 1;

            if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT))
                amount *= 5;

            if (dWheel > 0) {
                index -= amount;
            } else {
                index += amount;
            }

        }

        index = Math.max(0, Math.min(renderList.size() - 1, index));

        // 渲染 index 左边的
        for (int i = 0; i < index; i++) {

            Album al = renderList.get(i);

            if (index - i <= 7) {
                this.renderCoverImage(al, offsetX, coverSize, rotDegTarget, i, true, mouseX, mouseY, dWheel);
            }

            offsetX += coverSize * 0.5 + spacing;
        }

        offsetX = -coverSize * 0.5 + ((coverSize * 0.5 + spacing) * (renderList.size() - 1)) - scrollOffset;

        // 渲染 index 右边的
        for (int i = renderList.size() - 1; i >= index; i--) {
            Album al = renderList.get(i);

            if (i - index <= 7) {
                this.renderCoverImage(al, offsetX, coverSize, rotDegTarget, i, false, mouseX, mouseY, dWheel);
            }

            offsetX -= coverSize * 0.5 + spacing;
        }

//        offsetX = -coverSize * 0.5;
//        Album al = list.get(index);
//        this.renderCoverImage(al, offsetX, coverSize, rotDegTarget, index, false, mouseX, mouseY, dWheel);
//        api.getGLStateManager().enableDepth();
        api.getGLStateManager().depthMask(true);

        this.stopProjectionTransformation();

    }

    private void renderCoverImage(Album al, double offsetX, double coverSize, float rotDegTarget, int i, boolean left, double mouseX, double mouseY, int dWheel) {

        TextureManager textureManager = TextureManager.getInstance();

        ITextureObject texture = textureManager.getTexture(al.getCoverLocation());

        AlbumRenderingData renderingData = albumRenderingData.computeIfAbsent(al, k -> new AlbumRenderingData());

        if (texture == null && !renderingData.coverLoaded) {
            renderingData.coverLoaded = true;
            int cSize = 512;
            Textures.downloadTextureAndLoadAsync(al.getPicUrl() + "?param=" + cSize + "y" + cSize, al.getCoverLocation());
        }

        double fovy = 45.0f;
        double aspectRatio = (RenderSystem.getWidth() / RenderSystem.getHeight());
        double translateZ = 200.0f; // 对应视图矩阵中的 offset z

        // 焦点
        double f = (1.0 / Math.tan(Math.toRadians(fovy) / 2.0));

        double paneWidth = (translateZ * aspectRatio) / f * 2;
        double paneHeight = translateZ / f * 2;

        mouseX = mouseX / RenderSystem.getWidth() * paneWidth - paneWidth * 0.5;
        mouseY = mouseY / RenderSystem.getHeight() * paneHeight - paneHeight * 0.5;

        // 喜欢我的魔法数字吗
        double fontScale = (1 / aspectRatio) * 0.618;

        api.getGLStateManager().pushMatrix();

        api.getGLStateManager().translate(offsetX + coverSize * 0.5, coverSize * 0.5, 0);

        if (index != i) {
            renderingData.rotateDeg = Interpolations.interpolate(renderingData.rotateDeg, rotDegTarget * (left ? 1 : -1), 0.2f);
            renderingData.scale = Interpolations.interpolate(renderingData.scale, 0.75, 0.2f);
            renderingData.flipped = false;
        } else {
            renderingData.rotateDeg = Interpolations.interpolate(renderingData.rotateDeg, renderingData.flipped ? -180 : 0, renderingData.flipped ? 0.1f : 0.2f);
            renderingData.scale = Interpolations.interpolate(renderingData.scale, 1.0, 0.2f);
        }

        // rotate
        api.getGLStateManager().rotate(renderingData.rotateDeg, 0, 1, 0);
        api.getGLStateManager().translate(-(offsetX + coverSize * 0.5), -coverSize * 0.5, 0);

        // scale
        api.getGLStateManager().translate(offsetX + coverSize * 0.5, 0, 0);
        api.getGLStateManager().scale(renderingData.scale, renderingData.scale, 1);
        api.getGLStateManager().translate(-(offsetX + coverSize * 0.5), 0, 0);

        Rect.draw(offsetX, -coverSize * 0.5f, coverSize, coverSize, RGBA.color(128, 128, 128, 128));

        if (texture != null) {
            api.getGLStateManager().bindTexture(texture.getGlTextureId());
            texture.linearFilter();
            Image.draw(offsetX, -coverSize * 0.5, coverSize, coverSize, Image.Type.Normal);

            // reflection
            Shaders.VF_FADEOUT.draw(offsetX, coverSize * 0.5, coverSize, coverSize, 0.5, 0.85f);

            if (renderingData.flipped || (renderingData.rotateDeg < -5 && index == i)) {
                // flip it
                api.getGLStateManager().translate(offsetX + coverSize * 0.5, coverSize * 0.5, 0);
                api.getGLStateManager().rotate(-180, 0, 1, 0);
                api.getGLStateManager().translate(-(offsetX + coverSize * 0.5), -coverSize * 0.5, 0);

                double y = -coverSize * 0.5f;

                Image.drawLinearFlippedX(al.getCoverLocation(), offsetX, y, coverSize, coverSize, Image.Type.Normal);
                Rect.draw(offsetX, y, coverSize, coverSize, RGBA.color(0, 0, 0, 200));

                double imgSpacing = 1;
                double imgSize = 16;
                Image.draw(offsetX + coverSize - imgSize - imgSpacing, y + imgSpacing, imgSize, imgSize, Image.Type.Normal);

                CFontRenderer fr = FontManager.pf28bold;
                fr.drawString(fr.trim(al.getName(), (coverSize - imgSpacing * 2 - 2 - imgSize) / fontScale), offsetX + 2, y + 2, fontScale, -1);

                double contentSpacing = 2;

                double contentPaneX = offsetX + contentSpacing;
                double contentPaneY = y + imgSpacing * 2 + imgSize + contentSpacing;
                double contentPaneWidth = coverSize - contentSpacing * 2;
                double contentPaneHeight = coverSize - (imgSpacing * 2 + imgSize + contentSpacing * 2);

                StencilClipManager.beginClip(() -> Rect.draw(contentPaneX, contentPaneY, contentPaneWidth, contentPaneHeight, -1));
                Rect.draw(contentPaneX, contentPaneY, contentPaneWidth, contentPaneHeight, RGBA.color(255, 255, 255, 20));

                double yAdd = 5;

                if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT))
                    yAdd *= 2;

                if (RenderSystem.isHovered(mouseX, mouseY, contentPaneX, contentPaneY, contentPaneWidth, contentPaneHeight) && dWheel != 0) {
                    if (dWheel > 0)
                        renderingData.scrollTarget -= yAdd;
                    else
                        renderingData.scrollTarget += yAdd;
                }

                renderingData.scrollTarget = Interpolations.interpolate(renderingData.scrollTarget, 0, 0.4f);
                renderingData.scrollOffset = Interpolations.interpolate(renderingData.scrollOffset, renderingData.scrollTarget, 1f);

                if (renderingData.scrollTarget < 0)
                    renderingData.scrollTarget = Interpolations.interpolate(renderingData.scrollTarget, 0, 0.2f);

                double yOffset = contentPaneY - renderingData.scrollOffset;
                double entryHeight = fr.getHeight() * fontScale + 4;
                List<Music> musics = albumList.get(al);

                if (renderingData.scrollTarget > (musics.size() - 1) * entryHeight)
                    renderingData.scrollTarget = Interpolations.interpolate(renderingData.scrollTarget, (musics.size() - 1) * entryHeight, 0.2f);

                // 歌曲名称字号按原 UI 比例缩小（pf25 → pf18），配合下方 58/fontScale 的
                // 最大宽度裁剪（CFontRenderer.trim 对超长标题追加 "..."），避免遮挡时长列。
                fr = FontManager.pf18;

                for (int j = 0; j < musics.size(); j++) {
                    Music music = musics.get(j);

                    if ((j + 1) % 2 == 0) {
                        Rect.draw(contentPaneX, yOffset, contentPaneWidth, entryHeight, RGBA.color(0, 0, 0, 60));
                    }

                    fr.drawString((j + 1) + ".", contentPaneX + 2, yOffset + entryHeight * 0.5 - fr.getHeight() * 0.5 * fontScale, fontScale, -1);
                    fr.drawString(fr.trim(music.getName(), 58 / fontScale), contentPaneX + 4 + 12, yOffset + entryHeight * 0.5 - fr.getHeight() * 0.5 * fontScale, fontScale, -1);

                    long tMin = (music.getDuration() / 1000) / 60;
                    long tSec = ((music.getDuration() / 1000) - ((music.getDuration() / 1000) / 60) * 60);
                    String duration = (tMin < 10 ? "0" + tMin : tMin) + ":" + (tSec < 10 ? "0" + tSec : tSec);

                    fr.drawString(duration, contentPaneX + 4 + 74, yOffset + entryHeight * 0.5 - fr.getHeight() * 0.5 * fontScale, fontScale, -1);

                    MusicRenderingData musicData = musicRenderingData.computeIfAbsent(music, m -> new MusicRenderingData());

                    if (musicData.hoverAlpha > 0.05f) {
                        Rect.draw(contentPaneX, yOffset, contentPaneWidth, entryHeight, RGBA.color(1, 1, 1, musicData.hoverAlpha));
                    }

                    boolean hovered = RenderSystem.isHovered(mouseX, mouseY, contentPaneX, yOffset, contentPaneWidth, entryHeight);

                    musicData.hoverAlpha = Interpolations.interpolate(musicData.hoverAlpha, hovered ? 0.2f : 0.0f, 0.2f);

                    if (hovered && Mouse.isButtonDown(0) && !lmbPressed && renderingData.rotateDeg < -145 && renderingData.flipped) {
                        lmbPressed = true;
                        CloudMusic.play(musics, musics.indexOf(music));
                    }

                    yOffset += entryHeight;
                }

                StencilClipManager.endClip();

            }
        }

        api.getGLStateManager().popMatrix();

        if (index == i) {

            if (Mouse.isButtonDown(0) && !lmbPressed && this.clickResistTimer.isDelayed(250)) {

                boolean hovered = RenderSystem.isHovered(mouseX, mouseY, offsetX, -coverSize * 0.5, coverSize, coverSize);

                if (hovered && !renderingData.flipped) {
                    renderingData.flipped = true;
                }

                if (!hovered && renderingData.flipped) {
                    renderingData.flipped = false;
                }

                lmbPressed = true;
            }

//            OpenApiInstance.api.getRenderUtil().drawRect(mouseX, mouseY, 10, 10, Color.WHITE);

            this.stopProjectionTransformation();

            // mouseY = mouseY / RenderSystem.getHeight() * paneHeight - paneHeight * 0.5;

            CFontRenderer fr = FontManager.pf50bold;

            fr.drawCenteredStringWithShadow(al.getName(), RenderSystem.getWidth() * 0.5, RenderSystem.getHeight() * 0.5 + (coverSize - paneHeight * 0.225) / paneHeight * RenderSystem.getHeight(), -1);
//            fr.drawCenteredString(al.getA, RenderSystem.getWidth() * 0.5, RenderSystem.getHeight() * 0.5 + (coverSize - paneHeight * 0.25) / paneHeight * RenderSystem.getHeight() + fr.getHeight(), -1);

            this.setupProjectionTransformation();
        }

    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) {
        this.textBox.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {

        if (keyCode == Keyboard.KEY_ESCAPE) {

            if (!renderList.isEmpty() && albumRenderingData.computeIfAbsent(renderList.get(index), a -> new AlbumRenderingData()).flipped) {
                albumRenderingData.get(renderList.get(index)).flipped = false;
                return;
            }

            if (this.textBox.isFocused()) {
                this.textBox.setFocused(false);
                return;
            }

            api.displayScreen(NCMScreen.getInstance());
            return;
        }

        if (keyCode == Keyboard.KEY_LEFT && !textBox.isFocused()) {

            if (index > 0)
                index --;

            return;
        }

        if (keyCode == Keyboard.KEY_RIGHT && !textBox.isFocused()) {

            if (index < renderList.size() - 1)
                index ++;

            return;
        }

        if (textBox.isFocused()) {
            this.textBox.textboxKeyTyped(typedChar, keyCode);
            return;
        }

        if (KeyboardUtils.isKeyComboCtrl(keyCode, Keyboard.KEY_F)) {
            this.textBox.setFocused(true);
            this.textBox.setCursorPositionEnd();
            this.textBox.setSelectionPos(0);
        }

    }

    public static CoverflowOverlay byPlaylist(PlayList playList) {

        CoverflowOverlay screen = getInstance();

        screen.reloadOnClosed = true;
        screen.albumList.clear();
        screen.renderList.clear();
        screen.loadAlbumData(Collections.singletonList(playList));

        screen.clickResistTimer.reset();

        return screen;
    }

}
