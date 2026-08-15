# S4 · 基础 UI 移植记录

目标（CLAUDE.md §12 S4）：全屏播放器、控制栏、封面、进度条、歌词。
约束：Java 8 · Minecraft 1.8.9 Forge · 优先原样复用，仅在不兼容时改写。

## 0. 阶段结论

- **`./gradlew compileJava` 通过**（仅 deprecation / unchecked 警告）。
- **`./gradlew build` 通过**，产出 `build/libs/deuteriummusic-1.0.2.jar`（467 个 .class + 字体/着色器/HUD/yrc/dll 资源）。
- **游戏内冒烟测试（用户执行 `./gradlew runClient`）**：Mod 正常加载、M 键成功打开 UI。首轮渲染 NPE（见 §5）已修复，复测确认 UI 正常打开、不再崩溃。

## 1. 本轮改动清单（Java 21 → 8 降级）

| 模块 | 原文件 | 分类 | 改动 | 原因 | 验证 |
|---|---|---|---|---|---|
| ScrollPanel | tritium/rendering/ui/container/ScrollPanel.java | B | 5 处 `switch(alignment){ case X -> {...} }` → `case X: {... break; }`（VERTICAL 与 VERTICAL_WITH_HORIZONTAL_FILL 均声明 `double childrenHeightSum`，需花括号） | 箭头 switch 语句为 Java 14+ | compileJava 通过 |
| NavigateBar | tritium/screens/ncm/panels/NavigateBar.java | B | `instanceof PlaylistItem item` → `instanceof PlaylistItem` + 显式强转 | instanceof 模式为 Java 16+ | compileJava 通过 |
| NavigateBar | 同上 | B | `.stream()...toList()` → `collect(Collectors.toList())` | Stream.toList 为 Java 16+ | compileJava 通过 |
| TextField | tritium/rendering/entities/impl/TextField.java | B | `"*".repeat(n)` → `new String(new char[n]).replace('\0','*')` | String.repeat 为 Java 11+ | compileJava 通过 |
| MusicLyricsWidget | tritium/widget/impl/MusicLyricsWidget.java | B | `Math.clamp(v,0,1)` → `Math.max(0, Math.min(1, v))` | Math.clamp 为 Java 21+ | compileJava 通过 |
| MultipleEndpointAnimation | tritium/rendering/animation/MultipleEndpointAnimation.java | B | `List.getFirst/getLast` → `get(0)/get(size()-1)` | SequencedCollection 为 Java 21+ | compileJava 通过 |
| LyricParser | tritium/screens/ncm/LyricParser.java | B | `words.getLast()` → `words.get(size()-1)` | 同上 | compileJava 通过 |
| FontManager / CFontRenderer / NCMScreen / MusicLyricsWidget / TextField | 对应 switch 表达式 | B | switch 表达式 → 经典 switch / if-else | switch 表达式为 Java 14+ | compileJava 通过 |

## 2. 回填原项目逻辑（S2/S3 占位 → 原样移植）

### QRCodeGenerator（分类 B，+zxing）
- 原文件：`Deuterium/.../tritium/ncm/music/QRCodeGenerator.java`
- 依赖：`com.google.zxing.*`（build.gradle 已有 zxing）、`TextureManager`/`DynamicTexture`/`Location`/`MultiThreadingUtil`（均已就位）。
- 改动：**原样复制**，无 Java 8 降级。
- 原因：LoginRenderer 引用 `QRCodeGenerator.qrCode`，S2 将其延后到 S4。

### CloudMusic 歌词层 + 登录二维码（分类 C，Forge 无直接适配，纯 Java 8 回填）
- 原文件：`Deuterium/.../tritium/ncm/music/CloudMusic.java`
- 回填内容：
  - 字段：`lyrics`/`currentLyric`/`hasTransLyrics`/`hasRomanization`/`haveNoWords`。
  - 方法：`initLyrics`、`updateLyricsList`、`detectTranslations`、`addLongBreaks`、`lyricsHaveNoWords`、
    `addBreaksBetweenLyrics`、`createBreakLine`、`addBreakLine`、`addAndSortBreaks`、`getLyricDuration`、
    `updateCurrentLyric`、`findCurrentLyric`、`canJumpToNextEarly`、`resetLyricPositionUpdate`、
    `resetLyricStatus`、`setLyricsProgress`、`resetLyricDisplayStates`、`resetAllLyricsState`、`resetWordStates`、
    `getSecondaryLyrics`、`getTranslationOrRomanizationText`、`getRomanizationTextIfEnabled`、`hasSecondaryLyrics`。
  - `loadLyric`（原 no-op → 真实实现，含 yrc 本地覆盖逻辑）。
  - `qrCodeLogin`（原缺失 → 完整实现）。
  - 恢复 `PlayThread.waitForPlaybackCompletion` 中的 `CloudMusic.updateCurrentLyric(...)` 调用。
- 改动（相对原项目，Java 8 等价）：
  - `lyrics.getFirst()` → `lyrics.get(0)`。
  - `stream.readAllBytes()` → `ByteArrayOutputStream` + 复用已有 `writeTo(...)`。
- 依赖校验（全部已就位）：`StringUtils.returnEmptyStringIfNull`、`TritiumMusicExtension.musicLyrics.showRoman/showTranslation`、
  `CloudMusicApi.lyricNew/loginQrCheck/loginQrKey`、`DynamicTexture.readImage`、`Textures.loadTextureAsyncly`（@UtilityClass 静态化）、
  `NCMScreen.loginRenderer` + `LoginRenderer.tempUsername/avatarLoaded/tempAvatar`、`LyricLine` 全字段。
- 外部行为：与原项目一致，未改变数据流/状态。

## 3. 未完成（需人工）

- **游戏内冒烟测试**（任务 #30）：启动 1.8.9 Forge 客户端 → `OpenNCMScreen` → 搜索 → 播放真实歌曲 → 验证封面/进度/歌词驱动。
- 封面纹理加载 `loadMusicCover` 仍为 S5 占位（依赖 `commons-io` 的 `IOUtils`，build.gradle 未引入，归 S5 处理）。
- HUD toast `MusicToast` 仍为 S5 占位（`notifySongStart` 用 `System.out` 兜底）。

## 4. 决策树记录（§14）

| 问题 | 决策 |
|---|---|
| `String.repeat`/`Math.clamp`/`getFirst`/`toList`/instanceof 模式/箭头 switch | 仅 Java 版本问题 → Java 8 等价改写（分类 B） |
| `QRCodeGenerator` 缺失 | 原项目有现成实现且为纯 Java 8 → 原样复制（分类 A） |
| 歌词层被 S2 拆出 | 原样回填，仅 `getFirst`/`readAllBytes` 两处 Java 8 等价（分类 B/C） |
| `commons-io`（封面 IO 用） | 封面加载归 S5，本轮不引入，避免无依据扩展依赖 |

## 5. 运行时崩溃修复（字体 NPE）

**现象**：打开 UI 后 `java.lang.NullPointerException: Rendering screen`，
`LabelWidget.onRender(LabelWidget.java:64)` → `font.drawString(...)`，`font` 字段（默认 `FontManager.pf18`）为 null。

**根因**：`TritiumMusicExtension.init(api)` 在整个 Forge 移植中**从未被调用**。
它是唯一会执行 `fontManager.init()` → `loadFonts()` 的地方；`loadFonts()` 未执行 → 所有
`FontManager.pfXX` 静态 CFontRenderer 字段保持 null → `LabelWidget` 构造时的字段初始化
`CFontRenderer font = FontManager.pf18` 捕获到 null → 渲染 NPE。
同时 `init(api)` 还负责注册 `TritiumEventHandler` 与各模块/Widget，缺失导致 HUD 与 `onTick` 均未生效。

**修复（分类 C，Forge 生命周期适配）**：在 `DeuteriumMusicMod.init()`（FMLInitializationEvent，对应原项目
`ExtensionEntry` 的加载时机）中补上 `TritiumMusicExtension.getInstance().init(OpenAPI.getInstance())`。

**验证**：`./gradlew build` 通过；用户复测 `./gradlew runClient` 后按 M 打开 UI，确认 UI 正常打开、不再崩溃。
