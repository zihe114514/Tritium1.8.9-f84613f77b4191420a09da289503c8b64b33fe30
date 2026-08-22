package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.utils.Location;

/**
 * Static player-control icons imported from the supplied artwork.
 *
 * <p>Resource paths intentionally stay under the historical {@code muonium}
 * root because that root is part of the installed-mod resource compatibility
 * contract.</p>
 *
 * <p><b>必须带前导斜杠。</b>{@link Location#getResourceStream()} 用的是
 * {@code Location.class.getResourceAsStream(path)}，不带斜杠会被当成相对
 * {@code com/muoniumplayer/core/utils/} 的路径解析，于是这七张图一张都找不到：
 * 每个 {@code ThemedTextureIconWidget} 每帧都会抛一次 {@code MissingResourceException}
 * 并静默退回字体兜底字形。那些兜底字形与相邻的图标字体不同形状、也不同大小
 * （歌曲行的"收藏"就因此比旁边两枚小了约三成），看上去就是右侧控件对不齐。
 * 项目里另外两处真实的 classpath 贴图（{@code MusicToast} 的 hud 图、着色器）
 * 一直是带斜杠写的；{@code muonium/textures/album|music|playlist|user/...} 那些
 * 不带斜杠的 Location 只是网络封面的缓存键，从不读 classpath，所以没暴露这个问题。</p>
 */
public final class PlayerIconAssets {

    public static final Location FAVORITE = Location.of("/muonium/textures/player/icons/favorite.png");
    public static final Location PLAYLIST = Location.of("/muonium/textures/player/icons/playlist.png");
    public static final Location HISTORY = Location.of("/muonium/textures/player/icons/history.png");
    public static final Location PERSONAL_FM = Location.of("/muonium/textures/player/icons/personal-fm.png");
    public static final Location PLAY_MODE_SEQUENTIAL = Location.of("/muonium/textures/player/icons/play-mode-sequential.png");
    public static final Location PLAY_MODE_RANDOM = Location.of("/muonium/textures/player/icons/play-mode-random.png");
    public static final Location PLAY_MODE_SINGLE_LOOP = Location.of("/muonium/textures/player/icons/play-mode-single-loop.png");

    private PlayerIconAssets() {
    }

    /**
     * Returns {@code null} for legacy modes without supplied artwork.  The
     * caller then keeps its established icon-font fallback instead of mapping
     * a legacy state to the wrong visual icon.
     */
    public static Location forPlayMode(CloudMusic.PlayMode mode) {
        if (mode == null) {
            return null;
        }
        switch (mode) {
            case Sequential:
                return PLAY_MODE_SEQUENTIAL;
            case Random:
                return PLAY_MODE_RANDOM;
            case LoopSingle:
                return PLAY_MODE_SINGLE_LOOP;
            case LoopInList:
            default:
                return null;
        }
    }
}
