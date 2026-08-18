package tritium.settings;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Central registry for persisted player paths.
 *
 * <p>The filenames deliberately retain their historic Tritium/Deuterium values for
 * compatibility with existing installations. A later migration can add new paths
 * and dual-read behavior here without spreading path changes across UI code.</p>
 */
public final class ConfigPaths {

    public static final File HUD = new File("deuteriummusic_hud.json");
    public static final File PLAYER = new File("tritium_player_config.json");
    public static final File THEME = new File("tritium_player_theme.json");
    public static final Path MUSIC_AUTH = Paths.get("config", "tritium", "music_auth.json");

    private ConfigPaths() {
    }
}
