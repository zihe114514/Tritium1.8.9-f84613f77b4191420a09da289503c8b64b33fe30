package tritium;

import lombok.Getter;
import today.opai.api.OpenAPI;
import tritium.management.AbstractManager;
import tritium.management.FontManager;
import tritium.module.impl.OpenNCMScreen;
import tritium.ncm.music.CloudMusic;
import tritium.reflection.Reflection;
import tritium.rendering.Framebuffer;
import tritium.rendering.OpenGlHelper;
import tritium.settings.HudConfig;
import tritium.screens.ncm.NCMTheme;
import tritium.screens.ncm.NCMPlayerConfig;
import tritium.utils.other.multithreading.MultiThreadingUtil;
import tritium.widget.impl.MusicInfoWidget;
import tritium.widget.impl.MusicLyricsWidget;

import java.util.Arrays;
import java.util.List;

/**
 * @author IzumiiKonata
 * Date: 2026/3/31 22:28
 */
public class MuoniumPlayerExtension {

    public static final String NAME = "MuoniumPlayer";
    public static final String AUTHOR = "IzumiiKonata";
    public static final String VERSION = "1.0.0";

    @Getter
    private static final MuoniumPlayerExtension instance = new MuoniumPlayerExtension();

    @Getter
    private FontManager fontManager;

    public OpenNCMScreen tritiumMusic =  new OpenNCMScreen();
    public MusicInfoWidget musicInfo = new MusicInfoWidget();
    public MusicLyricsWidget musicLyrics = new MusicLyricsWidget();

    public MuoniumPlayerExtension() {

    }

    public void init(OpenAPI api) {
        api.registerEvent(MuoniumPlayerEventHandler.getInstance());

        // Load the persisted quality before the asynchronous account bootstrap can resolve a URL.
        NCMPlayerConfig.load();
        CloudMusic.quality = NCMPlayerConfig.getAudioQuality();
        MultiThreadingUtil.runAsync(CloudMusic::initNCM);
        Reflection.init(api);
//        Framebuffer.updateMcFramebuffer();

        this.fontManager = new FontManager();

        List<AbstractManager> managers = Arrays.asList(this.fontManager);

        for (AbstractManager manager : managers) {
//            logger.debug("calling init() on {}...", manager.getName());
            manager.init();
        }

        api.registerFeature(this.tritiumMusic);
        api.registerFeature(this.musicInfo);
        api.registerFeature(this.musicInfo.widget);
        api.registerFeature(this.musicLyrics);
        api.registerFeature(this.musicLyrics.widget);

        // 歌曲信息 HUD / 歌词 HUD：默认开启，位置与缩放由 HudConfig 归一化字段决定。
        // 原项目这两个模块默认关闭、靠 Opai ClickGUI 手动开启并拖动定位；独立 Mod 无 ClickGUI，
        // 故默认开启 + 加载 HudConfig 持久化位置，并提供快捷键开关（TOGGLE_MUSIC_INFO / TOGGLE_LYRICS）
        // 与 HUD 编辑器（EDIT_HUD，参考 cloudmusic/mod/gui/GuiOverlayEditor）。
        this.musicInfo.setEnabled(true);
        this.musicLyrics.setEnabled(true);
        HudConfig.load();
        NCMTheme.load();
        this.musicLyrics.loadHudEditorSettings();
    }

    public void unload() {

    }

    public static boolean isCallingFromMainThread() {
        return Thread.currentThread().getName().equals("Client thread");
    }

}
