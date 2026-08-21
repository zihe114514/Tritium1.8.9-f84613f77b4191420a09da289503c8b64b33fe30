package com.muoniumplayer.core.settings;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Central registry for MuoniumPlayer persisted paths.
 *
 * <p>All newly written files use the MuoniumPlayer names. On first startup after
 * the rename, known legacy Deuterium/Tritium files are moved to their new names
 * when the new target does not already exist, preserving existing user settings.</p>
 */
public final class ConfigPaths {

    public static final File HUD = new File("muoniumplayer_hud.json");
    public static final File PLAYER = new File("muoniumplayer_config.json");
    public static final File THEME = new File("muoniumplayer_theme.json");
    public static final Path MUSIC_AUTH = Paths.get("config", "muonium", "music_auth.json");
    public static final File NETEASE_ACCOUNTS = new File("config", "muonium/netease_accounts.json");

    /** User-selected GD Studio aggregated platform (netease/joox/...), persisted separately. */
    public static final File GD_SOURCE = new File("config", "muonium/gd_source.json");

    static {
        migrateLegacyFiles();
    }

    private ConfigPaths() {
    }

    /**
     * Migrates only when the new file is absent. Existing new files always win,
     * so a user can safely keep backups of the old configuration beside them.
     */
    private static void migrateLegacyFiles() {
        migrate(new File("deuteriummusic_hud.json"), HUD);
        migrate(new File("tritium_player_config.json"), PLAYER);
        migrate(new File("tritium_player_theme.json"), THEME);
        // Also migrate the intermediate Muonium names created by development builds.
        migrate(new File("muonium_hud.json"), HUD);
        migrate(new File("muonium_player_config.json"), PLAYER);
        migrate(new File("muonium_player_theme.json"), THEME);

        migrate(Paths.get("config", "tritium", "music_auth.json").toFile(), MUSIC_AUTH.toFile());
        migrate(Paths.get("config", "tritium", "netease_accounts.json").toFile(), NETEASE_ACCOUNTS);
    }

    private static void migrate(File legacy, File target) {
        if (legacy == null || target == null || !legacy.isFile() || target.exists()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            return;
        }
        try {
            Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            try {
                Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // A read-only/locked legacy file should not prevent the mod from loading.
            }
        }
    }
}
