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
- The Forge Mod ID is `muonium`. Legacy configuration files are migrated automatically on first startup.
- Resources and configuration paths use the `muonium` / `muoniumplayer` namespace; legacy names are migrated automatically.
- Build output, game runtime data, IDE metadata, crash dumps, credentials, downloaded music, and local archival snapshots are excluded from Git.

## License notices

Third-party and Forge-related notices are retained in the repository root and in `docs/licenses/`.


----------------------------------------------------------------------------------------------------------------
# MuoniumPlayer
>本Mod已集成InputFix 不兼容中文输入相关Mod 

>此项目基于[tritium-music](https://github.com/IzumiiKonata/tritium-music)二次开发

>100%氛围编码

> ⚠️ **警告**：不支持optifine的“快速渲染”功能
[![Minecraft](https://img.shields.io/badge/Minecrft-1.8.9-green.svg)](#)
[![Forge](https://img.shields.io/badge/Forge-11.15.1.2318-orange.svg)](#)
[![Java](https://img.shields.io/badge/Java-8-blue.svg)](#)

---

## 项目简介

MuoniumPlayer是一个运行在**Minecraft 1.8.9 Forge** 环境中的独立音乐播放器Mod，支持网易云音乐和qq音乐。



# 项目状态
## 已实现

- [x] eg1
- [x] eg2
- [x] eg3

## 未来计划

- [ ] 支持手机启动器
- [ ] 后面忘了

## 感谢以下项目

- [tritium-music](https://github.com/IzumiiKonata/tritium-music) - 播放器ui框架与主体音频处理逻辑

- [cadence](https://github.com/FPSMasterTeam/Cadence) - 网易云音乐/qq音乐api支持

- [neteasecloudmusicapienhanced](https://github.com/neteasecloudmusicapienhanced/api-enhanced) - 网易云音乐api支持
