# S0 源码审计：支撑/胶水层（Support Layer）

- 审计范围：`tritium/` 下除 `ncm/ rendering/ screens/ widget/` 之外的全部文件，及 `me.fan87.nativeinstrumentation` 全部 4 个文件。
- 审计性质：只读，未改动任何代码。
- 关键结论速览：
  - 原项目目标 JDK 为 **21**（大量 `switch` 表达式、`instanceof` 模式匹配、`Executors.newVirtualThreadPerTaskExecutor()`、`Math.clamp`、`List.getFirst/getLast`），移植到 Java 8 必须改写。
  - 支撑层里 **纯工具类（utils 大部分、management、settings）几乎可直接复用**；**Opai 专有的入口/模块/Widget/reflection/nativeinstrumentation/cursor 全部需 shim 或删除**。
  - JNA 已内置于 MC 1.8.9（3.4.0 + platform 3.4.0），但 User32Interface/CursorUtils 属 Windows 鼠标指针装饰，播放器不需要，建议删除。
  - zxing 未内置，是登录二维码所需（ncm 层 QRCodeGenerator），需额外引入 zxing-core。

---

## 1. 逐文件表

分类定义：A=直接复用（纯 Java/JDK，或依赖 MC 已内置库）；B=Java8 兼容改写（用了 Java9+ 语法/API）；C=Forge 1.8.9 适配（换事件/渲染/入口）；D=必须重写；E=删除。

| 文件 | 分类 | 职责 | 依赖（非 JDK） |
|---|---|---|---|
| `ExtensionEntry.java` | E（删除，被 Forge `@Mod` 入口替代） | Opai 扩展入口：`@ExtensionInfo` + 继承 `Extension`，`initialize(OpenAPI)` 转发给 `TritiumMusicExtension.init` | `today.opai.api.Extension / OpenAPI / annotations.ExtensionInfo` |
| `TritiumMusicExtension.java` | C（保留编排逻辑，OpenAPI 调用改 shim） | 单例、插件编排：注册事件、异步 `CloudMusic.initNCM()`、`Reflection.init`、创建 `FontManager`、注册 module/widget | `today.opai.api.OpenAPI`、`lombok.Getter`、ncm/rendering/widget 各层 |
| `TritiumEventHandler.java` | C（主线程队列逻辑可复用，事件改 Forge） | 主线程任务队列 `addScheduledTask` + `onLoop` 消费队列；`onRender2D` 驱动 `Framebuffer.updateMcFramebuffer`/`Interpolations.calcFrameDelta`/`MusicToast.render` | guava `Queues/Futures/ListenableFuture( Task)`、`commons-lang3.Validate`、`log4j`、`today.opai.api.events.EventRender2D`、`today.opai.api.interfaces.EventHandler` |
| `interfaces/SharedConstants.java` | C（仅一处 `OpenAPI api` 持有） | 把 `OpenAPI` 以静态字段暴露给所有 `implements SharedConstants` 的类 | `today.opai.api.OpenAPI`、`ExtensionEntry.getAPI()` |
| `interfaces/SharedRenderingConstants.java` | C（渲染 default 方法，依赖 rendering/Shaders 与 GLStateManager） | 圆角矩形/渐变/描边/贴图圆角/缩放旋转等绘制快捷方法 | rendering 层 `Shaders/RGBA/RenderSystem`，`SharedConstants.api.getGLStateManager()`，`java.awt.Color` |
| `interfaces/User32Interface.java` | E（删除，Windows 鼠标指针装饰） | JNA 声明 `user32` 的 `LoadCursorW/SetClassLongPtrW/SetCursor` | `com.sun.jna.*`（MC 已内置 3.4.0） |
| `management/AbstractManager.java` | A | 抽象管理器基类：name/logger/init/stop | `lombok.Getter`、`utils.logging` |
| `management/FontManager.java` | B（`switch` 表达式→改写；其余逻辑复用） | 加载 `tritium/fonts/*.ttf/.otf`，构造 `CFontRenderer` 字体集合（pf12…pf65bold、icon30、music18/40） | `lombok.SneakyThrows`、`rendering.font.CFontRenderer/FontKerning`、resources `tritium/fonts/*`、`java.awt.Font` |
| `module/impl/OpenNCMScreen.java` | C（改为 Forge KeyBinding + GuiScreen；值类型 shim） | 注册「打开 NCM GUI」模块：质量 ModeValue、musicToast/boundaries/lyricDebug 三个 BooleanValue，`onEnabled` 里 `api.displayScreen(NCMScreen)` | `today.opai.api.enums.EnumModuleCategory`、`features.ExtensionModule`、`interfaces.EventHandler`、`interfaces.modules.values.BooleanValue/ModeValue`、ncm/screens/settings |
| `reflection/ClassFinder.java` | E（删除，扫描 Opai `MatrixShield` 内部类） | 按字段/方法签名在类列表里匹配唯一类 | `lombok`、`reflection.ReflectionClasses`、`utils.Tuple` |
| `reflection/CommonReflectionClasses.java` | E（删除，Forge 下可直接引用 `ResourceLocation`/`Minecraft`） | 反射定位 MC 的 `ResourceLocation` 与 `Minecraft` 类 | `lombok.UtilityClass`、`log4j`、`utils.Lazy`、`ClassFinder` |
| `reflection/Reflection.java` | E（删除，Opai 动态岛 + MC `DefaultResourcePack` 字节码注入） | 定位并 ASM 改写 Opai 动态岛水印类与 MC `DefaultResourcePack`，注入 `textures/lyrics.svg` 让动态岛显示歌词 | `org.objectweb.asm.*`、`me.fan87.nativeinstrumentation.NativeInstrumentation`、`ClassFinder/ReflectionUtils`、`today.opai.api.OpenAPI/events.EventRender2D/interfaces.EventHandler` |
| `reflection/ReflectionClasses.java` | E（删除） | 维护「`MatrixShield` 前缀类」扫描列表 | `me.fan87.nativeinstrumentation.NativeInstrumentation` |
| `reflection/ReflectionUtils.java` | E（删除） | ASM tree 指令替换/克隆工具 + 扫描 `%APPDATA%/Opai/extensions` 动态加 classpath | `org.objectweb.asm.*`、`me.fan87.nativeinstrumentation.NativeInstrumentation`、`lombok` |
| `settings/ClientSettings.java` | A | 两个静态布尔开关 `SHOW_WIDGET_BOUNDARY` / `DEBUG_MODE` | 无 |
| `utils/KeyboardUtils.java` | A | 剪贴板读写 + Ctrl/Shift/Alt 组合键判断 | `commons-lang3.StringUtils`（内置）、`org.lwjgl.input.Keyboard`（LWJGL2，内置） |
| `utils/Lazy.java` | A | 线程安全懒加载 `Supplier` 包装 | 无 |
| `utils/Location.java` | A | 资源路径定位 + 缓存 + 校验 | `lombok`、`commons-lang3.Validate`（内置） |
| `utils/Tuple.java` | A | 二元组 | `lombok.Getter/Setter` |
| `utils/WidgetWrapper.java` | E（删除；`WidgetPosSizeInterface` 概念可平移） | 把「Opai `ExtensionModule` + `ExtensionWidget`」包装成可拖动 widget | `today.opai.api.features.ExtensionModule/ExtensionWidget`、`utils.Tuple` |
| `utils/cursor/CursorUtils.java` | E（删除，Windows 鼠标指针装饰） | 通过反射取 LWJGL `hwnd` + JNA 改系统光标 | `com.sun.jna.*`、`org.lwjgl.opengl.Display`、`interfaces.User32Interface` |
| `utils/json/JsonUtils.java` | A | Gson 封装 + 类型安全取值辅助 | `com.google.gson.*`（内置 2.2.4）、`commons-lang3.StringUtils`（内置） |
| `utils/logging/ConsoleColors.java` | A | ANSI 颜色常量 | 无 |
| `utils/logging/LogLevel.java` | A | 日志等级枚举 | `lombok.Getter` |
| `utils/logging/LogManager.java` | B（`switch` 表达式 + `instanceof Throwable t` 模式匹配→改写） | 自研日志格式化/打印 + `{}` 占位符解析 | `lombok` |
| `utils/logging/Logger.java` | A（实现 `log4j.Logger`，MC 内置 log4j 2.0-beta9） | 自研 `Logger` 实现 `org.apache.logging.log4j.Logger`，转发到 `LogManager` | `org.apache.logging.log4j.*`（内置） |
| `utils/logging/StringFormatter.java` | A | `{}`/`%` 混合格式化 | 无 |
| `utils/math/Mth.java` | A | floor/frac/lerp/limit/fastInvSqrt 等数学工具 | `lombok.UtilityClass` |
| `utils/network/HttpUtils.java` | A（依赖 `MultiThreadingUtil`，需一并迁移） | `HttpURLConnection` 封装 GET/POST/PUT/DELETE + 异步 + 下载 | `lombok`、`MultiThreadingUtil` |
| `utils/other/StringUtils.java` | A | 去 MC `§` 格式码、空串兜底 | 无 |
| `utils/other/WrappedInputStream.java` | A | 带进度回调的 `InputStream` 包装 | `lombok.SneakyThrows` |
| `utils/other/multithreading/MultiThreadingUtil.java` | B（`Executors.newVirtualThreadPerTaskExecutor()` → 固定/缓存线程池；`runOnMainThread` → `TritiumEventHandler` 队列） | 异步执行 + 主线程阻塞/投递工具 | `lombok.SneakyThrows`、`tritium.TritiumEventHandler`、`utils.logging.Logger` |
| `utils/timing/Timer.java` | A | 纳秒计时器 / 延迟判断 | `java.time.Duration`（Java 8 内置） |
| `me.fan87.nativeinstrumentation/NativeInstrumentation.java` | E（删除） | 自实现 `java.lang.instrument.Instrumentation`（JNI 类变换），加载 `native_instrumentation_native-*.dll` | 本地库 `native_instrumentation_native-windows-x86_64.dll`、`lombok.Getter`、JDK `java.lang.instrument`、`java.lang.Module`（Java9+） |
| `me.fan87.nativeinstrumentation/OsDetector.java` | E（删除） | 本地库文件名 OS/arch 探测 | 无（附属于上者） |
| `me.fan87.nativeinstrumentation/TransformerInfo.java` | E（删除） | transformer 元数据 | JDK `java.lang.instrument.ClassFileTransformer` |
| `me.fan87.nativeinstrumentation/TransformerManager.java` | E（删除） | transformer 列表管理/调用 | JDK `java.lang.instrument` |

---

## 2. Opai 耦合面（`today.opai.api.*` 精确清单）

以下是从支撑层 + 相关模块/widget 代码中精确提取到的 Opai API 用法，是 Forge 移植时 **shim（适配层）必须覆盖的最小面**。

### 2.1 入口 / 生命周期类
- `today.opai.api.Extension`（抽象基类）
  - 静态方法 `static OpenAPI getAPI()`（`ExtensionEntry.getAPI()` 继承调用，返回当前 `OpenAPI` 实例）
  - 实例方法 `void initialize(OpenAPI openAPI)`、`void onUnload()`
- `today.opai.api.annotations.ExtensionInfo`
  - 属性：`String name()`、`String author()`、`String version()`（标注在 `ExtensionEntry` 上）

### 2.2 `today.opai.api.OpenAPI`（实例方法）
- `void registerEvent(EventHandler handler)` — `TritiumMusicExtension`、`Reflection`、`RenderSystem` 使用
- `void unregisterEvent(EventHandler handler)` — `Reflection` 使用
- `void registerFeature(Object feature)` — `TritiumMusicExtension` 使用，实参为 `ExtensionModule` 与 `ExtensionWidget` 两种
- `void displayScreen(Object screen)` — `OpenNCMScreen.onEnabled`，实参 `NCMScreen.getInstance()`（`ExtensionScreen` 子类）
- `ValueManager getValueManager()`
- `GLStateManager getGLStateManager()`（371 处，见 2.5）
- `ShaderUtil getShaderUtil()` → `drawWithBloom(Runnable)`
- `FontUtil getFontUtil()` → `Font getVanillaFont()`
- `RenderUtil getRenderUtil()`（仅注释中 `drawRect`，未实际调用）

### 2.3 `today.opai.api.interfaces.EventHandler`（接口，需实现的方法）
- `void onLoop()` — `TritiumEventHandler` 实现
- `void onRender2D(EventRender2D event)` — `TritiumEventHandler`、`Reflection` 实现
- `void onTick()` — `OpenNCMScreen` 实现（内部 `setEnabled(false)`）
- `void onEnabled()` — `OpenNCMScreen` 实现（`api.displayScreen(...)`）

### 2.4 `today.opai.api.events.EventRender2D`
- 方法 `WindowResolution getWindowResolution()`（`RenderSystem` 调用）
  - `WindowResolution.getScaleFactor()`、`.getWidth()`、`.getHeight()`
- 支撑层代码未直接读取 `EventRender2D` 的其他字段（如 partialTicks 等），仅透传

### 2.5 `today.opai.api.interfaces.render.GLStateManager`（方法全集，已用到的）
`pushMatrix` `popMatrix` `translate(x,y,z)` `scale(x,y,z)` `rotate(angle,x,y,z)` `color(r,g,b,a)` `bindTexture(int)` `enableBlend` `disableBlend` `enableTexture2D` `disableTexture2D` `enableAlpha` `disableAlpha` `tryBlendFuncSeparate(4 int)` `matrixMode(int)` `loadIdentity` `ortho(6 double)` `viewport(4 int)` `colorMask(4 boolean)` `depthMask(boolean)` `enableDepth` `disableDepth` `clearDepth(double)` `clear(int)` `clearColor(4 float)` `shadeModel(int)` `enableColorMaterial` `disableLighting` `callList(int)` `colorMaterial`（部分为注释/未用）。
> 注意：这些几乎都能在 MC 1.8.9 的 `GlStateManager` 上找到 1:1 等价方法，shim 成本低。

### 2.6 `today.opai.api.enums.EnumModuleCategory`
- 枚举值：`MISC`（`OpenNCMScreen`）、`VISUAL`（三个 widget）

### 2.7 `today.opai.api.features.ExtensionModule`（抽象基类）
- 构造器 `ExtensionModule(String name, String description, EnumModuleCategory category)`
- `void setEventHandler(EventHandler)`
- `void addValues(Value...)`
- `boolean isEnabled()`（`WidgetWrapper.renderPredicate` 用）
- `void setEnabled(boolean)`（`onTick` 用）
- `String getName()`（`WidgetWrapper` 用）

### 2.8 `today.opai.api.features.ExtensionWidget`
- 构造器 `ExtensionWidget(String name)`
- 抽象方法 `void render()`、`boolean renderPredicate()`
- `float getX()/getY()/getWidth()/getHeight()`、`void setX/setY/setWidth/setHeight(float)`

### 2.9 值类型（`today.opai.api.interfaces.modules.values.*` + `ValueManager`）
`getValueManager()` 工厂方法：
- `BooleanValue createBoolean(String name, boolean default)`
- `ModeValue createModes(String name, String default, String[] modes)`
- `NumberValue createDouble(String name, double default, double min, double max, double increment)`
- `ColorValue createColor(String name, java.awt.Color default)`

各值类型方法（已用到）：
- `BooleanValue`：`boolean getValue()`、`setHiddenPredicate(Supplier<Boolean>)`、`setValueCallback(Consumer<Boolean>)`
- `ModeValue`：`String getValue()`、`setValueCallback(Consumer<String>)`
- `NumberValue`：`double getValue()`、`float floatValue()`、`setHiddenPredicate(...)`、`setValueCallback(Consumer<Double>)`
- `ColorValue`：`java.awt.Color getValue()`、`setAlphaAllowed(boolean)`

### 2.10 其他（widget/screens 层，shim 同样需要）
- `today.opai.api.features.ExtensionScreen`（`NCMScreen`、`CoverflowOverlay` 的基类）
- `today.opai.api.enums.EnumChatColor`（`WHITE`、`GRAY`，用于动态岛歌词上色）
- `today.opai.api.interfaces.render.Font`（`getFontUtil().getVanillaFont()`，含 `getWidth/drawString`）

---

## 3. 生命周期（从 initialize 到播放器就绪）

1. **`ExtensionEntry.initialize(OpenAPI)`**（Opai 加载扩展时回调）→ 转交 `TritiumMusicExtension.getInstance().init(api)`。
2. **`TritiumMusicExtension.init(api)`** 依次：
   1. `api.registerEvent(TritiumEventHandler.getInstance())` —— 注册主线程队列与 Render2D 钩子。
   2. `MultiThreadingUtil.runAsync(CloudMusic::initNCM)` —— 异步初始化网易云（登录状态/歌词缓存等），不阻塞。
   3. `Reflection.init(api)` —— 再注册一个一次性 `EventHandler`（见第 4 节，Opai 动态岛字节码注入）。
   4. `this.fontManager = new FontManager()`；`fontManager.init()` → `loadFonts()`（读 `tritium/fonts/*` 创建 20+ 个 `CFontRenderer`）→ `waitUntilAllLoaded()` 轮询等待所有字体纹理上传完成。
   5. `api.registerFeature(...)` 注册 7 个：`tritiumMusic`（OpenNCMScreen 模块）、`musicSpectrum`/`musicInfo`/`musicLyrics`（三个 module 及各自的 `.widget`）。
3. **`TritiumEventHandler` 主线程任务队列**：
   - `static <V> ListenableFuture<V> addScheduledTask(Callable<V>)`：非主线程调用时，把任务塞进 `scheduledTasks` 队列（guava `ListenableFutureTask`）返回 future；已是主线程则直接同步执行返回 `Futures.immediateFuture`。`addScheduledTask(Runnable)` 用 `Executors.callable` 包装后转调。
   - `onLoop()`（Opai 每 tick 回调）：`synchronized(scheduledTasks)` 下 `while(!empty) runTask(poll())`；`runTask` 执行 `task.run()` 后 `task.get()`，捕获 `ExecutionException/InterruptedException`，`OutOfMemoryError` 特殊 rethrow。
   - 移植映射：`onLoop` → Forge `ClientTickEvent`；`addScheduledTask` 队列逻辑可原样保留（guava 已内置）。
4. **`onRender2D(EventRender2D)`**：每次 2D 渲染回调执行三件事 —— `Framebuffer.updateMcFramebuffer()`（同步 MC 主 framebuffer 尺寸/状态）、`Interpolations.calcFrameDelta()`（计算帧间隔供动画插值）、若 `tritiumMusic.musicToast.getValue()` 为真则 `MusicToast.render()`（播放/切换歌曲的 Toast 提示）。
   - 移植映射：`onRender2D` → Forge `RenderGameOverlayEvent`（或 GuiIngame 钩子）。
5. `TritiumMusicExtension.isCallingFromMainThread()` 通过线程名 `"Client thread"` 判断主线程（MC 客户端线程名，Forge 下同样成立，可直接复用）。

---

## 4. reflection 层（反射的是什么 / 能否删）

- `ReflectionClasses`：调用 `NativeInstrumentation.getInstance().getAllLoadedClasses()` 枚举 JVM 已加载类，**只保留 `MatrixShield` 前缀的类** —— 即 Opai 客户端的混淆/水印（MatrixShield）内部类，不是 Minecraft 的类。
- `ClassFinder`：在 `ReflectionClasses` 的扫描列表里，按「父类 + 接口 + 字段类型/修饰符 + 方法返回类型/参数/修饰符」的组合签名，唯一匹配一个类。用于定位 Opai 动态岛相关类（`DYN_1`、`DynamicIslandClass`、`DynamicIslandEntity`）以及 MC 的 `ResourceLocation`/`IResourcePack`/`DefaultResourcePack`。
- `CommonReflectionClasses`：用 `ClassFinder` 反射定位 MC 的 `net.minecraft.util.ResourceLocation`（两个 `String` protected final 字段 + `splitObjectName` 方法）和 `net.minecraft.client.Minecraft`（Logger/Thread/volatile boolean/static int 字段）。
- `Reflection`：把上面定位到的类用 **ASM 字节码改写**（`Instrumentation.redefineClasses`）：
  1. `transformDefaultResourcePack()`：给 MC `DefaultResourcePack` 的 `getInputStream` 方法注入分支 —— 当资源名以 `textures/lyrics.svg` 结尾时返回内联的歌词图标 SVG。
  2. `transformDynIsland()`：给 Opai 动态岛类的渲染方法注入字节码，读取系统属性 `ncm.dynIslandLyrics`，把歌词作为动态岛实体塞进列表 —— 这是「灵动岛歌词」功能的实现。
- **为什么播放器需要反射？** 只有「灵动岛歌词」这个 Opai 客户端特有功能需要（把歌词画进客户端水印/动态岛）。核心播放器（API/音频/歌词/UI）不依赖它。
- **移植到 Forge 后能否删？**
  - `ReflectionClasses`/`ClassFinder`/`Reflection`/`ReflectionUtils`/`CommonReflectionClasses` **全部可删除**。理由：
    - `MatrixShield`/动态岛是 Opai 私有类，Forge 环境不存在，无反射目标。
    - MC 的 `ResourceLocation`/`DefaultResourcePack` 在 Forge 下可直接 `import`，无需反射。
    - 唯一有移植价值的「歌词图标 SVG」可直接放到 Mod 资源目录 `assets/.../textures/lyrics.svg` 由 `TextureManager` 正常加载，无需字节码注入 `DefaultResourcePack`。
  - 删除后同时移除对 `org.objectweb.asm`（ASM）和 `me.fan87.nativeinstrumentation` 的全部依赖。
  - `MusicLyricsWidget.dynIsland`（BooleanValue）因依赖 `Reflection.DYNAMIC_ISLAND_SUPPORTED`，删除后该开关恒为 false，需在 shim 中把 `DYNAMIC_ISLAND_SUPPORTED` 常量改为 `false`（或直接删该设置项）。见第 9 节「动态岛」。

---

## 5. nativeinstrumentation（me.fan87 4 文件）—— 可删除，证据充分

这 4 个文件（`NativeInstrumentation`、`OsDetector`、`TransformerInfo`、`TransformerManager`）是 **一套自实现的 `java.lang.instrument.Instrumentation`（HotSpot InstrumentationImpl 的简化复制品）**：

- `NativeInstrumentation implements Instrumentation`：用 `private static native ...` 声明一组 JNI 方法（`libInit/redefineClasses0/getAllLoadedClasses0/invokeMethodS/...`），静态块 `loadNativeLib()` 从资源 `native_instrumentation_native-<os>-<arch>.dll`（仓库中确有 `src/main/resources/native_instrumentation_native-windows-x86_64.dll`）解压到临时目录 `System.load`。
- `TransformerManager`/`TransformerInfo`：管理 `ClassFileTransformer` 列表、前缀、调用顺序。
- `OsDetector`：本地库文件名的 OS/arch 探测。
- 用途：给 `reflection/Reflection` 提供 `getAllLoadedClasses()`（扫 MatrixShield 类）与 `redefineClasses()`（ASM 注入动态岛歌词），以及 `ReflectionUtils` 的 `invokeMethodS/doPrivileged`（动态加载 Opai extensions 目录里的 jar）。
- **播放器是否依赖？** 否。唯一消费者是 reflection 层（第 4 节，全部要删）。API/音频/歌词/UI 无任何引用。
- **能否删除？** 是（预期 E）。证据：
  1. 依赖链 `Reflection/ReflectionUtils/ReflectionClasses → NativeInstrumentation`，三者全部 E 删除。
  2. 需要额外原生 `.dll` 资源 + JNI，属于客户端专用 class 变换基建，与独立 Mod 无交集。
  3. 该类还用了 `java.lang.Module`（`redefineModule`/`isModifiableModule`），本身就不是 Java 8 兼容的。
- 删除后：`reflection/` 5 文件、`me/fan87/nativeinstrumentation/` 4 文件、`src/main/resources/native_instrumentation_native-windows-x86_64.dll`、以及 ASM 依赖一并移除。

---

## 6. 工具类复用性

- **纯 Java、无外部依赖、可直接复用（A）**：
  `settings/ClientSettings`、`utils/Lazy`、`utils/Tuple`、`utils/Location`（仅 commons-lang3）、`utils/math/Mth`、`utils/other/StringUtils`、`utils/other/WrappedInputStream`、`utils/timing/Timer`、`utils/logging/ConsoleColors`、`utils/logging/LogLevel`、`utils/logging/StringFormatter`、`management/AbstractManager`。
- **依赖 MC 已内置库、可直接复用（A）**：
  `utils/KeyboardUtils`（commons-lang3 + LWJGL2 `Keyboard`）、`utils/json/JsonUtils`（gson 2.2.4 + commons-lang3）、`utils/logging/Logger`（log4j 2.0-beta9）、`utils/network/HttpUtils`（依赖 `MultiThreadingUtil`）。
- **依赖 JNA（MC 已内置 3.4.0，但功能是 Windows 光标装饰，建议删）**：`interfaces/User32Interface`、`utils/cursor/CursorUtils`。
- **依赖 Opai，必须 shim/删**：`ExtensionEntry`、`utils/WidgetWrapper`、`module/impl/OpenNCMScreen`、`interfaces/SharedConstants`（`OpenAPI api` 静态持有）、`interfaces/SharedRenderingConstants`（`api.getGLStateManager()`）、`TritiumMusicExtension`/`TritiumEventHandler`（事件面）。
- **依赖 zxing**：支撑层无；zxing 只在 `ncm/music/QRCodeGenerator.java`（登录二维码），见第 7 节。
- **依赖 guava**：`TritiumEventHandler` 用 `Queues.newArrayDeque` / `Futures` / `ListenableFuture` / `ListenableFutureTask`，guava 17.0 内置，直接可用。

---

## 7. 外部依赖清单（支撑层 import 的非 JDK 依赖）

MC 1.8.9 官方 `version.json` 确认的内置库列表（client）：gson 2.2.4、guava 17.0、commons-lang3 3.3.2、commons-io 2.4、commons-codec 1.9、log4j-api/core 2.0-beta9、netty-all 4.0.23.Final、lwjgl 2.9.4-nightly-20150209、**jna 3.4.0 + jna-platform 3.4.0**、jinput、oshi-core、paulscode、twitch、authlib、realms、httpclient 等。

| 依赖 | 支撑层使用位置 | MC 1.8.9 是否内置 | 结论 |
|---|---|---|---|
| `lombok`（`@Getter/@Setter/@SneakyThrows/@UtilityClass/@AllArgsConstructor/@EqualsAndHashCode`） | 全层散布（FontManager/Tuple/Location/Mth/HttpUtils/ClassFinder/Logger/LogManager/LogLevel/AbstractManager/...） | 否（编译期注解处理器） | 编译期仅需。方案：Forge 构建加 `compileOnly 'org.projectlombok:lombok:1.18.x'`；或一次性 delombok 移除依赖 |
| `com.google.gson` | `utils/json/JsonUtils` | 是（2.2.4） | 直接复用（注意 API 需兼容 2.2.4，本项目用法均为基础 API，兼容） |
| `org.apache.commons.lang3` | `KeyboardUtils`、`Location`、`TritiumEventHandler`、`JsonUtils`、ncm 层 | 是（3.3.2） | 直接复用（`Validate`/`StringUtils` 均存在） |
| `org.apache.commons.io` | 仅 ncm 层 `CloudMusic.IOUtils`（支撑层无） | 是（2.4） | 直接复用 |
| `org.apache.logging.log4j`（log4j2） | `utils/logging/Logger`、`TritiumEventHandler`（经 tritium Logger）、`CommonReflectionClasses` | 是（2.0-beta9） | 直接复用（`Level/Marker/Message/MessageFactory` 均存在） |
| `com.google.common`（guava） | `TritiumEventHandler`（`Queues/Futures/ListenableFuture/ListenableFutureTask`） | 是（17.0） | 直接复用 |
| `org.lwjgl.input.Keyboard` / `org.lwjgl.opengl.*`（LWJGL2） | `KeyboardUtils`、`CursorUtils`、widget/rendering 层 | 是（2.9.4） | 直接复用 |
| `com.sun.jna`（+platform.win32） | `interfaces/User32Interface`、`utils/cursor/CursorUtils` | 是（3.4.0 + platform 3.4.0） | 内置可用，但功能（Windows 光标装饰）播放器不需要，建议删文件即可，无需引 jar |
| `org.objectweb.asm`（ASM） | `reflection/Reflection`、`reflection/ReflectionUtils` | 否 | 随 reflection 层一并删除，**无需引入** |
| `com.google.zxing` | 仅 ncm 层 `QRCodeGenerator`（`BarcodeFormat/EncodeHintType/MultiFormatWriter/BitMatrix`） | 否 | **必须额外引入**。用途：登录二维码生成。最小化方案：仅 `com.google.zxing:core:3.5.x`（Java 8 兼容），不引 `javase` |
| `java.lang.instrument` | `me/fan87/nativeinstrumentation/*` | JDK 内置 | 随删除，无需处理 |

> 结论：支撑层本身几乎不需要新增外部 jar —— 唯一「真正需要新增」的是 **zxing-core**（且属于 ncm 层登录二维码），其余要么内置、要么随删除消失、要么 lombok 仅编译期。

---

## 8. Java 8 兼容性

原项目目标 JDK 为 21。支撑层内 Java 9+ 语法/API 清单（均需 B 类改写）：

| 文件 | 位置 | 问题 | 改写 |
|---|---|---|---|
| `management/FontManager` | `create(String,String)` 内 `return switch(name){ case "googlesans" -> ...; yield ...}` | Java 14 `switch` 表达式 + `yield` | 改为传统 `switch`/`if-else` 分支返回 |
| `utils/logging/LogManager` | `getSymbolColor()` | Java 14 `switch` 表达式 | 传统 `switch` |
| `utils/logging/LogManager` | `toString()`、`parse()` 内 `o instanceof Throwable t` | Java 16 `instanceof` 模式匹配 | `Throwable t = (Throwable) o` 显式强转 |
| `utils/other/multithreading/MultiThreadingUtil` | `Executors.newVirtualThreadPerTaskExecutor()` | Java 21 虚拟线程 API | `Executors.newFixedThreadPool` 或 `Executors.newCachedThreadPool`（或复用 Forge 自带线程池） |
| `me/fan87/nativeinstrumentation/NativeInstrumentation` | `redefineModule(Module,...)`/`isModifiableModule(Module)` | Java 9 `java.lang.Module` | 整个类 E 删除 |

> 注（超出支撑层、供后续阶段参考）：`widget/impl/MusicLyricsWidget` 还用了 `Math.clamp`（Java 21）、`List.getFirst()/getLast()`（Java 21）、多处 `switch` 表达式。S4/S5 阶段需一并处理。

`ClassFinder` 里的 `new ArrayList<>(){{ add(Object.class); }}` 是双括号匿名初始化，Java 8 合法，无需改。

---

## 9. 结论

### 9.1 支撑层可复用清单（A/B/C 保留）
- **纯工具类（A）**：`ClientSettings`、`Lazy`、`Tuple`、`Location`、`Mth`、`StringUtils`、`WrappedInputStream`、`Timer`、`ConsoleColors`、`LogLevel`、`StringFormatter`、`AbstractManager`、`KeyboardUtils`、`JsonUtils`、`HttpUtils`、`Logger`。
- **B 类改写**：`FontManager`、`LogManager`、`MultiThreadingUtil`（3 个文件，改写点见第 8 节）。
- **C 类 Forge 适配**：`TritiumMusicExtension`（编排逻辑保留）、`TritiumEventHandler`（主线程队列 + Render2D 逻辑保留）、`SharedConstants`（静态 API 持有改为 shim）、`SharedRenderingConstants`（GL 调用改 `GlStateManager`）、`OpenNCMScreen`（改 KeyBinding+GuiScreen）。
- **`FontManager` 依赖的资源**：`tritium/fonts/{pf_normal.ttf,pf_middleblack.ttf,sfregular.otf,sfbold.otf,icomoon.ttf,music.ttf}` 需随包迁移。

### 9.2 必须 shim 的 Opai API（精确，供 shim 层实现）
见第 2 节全表。核心 10 项：
1. `Extension`（`getAPI()` / `initialize` / `onUnload`）+ `@ExtensionInfo` —— 由 Forge `@Mod` 入口替代。
2. `OpenAPI.registerEvent` / `unregisterEvent` —— Forge `FMLCommonHandler.bus().register`。
3. `OpenAPI.registerFeature(module|widget)` —— 由 Mod 自己的 module/widget 注册表替代。
4. `OpenAPI.displayScreen(ExtensionScreen)` —— `Minecraft.getMinecraft().displayGuiScreen(GuiScreen)`。
5. `OpenAPI.getValueManager()` + `createBoolean/createModes/createDouble/createColor` —— shim 成自研 `ValueManager`（或持久化到配置文件）。
6. `OpenAPI.getGLStateManager()` —— shim 成 MC `net.minecraft.client.renderer.GlStateManager`（方法几乎 1:1）。
7. `OpenAPI.getShaderUtil().drawWithBloom(Runnable)` —— 移植 bloom/模糊后提供等价。
8. `OpenAPI.getFontUtil().getVanillaFont()` —— 直接返回 MC `FontRenderer`。
9. `EventHandler.onLoop/onRender2D/onTick/onEnabled` —— Forge `ClientTickEvent` / `RenderGameOverlayEvent`。
10. 值类型接口 `BooleanValue/ModeValue/NumberValue/ColorValue`（`getValue` + callback + hiddenPredicate）+ `ExtensionModule/ExtensionWidget/EnumModuleCategory/EnumChatColor/ExtensionScreen`。

### 9.3 可删除清单（E）
- `ExtensionEntry.java`
- `interfaces/User32Interface.java`、`utils/cursor/CursorUtils.java`（Windows 光标装饰，非播放器功能）
- `utils/WidgetWrapper.java`（Opai widget 包装；若保留 widget 系统则仅借鉴 `WidgetPosSizeInterface` 思想）
- `reflection/` 全部 5 文件（ClassFinder/CommonReflectionClasses/Reflection/ReflectionClasses/ReflectionUtils）
- `me/fan87/nativeinstrumentation/` 全部 4 文件 + `src/main/resources/native_instrumentation_native-windows-x86_64.dll`
- 连带移除依赖：`org.objectweb.asm`（ASM）
- 功能取舍：**「灵动岛歌词」**（`Reflection.DYNAMIC_ISLAND_SUPPORTED` + `dynIsland` 开关）依赖 Opai 动态岛与字节码注入，Forge 下无法等价移植；按 CLAUDE.md §14 记录为「限制」，shim 中把 `DYNAMIC_ISLAND_SUPPORTED` 置 `false`，`dynIsland` 设置项隐藏或移除，并在回归清单中标注。

### 9.4 需额外引入的外部 jar 及最小化方案
- **zxing-core**（唯一必需新增）：`ncm/music/QRCodeGenerator` 登录二维码用；仅引入 `com.google.zxing:core`（如 3.5.x，Java 8 兼容），不引入 `javase`/`android-core`。
- **lombok**：仅编译期。推荐 Forge 构建加 `compileOnly`；或对保留文件做一次性 delombok（`@Getter/@Setter/@SneakyThrows/@UtilityClass/@AllArgsConstructor/@EqualsAndHashCode` 生成代码无 Java9+ 特性）。
- **JNA**：无需额外引入（MC 1.8.9 已内置 3.4.0），且相关文件建议删除。
- **gson / guava / commons-lang3 / commons-io / log4j / LWJGL2**：全部内置，无需引入。

### 9.5 给 S1 阶段的最小落地建议
1. 新建 Forge `@Mod` 入口，替代 `ExtensionEntry`；`SharedConstants.api` 改为指向 shim 的静态 `OpenAPI`（或直接改为持有 `Minecraft`/`GlStateManager` 引用的适配器）。
2. 先搬 `settings/ management/ utils/`（去掉 cursor、WidgetWrapper），把 `LogManager/FontManager/MultiThreadingUtil` 做 Java 8 改写。
3. 实现 §9.2 的 10 项 shim；`TritiumEventHandler` 挂到 `ClientTickEvent` + `RenderGameOverlayEvent`。
4. 删除 §9.3 全部文件与 ASM/原生库资源，`DYNAMIC_ISLAND_SUPPORTED=false`。
5. `build.gradle` 加 `compileOnly lombok` + `compile zxing-core`。
