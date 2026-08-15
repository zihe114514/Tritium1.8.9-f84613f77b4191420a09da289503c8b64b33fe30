# 播放器 UI 层审计（S0）：tritium.screens.ncm + tritium.widget + 渲染依赖

> 范围：`tritium/screens/ncm/**`、`tritium/widget/**`（含 impl）、以及它们实际 import 的 `tritium/rendering/**` 与 `tritium/utils/**` 支撑类。
> 结论先行：**UI 页面状态机、布局、交互、歌词逻辑全部可直接复用（A 类）；被 `today.opai.api` 包裹的部分集中在一个很小的面（GLStateManager + ValueManager + displayScreen/registerFeature/registerEvent + WindowResolution/Font），shim 规模 ≈ 7 个接口 + 20 个 GL 方法 + 4 种 Value 类型。**

---

## 1. 逐文件表

分类定义沿用项目约定：A 直接复用 / B Java8 兼容 / C Forge 适配 / D 必须重写 / E 删除。

| 文件 | 分类 | 职责 | 依赖的其他 tritium 类 |
|---|---|---|---|
| `screens/ncm/NCMScreen.java` | **C**（屏幕骨架，extends ExtensionScreen → 改 extends GuiScreen） | 播放器主全屏 GUI：布局 basePanel/导航栏/控制栏/当前面板/歌词面板/登录浮层/下载浮层；页面切换动画（prevAnimatingPanel + StencilClip + scale）；前进/后退导航栈（actions 列表 + 鼠标侧键）；ESC/空格键处理 | ExtensionScreen, Panel, RectWidget, NavigateBar, ControlsBar, HomePanel, MusicLyricsPanel, LoginRenderer, RenderSystem, Rect, StencilClipManager, Interpolations, FontManager, CursorUtils, MultiThreadingUtil, CloudMusic, OptionsUtil |
| `screens/ncm/NCMPanel.java` | **A**（纯抽象基类，仅 extends Panel） | 所有页面面板基类，抽象 `onInit()`；提供 `getColor(ColorType)` | Panel, NCMScreen |
| `screens/ncm/MusicLyricsPanel.java` | **B/C**（B: 语法；C: 逐字歌词逐字裁剪用 GLStateManager+投影矩阵+FBO） | 全屏歌词页：封面放大/背景模糊/低频频谱缩放、逐字歌词(.yrc)渲染（stencil FBO + STENCIL shader）、滚动/点击 seek、进度条/音量条拖拽、播放/上一首/下一首图标 | CloudMusic, AudioPlayer, Music, LyricLine, LyricParser, Framebuffer, Image, Rect, RGBA, StencilClipManager, Shaders, ShaderProgram, RenderSystem, TextureManager, ITextureObject, ScrollText, IconWidget, Easing, Interpolations, SpringAnimation, FontManager, TritiumMusicExtension, MusicLyricsWidget, ClientSettings, HttpUtils, MultiThreadingUtil, Timer, Mth, CursorUtils |
| `screens/ncm/LyricLine.java` | **A**（纯数据 + 布局，无 api 依赖） | 一行歌词数据模型：timestamp/lyric/translation/romanization/words(逐字)/posY/height/spring 动画；`computeHeight(width)` 换行高度计算 | FontManager(CFontRenderer), SpringAnimation, SpringParams, Timer |
| `screens/ncm/LyricParser.java` | **A/B**（B: `getLast()` Java21 → `get(size-1)`） | 歌词解析：普通 LRC（多时间戳行）→ `parse()`；逐字 .yrc `(start,dur,0)text` 正则 → `parseYrc()`；翻译/罗马音按时间戳合并 | Tuple, gson JsonObject |
| `screens/ncm/CoverflowOverlay.java` | **C**（extends ExtensionScreen；gluPerspective + GL11 深度缓冲 3D 封面流） | 封面流：3D 透视投影旋转封面、滚轮/左右键翻页、封面翻转显示歌单曲目列表、Ctrl+F 搜索框、点击播放 | ExtensionScreen, CloudMusic, Album/Music/PlayList, TextField, CFontRenderer, RenderSystem, Image, Rect, RGBA, StencilClipManager, Shaders, TextureManager, Textures, ITextureObject, KeyboardUtils, MultiThreadingUtil, Timer, glu.Project |
| `screens/ncm/LoginRenderer.java` | **B/C**（B: 无；C: api.color/bindTexture → GlStateManager） | 登录浮层：扫码提示 + 二维码纹理 + 临时头像/用户名；后台线程跑 `CloudMusic.qrCodeLogin()` | CloudMusic, QRCodeGenerator, OptionsUtil, TextureManager, Image, Rect, RenderSystem, ITextureObject, FontManager, Interpolations, Location |
| `screens/ncm/panels/ControlsBar.java` | **A**（纯 widget 树 + 回调，零 api 直调） | 底部控制栏：封面缩略图(点击开歌词页)、播放/暂停、上一首/下一首、可点击 Seek 进度条(RoundedRectWidget 子类)、当前/剩余时间、歌名/歌手标签 | NCMPanel, MusicLyricsPanel, NCMScreen, CloudMusic, AudioPlayer, FontManager, MusicLyricsWidget, RectWidget/RoundedImageWidget/RoundedRectWidget/LabelWidget/IconWidget |
| `screens/ncm/panels/HomePanel.java` | **A/B**（B: 无；`recommendResource` 是 API 侧） | 首页：欢迎语 + 推荐歌单 ScrollPanel；每个 PlaylistWidget(内部类) 封面 + 名称，点击进入 PlaylistPanel | NCMPanel, CloudMusicApi, PlayList, ScrollPanel, AbstractWidget, LabelWidget, RoundedImageWidget, Textures, TextureManager, FontManager, JsonUtils, MultiThreadingUtil, Location |
| `screens/ncm/panels/MusicWidget.java` | **A**（纯 widget，用 `EnumChatColor.GRAY` 字符串） | 歌单内单曲行：封面、序号、歌名(+译名)、歌手-专辑、时长、dirty 标记、正在播放/悬停高亮，点击播放 | RoundedRectWidget, CloudMusic, Music, PlayList, Textures, TextureManager, LabelWidget, RoundedImageWidget, RoundedRectWidget, EnumChatColor |
| `screens/ncm/panels/NavigateBar.java` | **B**（`instanceof item` 模式匹配 + `Stream.toList()` 需降级） | 左侧导航栏：搜索框(Ctrl+F)、主页项、我的歌单/收藏歌单分组、PlaylistItem(内部类)、创建者头像/名称 | NCMPanel, CloudMusic, Music, PlayList, TextFieldWidget, ScrollPanel, Panel, Textures, TextureManager, KeyboardUtils, JsonUtils, MultiThreadingUtil, FontManager, RenderSystem |
| `screens/ncm/panels/PlaylistPanel.java` | **A/C**（C: `api.displayScreen(CoverflowOverlay)`） | 歌单详情页：封面、播放/乱序播放/Coverflow 按钮、创建者、歌单名/信息、曲目 ScrollPanel(MusicWidget 列表)、歌单内搜索过滤 | NCMPanel, CoverflowOverlay, CloudMusic, Music, PlayList, TextFieldWidget, ScrollPanel, Panel, Textures, TextureManager, FontManager, RenderSystem, KeyboardUtils, RoundedButtonWidget 等 |
| `widget/impl/MusicInfoWidget.java` | **C**（HUD：extends ExtensionModule/ExtensionWidget + ValueManager + shaderUtil） | HUD 悬浮组件：封面/模糊背景/歌名/歌手(或歌词)/进度条/时间/下载进度 | ExtensionModule, ExtensionWidget, EventHandler, BooleanValue, NumberValue, WidgetWrapper, CloudMusic, LyricLine, ScrollText, CFontRenderer, Rect, RGBA, StencilClipManager, TextureManager, ITextureObject, Interpolations |
| `widget/impl/MusicLyricsWidget.java` | **C**（HUD 歌词：ValueManager 多个配置项 + 逐字滚动/渐变/滑入） | HUD 悬浮歌词：Scroll/FadeIn/SlideIn 三种逐字效果、Left/Center/Right 对齐、优雅滚动、翻译/罗马音、单行模式、动态岛(System.setProperty) | ExtensionModule, ExtensionWidget, EventHandler, BooleanValue/ModeValue/NumberValue, WidgetWrapper, CloudMusic, LyricLine, CFontRenderer, Rect, RGBA, StencilClipManager, Easing, Interpolations, Mth, Reflection, ClientSettings, TritiumMusicExtension |
| `widget/impl/MusicSpectrumWidget.java` | **C**（HUD 频谱：GL11 顶点 + api.color） | HUD 频谱：AudioPlayer.bandValues → 直方条 + 峰值指示器；直接 `GL11.glBegin(TRIANGLES)` | ExtensionModule, ExtensionWidget, EventHandler, BooleanValue/ColorValue/NumberValue, WidgetWrapper, CloudMusic, AudioPlayer, Interpolations, RenderSystem, TritiumMusicExtension |
| `widget/impl/SpectrumVisualizer.java` | **A**（纯数学，无 api/LWJGL） | 频谱分带（Bark 频段）+ 归一化：sampleRate/fftSize/numBands → bandMagnitudes | TritiumMusicExtension |

---

## 2. `today.opai.api` 耦合面（重点）

### 2.1 入口/生命周期类型（被 extends/import 的类）

| today.opai.api 类型 | 提供/被覆写 | 子类如何使用 |
|---|---|---|
| `ExtensionScreen` | 覆写 `initGui()`、`onGuiClosed()`、`drawScreen(int,int)`、`keyTyped(char,int)`、`mouseClicked(int,int,int)` | `NCMScreen`/`CoverflowOverlay` extends 它；签名与 MC 1.8.9 `GuiScreen` **完全一致**，直接换基类即可 |
| `ExtensionModule` | 构造 `super(name, desc, EnumModuleCategory)`；覆写 `onTick()`/`onEnabled()`；调用 `addValues(...)`、`setEventHandler(EventHandler)`、`isEnabled()`、`getName()`、`setEnabled(bool)` | `OpenNCMScreen`(模块，onEnabled→打开 GUI)、`MusicInfoWidget`/`MusicLyricsWidget`/`MusicSpectrumWidget`(HUD) |
| `ExtensionWidget` | 构造 `new ExtensionWidget(name)`；覆写 `render()`、`renderPredicate()`；提供 `getX/getY/setX/setY/getWidth/getHeight/setWidth/setHeight` | `WidgetWrapper.createWrapper(module, render)` 内建一个匿名 ExtensionWidget 包住 HUD 的 onRender；PosSizeInterface 转发到 widget 的 x/y/width/height |
| `Extension`（`@ExtensionInfo` + `initialize(OpenAPI)`/`onUnload()`） | 扩展入口 | `ExtensionEntry` |
| `EventHandler` 接口 | 覆写 `onRender2D(EventRender2D)`、`onLoop()` | `TritiumEventHandler`、`RenderSystem`(匿名)、`Reflection` |
| `EventRender2D` | `getWindowResolution()` | 取出 `WindowResolution` → `getScaleFactor()/getWidth()/getHeight()`（等价 MC `ScaledResolution`） |
| 枚举 | `EnumModuleCategory.VISUAL/MISC`、`EnumChatColor.WHITE/GRAY` | 构造参数/文本染色 |

### 2.2 `api` 字段（`OpenAPI`，经 `SharedConstants.api = ExtensionEntry.getAPI()` 静态注入）的实际调用点

| OpenAPI 方法 | 调用点 | Forge 1.8.9 等价 |
|---|---|---|
| `api.registerEvent(EventHandler)` | TritiumMusicExtension.init、RenderSystem 静态块、Reflection.init | `MinecraftForge.EVENT_BUS.register(...)` |
| `api.registerFeature(ExtensionModule)` | TritiumMusicExtension.init（6 次：3 模块 + 3 widget） | 自建 feature 表 / 直接持有引用 |
| `api.displayScreen(ExtensionScreen)` / `api.displayScreen(null)` | NCMScreen(closing→null)、CoverflowOverlay(关闭→NCMScreen、ESC→NCMScreen)、OpenNCMScreen.onEnabled、PlaylistPanel(→CoverflowOverlay) | `Minecraft.getMinecraft().displayGuiScreen(...)` |
| `api.getValueManager().createBoolean(name, default)` | MusicInfoWidget / MusicLyricsWidget / MusicSpectrumWidget / OpenNCMScreen | 自建 Value 工厂 |
| `api.getValueManager().createDouble(name, default, min, max, inc)` | 同上 | 同上 |
| `api.getValueManager().createModes(name, default, String[])` | MusicLyricsWidget、OpenNCMScreen | 同上 |
| `api.getValueManager().createColor(name, Color)` | MusicSpectrumWidget | 同上 |
| `api.getShaderUtil().drawWithBloom(Runnable)` | MusicInfoWidget（HUD 外发光） | 委托 tritium 自己的 `Shaders.BLOOM_SHADER.run(...)` |
| `api.getFontUtil().getVanillaFont()` | MusicToast（唯一使用点，返回 `today.opai.api.interfaces.render.Font`） | `Minecraft.fontRendererObj` 薄封装 |

### 2.3 `GLStateManager`（`api.getGLStateManager()`，渲染层最核心 shim）

以下方法在 UI/渲染代码中被实际调用（含参数），Forge 里需逐一映射到 `net.minecraft.client.renderer.GlStateManager`：

- 矩阵：`pushMatrix()`、`popMatrix()`、`scale(double x,double y,double z)`、`translate(double x,double y,double z)`、`rotate(float angle,float x,float y,float z)`、`matrixMode(int)`、`loadIdentity()`、`ortho(l,r,b,t,near,far)`
- 颜色/混合：`color(float r,float g,float b,float a)`、`enableBlend()`、`disableBlend()`、`tryBlendFuncSeparate(sf,df,sfA,dfA)`、`shadeModel(int)`、`enableColorMaterial()`、`disableAlpha()`、`enableAlpha()`、`alphaFunc(func,ref)`、`colorMask(r,g,b,a)`
- 纹理：`enableTexture2D()`、`disableTexture2D()`、`bindTexture(int)`、`generateTexture()`、`deleteTexture(int)`、`isTexture2DEnabled()`（仅注释）
- 深度/其他：`enableDepth()`、`disableDepth()`、`depthMask(boolean)`、`clear(int)`、`clearColor(r,g,b,a)`、`clearDepth(double)`、`disableLighting()`、`viewport(x,y,w,h)`、`callList(int)`

> 全部在 MC 1.8.9 `GlStateManager` 有一一对应（`tryBlendFuncSeparate`、`disableAlpha/enableAlpha`、`enableColorMaterial`、`shadeModel`、`colorMask`、`alphaFunc` 均为 1.8.9 已有方法名）。**这层是纯薄封装，不需要改任何 UI 调用点。**

### 2.4 值类型读写（Value 类型）

| Value 类型 | 读 | 写 | 其他 |
|---|---|---|---|
| `BooleanValue` | `getValue()` | — | `setValueCallback(b -> ...)`、`setHiddenPredicate(() -> bool)` |
| `NumberValue`（double） | `getValue()` | `setValue(double)`（如音量 `volume.setValue(percent)`） | `setValueCallback`、`setHiddenPredicate` |
| `ModeValue` | `getValue()`（返回 String） | — | 构造时给 String[] 选项 |
| `ColorValue` | `getValue()`（返回 `java.awt.Color`）、`.getRGB()` | — | `setAlphaAllowed(true)` |

### 2.5 关键结论

**UI 文件对 `today.opai.api` 的耦合只有 3 类**：① `extends ExtensionScreen/ExtensionModule/ExtensionWidget`（改基类）；② `api.getGLStateManager()`（薄封装）；③ `api.getValueManager()/displayScreen/registerFeature/registerEvent/getFontUtil/getShaderUtil`（各 1 处薄封装）。页面状态机、widget 树、布局算法、动画、歌词逻辑**不含任何 api 调用**。

---

## 3. UI 结构与交互

### 3.1 NCMScreen 页面/浮层结构

`NCMScreen`（单例）绘制顺序（`drawScreen` 内）：
1. **basePanel**：根容器（含 NavigateBar + currentPanelBg + ControlsBar），带整体 alpha 淡入 + `scaleAtPos(中心, 0.9+alpha*0.1)` 缩放。
2. **NavigateBar**（`playlistsPanel`）：左侧 15% 宽导航栏。
3. **currentPanelBg**：当前页面背景矩形（右侧 85% 宽 × 高 93%）。
4. **currentPanel**（`NCMPanel`）：当前页面，在 currentPanelBg 范围内 StencilClip 裁剪 + 缩放动画（1.1→1.0）；切换时 `prevAnimatingPanel` 淡出。
5. **ControlsBar**：底部控制栏。
6. **MusicLyricsPanel**（浮层，非 Panel，全屏覆盖，单独 `onRender`）：点封面/封面缩略图打开；ESC 关闭。
7. **LoginRenderer**（浮层）：未登录时（`OptionsUtil.getCookie().isEmpty()`）显示扫码登录。
8. **下载浮层**（`renderDownloadingPanel`）：顶部居中 "Downloading..." + 进度条。

### 3.2 页面切换机制

- `setCurrentPanel(NCMPanel)` → 维护一个 `actions` 历史栈 + `currentActionPointer`（**前进/后退**），鼠标侧键 button4=前进、button3=后退。
- 面板类型：`HomePanel`（首页/推荐歌单）、`PlaylistPanel`（歌单详情 + 搜索模式）、`NavigateBar.PlaylistItem` 点击 → 进入对应 PlaylistPanel。
- 转场：`innerSetCurrentPanel` 把旧面板设为 `prevAnimatingPanel`（淡出），新面板 `curPanelAlphaAnimation` 从 0 淡入 + 缩放。
- 跨屏：`CoverflowOverlay` 是**独立 ExtensionScreen**，从 PlaylistPanel 的 "Coverflow" 按钮 `api.displayScreen(...)` 打开，ESC/关闭回到 NCMScreen。

### 3.3 交互细节

- **键盘**（NCMScreen.keyTyped）：先派发 basePanel → currentPanel；ESC=关歌词页/关 GUI；空格=播放/暂停。**键盘自动重复**（`Keyboard.enableRepeatEvents`）。
- **鼠标**（mouseClicked）：派发 basePanel → currentPanel → controlsBar；侧键前进/后退；滚轮在 drawScreen 里读 `Mouse.getDWheel()` 传给各面板。
- **搜索**：NavigateBar 搜索框 Ctrl+F 聚焦，回车 → 新建 `PlayList(searchMode=true)` 的 PlaylistPanel，后台 `CloudMusic.search()`，结果回填。PlaylistPanel 内还有歌单内搜索（Ctrl+G）。
- **Coverflow 内搜索**：Ctrl+F 聚焦 TextField，实时过滤专辑/歌曲名/译名。

### 3.4 ControlsBar（控制栏）按钮与交互

- `RoundedImageWidget` 封面缩略图 → 点击打开 `MusicLyricsPanel`。
- `IconWidget` 播放/暂停（图标 B↔A，随 `CloudMusic.player.isPausing()` 切换）、上一首(H)、下一首(E)。
- `RoundedRectWidget` 进度条：`onRender` 里点击/拖动 → `player.setPlaybackTime(percent*total)` 并 `MusicLyricsWidget.resetProgress` + `MusicLyricsPanel.resetProgress`。
- 两个 `LabelWidget` 显示当前时间 / `-剩余时间`、歌名、歌手 - 专辑名。

### 3.5 歌词（LyricParser / MusicLyricsPanel / LyricLine）

- **普通歌词（LRC）**：`LyricParser.parse(JsonObject)` 读 `lrc.lyric` 字符串，`parseLine` 用正则 `(\[[mm:ss.xx]+\])(text)`（兼容 `mm:ss:xxx` 形式）拆多时间戳行，按 timestamp 排序；`tlyric`/`romalrc` 翻译/罗马音按 timestamp 映射合并。
- **逐字歌词（.yrc）**：`parseYrc` 读 `yrc.lyric`，每行 `[start,dur]` + `(start,dur,0)word` 正则 `parseWordTimings` 生成 `LyricLine.Word[]`（含 per-word `emphasizes[]`），`lyric` 由 words 拼接。
- **渲染**（MusicLyricsPanel.renderLyrics）：逐行 `computeHeight(width)` 换行 + `SpringAnimation` 定位 + `Interpolations` 平滑滚动；当前行逐字用**两个 FBO**（stencil FBO 画进度遮罩 + base FBO 画文字）+ `Shaders.STENCIL.draw(base, stencil, ...)` 做逐字进度裁剪；点击某行 → `player.setPlaybackTime(timestamp)`；滚轮偏移 + 3 秒后回中。
- **HUD 逐字效果**（MusicLyricsWidget）：Scroll（StencilClip 裁剪）、FadeIn（word.alpha）、SlideIn（word.progress + Easing）；对齐 Left/Center/Right；优雅滚动（graceScroll 逐行插值）。

### 3.6 四个 widget（HUD 悬浮组件）

- **MusicInfoWidget**：HUD 卡片（封面 + 歌名 + 歌手或歌词 + 进度条 + 时间 + 下载进度），宽 230 高 56，可拖动（WidgetWrapper 提供 x/y）。
- **MusicLyricsWidget**：HUD 歌词，多配置项（Scroll Effects/Align/Width/Height/LyricHeight/Shadow/SingleLine/ShowTranslation/ElegantScrolling/ShowRoman/DynIsland）。
- **MusicSpectrumWidget**：HUD 频谱（AudioPlayer.bandValues → 直方条 + 峰值指示）。
- **SpectrumVisualizer**：纯数学频谱分带（Bark 频段 20Hz–14kHz），供频谱计算（当前 AudioPlayer 实际走 `bandValues`，见 audio 审计）。

---

## 4. 渲染依赖清单（`tritium/rendering/**`）

UI 文件 import 的渲染类，按层归类（供 renderer.md 对齐）：

| 层 | 类 | 是否 api/GL11 | 说明 |
|---|---|---|---|
| 图元 | `Rect` | 纯（→RenderSystem.drawRect→GL11 顶点） | 矩形 |
| 图元 | `RGBA` | 纯 | 颜色位运算 |
| 图元 | `Image` | api.getGLStateManager + GL11 | 纹理绘制（Type.Normal/NoColor 等）、drawLinearFlippedX |
| 图元 | `StencilClipManager` | GL11 模板 + api.colorMask/depthMask | 模板裁剪 beginClip/endClip |
| 图元 | `Framebuffer` | GL11/GL14 FBO + api（viewport/ortho/colorMask…） | 离屏渲染（逐字歌词、blur） |
| 图元 | `TextureManager` / `Textures` / `ITextureObject` / `DynamicTexture` / `TextureUtil` | GL11/GL12/GL14 | 纹理注册 + 异步下载（`downloadTextureAndLoadAsync`） |
| 图元 | `AnimatedTexture` / `MusicToast` | api + GL11；MusicToast 用 `api.getFontUtil().getVanillaFont()` | HUD 动图 / 播放 toast |
| 渲染系统 | `rendersystem.RenderSystem` | api.getGLStateManager + GL11 + `api.registerEvent(EventRender2D→WindowResolution)` | 宽高/缩放/颜色/矩形/渐变/FBO 工厂 |
| 字体 | `font.CFontRenderer` / `TextureAtlas` / `Glyph` / `GlyphGenerator` / `FontKerning` | api.getGLStateManager + GL11/GL12/GL14 | 自绘 TTF 字形图集（不是 MC FontRenderer） |
| 动画 | `animation.Interpolations` / `Easing` / `MultipleEndpointAnimation` / `spring.SpringAnimation`/`SpringParams` | 纯（依赖 RenderSystem.getFrameDeltaTime） | 帧率无关插值 |
| 实体 | `entities.impl.TextField` / `ScrollText` | TextField 用 Keyboard/Mouse + api.color；ScrollText 用 GL11/Display | 输入框 / 滚动文本 |
| shader | `shader.ShaderProgram` / `ShaderCompiler` / `Shaders` / `uniform.*` / `impl.*(RQ/RQT/RQG/ROQ/ROGQ/Stencil/Bloom/GaussianBlur/Blend/Deconverge/VFFadeout)` | **GL20（GLSL）+ GL11**，`BufferUtils` + `Display` | 圆角矩形/圆角纹理/渐变/描边/模板/泛光/高斯模糊 |
| UI 组件 | `ui.AbstractWidget`（组件树） / `ui.container.Panel/ScrollPanel/CroppedPanel` / `ui.widgets.RectWidget/RoundedRectWidget/RoundedImageWidget/ImageWidget/LabelWidget/ScrollLabelWidget/IconWidget/TextFieldWidget/RoundedButtonWidget` | AbstractWidget 仅 `api.getGLStateManager().pushMatrix/popMatrix`（transformations 路径）；其余走 Rect/shaders | **自建 widget 工具包**，几乎全纯 Java 布局 + 回调 |
| UI 组件 | `ui.container.ScrollPanel` | StencilClipManager + Keyboard | 滚动容器 |
| 其他 | `OpenGlHelper` / `BooleanState` / `GaussianKernel` / `ChatAllowedCharacters` | GL11 | 辅助 |

**关键：整个 `ui` widget 工具包是自包含的纯 Java UI 框架**（布局树 + 命中测试 + 事件派发 + 回调），唯一 api 耦合是 `AbstractWidget.renderWidget` 里 transformations 的 push/popMatrix 两行。渲染原语（Rect/Image/RoundedRect 家族 shader）已经是 LWJGL2 + 自研 GL20 shader，**不依赖 `today.opai.api` 之外的任何现代渲染 API**。

---

## 5. 线程模型

- **后台线程**：`MultiThreadingUtil.runAsync(Runnable)` → `Executors.newVirtualThreadPerTaskExecutor()`（**Java 21 API，必须换成 `Executors.newFixedThreadPool` 或 MC 已有线程池**）。用于：
  - 搜索（NavigateBar `CloudMusic.search`）
  - 推荐歌单加载（HomePanel `CloudMusicApi.recommendResource`）
  - 封面/头像下载（`Textures.downloadTextureAndLoadAsync` → `HttpUtils.downloadStream` → `loadTexture`）
  - 逐字歌词下载（MusicLyricsPanel `fetchTTMLLyrics` → gitee yrc）
  - 登录（LoginRenderer 直接 `new Thread` 跑 `CloudMusic.qrCodeLogin`）
  - 封面流专辑数据装配（CoverflowOverlay `loadAlbumData`）
- **回主线程**：`MultiThreadingUtil.runOnMainThread(Runnable)` / `runOnMainThreadBlocking(Supplier)` → `TritiumEventHandler.addScheduledTask(...)`（Guava `ListenableFutureTask` 队列，`onLoop()` 在主线程 drain）。**等价 MC `Minecraft.addScheduledTask`**。
- **纹理上传**：`Textures.loadTexture` 里检查 `TritiumMusicExtension.isCallingFromMainThread()`（判断线程名 == "Client thread"），非主线程则 addScheduledTask 重投。
- **渲染线程隔离**：所有 GL 调用都在 `drawScreen`/`onRender`/`onRender2D` 内（主线程）；下载/解码/解析/FFT 在后台。

---

## 6. Java 8 / LWJGL 兼容性

### 6.1 Java 语法（需降级，本 UI 范围实例）

| 模式 | 文件:位置 |
|---|---|
| switch 表达式 `case X ->` | `NCMScreen.getColor`（switch + `->` 返回）；`MusicLyricsWidget.calculateAlignmentX/calculateSlideInTargetX/renderAlignedText`；`FontManager.create`（switch + `yield`）；`ScrollPanel` 多处（switch 表达式 + `yield`） |
| `List.removeLast()`（Java 21 SequencedCollection） | `NCMScreen.setCurrentPanel`（`actions.removeLast()`） |
| `List.getLast()` / `getFirst()` | `LyricParser.parseWordTimings`（`words.getLast()`）；`MusicLyricsWidget.handleSingleLineMode/getPrevWord`（`lyrics.getFirst()`、`words.getLast()`） |
| `Math.clamp`（Java 21） | `MusicLyricsWidget.onRender`（`Math.clamp(...)`） |
| `instanceof X pattern` | `NavigateBar.layout`（`child instanceof PlaylistItem item`） |
| `Stream.toList()`（Java 16） | `NavigateBar.layout`（`pl.stream().filter(...).toList()`） |
| `Double.isFinite/Float.isFinite` | MusicLyricsPanel/MusicSpectrumWidget/SpectrumVisualizer（**Java 8 已支持，OK**） |
| `String.formatted`/`Duration` | Timer/MusicInfoWidget 用 `java.time.Duration`（Java 8 已有，OK） |
| `Executors.newVirtualThreadPerTaskExecutor()`（Java 21） | `MultiThreadingUtil`（**必改**） |
| Lombok `@Getter/@Setter/@SneakyThrows/@UtilityClass/@EqualsAndHashCode(cacheStrategy)` | 全项目（需 lombok Java8 版或 delombok，见 architecture.md） |

### 6.2 LWJGL（**纯 LWJGL 2，零 LWJGL 3**）

| API | 使用点 |
|---|---|
| `org.lwjgl.opengl.GL11` | Rect/RenderSystem/Image/MusicToast/StencilClipManager/Framebuffer/CFontRenderer/ShaderProgram/各 shader/ScrollText/MusicSpectrumWidget |
| `GL12/GL13/GL14` | TextureUtil/DynamicTexture/TextureAtlas/BloomShader/GaussianBlurShader/StencilShader（多纹理 + FBO） |
| `GL20`（GLSL `glUseProgram` 等） | ShaderProgram/ShaderCompiler/uniform.* |
| `org.lwjgl.input.Keyboard/Mouse` | NCMScreen/CoverflowOverlay/MusicLyricsPanel/ControlsBar/NavigateBar/PlaylistPanel/TextField/ScrollPanel/KeyboardUtils |
| `org.lwjgl.opengl.Display` | RenderSystem/ScrollText/Deconverge/BloomShader/GaussianBlurShader/CursorUtils |
| `org.lwjgl.BufferUtils` | BloomShader/GaussianBlurShader |
| `org.lwjgl.util.glu.Project`（`gluPerspective`） | CoverflowOverlay |

MC 1.8.9 的 LWJGL 2 环境**全部具备**（含 GL20/FBO/multitexture）。无需引入 LWJGL 3，无共存问题。

### 6.3 需要特别注意的原生/外部依赖（UI 直接牵连）

- `CursorUtils`：JNA + `org.lwjgl.opengl.WindowsDisplay` 反射改 Windows 光标（ARROW/HAND/TEXT），**Windows-only，D 类**——1.8.9 应改为 MC 自身光标或 GLFW 等价，否则降级为空操作。
- `KeyboardUtils`：JNA/awt 剪贴板 + LWJGL Keyboard（剪贴板用 awt `Toolkit`，可用）。
- `FontManager`/`CFontRenderer`：自绘字形图集（读 `tritium/fonts/*.ttf` + `sfregular.otf/sfbold.otf`），非 MC FontRenderer；`FontManager` 是 `AbstractManager`，在 init 时同步等待字形加载（`waitUntilAllLoaded`）。
- `MusicToast`：用了 `api.getFontUtil().getVanillaFont()`（原版字体，唯一对原版 FontRenderer 的依赖）。

---

## 7. 结论

1. **页面状态机、布局、交互逻辑可 100% 直接复用**（NCMScreen 的页面栈/前进后退、面板切换动画、ControlsBar、HomePanel/PlaylistPanel/NavigateBar、Coverflow、歌词面板、HUD 逻辑）。它们是对自建 widget 工具包（`tritium.rendering.ui`）的调用，**几乎不含任何 `today.opai.api` 或 net.minecraft 依赖**。
2. **被 `today.opai.api` 抽象包裹的部分很小**，shim 规模：
   - 3 个基类替换：`ExtensionScreen→GuiScreen`、`ExtensionModule/ExtensionWidget→自建 Module/Widget 基类`、`Extension→@Mod`；
   - 1 个 `GLStateManager` 接口（约 20 个方法，全部映射到 MC 1.8.9 `GlStateManager`）；
   - 1 个 `ValueManager`（4 种 Value 类型 + `getValue/setValue/setValueCallback/setHiddenPredicate`）；
   - 4 个 OpenAPI 方法（`registerEvent/registerFeature/displayScreen/getFontUtil/getShaderUtil`）各一处薄封装；
   - 1 个 `WindowResolution`（← `EventRender2D.getWindowResolution`，映射 `ScaledResolution`）；1 个 `Font` 接口（映射 `FontRenderer`，仅 MusicToast 用）。
3. **渲染层（shader/FBO/圆角/模糊/模板裁剪/字体）已经是 LWJGL2 + 自研 GL20 shader**，可在 1.8.9 OpenGL Context 内原样运行，无需降级或替换渲染后端；需 renderer.md 单独确认 GL20 在 1.8.9 的 shader 编译细节。
4. **主要工作量不在 UI 逻辑，而在工程化**：Java 21→8 语法降级（switch 表达式 / `getLast`/`removeLast`/`getFirst` / `Math.clamp` / `instanceof` 模式 / `Stream.toList` / 虚拟线程）、lombok 处理、`CursorUtils` 的 Windows-only 光标替换、以及 `MultiThreadingUtil` 的线程池替换。
