# 核心架构审计：Deuterium → Forge 1.8.9 移植结论（S0 主结论）

> 由主会话直接审计得出，与 5 个子系统 agent 报告互补。本文回答：**移植的架构边界在哪、需要造多大的适配层。**

## 1. 总体架构判定

Deuterium 是一个 **Opai 客户端（`today.opai.api`）的「音乐扩展」**。播放器 Tritium 完全构建在 Opai 客户端的扩展框架之上：

- `tritium/` 树（145 文件，22385 行）**零个 `net.minecraft.*` import**，全部通过 `today.opai.api`（约 17 个类型）访问 Minecraft。
- `repackage/`（175 文件，34059 行）是打包进来的第三方音频库（JSyn / JLayer MP3 / jFLAC / processing.sound / jipes FFT），自包含，仅依赖 `javax.sound.sampled` + lombok。
- `me/fan87/nativeinstrumentation/`（4 文件，863 行）是客户端自身的 **Java Instrumentation 类变换注入器**，与播放器无关，**整体可删（E）**。

**核心结论：移植 = ① 为 `today.opai.api` 造一个 Forge 1.8.9 适配层（shim），② 全量 Java 8 语法降级，③ 补 3 个外部 jar，④ 用 Forge 事件总线替换 Opai 事件系统。** 播放器本体逻辑（API/加密/音频/UI/渲染）几乎全部可原样复用。

## 2. `today.opai.api` 适配层表面（精确清单，造 shim 的依据）

以下每个类型/方法都在源码中被实际调用，移植时必须提供等价物。

### 2.1 OpenAPI（`api`，通过 `ExtensionEntry.getAPI()` 静态获取）
| 调用点 | 用途 | Forge 1.8.9 等价 |
|---|---|---|
| `api.registerEvent(EventHandler)` | 注册事件处理器 | Forge `MinecraftForge.EVENT_BUS.register()` |
| `api.registerFeature(ExtensionModule)` | 注册模块/控件 | 自建 feature 注册表（或直接 new） |
| `api.displayScreen(ExtensionScreen)` / `api.displayScreen(null)` | 打开/关闭 GUI | `Minecraft.getMinecraft().displayGuiScreen(screen)` |
| `api.getValueManager().createBoolean/Modes/Double/Color(...)` | 配置项 | 自建 settings 层（`createBoolean(String,boolean)` 等） |
| `api.getGLStateManager()` | 渲染状态 | 委托 `net.minecraft.client.renderer.GlStateManager` |
| `api.getFontUtil().getVanillaFont()` | 原版字体 | 委托 `Minecraft.fontRendererObj` |

### 2.2 渲染抽象（精确方法名）
- `GLStateManager`：`pushMatrix() popMatrix() scale(x,y,z) translate(x,y,z) rotate(a,x,y,z) color(r,g,b,a) enableTexture2D() disableTexture2D() enableBlend() disableBlend() disableAlpha() enableAlpha() alphaFunc(f,v) tryBlendFuncSeparate(...) shadeModel(m)` —— **全部有 MC 1.8.9 `GlStateManager` 一一对应方法**，直接薄封装。
- `WindowResolution`（来自 `EventRender2D.getWindowResolution()`）：`getScaleFactor() getWidth() getHeight()` —— 等价于 MC `ScaledResolution`。
- `Font`：`getWidth(String) getHeight() drawString(String,x,y,color)` —— 等价于 MC `FontRenderer`。
- `EventRender2D`：`getWindowResolution()` —— 等价于 Forge `RenderGameOverlayEvent`（或自建 2D 渲染钩子）。

### 2.3 事件/生命周期抽象
- `Extension extends today.opai.api.Extension`（`@ExtensionInfo` + `initialize(OpenAPI)` / `onUnload()`）→ **`@Mod` + `Mod` 类 + `FMLPreInitializationEvent/InitializationEvent`**。
- `ExtensionScreen`：覆写 `initGui() onGuiClosed() drawScreen(int,int) keyTyped(char,int) mouseClicked(int,int,int)` —— **签名与 MC `GuiScreen` 完全一致**，直接 `extends GuiScreen`。
- `ExtensionModule`：`setEventHandler() addValues(...) setValueCallback() setEnabled()` + 覆写 `onEnabled()/onTick()`。
- `ExtensionWidget`：HUD 悬浮控件基类。
- `EventHandler`：`onLoop() onRender2D(EventRender2D) onTick() onEnabled()` —— **主线程任务队列 `addScheduledTask` 即 MC `addScheduledTask`**。
- 值类型：`BooleanValue getValue()/setValue()/setValueCallback()`，`NumberValue`，`ModeValue`，`ColorValue` —— 自建薄封装即可。

## 3. Java 8 语法降级清单（B 类，机械改写，非设计改动）

源码是 **Java 17/21 语法**，当前构建 JDK 是 **Java 8**（`1.8.0_432`），必须全量降级：

| 模式 | 数量 | 位置举例 | 改写 |
|---|---|---|---|
| switch 表达式 `case X ->` | 97 处 / 14 文件 | tritium(NCMScreen/FontManager/TextField/ScrollPanel/…) + repackage(IFFParser/RiffFile/Bitstream/Header/…) | switch 语句 / if-else |
| `getFirst/getLast/removeFirst/removeLast/reversed`（SequencedCollection, Java 21） | ~25 处 | CloudMusic/LyricParser/NCMScreen/MusicLyricsWidget/MultipleEndpointAnimation + repackage(SynthesisEngine/UnitDataQueuePort/ScheduledQueue) | `get(0)/get(size-1)/remove(0)/remove(size-1)` |
| `instanceof X pattern` | 7 处 | SequentialDataCrossfade/OutputChannels/FLACDecoder/NavigateBar/LogManager | 传统 instanceof + 强转 |
| `String.repeat()` | 1 处 | TextField | 循环/helper |
| `Stream.toList()` | 2 处 | Music/NavigateBar | `collect(Collectors.toList())` |
| `yield`（switch 表达式内） | 若干 | FontManager/TextField | 随 switch 表达式一起改写 |

无 `var` / `record` / `List.of` / 文本块。

## 4. LWJGL 结论

**纯 LWJGL 2，无 LWJGL 3**（无 `org.lwjgl.glfw` / `MemoryStack` / `glfw*`）。使用面：
`GL11/GL20/GL14/GL13/GL12`、`Display`、`Keyboard`、`Mouse`、`BufferUtils`、`glu.Project`。

- GL11=固定管线、GL20=GLSL shader、GL14=FBO、GL13=多纹理。**MC 1.8.9 全部具备**。
- 渲染层还大量用 `api.getGLStateManager()`（→ MC GlStateManager），且 `RenderSystem.drawRect` 已用 GL11 直接顶点（Tessellator 已注释掉）。
- 结论：渲染层可在 MC 1.8.9 OpenGL Context 内运行，**无需引入 LWJGL 3，无需共存方案**。

## 5. 外部 jar 依赖

| 依赖 | 用途 | MC 1.8.9 是否内置 | 处理 |
|---|---|---|---|
| Lombok（`@Getter/@Setter/@SneakyThrows/@UtilityClass/@Data/…`，约 100+ 处） | 样板代码 | 否 | 编译期注解处理器。Gradle 4.5 + Java8 下可作 compileOnly+apt；或 delombok。**待定，优先尝试 lombok 5.x 兼容 Java8 版本** |
| Gson `com.google.gson` | JSON | 是（MC 内置） | 直接用 |
| log4j `org.apache.logging.log4j` | 日志 | 是 | 直接用 |
| commons-lang3 / commons-io | 工具 | 是 | 直接用 |
| JNA `com.sun.jna` + jna-platform | Windows 原生（剪贴板/光标/User32） | 否 | 需引入 jar；仅 `User32Interface`/`CursorUtils` 用到，可评估最小化或裁剪 |
| ZXing `com.google.zxing` | 二维码登录（QRCodeGenerator） | 否 | 需引入 jar；仅登录功能用到，可保留为可选依赖 |

## 6. 入口 → Forge 生命周期映射

```
ExtensionEntry.initialize(OpenAPI)
  └ TritiumMusicExtension.init(api)
       ├ api.registerEvent(TritiumEventHandler)   → EVENT_BUS.register
       ├ runAsync(CloudMusic::initNCM)            → 线程池（原样）
       ├ Reflection.init(api)                      → 评估是否可删（Opai 专有反射）
       ├ FontManager.init()                        → 字体加载（原样）
       └ api.registerFeature(6 个 module/widget)    → 自建注册
```
- `TritiumEventHandler.onLoop` 主线程队列 = MC `Minecraft.addScheduledTask`。
- `onRender2D`：`Framebuffer.updateMcFramebuffer()` + `Interpolations.calcFrameDelta()` + `MusicToast.render()` → 挂在 Forge `RenderGameOverlayEvent.Pre/Post`。
- 打开 GUI：`OpenNCMScreen.onEnabled → api.displayScreen(NCMScreen)` → `Minecraft.displayGuiScreen(NCMScreen.getInstance())`。

## 7. 后续待补（等 agent 报告）
- NCM API 加密链路是否完整自包含（agent: ncm-api.md）
- 音频输出是否依赖 JavaSound、在 1.8.9 的适配（agent: audio.md）
- UI 页面结构 & 渲染依赖（agent: ui.md）
- 渲染层 shader/FBO 在 1.8.9 可行性（agent: renderer.md）
- 支撑层可复用/可删清单、reflection 可否删（agent: support.md）
