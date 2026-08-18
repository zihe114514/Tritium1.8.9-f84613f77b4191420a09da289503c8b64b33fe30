package tritium.screens.ncm;

import lombok.Getter;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import today.opai.api.features.ExtensionScreen;
import tritium.interfaces.SharedConstants;
import tritium.interfaces.SharedRenderingConstants;
import tritium.management.FontManager;
import tritium.ncm.OptionsUtil;
import tritium.ncm.music.CloudMusic;
import tritium.ncm.music.dto.Music;
import tritium.rendering.Rect;
import tritium.rendering.Framebuffer;
import tritium.rendering.StencilClipManager;
import tritium.rendering.animation.Interpolations;
import tritium.rendering.rendersystem.RenderSystem;
import tritium.rendering.ui.AbstractWidget;
import tritium.rendering.ui.container.Panel;
import tritium.rendering.ui.widgets.RectWidget;
import tritium.rendering.ui.widgets.RoundedRectWidget;
import tritium.screens.ncm.panels.ControlsBar;
import tritium.screens.ncm.panels.HomePanel;
import tritium.screens.ncm.panels.NavigateBar;
import tritium.screens.ncm.panels.PlaylistPanel;
import tritium.screens.ncm.panels.PersonalFmPanel;
import tritium.utils.cursor.CursorUtils;
import tritium.utils.other.multithreading.MultiThreadingUtil;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * @author IzumiiKonata
 * Date: 2025/10/16 19:47
 */
public class NCMScreen extends ExtensionScreen implements SharedConstants, SharedRenderingConstants {

    /** 统一的播放器外框线宽；内容裁剪使用同一尺寸，避免模糊污染外框。 */
    public static final double PLAYER_BORDER_THICKNESS = .8;

    @Getter
    private static NCMScreen instance = new NCMScreen();

    float alpha = 0f;
    boolean closing = false;

    Panel basePanel = new Panel();

    @Getter
    NavigateBar playlistsPanel;

    RectWidget currentPanelBg = new RectWidget();

    float prevAnimatingPanelAlpha = 0f;
    NCMPanel prevAnimatingPanel = null;
    NCMPanel currentPanel = null;
    float curPanelAlphaAnimation = 0f;

    @Getter
    ControlsBar controlsBar;

    public MusicLyricsPanel musicLyricsPanel = null;

    /** 主题色插值由 NCMTheme 负责；此渲染器只负责按钮起点的波浪与玻璃高光。 */
    private final ThemeTransitionRenderer themeTransitionRenderer = new ThemeTransitionRenderer();

    /**
     * 播放器尺寸通过真实布局边界动画，而不是在最外层叠加 GL scale。
     * 这样鼠标命中、Stencil 裁剪、Framebuffer 和文字换行始终共享同一坐标系。
     */
    private double animatedPanelScale = -1.0;
    private double panelWidth = -1.0;
    private double panelHeight = -1.0;

    /**
     * 加入歌单弹窗（模态覆盖层）。为 null 表示未打开。
     */
    public AddToPlaylistOverlay addToPlaylistOverlay = null;

    /** 双平台账号管理（含二维码登录二级页面）的模态覆盖层。 */
    public AccountManagerOverlay accountManagerOverlay = null;

    /** 对取消收藏等破坏性操作的二次确认层。 */
    public ConfirmationOverlay confirmationOverlay = null;

    /**
     * 表示是否需要重新布局, 当用户信息和用户歌单加载完设置为 true,
     * 然后会自动进行重新布局并设为 false
     */
    private boolean dirty = true;

    public NCMScreen() {

    }

    @Override
    public void initGui() {
        alpha = 0f;
        closing = false;

        this.checkDirty();

        if (this.musicLyricsPanel != null)
            this.musicLyricsPanel.onInit();

        Keyboard.enableRepeatEvents(true);
        CursorUtils.setCursor(CursorUtils.ARROW);
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void checkDirty() {
        if (this.dirty) {
            this.dirty = false;

            /*
             * 刷新帐号/歌单会重建侧栏和控制栏，但内容面板并不属于 basePanel，
             * 因此可以安全保留。此前这里无条件 new HomePanel()，导致用户在
             * 歌单详情、搜索结果等页面点击刷新后被强制送回主页。
             */
            NCMPanel panelBeforeLayout = this.currentPanel;
            this.layout();

            if (panelBeforeLayout == null) {
                // 首次打开播放器仍默认进入主页。
                this.setCurrentPanel(new HomePanel());
            } else if (this.playlistsPanel != null) {
                // 新侧栏需要重新映射当前面板的选中状态；内容页面保持原样。
                this.playlistsPanel.selectCurrentPanel(panelBeforeLayout);
            }
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
    }

    public void layout() {
        this.basePanel.getChildren().clear();

        RoundedRectWidget bg = new RoundedRectWidget();
        bg.setBeforeRenderCallback(() -> {
            bg.setBounds(0, 0, bg.getParentWidth(), bg.getParentHeight());
            bg.setRadius(this.getPlayerCornerRadius());
            bg.setColor(getColor(ColorType.GENERIC_BACKGROUND));
            // 液态玻璃保留足够实体感，同时让游戏画面能轻微透入形成材质层次。
            bg.setAlpha(1.0f - NCMTheme.getLiquidGlassAmount() * 0.14f);
        });
        this.basePanel.addChild(bg);

        ThemeGlassWidget glassSurface = new ThemeGlassWidget();
        glassSurface.setBeforeRenderCallback(() ->
                glassSurface.setBounds(0, 0, glassSurface.getParentWidth(), glassSurface.getParentHeight()));
        this.basePanel.addChild(glassSurface);

        this.basePanel.setBeforeRenderCallback(() -> this.basePanel.center());

        this.playlistsPanel = new NavigateBar();
        this.basePanel.addChild(this.playlistsPanel);

        this.basePanel.addChild(this.currentPanelBg);

        this.currentPanelBg.setBeforeRenderCallback(() -> {
            double controlsHeight = Math.min(this.getPanelHeight(), Math.max(28.0, this.getPanelHeight() * .07));
            this.currentPanelBg.setBounds(
                    playlistsPanel.getWidth(),
                    0,
                    Math.max(0.0, this.currentPanelBg.getParentWidth() - playlistsPanel.getWidth()),
                    Math.max(0.0, this.getPanelHeight() - controlsHeight)
            );
            this.currentPanelBg.setColor(getColor(ColorType.GENERIC_BACKGROUND));
            this.currentPanelBg.setAlpha(1.0f - NCMTheme.getLiquidGlassAmount() * 0.24f);
        });

        this.controlsBar = new ControlsBar();
        this.controlsBar.onInit();
    }

    public double getSpacing() {
        return 16.0;
    }

    public double getPlayerBorderThickness() {
        return PLAYER_BORDER_THICKNESS;
    }

    private double getPlayerCornerRadius() {
        return Math.max(8.0, Math.min(18.0, this.getPanelHeight() * .028));
    }

    public double getPanelWidth() {
        if (this.panelWidth > 0) return this.panelWidth;
        return this.getAvailablePanelWidth() * this.getEffectiveTargetScale();
    }

    public double getPanelHeight() {
        if (this.panelHeight > 0) return this.panelHeight;
        return this.getAvailablePanelHeight() * this.getEffectiveTargetScale();
    }

    public double getPanelX() {
        return RenderSystem.getWidth() * .5 - this.getPanelWidth() * .5;
    }

    public double getPanelY() {
        return RenderSystem.getHeight() * .5 - this.getPanelHeight() * .5;
    }

    private double getAvailablePanelWidth() {
        return Math.max(1.0, RenderSystem.getWidth() - this.getSpacing() * 2);
    }

    private double getAvailablePanelHeight() {
        return Math.max(1.0, RenderSystem.getHeight() - this.getSpacing() * 2);
    }

    /** 返回用户选择的真实尺寸；响应式子组件负责在较小面板中重新排版。 */
    private double getEffectiveTargetScale() {
        return NCMPlayerConfig.getPlayerScale();
    }

    private void updatePanelScaleAnimation() {
        double targetScale = this.getEffectiveTargetScale();
        if (this.animatedPanelScale < 0) {
            this.animatedPanelScale = targetScale;
        } else {
            this.animatedPanelScale = Interpolations.interpolate(this.animatedPanelScale, targetScale, .18f);
            if (Math.abs(this.animatedPanelScale - targetScale) < .0005) {
                this.animatedPanelScale = targetScale;
            }
        }

        this.panelWidth = this.getAvailablePanelWidth() * this.animatedPanelScale;
        this.panelHeight = this.getAvailablePanelHeight() * this.animatedPanelScale;
    }

    @Override
    public void drawScreen(int mX, int mY) {
        // 响应式布局：GuiScreen 打开期间 RenderGameOverlayEvent 不触发，width/height/scaleFactor
        // 停留在上一次游戏帧值。每帧刷新 ScaledResolution，保证窗口 resize / GUI Scale 变化后
        // 布局与鼠标坐标映射立即正确。
        RenderSystem.refreshResolution();
        // The root rounded clip needs the main Minecraft framebuffer's stencil
        // attachment before any player pixels are drawn (DrawScreenEvent.Post is
        // too late for the first frame).
        Framebuffer.updateMcFramebuffer();
        this.updatePanelScaleAnimation();

        if (closing && alpha <= 0.02f)
            api.displayScreen(null);

        alpha = Interpolations.interpolate(alpha, closing ? 0f : 1f, 0.4f);

        CursorUtils.resetOverride();

//        Shaders.GAUSSIAN_BLUR_SHADER.run(Collections.singletonList(() -> {
//            Rect.draw(0, 0, RenderSystem.getWidth(), RenderSystem.getHeight(), hexColor(1, 1, 1, alpha));
//        }));

        this.checkDirty();

        int dWheel = Mouse.getDWheel();
        // 加入歌单弹窗为模态：滚轮只应作用于弹窗内歌单列表，不能穿透到下方 basePanel/controlsBar。
        // 否则在弹窗内滚动时会同时滚动下层的歌单/歌曲列表。
        int baseDWheel = (this.addToPlaylistOverlay != null || this.accountManagerOverlay != null
                || this.confirmationOverlay != null) ? 0 : dWheel;

        RenderSystem.FIXED_SCALE = true;

        // —— GL 状态隔离（关键修复：播放器打开/关闭时半透明元素发白）——
        // NCMScreen 同样用裸 GL11 绘制（GLStateManagerImpl 直接透传 GL11，绕过 Minecraft 的
        // GlStateManager 缓存），且内部 StencilClipManager 会改 stencil/colorMask/depthMask、
        // ScrollText 与面板可能改投影矩阵。若不在边界保存/恢复，会与 Minecraft 互相污染状态。
        // 此处保存全部 GL 属性与投影矩阵，并在 drawScreen 结束时恢复。
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);

        // 重置为已知 2D GUI 状态，避免继承游戏世界/HUD 渲染遗留的 blend/alpha/color。
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glShadeModel(GL11.GL_FLAT);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);

        api.getGLStateManager().pushMatrix();
        double xScale = RenderSystem.getWidthNotScaled() / RenderSystem.getWidth();
        double yScale = RenderSystem.getHeightNotScaled() / RenderSystem.getHeight();
        double mouseX = mX / xScale;
        double mouseY = mY / yScale;
        api.getGLStateManager().scale(xScale, yScale, 1);

        this.scaleAtPos(RenderSystem.getWidth() * .5, RenderSystem.getHeight() * .5, 0.9 + (alpha * 0.1));

        this.basePanel.setBounds(this.getPanelWidth(), this.getPanelHeight());
        this.basePanel.setPosition(this.getPanelX(), this.getPanelY());
        final double cornerRadius = this.getPlayerCornerRadius();

        StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
        if (this.musicLyricsPanel == null || this.musicLyricsPanel.alpha <= .9f) {
            this.basePanel.setAlpha(alpha);
            this.basePanel.renderWidget(mouseX, mouseY, baseDWheel);
            // Re-assert the root rounded clip after child/framebuffer rendering.
            StencilClipManager.restoreActiveClip();

            float alphaInterpolateSpeed = 0.4f;
            if (this.prevAnimatingPanel != null) {
                this.prevAnimatingPanel.setAlpha(this.prevAnimatingPanelAlpha = Interpolations.interpolate(this.prevAnimatingPanelAlpha, 0f, alphaInterpolateSpeed));
                this.prevAnimatingPanel.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight());

                api.getGLStateManager().pushMatrix();
                this.scaleAtPos(this.currentPanelBg.getX() + this.currentPanelBg.getWidth() * .5, this.currentPanelBg.getY() + this.currentPanelBg.getHeight() * .5, 0.9 + (this.prevAnimatingPanel.getAlpha() * 0.1));
                this.prevAnimatingPanel.renderWidget(mouseX, mouseY, baseDWheel);
                api.getGLStateManager().popMatrix();

                if (this.prevAnimatingPanelAlpha <= 0.02f)
                    this.prevAnimatingPanel = null;
            } else if (this.currentPanel != null) {
                curPanelAlphaAnimation = Interpolations.interpolate(curPanelAlphaAnimation, 1f, alphaInterpolateSpeed);
                this.currentPanel.setAlpha(Math.min(this.basePanel.getAlpha(), curPanelAlphaAnimation));
                this.currentPanel.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight());

                StencilClipManager.beginClip(() -> Rect.draw(this.currentPanelBg.getX(), this.currentPanelBg.getY(), this.currentPanelBg.getWidth(), this.currentPanelBg.getHeight(), -1));

                api.getGLStateManager().pushMatrix();
                this.scaleAtPos(this.currentPanelBg.getX() + this.currentPanelBg.getWidth() * .5, this.currentPanelBg.getY() + this.currentPanelBg.getHeight() * .5, 1.1 - (curPanelAlphaAnimation * 0.1));

                this.currentPanel.renderWidget(mouseX, mouseY, baseDWheel);
                api.getGLStateManager().popMatrix();

                StencilClipManager.endClip();
            }

            this.controlsBar.setAlpha(alpha);
            this.controlsBar.setBounds(this.currentPanelBg.getX(), this.currentPanelBg.getY() + this.currentPanelBg.getHeight(), this.currentPanelBg.getWidth(), this.getPanelHeight() - this.currentPanelBg.getHeight());
            this.controlsBar.renderWidget(mouseX, mouseY, baseDWheel);
            StencilClipManager.restoreActiveClip();
        }
        StencilClipManager.endClip();

        if (this.musicLyricsPanel != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            roundedRect(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(),
                    cornerRadius, getColor(ColorType.GENERIC_BACKGROUND)
                            | ((int) (this.musicLyricsPanel.alpha * 255)) << 24);
            this.musicLyricsPanel.onRender(mouseX, mouseY, basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(), dWheel);
            StencilClipManager.restoreActiveClip();
            StencilClipManager.endClip();

            if (this.musicLyricsPanel.shouldClose())
                this.musicLyricsPanel = null;
        }

        // 波浪绘制在播放器内容之上、模态弹窗之下，并复用播放器圆角 Stencil。
        if (this.themeTransitionRenderer.isActive()) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.themeTransitionRenderer.renderWave(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), alpha);
            StencilClipManager.endClip();
        }
        // 加入歌单弹窗：渲染在面板之上、与 basePanel 同一坐标空间，作为模态覆盖层。
        if (this.addToPlaylistOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.addToPlaylistOverlay.setBounds(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight());
            this.addToPlaylistOverlay.setAlpha(alpha);
            this.addToPlaylistOverlay.renderWidget(mouseX, mouseY, dWheel);

            if (this.addToPlaylistOverlay.shouldClose())
                this.addToPlaylistOverlay = null;
            StencilClipManager.endClip();
        }

        // 账号管理始终位于最上层，并独占鼠标/滚轮输入。
        if (this.accountManagerOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.accountManagerOverlay.setBounds(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight());
            this.accountManagerOverlay.setAlpha(alpha);
            this.accountManagerOverlay.renderWidget(mouseX, mouseY, dWheel);
            if (this.accountManagerOverlay.shouldClose()) {
                this.accountManagerOverlay.dispose();
                this.accountManagerOverlay = null;
            }
            StencilClipManager.endClip();
        }

        // 二次确认始终位于所有播放器内容之上，并独占鼠标/滚轮输入。
        if (this.confirmationOverlay != null) {
            StencilClipManager.beginClip(() -> roundedRect(basePanel.getX(), basePanel.getY(),
                    basePanel.getWidth(), basePanel.getHeight(), cornerRadius, -1));
            this.confirmationOverlay.setBounds(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight());
            this.confirmationOverlay.setAlpha(alpha);
            this.confirmationOverlay.renderWidget(mouseX, mouseY, 0);
            if (this.confirmationOverlay.shouldClose()) {
                this.confirmationOverlay = null;
            }
            StencilClipManager.endClip();
        }

        roundedOutline(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(),
                cornerRadius, getPlayerBorderThickness(), new Color(255, 255, 255,
                        Math.max(0, Math.min(255, (int) (alpha * 25)))));
        float glassAmount = NCMTheme.getLiquidGlassAmount();
        if (glassAmount > 0.001f) {
            int glassBorderAlpha = Math.max(0, Math.min(255, (int) (alpha * glassAmount * 88)));
            roundedOutlineGradient(basePanel.getX(), basePanel.getY(), basePanel.getWidth(), basePanel.getHeight(),
                    cornerRadius, .72,
                    new Color(92, 138, 196, glassBorderAlpha / 3),
                    new Color(255, 255, 255, glassBorderAlpha),
                    new Color(115, 164, 230, glassBorderAlpha / 2),
                    new Color(225, 244, 255, glassBorderAlpha));
            roundedRect(basePanel.getX() + cornerRadius * 1.25, basePanel.getY() + .75,
                    Math.max(1.0, basePanel.getWidth() - cornerRadius * 2.5), .7, .35,
                    1.0f, 1.0f, 1.0f, alpha * glassAmount * .30f);
        }
        api.getGLStateManager().popMatrix();
        RenderSystem.FIXED_SCALE = false;
        CursorUtils.setOverride();

        // 恢复投影矩阵与全部 GL 属性（替代原先只 enableTexture2D 的不完整恢复）。
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopAttrib();

    }

    /** 由主题按钮传入绝对 GUI 坐标，保证涟漪确实从按钮处开始。 */
    public void cycleThemeFrom(double originX, double originY) {
        NCMTheme.ThemePreset target = NCMTheme.next();
        this.themeTransitionRenderer.begin(originX, originY, target.getColor(ColorType.ACCENT));
    }

    private final class ThemeGlassWidget extends AbstractWidget<ThemeGlassWidget> {
        private ThemeGlassWidget() {
            this.setClickable(false);
        }

        @Override
        public void onRender(double mouseX, double mouseY) {
            float amount = NCMTheme.getLiquidGlassAmount() * this.getAlpha();
            if (amount <= 0.001f) return;
            themeTransitionRenderer.renderLiquidGlassSurface(this.getX(), this.getY(),
                    this.getWidth(), this.getHeight(), getPlayerCornerRadius(), amount);
        }
    }
    public LoginRenderer loginRenderer = null;

    int currentActionPointer = 0;
    List<Runnable> actions = new ArrayList<>();

    public void setCurrentPanel(NCMPanel panel) {
        this.innerSetCurrentPanel(panel, true);

        if (panel != null) {
            Runnable action = () -> this.innerSetCurrentPanel(panel, false);

            if (actions.isEmpty()) {
                currentActionPointer = 0;
                actions.add(action);
            } else {
                ++ currentActionPointer;

                while (actions.size() > currentActionPointer + 1)
                    actions.remove(actions.size() - 1);

                if (currentActionPointer < actions.size()) {
                    actions.set(currentActionPointer, action);
                } else {
                    actions.add(action);
                }
            }
        }
    }

    /**
     * Rebuilds the visible playlist panel after asynchronously loaded metadata
     * changes its row badges. Other panels are intentionally left untouched so
     * an account refresh cannot trigger duplicate home/search requests.
     */
    public void reloadCurrentPanel() {
        if (this.currentPanel instanceof PlaylistPanel || this.currentPanel instanceof PersonalFmPanel) {
            this.currentPanel.onInit();
        }
    }
    private void innerSetCurrentPanel(NCMPanel panel, boolean shouldCallInit) {
        this.prevAnimatingPanel = this.currentPanel;
        this.prevAnimatingPanelAlpha = 1.0f;
        this.currentPanel = panel;
        if (panel != null) {
            if (shouldCallInit)
                this.currentPanel.onInit();
            this.currentPanel.setAlpha(0);
            this.curPanelAlphaAnimation = 0f;
        }
        if (this.playlistsPanel != null) {
            this.playlistsPanel.selectCurrentPanel(panel);
        }
    }

    /**
     * Returns to the immediately preceding content page. The same history is
     * used by the existing mouse side-button navigation, so the page animation
     * and lifecycle remain consistent with the rest of the player.
     */
    public void navigateBack() {
        if (currentActionPointer > 0) {
            --currentActionPointer;
            actions.get(currentActionPointer).run();
            return;
        }

        // A standalone secondary panel can still offer a useful escape route.
        if (!(this.currentPanel instanceof HomePanel)) {
            this.setCurrentPanel(new HomePanel());
        }
    }

    /**
     * 打开“加入歌单”弹窗。由 MusicWidget 的「+」按钮调用。
     * 复用原项目 PlayList.addToList(musicId) → CloudMusicApi.playlistTracks("add", pid, musicId)。
     */
    public void openAddToPlaylist(Music music) {
        if (music != null && music.isQQ()) {
            return; // QQ 曲目不可提交到网易云歌单。
        }
        this.addToPlaylistOverlay = new AddToPlaylistOverlay(music);
        this.addToPlaylistOverlay.onInit();
    }

    /** Opens a modal confirmation without running the action until the user explicitly confirms. */
    public void openConfirmation(String title, String message, String confirmText, Runnable onConfirm) {
        this.confirmationOverlay = new ConfirmationOverlay(title, message, confirmText, onConfirm);
        this.confirmationOverlay.onInit();
    }

    public void openAccountManager() {
        if (this.accountManagerOverlay != null) this.accountManagerOverlay.dispose();
        this.accountManagerOverlay = new AccountManagerOverlay();
        this.accountManagerOverlay.onInit();
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {

        if (this.confirmationOverlay != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.confirmationOverlay.cancel();
            }
            return;
        }

        if (this.accountManagerOverlay != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.accountManagerOverlay.handleEscape();
            }
            return;
        }

        // 加入歌单弹窗为模态：仅处理 ESC，其余按键不穿透到下层面板。
        if (this.addToPlaylistOverlay != null) {
            if (keyCode == Keyboard.KEY_ESCAPE)
                this.addToPlaylistOverlay = null;
            return;
        }

        if (this.basePanel.onKeyTypedReceived(typedChar, keyCode)) {
            return;
        }

        if (this.currentPanel != null && this.currentPanel.onKeyTypedReceived(typedChar, keyCode)) {
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {

            if (this.musicLyricsPanel != null)
                this.musicLyricsPanel.close();
            else
                closing = true;

        }

        if (keyCode == Keyboard.KEY_SPACE && CloudMusic.currentlyPlaying != null && CloudMusic.player != null && !CloudMusic.player.isFinished()) {
            if (CloudMusic.player.isPausing())
                CloudMusic.player.unpause();
            else
                CloudMusic.player.pause();
        }

    }

    @Override
    public void mouseClicked(int mX, int mY, int mouseButton) {

        double xScale = RenderSystem.getWidthNotScaled() / (RenderSystem.getFixedWidth() * .5);
        double yScale = RenderSystem.getHeightNotScaled() / (RenderSystem.getFixedHeight() * .5);
        double mouseX = mX / xScale;
        double mouseY = mY / yScale;

        if (this.confirmationOverlay != null) {
            this.confirmationOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        if (this.accountManagerOverlay != null) {
            this.accountManagerOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        // 加入歌单弹窗优先捕获鼠标，阻止点击穿透到下层歌单/歌曲列表。
        if (this.addToPlaylistOverlay != null) {
            this.addToPlaylistOverlay.onMouseClickReceived(mouseX, mouseY, mouseButton);
            return;
        }

        if (musicLyricsPanel == null) {
            this.basePanel.onMouseClickReceived(mouseX, mouseY, mouseButton);

            if (this.currentPanel != null)
                this.currentPanel.onMouseClickReceived(mouseX, mouseY, mouseButton);

            this.controlsBar.onMouseClickReceived(mouseX, mouseY, mouseButton);

            // forward
            if (mouseButton == 4) {

                // is last
                if (currentActionPointer >= actions.size() - 1) {
                    currentActionPointer = actions.size() - 1;
                } else {
                    currentActionPointer ++;
                    actions.get(currentActionPointer).run();
                }

            }
            // go back
            else if (mouseButton == 3) {
                this.navigateBack();
            }

        } else {
            this.musicLyricsPanel.mouseClicked(mouseX, mouseY, mouseButton);
        }

    }

    /** 播放器主题的颜色语义；具体颜色由 {@link NCMTheme} 提供。 */
    public enum ColorType {

        GENERIC_BACKGROUND,
        ELEMENT_BACKGROUND,
        ELEMENT_HOVER,
        PRIMARY_TEXT,
        SECONDARY_TEXT,
        ACCENT,
        ACCENT_HOVER,
        INPUT_BACKGROUND,
        NAVIGATION_BACKGROUND,
        BORDER

    }

    public static int getColor(ColorType type) {
        return NCMTheme.getColor(type);
    }
}
