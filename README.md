# MuoniumPlayer

MuoniumPlayer is a **Minecraft 1.8.9 Forge** music-player mod with NetEase Cloud Music integration, in-game player controls, full-screen lyrics, desktop lyrics/HUD, dynamic-island notifications, playlist management, and configurable themes.

## Requirements

- Minecraft **1.8.9**
- Forge **11.15.1.2318**
- Java **8**

## Build

On Windows:

```bat
gradlew.bat build --no-daemon
```

The development artifact is generated at:

```text
build/libs/MuoniumPlayer-1.0.0-dev.jar
```

## Development notes

- The published project name is **MuoniumPlayer**.
- The Forge Mod ID remains `deuteriummusic` for existing installations and configuration compatibility.
- Historical configuration file names and the `tritium` resource namespace are intentionally retained so users do not lose settings or bundled assets after upgrading.
- Build output, game runtime data, IDE metadata, crash dumps, credentials, downloaded music, and local archival snapshots are excluded from Git.

## License notices

Third-party and Forge-related notices are retained in the repository root and in `docs/licenses/`.
