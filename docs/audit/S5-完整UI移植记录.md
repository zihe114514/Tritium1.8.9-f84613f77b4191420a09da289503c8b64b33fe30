# S5 · 完整 UI 移植记录

目标（CLAUDE.md §12 S5）：搜索、歌单、Coverflow、动画、HUD、频谱等按原项目逐项恢复。
约束：Java 8 · Minecraft 1.8.9 Forge · 优先原样复用，仅在不兼容时改写。

## 0. 阶段结论

- 搜索 / 歌单 / Coverflow / 动画 / 频谱等 UI 逻辑在 S4 已随源码树整体移植（`./gradlew build` 467 类），
  本轮审计未发现除下述两项外的 tritium 侧占位。
- 本轮回填 S4 延后的两项（封面纹理加载、HUD toast），并确认 HUD Widget 渲染坐标系正确。
- **`./gradlew build` 通过**；游戏内运行验证待用户复测（真实播放时封面/模糊/HUD toast 联动）。

## 1. 本轮改动清单

### 1.1 loadMusicCover 封面加载（分类 C，原样回填）
- 原文件：`Deuterium/.../tritium/ncm/music/CloudMusic.java`（`loadMusicCover` / `shouldLoadCover` /
  `loadMainCoverAsync` / `loadCoverTextures` / `loadSmallCoverAsync` / `gaussianBlur` + `GAUSSIAN_KERNEL`）。
- 原依赖（均已就位）：`IOUtils`(commons-io)、`GaussianKernel.generate`、`TextureManager.getInstance/getTexture`、
  `Textures.loadTexture`、`DynamicTexture.readImage`、`HttpUtils.downloadStream`、`Location`、
  `Music.getCoverUrl/getCoverLocation/getBlurredCoverLocation/getSmallCoverLocation`、`MultiThreadingUtil.runAsync`、
  `java.awt.image.ConvolveOp/Kernel`、`java.awt.RenderingHints/Graphics2D`。
- 改动：**原样回填**。仅把原文件 `java.awt.*` / `java.io.*` 通配 import 改为显式 import（与 CloudMusic.java 现有风格一致，行为不变）。
  - commons-io `IOUtils` 无需新增 build.gradle 依赖：1.8.9 Forge 的 `minecraft` 配置已将 commons-io 2.4 暴露在编译/运行类路径。
- 外部行为：与原项目一致——封面下载(320) → 注册主纹理 → 异步高斯模糊(41×41 kernel, blur 31) → 注册模糊纹理；
  小封面(128) 独立异步加载。
- 验证：`./gradlew build` 通过；待真实播放复测封面/歌词界面模糊背景。

### 1.2 notifySongStart → MusicToast（分类 A，恢复原调用）
- 原文件：同上，`notifySongStart(Music)`。
- 改动：将 S3 的 `System.out.printf` 占位恢复为 `MusicToast.pushMusicToast(song.getArtistsName() + " - " + song.getName());`。
  - `MusicToast` 已带 Lombok `@UtilityClass`，`pushMusicToast` 为静态，调用方式与原项目一致。
- 验证：`./gradlew build` 通过。

### 1.3 HUD Widget 渲染坐标系（分类 C，仅更新注释，无代码行为变化）
- 原文件：`com/deuterium/music/ClientEventHandler.java`（`onRenderOverlay`）。
- 结论：现有渲染循环正确，无需改代码。
  - `EventRender2D(WindowResolutionImpl=ScaledResolution)` 每帧驱动 `onRender2D`，`RenderSystem` 的静态
    EventHandler 从中捕获 `width/height/scaleFactor`（= GUI 缩放坐标）。
  - `widget.render()` 在 `RenderGameOverlayEvent.Post`（GUI 缩放坐标空间）绘制，二者坐标系一致。
  - `renderPredicate = module.isEnabled()`，HUD 模块默认关闭（与原项目一致），故默认不渲染。
- 改动：删除陈旧的 `TODO(S5)` 注释，替换为上述坐标系统说明。
- 未处理（记录限制）：HUD Widget 位置默认 (0,0)。原项目由 Opai 客户端 HUD 编辑器/拖拽系统设置并持久化
  `ExtensionWidget.x/y/width/height`；独立 Mod 无该宿主编辑器，等价于按键绑定的 C 类适配尚未做。默认模块关闭，
  不影响当前播放器主界面。

### 1.4 实装歌曲信息/歌词 HUD（分类 C，2026-08-14 完成）

- 模块：歌曲信息 HUD（`MusicInfoWidget`）/ 歌词 HUD（`MusicLyricsWidget`）。
- 原文件：`tritium/widget/impl/MusicInfoWidget.java`、`MusicLyricsWidget.java`（与原始 1:1，未改）。
- 原依赖：Opai 客户端 ClickGUI（模块开关）+ HUD 编辑器（拖拽定位）。
- 原行为：两模块默认关闭，由用户经 ClickGUI 开启、经 HUD 编辑器拖到任意位置并持久化。
- 改动（C：Forge 适配，仿 `OPEN_MUSIC_PLAYER` 的既有 §14 C 模式）：
  1. `DeuteriumMusicMod` 新增 `TOGGLE_MUSIC_INFO`（默认 I）/ `TOGGLE_LYRICS`（默认 L）两个 KeyBinding 并注册。
  2. `ClientEventHandler.onKeyInput` 响应两键，`setEnabled(!isEnabled())` 切换。
  3. `TritiumMusicExtension.init` 中 `musicInfo`/`musicLyrics` 默认 `setEnabled(true)`，并调用
     `setupDefaultWidgetPositions()`：歌曲信息卡片置左上角 (8,8)，歌词按 `ScaledResolution` 水平居中、靠近底部
     （分辨率未就绪时退化为固定位置）。
  4. 连带修复 `CloudMusic`：恢复被 S3 砍掉的下载进度链 `NCMScreen.getInstance().downloading/downloadProgress/downloadSpeed`
     链式赋值（5 处），以及 `loadNCM` 里被注释的 `NCMScreen.getInstance().markDirty()`。
- 改动原因：独立 Mod 无 ClickGUI / HUD 编辑器，HUD 模块默认关闭则永远无法显示，属「实装」缺口；按键绑定 + 默认开启 +
  固定默认位置是最小等价适配。
- 是否改变外部行为：是——独立 Mod 中 HUD 默认可见（原项目默认隐藏，需手动开启）。此为独立 Mod 无宿主 GUI 的必要等价
  适配，用户可经 I/L 键关闭；全屏播放器主界面行为不变。
- 验证：`./gradlew compileJava --offline` 通过。

## 2. 未完成（待人工/后续）

- 游戏内复测：真实播放时封面、歌词界面高斯模糊背景、切歌 HUD toast 的联动。
- ~~HUD Widget 默认位置适配~~（已在 §1.4 完成）。
