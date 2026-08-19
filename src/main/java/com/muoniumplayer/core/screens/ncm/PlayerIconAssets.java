package com.muoniumplayer.core.screens.ncm;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.utils.Location;

/**
 * Static player-control icons imported from the supplied artwork.
 *
 * <p>Resource paths intentionally stay under the historical {@code tritium}
 * root because that root is part of the installed-mod resource compatibility
 * contract.</p>
 */
public final class PlayerIconAssets {

    public static final Location FAVORITE = Location.of("tritium/textures/player/icons/favorite.png");
    public static final Location PLAYLIST = Location.of("tritium/textures/player/icons/playlist.png");
    public static final Location HISTORY = Location.of("tritium/textures/player/icons/history.png");
    public static final Location PERSONAL_FM = Location.of("tritium/textures/player/icons/personal-fm.png");
    public static final Location PLAY_MODE_SEQUENTIAL = Location.of("tritium/textures/player/icons/play-mode-sequential.png");
    public static final Location PLAY_MODE_RANDOM = Location.of("tritium/textures/player/icons/play-mode-random.png");
    public static final Location PLAY_MODE_SINGLE_LOOP = Location.of("tritium/textures/player/icons/play-mode-single-loop.png");

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
