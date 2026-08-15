# Deuterium 渲染层源码审计报告（S0 · 只读）

范围：`Deuterium/src/main/java/tritium/rendering/`（递归，共 70 个 .java）+ 对应 shader 资源 `src/main/resources/tritium/shaders/`（12 个）+ 字体资源 `src/main/resources/tritium/fonts/`（6 个 ttf/otf）。

结论速览（详见第 10 节）：

- **纯 LWJGL 2，无 LWJGL 3**。全目录没有 `org.lwjgl.glfw` / `org.lwjgl.system` / `MemoryStack` / `glfwCreateWindow`，只有 `org.lwjgl.opengl.*`（GL11/12/13/14/20/30 + EXTFramebufferObject）、`org.lwjgl.BufferUtils`、`org.lwjgl.input.Keyboard/Mouse`、`org.lwjgl.opengl.Display`。
- **Shader / FBO / Stencil / 模糊在 MC 1.8.9 全部可行**。GLSL 全是兼容模式（`#version 120` 为主，仅 `blur.frag`、`stencil.frag` 用 `#version 130`），用的是 `gl_TexCoord[0]` / `gl_ModelViewProjectionMatrix` 等固定管线内置量，没有 core profile 语法，也没有 MC 1.8.9 缺失的能力（无 MSAA、无 compute、无 geometry/tessellation shader）。
- **字体渲染强依赖 java.awt**（FontRenderContext / GlyphVector / FontMetrics / Graphics2D / BufferedImage），运行时从 `resources/tritium/fonts/*.ttf|otf` 读字体，逐字形渲染成纹理图集。
- **圆角/圆角图片/圆角渐变是用 Shader 做的**（RQShader/RQTShader/RQGShader/ROQShader/ROGQShader，SDF + smoothstep），**不是 stencil、不是顶点**。stencil 只用于「裁剪」(StencilClipManager → CroppedPanel/ScrollPanel/TextField/ScrollText)。
- **可直接复用的纯 Java 层很多**（Easing/Animation/Spring/GaussianKernel/RGBA/ChatAllowedCharacters/Glyph/FontKerning/FilterState/ITextureObject/BooleanState 等）。
- **必须做 Java 8 降级**：发现 Java 9+/14+/21+ 语法（switch 表达式 `->`/`yield`、`String.repeat`、`List.getFirst/getLast`），集中在 CFontRenderer、TextField、ScrollPanel、MultipleEndpointAnimation。

---

## 1. LWJGL / OpenGL API 盘点（最重要）

### 1.1 结论

- **没有 LWJGL 3**。已用 grep 全量确认：`org.lwjgl.glfw`、`org.lwjgl.system`、`MemoryStack`、`glfwCreateWindow` 在 rendering 目录命中数为 0。
- 全部是 **LWJGL 2** 的 `org.lwjgl.opengl.*` + `org.lwjgl.input.*` + `org.lwjgl.BufferUtils` + `org.lwjgl.opengl.Display`。
- Minecraft 1.8.9 自带的 LWJGL 2.9.4 里 **GL11/GL12/GL13/GL14/GL20/GL30 与 `EXTFramebufferObject` 类全部存在**，并且 vanilla 自己就在用（`net.minecraft.client.shader.Framebuffer` 用同一套 GL30 FBO 调用）。

### 1.2 使用的 LWJGL 类与方法清单

| LWJGL 类 | 对应 OpenGL 特性 | 在渲染层中的用途 | 文件（代表） |
|---|---|---|---|
| `org.lwjgl.opengl.GL11` | 固定管线 / GL 1.x | 立即模式 `glBegin/glEnd`(GL_QUADS/GL_TRIANGLES/GL_TRIANGLE_STRIP)、`glVertex2d/3d`、`glTexCoord2d/2f`、显示列表 `glGenLists/glNewList/glCallList/glDeleteLists`、矩阵 `glMatrixMode/glOrtho/glTranslate/glScale/glRotate`、stencil `glStencilFunc/Mask/Op/glClearStencil`、纹理参数 `glTexParameteri/f`、`glTexImage2D/glTexSubImage2D`、`glPixelStorei`、`glTexEnvi`、`glGetInteger/glGetBoolean`、`glColor4f`、`glEnable/glDisable` | 几乎全部：Image / AnimatedTexture / CFontRenderer / Framebuffer / StencilClipManager / RenderSystem / ShaderProgram / TextureUtil / BooleanState |
| `org.lwjgl.opengl.GL12` | GL 1.2（3D 纹理参数 / BGRA 上传） | `GL_CLAMP_TO_EDGE`、`GL_TEXTURE_MAX_LEVEL`、`GL_TEXTURE_MIN_LOD/MAX_LOD`、`GL_BGRA`、`GL_UNSIGNED_INT_8_8_8_8_REV` | TextureUtil / DynamicTexture / TextureAtlas / Framebuffer |
| `org.lwjgl.opengl.GL13` | GL 1.3（多纹理） | `glActiveTexture(GL_TEXTURE0/16/20)` | BloomShader / GaussianBlurShader / StencilShader |
| `org.lwjgl.opengl.GL14` | GL 1.4 | `GL_TEXTURE_LOD_BIAS`（mipmap LOD 偏移） | TextureUtil / DynamicTexture / TextureAtlas |
| `org.lwjgl.opengl.GL20` | GL 2.0（Shader/GLSL） | `glCreateShader`、`glShaderSource`、`glCompileShader`、`glCreateProgram`、`glAttachShader`、`glLinkProgram`、`glValidateProgram`、`glUseProgram`、`glGetUniformLocation`、`glUniform1f/1i/2f/3f/4f/1/4`、`glGetShaderi`、`glGetShaderInfoLog` | ShaderCompiler / ShaderProgram / uniform/* |
| `org.lwjgl.opengl.GL30` | GL 3.0（FBO） | `glGen/Bind/Delete Framebuffers`、`glGen/Bind/Delete Renderbuffers`、`glRenderbufferStorage(GL_DEPTH24_STENCIL8)`、`glFramebufferTexture2D`、`glFramebufferRenderbuffer`、`glCheckFramebufferStatus`、`glGetFramebufferAttachmentParameteri` | OpenGlHelper / Framebuffer |
| `org.lwjgl.opengl.EXTFramebufferObject` | EXT 扩展 | `glFramebufferRenderbufferEXT`（把 depth24-stencil8 renderbuffer 同时挂到 `GL_STENCIL_ATTACHMENT`） | Framebuffer（第 123 行） |
| `org.lwjgl.BufferUtils` | — | `createFloatBuffer`（上传高斯核 uniform） | BloomShader / GaussianBlurShader |
| `org.lwjgl.input.Keyboard` | — | `enableRepeatEvents`、`isKeyDown`、`KEY_*` 常量 | TextField / ScrollPanel |
| `org.lwjgl.input.Mouse` | — | `getX`、`getY`、`isButtonDown` | RenderSystem / TextField |
| `org.lwjgl.opengl.Display` | — | `getWidth`、`getHeight`、`isVisible`（拿屏幕尺寸 / 可见性） | RenderSystem / Framebuffer / ScrollText / BloomShader / GaussianBlurShader / Deconverge |

### 1.3 OpenGL 版本特性 → MC 1.8.9 可行性

| 特性 | OpenGL 版本 | MC 1.8.9 是否支持 |
|---|---|---|
| 立即模式 + 显示列表 + 矩阵栈（GL11） | GL 1.1 | ✅ vanilla 本身就用（Tessellator 底层也是 GL11） |
| BGRA 上传 / clamp-to-edge（GL12） | GL 1.2 | ✅ |
| 多纹理 `glActiveTexture`（GL13，unit 0/16/20） | GL 1.3 | ✅ 现代驱动 ≥32 单元；vanilla `GlStateManager` 已管理 activeTextureUnit |
| mipmap LOD bias（GL14） | GL 1.4 | ✅ |
| Shader / GLSL（GL20） | GL 2.0 | ✅ vanilla 用 `ARBShaderObjects`/GL20；本层直接用 GL20 |
| FBO + renderbuffer（GL30） | GL 3.0 | ✅ vanilla `Framebuffer` 用同一套；`OpenGlHelper.isFramebufferEnabled()` 标志 |
| `GL_DEPTH24_STENCIL8` + stencil attachment | GL 3.0 / EXT_packed_depth_stencil | ✅ 现代驱动标配；EXT 路径已保留 |

**唯一需要留意**：`blur.frag` 和 `stencil.frag` 是 `#version 130`（GL 3.0）。在极老的仅 GL 2.1 驱动上会编译失败；现代硬件无影响。最小降级方案 = 改成 `#version 120` + `texture()` → `texture2D()`（见第 3 节）。

---

## 2. 逐文件表（分类：A 直接复用 / B Java8 兼容 / C Forge 适配 / D 必须重写 / E 删除）

> 说明：几乎所有渲染文件都 `implements SharedConstants`（`SharedConstants.api = today.opai.api.OpenAPI`），通过 `api.getGLStateManager()` 访问 GL 状态。因此「C」是主流分类；标记为 A 的是**纯 Java、无 Opai/GL 依赖**可直接拷贝的文件。B 表示纯逻辑但用了 Java 9+ 语法。

### 2.1 rendering 根目录（13）

| 文件 | 分类 | 职责 | 依赖 |
|---|---|---|---|
| `AnimatedTexture` | C | 精灵图逐帧动画（.mcmeta 风格 JSON），支持 interpolate 补帧；用 java.awt Graphics2D 生成补帧 → DynamicTexture 上传 → GL11 立即模式绘制 | Display、TextureManager、DynamicTexture、Location、JsonUtils、MultiThreadingUtil、Timer、ImageIO |
| `BooleanState` | A | GL capability 开关缓存（glEnable/glDisable 幂等封装） | 仅 GL11 |
| `ChatAllowedCharacters` | A | 字符白名单过滤（复制自 MC 的 ChatAllowedCharacters） | 无 |
| `Framebuffer` | C | FBO 封装：颜色纹理 + depth24-stencil8 renderbuffer + 嵌套 stencil 状态保存/恢复 + 显示列表绘制全屏 quad + `mcFramebuffer`（主帧缓冲）探测 | OpenGlHelper、TextureUtil、EXTFramebufferObject、GL30、Display、`api.getGLStateManager()`、StencilClipManager |
| `GaussianKernel`（根目录，静态 `generate(int)`） | A | 2D 高斯卷积核生成（纯数学，HashMap 缓存） | 无（未被 shader 使用；shader 用的是 impl 版） |
| `Image` | C | 通用贴图绘制（drawModalRectWithCustomSizedTexture 及 flip/rotate 变体、linear/nearest、渐变透明） | GL11、TextureManager、DynamicTexture、FontManager、Location |
| `MusicToast` | C | HUD「正在播放」Toast；依赖 Opai `Font`（vanilla 字体）+ `api.getFontUtil()` + `api.getGLStateManager()` | **today.opai.api.interfaces.render.Font**、FontManager、AnimatedTexture、Animation、RGBA |
| `OpenGlHelper` | C | FBO 函数的 GL30 薄封装（`isFramebufferEnabled()` 恒返 true；照抄 vanilla OpenGlHelper 的 FBO 子集） | GL30 |
| `Rect` | C | 矩形绘制，委托 `RenderSystem.drawRect` | RenderSystem |
| `RGBA` | A | ARGB 颜色打包/解包/灰度/lerp（纯 Java） | lombok @UtilityClass、Mth、RenderSystem.DIVIDE_BY_255（仅常量） |
| `StencilClipManager` | C | 基于 stencil 缓冲的嵌套裁剪（GL_INCR/GL_EQUAL 维护 stencil 值栈），依赖 `Framebuffer.currentlyBinding` 有 stencil | GL11、`api.getGLStateManager()`、BooleanState、Framebuffer |
| `TextureManager` | C | 纹理注册表（Location → ITextureObject，ConcurrentHashMap）+ 动态纹理命名/绑定 | DynamicTexture、ITextureObject、TextureUtil、Location |
| `TextureUtil` | C | 纹理上传/过滤/钳制/删除工具（`api.getGLStateManager().generateTexture/deleteTexture/bindTexture`） | GL11/12/14、`api.getGLStateManager()` |

### 2.2 animation（4）+ animation/spring（4）

| 文件 | 分类 | 职责 | 依赖 |
|---|---|---|---|
| `Animation` | A | 基于 easing + 纳秒计时的单值动画 | 纯 Java（Duration） |
| `Easing` | A | 25 种缓动函数（enum + `Function<Double,Double>`） | commons-lang3 StringUtils |
| `Interpolations` | C | 帧率无关插值/颜色插值/线性插值，依赖 `RenderSystem.getFrameDeltaTime()` | RenderSystem、Mth、java.awt.Color |
| `MultipleEndpointAnimation` | B | 多端点顺序动画 | **Java 21+：`List.getFirst()/getLast()`** |
| `SpringAnimation` | A | 弹簧物理动画（解析解，可排队参数/位置） | 纯 Java（DoubleUnaryOperator） |
| `SpringParams` | A | 弹簧参数（mass/damping/stiffness/soft） | 纯 Java |
| `QueuedParams` | A | 延迟生效的弹簧参数队列项 | 纯 Java |
| `QueuedPosition` | A | 延迟生效的目标位置队列项 | 纯 Java |

### 2.3 entities/impl（2）

| 文件 | 分类 | 职责 | 依赖 |
|---|---|---|---|
| `ScrollText` | C | 超宽文本横向滚动（stencil 裁剪 + 两个 FBO + StencilShader 合成渐变淡出） | Display、GL11、Framebuffer、StencilClipManager、Shaders.STENCIL、CFontRenderer、Animation、Interpolations |
| `TextField` | B+C | 文本框（光标/选择/复制粘贴/输入过滤，与 MC GuiTextField 同构） | Keyboard、Mouse、ChatAllowedCharacters、StencilClipManager、RenderSystem、KeyboardUtils；**Java 14+ switch 表达式 + `String.repeat`(Java 11+)** |

### 2.4 font（5）

| 文件 | 分类 | 职责 | 依赖 |
|---|---|---|---|
| `CFontRenderer` | C | 自绘字体渲染器：字形图集 + 立即模式/显示列表绘制 + 换行/裁切/阴影/居中 | **java.awt**（Font/FontMetrics）、GL11、`api.getGLStateManager()`、TextureAtlas、GlyphGenerator、RGBA、RenderSystem；**Java 14+ switch 表达式（getColorCode）** |
| `Glyph` | A | 单个字形（宽高 + UV + uploaded 状态） | lombok |
| `GlyphGenerator` | C | 用 **java.awt**（FontRenderContext/GlyphVector/FontMetrics/Graphics2D）离线光栅化字形 → 异步上传 TextureAtlas | java.awt.*、ImageIO、MultiThreadingUtil、TextureAtlas |
| `FontKerning` | A | 字间距（当前为 stub，恒返 0） | 无 |
| `TextureAtlas` | C | 2048×2048 字形图集（GL_ALPHA 单通道，glTexSubImage2D 增量上传） | GL11/12/14、`api.getGLStateManager()`、MultiThreadingUtil、java.awt.image.BufferedImage |

### 2.5 rendersystem（1）

| 文件 | 分类 | 职责 | 依赖 |
|---|---|---|---|
| `RenderSystem` | C（核心适配点） | 2D 渲染中枢：drawRect/gradient、纹理过滤、FBO 工厂、scaleFactor、hover 判定、颜色工具 | **today.opai.api**（OpenAPI / EventRender2D / EventHandler / GLStateManager / WindowResolution）、Display、Mouse、GL11、Framebuffer、RGBA、Rect |

### 2.6 shader（4）+ shader/impl（12）+ shader/uniform（7）

| 文件 | 分类 | 职责 | 依赖 |
|---|---|---|---|
| `Shader`（抽象） | A | shader 抽象基类（active 标志 + run/update） | 无 GL（lombok） |
| `ShaderCompiler` | C | GLSL 编译链接（读 `/tritium/shaders/*` 资源） | GL11/GL20、Location |
| `ShaderProgram` | C | program 封装 + 全屏 quad 绘制（立即模式 + 显示列表缓存） | GL11/GL20、RenderSystem |
| `Shaders` | C | 全部 shader 单例注册表 | impl/* |
| `BlendShader` | C | 预乘 alpha 混合（blend.frag） | ShaderProgram、Uniform1i |
| `BloomShader` | C | 泛光（两次 separable 高斯 + bloom.frag，双 FBO ping-pong，radius=12） | GL11/13、BufferUtils、Display、Framebuffer、ShaderProgram、uniform/* |
| `Deconverge` | C | RGB 通道色散（deconverge.frag，chromatic aberration） | Display、ShaderProgram、Uniform1i/2f/3f |
| `GaussianBlurShader` | C | 高斯模糊（blur.frag，双 FBO ping-pong，radius=5，`u_kernel[128]`） | GL11/13、BufferUtils、Display、Framebuffer、ShaderProgram、uniform/* |
| `GaussianKernel`（impl，实例版 `getKernel()`） | A | 1D 高斯核（供 blur/bloom uniform） | 纯 Java（FloatBuffer） |
| `ROQShader` | C | 圆角描边（roq.glsl，SDF） | GL11、ShaderProgram、Uniform1f/2f/4f、java.awt.Color |
| `ROGQShader` | C | 圆角描边 + 四角渐变（rogq.frag） | 同上 |
| `RQShader` | C | 纯色圆角矩形（rq.frag，SDF + smoothstep 抗锯齿） | 同上 |
| `RQTShader` | C | 圆角贴图矩形（rqt.frag，支持 uv offset/scale） | GL11、ShaderProgram、Uniform1f/1i/2f |
| `RQGShader` | C | 圆角 + 四角颜色渐变（rqg.frag，含噪声 dithering） | GL11、ShaderProgram、Uniform1f/2f/4f、java.awt.Color |
| `StencilShader` | C | 用 stencil 纹理做 alpha 遮罩（stencil.frag，双纹理采样） | GL11/13、ShaderProgram、Uniform1i、RenderSystem |
| `VFFadeoutShader` | C | 贴图向上渐变淡出（vf_fadeout.frag，覆盖流背景用） | GL11、ShaderProgram、Uniform1f/1i/2f |
| `Uniform1f/1i/2f/3f/4f/4FB/FB` | C | uniform 封装（惰性 setValue 避免重复 glUniform） | GL20、ShaderProgram |

### 2.7 texture（5）

| 文件 | 分类 | 职责 | 依赖 |
|---|---|---|---|
| `AbstractTexture` | C | 纹理基类（glTextureId 惰性生成、过滤/mipmap 状态） | GL11、TextureUtil、RenderSystem、`api.getGLStateManager()`、TritiumMusicExtension（主线程判定）、MultiThreadingUtil |
| `DynamicTexture` | C | 从 BufferedImage 上传纹理（分块 ByteBuffer + glTexSubImage2D，主线程判定 + 阻塞调度） | GL11/12/14、java.awt.image、ImageIO、MultiThreadingUtil、TritiumMusicExtension |
| `FilterState` | A | 过滤枚举（LINEAR/NEAREST） | 无 |
| `ITextureObject` | A | 纹理对象接口 | 无 |
| `Textures` | C | 纹理加载门面（同步/异步下载+上传，`HttpUtils.downloadStream`） | DynamicTexture、TextureManager、HttpUtils、MultiThreadingUtil、TritiumEventHandler（调度） |

### 2.8 ui（1）+ ui/container（3）+ ui/widgets（9）

| 文件 | 分类 | 职责 | 依赖 |
|---|---|---|---|
| `AbstractWidget` | C | 组件基类：父子树、bounds/坐标、alpha 继承、hover/点击/滚轮/键盘事件分发、debug 布局 | `api.getGLStateManager()`（pushMatrix/popMatrix）、RenderSystem、CursorUtils、RGBA、Rect |
| `Panel` | C | 隐形容器（仅分组） | AbstractWidget、Rect |
| `ScrollPanel` | C | 可滚动容器（对齐方式、滚轮、stencil 裁剪、惰性渲染子组件） | Keyboard、StencilClipManager、Interpolations、Rect；**Java 14+ switch 表达式（多处）** |
| `CroppedPanel` | C | 矩形裁剪容器（stencil） | StencilClipManager、Rect |
| `RectWidget` | C | 矩形 widget | Rect、AbstractWidget |
| `RoundedRectWidget` | C | 圆角矩形 widget（走 SharedRenderingConstants.roundedRect → RQShader） | AbstractWidget、SharedRenderingConstants |
| `RoundedImageWidget` | C | 圆角贴图 widget（roundedRectTextured → RQTShader） | TextureManager、Interpolations、ITextureObject、Location |
| `RoundedButtonWidget` | C | 圆角按钮（RoundedRectWidget + 居中 LabelWidget 子组件） | RoundedRectWidget、LabelWidget、CFontRenderer |
| `LabelWidget` | C | 文本 label（超宽时滚动或 trim） | FontManager、ScrollText、CFontRenderer |
| `ImageWidget` | C | 贴图 widget | Image、TextureManager、ITextureObject |
| `IconWidget` | C | 图标（字体图标 CFontRenderer + hover 圆角底 + 点击动画） | Interpolations、CFontRenderer、RenderSystem、AbstractWidget |
| `ScrollLabelWidget` | C | 单行滚动文本（stencil 裁剪） | StencilClipManager、Rect、Interpolations、Timer |
| `TextFieldWidget` | C | TextField 的 widget 封装 | TextField、CursorUtils、CFontRenderer |

---

## 3. Shader 子系统

### 3.1 编译/使用流程

1. `ShaderCompiler.compile(frag, vert)`：`Location.of("/tritium/shaders/" + resource)` 读 classpath 资源 → `GL20.glCreateShader`(FRAGMENT/VERTEX) → `glShaderSource` → `glCompileShader` → `checkIfCompiled`（`glGetShaderi` + `glGetShaderInfoLog`）→ `glCreateProgram` → `glAttachShader×2` → `glValidateProgram` → `glLinkProgram` → `glDeleteShader×2`。
   - 注意：`glValidateProgram` 在 `glLinkProgram` **之前**调用，且未检查 validate 结果，属原项目小瑕疵但无害（结果被忽略）。
2. `ShaderProgram`：持有 `programId`，`start()/stop()` = `glUseProgram(id / 0)`；`drawQuadFlipped/drawQuad` 用 GL11 立即模式 + 显示列表缓存画全屏/任意 quad。
3. `uniform/*`：构造时 `glGetUniformLocation`，`setValue` 时惰性 `glUniform*`。
4. `Shaders` 静态持有全部实例：`BLOOM_SHADER / BLUR_SHADER / BLEND / ROQ / ROGQ / RQ / RQT / RQG / DECONVERGE / STENCIL / VF_FADEOUT`。

### 3.2 shader 资源对照（`resources/tritium/shaders/`）

| 资源 | GLSL 版本 | 使用处 | 播放器是否实际使用 |
|---|---|---|---|
| `vertex.vsh` | `#version 120` | 所有 shader 共用的顶点着色器（`gl_ModelViewProjectionMatrix * gl_Vertex`） | 是（公共） |
| `rq.frag` | 120 | RQShader（纯色圆角矩形） | **是**（UI 圆角底） |
| `rqt.frag` | 120 | RQTShader（圆角贴图） | **是**（封面圆角） |
| `rqg.frag` | 120 | RQGShader（圆角渐变） | 是 |
| `roq.glsl` | 120 | ROQShader（圆角描边） | 是 |
| `rogq.frag` | 120 | ROGQShader（圆角渐变描边） | 是 |
| `blur.frag` | **130** | GaussianBlurShader（模糊） | 是（背景模糊） |
| `bloom.frag` | 120 | BloomShader（泛光） | 是（频谱/高光） |
| `blend.frag` | 120 | BlendShader（预乘 alpha 混合） | 是 |
| `stencil.frag` | **130** | StencilShader（stencil 遮罩合成） | 是（滚动文字淡出） |
| `vf_fadeout.frag` | 120 | VFFadeoutShader（渐变淡出） | 是（封面流背景） |
| `deconverge.frag` | 120 | Deconverge（RGB 色散） | 是（可选特效） |

### 3.3 MC 1.8.9 是否支持这些 GLSL

- **支持**。全部 shader 都用兼容模式内置量：`gl_TexCoord[0]`、`gl_MultiTexCoord0`、`gl_ModelViewProjectionMatrix`、`gl_Vertex`、`texture2D()`。没有任何 core-profile 语法（无 `in/out`、无 VAO、无 `#version 330+`）。
- MC 1.8.9 用 LWJGL 2 Display 创建的是**兼容上下文**（非 core），因此 `gl_TexCoord[0]` 这类弃用但兼容的内置量可用。
- `#version 130`（blur.frag / stencil.frag）要求 GL 3.0 兼容上下文。任何支持 MC 1.8.9 的现代驱动都满足；极老 GL 2.1 显卡会失败。
  - **最小降级**：`#version 130` → `#version 120`，并把 `texture()` → `texture2D()`（两处），即可降到 GL 2.1 兼容。建议移植时直接做这个降级以扩大兼容面。
- 没有用到 MC 1.8.9 缺失的能力：无 MSAA、无多重颜色附件（只用 COLOR_ATTACHMENT0）、无 compute/geometry/tessellation。

---

## 4. Framebuffer / Stencil / 模糊

### 4.1 Framebuffer（FBO）

`Framebuffer.java` 是 vanilla `net.minecraft.client.shader.Framebuffer` 的移植版，结构几乎一致：
- 构造：`glGenFramebuffers` + `glGenTextures`（颜色）+ 可选 `glGenRenderbuffers`（depth24-stencil8）。
- `createFramebuffer`：`glTexImage2D(RGBA8)` 建颜色纹理 → `glFramebufferTexture2D(COLOR_ATTACHMENT0)` → `glRenderbufferStorage(GL_DEPTH24_STENCIL8)` → `glFramebufferRenderbuffer(DEPTH_ATTACHMENT)` + `EXTFramebufferObject.glFramebufferRenderbufferEXT(STENCIL_ATTACHMENT)`（stencil 附件走 EXT，因为 GL30 核心无 stencil attachment 常量，与 vanilla 相同）。
- `forceBind/bindFramebuffer/unbindFramebuffer`：切换绑定并保存/恢复 stencil 状态（`saveStencilState/restoreStencilState`）。
- `framebufferRenderExt`：用显示列表（`glGenLists` + 编译的 TRIANGLE_STRIP quad）把 FBO 纹理画回屏幕。
- `updateMcFramebuffer`：探测 MC 主 FBO（`glGetInteger(GL_FRAMEBUFFER_BINDING)` + `glGetFramebufferAttachmentParameteri`）得到 `mcFramebuffer`，供 blur/bloom 在「主帧缓冲」上 ping-pong。

依赖 OpenGL 版本：**GL 3.0（GL30 FBO）+ EXT_framebuffer_object（stencil 附件）**。MC 1.8.9 全支持。

### 4.2 StencilClipManager（stencil 裁剪）

- 用 **GL11 stencil 测试**（无 shader、无 FBO）实现嵌套矩形裁剪。
- 机制：`beginClip()` 关 color/depth mask → `glStencilFunc(GL_ALWAYS,1,0xFF)` → `glStencilOp(KEEP,KEEP,INCR)` 画裁剪形状 → `updateClip()` 恢复 mask、`glStencilFunc(GL_EQUAL, value)`；`endClip()` 弹栈恢复。每个 `Framebuffer` 持有一个 stencil 栈，支持嵌套。
- 依赖 GL11 stencil + `useDepth` FBO（stencil 缓冲附着在 depth24-stencil8 renderbuffer 上）。MC 1.8.9 全支持。

### 4.3 模糊/泛光（GaussianBlurShader / BloomShader）

- 实现：**FBO + shader**。两个全屏 FBO（input/output）做 separable 两趟高斯（水平 + 垂直），`BufferUtils.createFloatBuffer` 上传 1D 核到 `u_kernel`，`u_texel_size`/`u_direction` 控制步长方向。
- 多纹理：`GL13.glActiveTexture(GL_TEXTURE20 / GL_TEXTURE0)` 绑定两幅纹理（diffuse + other）。
- Bloom 额外做 alpha 预乘、blend func 切换、半径 12；Blur 半径 5。
- 依赖 OpenGL 版本：GL20（shader）+ GL30（FBO）+ GL13（多纹理）。MC 1.8.9 全支持；`blur.frag` 的 `#version 130` 见 3.3 降级说明。

---

## 5. 字体渲染

- **强依赖 java.awt**：`GlyphGenerator` 用 `FontRenderContext`、`GlyphVector`（`createGlyphVector`、`getGlyphMetrics().getAdvance()/getLSB()`）、`FontMetrics`（ascent/descent）、`Graphics2D`（`drawString` + `RenderingHints` 抗锯齿）把每个字符离线光栅化成 `BufferedImage`，再白化成 alpha 单通道上传到 `TextureAtlas`。
- `CFontRenderer`：构造时 `font.deriveFont(sizePx * 2)`；`drawString` 用 GL11 `GL_TRIANGLES` 立即模式（备选显示列表 `glCallList`）逐字形贴图绘制；支持 `§` 颜色码、居中、阴影、描边、`fitWidth` 中文换行/括号成对处理。
- **依赖字体资源**：`resources/tritium/fonts/` 下有 `icomoon.ttf`、`music.ttf`、`pf_middleblack.ttf`、`pf_normal.ttf`、`sfbold.otf`、`sfregular.otf`（由 `FontManager`/`Font.createFont` 加载，非 rendering 目录）。
- `TextureAtlas`：2048×2048、`GL_ALPHA` 单通道纹理，`glTexSubImage2D` 增量上传；依赖 GL11/12/14。
- MC 1.8.9 适配点：java.awt 在 JRE 8 可用、无问题；但字形光栅化放在 `MultiThreadingUtil.runAsync` 异步线程、上传放主线程，需换成 Forge 的主线程调度。整体可复用，属 C。

---

## 6. 圆角 / 图片 / 纹理

- **圆角实现 = Shader（GL20），不是 stencil、不是顶点、不是三角扇**：
  - 纯色圆角 `rq.frag`（RQShader）：SDF（`length(max(abs(p)-b,0))-r`）+ `smoothstep` 抗锯齿。
  - 圆角贴图 `rqt.frag`（RQTShader）：贴图采样 + SDF 圆角 alpha，支持 `u_offset/u_scale` 做 uv 裁剪。
  - 圆角渐变 `rqg.frag`（RQGShader）：四角颜色 `mix` + 噪声 dithering。
  - 圆角描边 `roq.glsl`/`rogq.frag`：SDF 描边（`smoothstep` 差）。
  - 上层入口：`SharedRenderingConstants.roundedRect/roundedRectTextured/roundedOutline/roundedOutlineGradient/roundedRectGradient*` → 静态调用 `Shaders.RQ_SHADER / RQT_SHADER / RQG_SHADER / ROQ_SHADER / ROGQ_SHADER`。
- **图片**：`Image.drawModalRectWithCustomSizedTexture*` 系列 = GL11 立即模式 `GL_QUADS`（与 vanilla `Gui.drawModalRectWithCustomSizedTexture` 同构），flip/rotate/渐变 alpha 变体齐全。
- **纹理**：`DynamicTexture` 从 `BufferedImage`（`ImageIO.read`）分块转 `ByteBuffer`（`GL_BGRA`/`GL_UNSIGNED_INT_8_8_8_8_REV`）上传；`AbstractTexture` 管 glTextureId 生命周期；`TextureManager` 管 Location→纹理映射；`Textures` 门面负责异步下载+上传。DynamicTexture 依赖 java.awt.image + 主线程判定（`TritiumMusicExtension.isCallingFromMainThread`）→ C。

---

## 7. UI widget 层

层级与职责：

- **`AbstractWidget<SELF>`**（基类）：泛型 self 链式 API；`Bounds`（相对父坐标 x/y + width/height）；`children` 树（CopyOnWriteArrayList）；`getX/getY` 递归累加父坐标；alpha 沿父子链相乘；`renderWidget` 递归渲染（beforeRenderCallback → onRender → 子组件 → debug）；`onMouseClickReceived/onDWheelReceived/onKeyTypedReceived` 事件自底向上分发（子优先，子未消费才轮到自身）；hover 判定；`setMargin/expand/center/setTransformations` 等布局工具。用 `api.getGLStateManager().pushMatrix/popMatrix` 包变换。
- **`Panel`**：空容器（分组）。
- **`CroppedPanel`**：`StencilClipManager.beginClip(矩形)/endClip()` 裁剪子内容。
- **`ScrollPanel`**：VERTICAL / HORIZONTAL / VERTICAL_WITH_HORIZONTAL_FILL 三种排列；`targetScrollOffset` 经 `Interpolations.interpolate` 平滑滚动；滚轮 + Shift 加速；`alignChildren` 重排子坐标；stencil 裁剪可视区 + `shouldRenderChildren` 视口剔除。
- **widgets**：`RectWidget`（矩形）、`RoundedRectWidget`（圆角矩形）、`RoundedImageWidget`（圆角贴图 + fadeIn）、`RoundedButtonWidget`（圆角按钮 = RoundedRectWidget + 居中 LabelWidget）、`LabelWidget`（文本，超宽时 ScrollText 滚动或 trim）、`ImageWidget`（贴图）、`IconWidget`（字体图标 + hover 圆角底 + 点击涟漪动画）、`ScrollLabelWidget`（单行滚动）、`TextFieldWidget`（包裹 `TextField`）。

---

## 8. 动画

- **纯 Java，可整体复用（A）**：
  - `Easing`：25 种缓动函数（enum + lambda），Java 8 完全兼容。
  - `Animation`：`System.nanoTime()` 计时 + easing 插值，支持 run/reset/setValue。
  - `MultipleEndpointAnimation`：多端点顺序动画（**仅 `getFirst/getLast` 是 Java 21+，需降级为 `get(0)/get(size-1)`，B**）。
  - `spring/*`：`SpringAnimation` 用阻尼弹簧解析解（质量/阻尼/刚度），支持 `QueuedParams`/`QueuedPosition` 延迟生效；纯数学，A。
- `Interpolations` 因依赖 `RenderSystem.getFrameDeltaTime()`（帧率无关插值）归 C；但本身是纯数学，只需替换 delta time 来源。

---

## 9. today.opai.api 依赖（渲染层内）

全目录**直接** `import today.opai.api` 的只有两个文件：

| 文件 | import | 用途 |
|---|---|---|
| `rendersystem/RenderSystem.java` | `today.opai.api.OpenAPI`、`today.opai.api.events.EventRender2D`、`today.opai.api.interfaces.EventHandler`、`today.opai.api.interfaces.render.GLStateManager`、`today.opai.api.interfaces.render.WindowResolution` | 静态块里 `api.registerEvent(EventHandler.onRender2D)` 取 scaleFactor/width/height；全文件用 `api.getGLStateManager()` |
| `MusicToast.java` | `today.opai.api.interfaces.render.Font` | `api.getFontUtil().getVanillaFont()` 拿 MC 原版字体画 Toast 文本 |

**间接依赖**（所有渲染类都 `implements SharedConstants`）：
- `SharedConstants.api = ExtensionEntry.getAPI()` → `today.opai.api.OpenAPI`。
- `api.getGLStateManager()` 返回 Opai 的 `GLStateManager`（约等于 vanilla `GlStateManager` 的 1:1 包装，方法名完全对应）。
- `api.getFontUtil().getVanillaFont()`（MusicToast 用）、`api.registerEvent(...)`（RenderSystem 用）。

Opai 的 `GLStateManager` 接口方法（在 rendering 中被调用的）与 MC 1.8.9 vanilla `GlStateManager` 静态方法一一对应：`enableBlend/disableBlend/enableAlpha/disableAlpha/enableTexture2D/disableTexture2D/enableDepth/disableDepth/depthMask/colorMask/color/bindTexture/deleteTexture/generateTexture/tryBlendFuncSeparate/blendFunc/alphaFunc/shadeModel/matrixMode/loadIdentity/ortho/translate/scale/rotate/pushMatrix/popMatrix/viewport/clearColor/clearDepth/clear/callList/enableColorMaterial/disableLighting/setActiveTexture`。**这些全部是 MC 1.8.9 `GlStateManager` 已有的方法**，适配成本极低（写一个薄 adapter 或全局替换调用点）。

---

## 10. 结论与适配点

### 10.1 可直接复用（A，纯 Java 拷贝即用）

`Easing`、`Animation`、`SpringAnimation`、`SpringParams`、`QueuedParams`、`QueuedPosition`、`GaussianKernel`（根 + shader.impl 两个）、`RGBA`、`ChatAllowedCharacters`、`Glyph`、`FontKerning`、`FilterState`、`ITextureObject`、`BooleanState`、`Shader`（抽象基类）。

### 10.2 需 Java 8 降级（B）

| 位置 | 问题 | 改法 |
|---|---|---|
| `CFontRenderer.getColorCode` | switch 表达式 `case '0' -> ...`（Java 14+） | 改经典 switch 语句或查表 |
| `TextField.textboxKeyTyped` | switch 表达式 + `yield`（Java 14+） | 改经典 switch 语句 |
| `TextField.getDisplayText` | `"*".repeat(n)`（Java 11+） | 手写循环 / `StringBuilder` |
| `ScrollPanel` 多处 | switch 表达式 `case X -> { ... yield ... }`（Java 14+） | 改经典 switch 语句 |
| `MultipleEndpointAnimation` | `List.getFirst()/getLast()`（Java 21+） | 改 `get(0)/get(size()-1)` |

### 10.3 需 Forge/1.8.9 适配（C）—— 具体适配点

1. **GLStateManager 替换**：全局 `api.getGLStateManager().xxx()` → vanilla `GlStateManager.xxx()`（方法一一对应，见第 9 节）。工作量小，可写一个 `interface GLStateManager` + MC 实现作为过渡。
2. **屏幕尺寸**：`Display.getWidth()/getHeight()`（RenderSystem/Framebuffer/ScrollText/Bloom/GaussianBlur/Deconverge）→ 用 `ScaledResolution`/`Minecraft.getMinecraft().displayWidth/displayHeight`；scaleFactor 用 `new ScaledResolution(mc).getScaleFactor()`。
3. **渲染事件注册**：`RenderSystem` 静态块 `api.registerEvent(EventRender2D)` → Forge `RenderGameOverlayEvent.Post`（HUD）或 `GuiScreenEvent.DrawScreenEvent` / 自定义 GUI 的 draw 里手动更新 scaleFactor/width/height。
4. **字体**：`api.getFontUtil().getVanillaFont()`（MusicToast）→ MC `FontRenderer`（`mc.fontRendererObj`）或其 `Font` 接口适配。
5. **主线程判定/调度**：`TritiumMusicExtension.isCallingFromMainThread()`、`MultiThreadingUtil.runOnMainThread/runOnMainThreadBlocking/runAsync`、`TritiumEventHandler.addScheduledTask` → `Minecraft.isCallingFromMinecraftThread()` + `addScheduledTask`（或 `GlStateManager` 线程安全包装）。**关键约束：GL 调用必须回主线程，耗时任务（字形光栅化/图片解码/纹理上传准备）放异步线程。**
6. **键盘/鼠标**：`org.lwjgl.input.Keyboard/Mouse` 在 MC 1.8.9 同样可用（vanilla 自己就用），无需替换；仅当走 GuiScreen 时才需要接管事件。
7. **`Location` / `JsonUtils` / `Mth` / `Timer` / `Lazy` / `KeyboardUtils` / `CursorUtils` / `HttpUtils` / `FontManager` / `ClientSettings`**：这些是 `tritium.utils.*`/`tritium.management.*`/`tritium.settings.*` 的兄弟工具，不在本审计范围，但渲染层强依赖，需一并审计/移植（多为纯 Java，A/B）。
8. **Shader 资源路径**：`Location.of("/tritium/shaders/...")` 读 classpath 资源，Forge 下保持 `src/main/resources/tritium/shaders/` 位置不变即可。
9. **`Framebuffer.updateMcFramebuffer` 探测主 FBO**：依赖 MC 的 FBO 绑定状态，在 Forge 下应在每帧渲染前正确调用，且需与 MC 自身的 `Framebuffer`（vanilla）区分开，避免 `currentlyBinding` 静态状态串扰。

### 10.4 必须重写 / 删除（D/E）

- **无 D（无需整类重写）**。GLSL 与 GL 调用都是 MC 1.8.9 已支持的能力，不存在「MC 缺失、必须砍掉」的功能。
- **E（可删）**：`FontKerning` 目前是 stub（恒返 0），若原项目后续无真实字间距需求，可保留或删除；`GaussianKernel`（根目录静态版）未被 shader 使用（shader 用 impl 版），可删除。`AnimatedTexture` 内被注释掉的 `Tessellator/WorldRenderer/DefaultVertexFormats` 代码块是原作者的 MC 1.8.9 思路草稿，可作为移植参考，不需保留。

### 10.5 最终可行性判定

- **Shader 子系统：能在 MC 1.8.9 跑**。GLSL 版本 120/130 + 兼容内置量；唯一建议是把 `blur.frag`/`stencil.frag` 从 `#version 130` 降级到 `#version 120`（`texture()`→`texture2D()`）以兼容老驱动。
- **FBO / Stencil / 模糊：能在 MC 1.8.9 跑**。GL30 FBO + EXT stencil 附件 + GL11 stencil 测试 + GL13 多纹理，全部是 MC 1.8.9 已有能力（vanilla `Framebuffer` 同款实现）。
- **字体：依赖 java.awt**（JRE 8 自带，无兼容问题）；需做的是把字形光栅化的异步线程与纹理上传的主线程调度换成 Forge 机制。
- **圆角：Shader 实现**（非 stencil/顶点），随 shader 子系统一并可行。
- **最大工作不在 GL，而在「解耦 Opai」**：把 `api.getGLStateManager()`/`api.registerEvent`/`Display`/`api.getFontUtil()` 换成 MC 1.8.9 等价物，以及把 `MultiThreadingUtil`/`TritiumMusicExtension` 换成 Forge 线程模型。GL/GLSL 本身无需降级、无需引 LWJGL 3、无需引入 MC 没有的能力。
