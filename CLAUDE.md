Deuterium Music Standalone
Minecraft 1.8.9 Forge · 编程 Agent 执行规范 v2.0
用途：本文件直接提供给 Coding AI / 编程 Agent。它不是普通需求介绍，而是开发时必须遵守的执行合同。
0. 一句话任务定义
把 Deuterium 原项目中的网易云音乐播放器完整抽离，做成可独立运行的 Minecraft 1.8.9 Forge Mod；原项目是唯一产品标杆，目标是“移植”，不是“重新设计一个类似播放器”。
1. 最高优先级规则（必须先读）
●Deuterium 原源码、资源和实际运行表现 > AI 自己的设计判断。
●能直接复用原代码就直接复用；只有不兼容时才改写。
●允许改变内部实现，不允许无必要改变外部行为、UI、交互、数据流和状态。
●不得为了省事删除原功能，不得用“功能类似”的新实现冒充移植完成。
●不得自行寻找新的网易云 API、重新实现加密或替换原 API 链路。
●不得擅自升级 Java、Forge、LWJGL 或引入现代 Minecraft/Fabric/NeoForge API。
●每次改动必须能回答：原代码在哪里？为什么不能直接用？1.8.9 如何等价实现？如何验证？
2. 工作方式：先审计，后编码
任何模块开始编码前，严格执行：
1.定位 Deuterium 对应源码和资源。
2.递归检查直接依赖，不要凭文件名猜测依赖。
3.记录原模块的输入、输出、状态、线程、事件、UI 和错误处理。
4.把依赖分为：A 可直接复用 / B Java8兼容 / C Forge1.8.9适配 / D 必须重写 / E 可删除。
5.优先处理 A；B/C 做最小改动；D 必须说明原因；E 必须证明播放器不需要。
6.完成一个模块就编译、运行和回归，不要最后一次性修全部问题。
3. 原项目对照基准
播放器 UI 与逻辑至少应对照以下原项目模块（实际源码名称以仓库为准）：
●tritium.ncm.api.CloudMusicApi
●tritium.ncm.RequestUtil
●tritium.ncm.OptionsUtil
●tritium.ncm.CryptoUtil
●tritium.ncm.DeviceIdGenerator
●tritium.ncm.math.*
●tritium.ncm.music.*
●NCMScreen / NCMPanel
●MusicLyricsPanel / LyricLine / LyricParser
●ControlsBar / NavigateBar / MusicWidget
●HomePanel / PlaylistPanel / CoverflowOverlay
4. 网易云 API：P0，直接复用
不要重新开发网易云 API。 原项目已经包含 API、请求、加密、设备 ID、音乐数据和播放器相关实现。
必须优先抽取并复用：
●CloudMusicApi.java
●RequestUtil.java
●OptionsUtil.java
●CryptoUtil.java
●DeviceIdGenerator.java
●ncm/math/*
●ncm/music/*
禁止：
●换第三方网易云 API。
●自己重新写网易云加密。
●无依据地更换 Endpoint、参数或返回模型。
●为了“更现代/更简洁”重写 CloudMusicApi。
允许：Java 8 兼容、异步线程改造、Forge 生命周期适配、明确失效接口的必要修复。所有修改必须记录。
5. 播放器核心：优先原样移植
●Music / Playlist / Lyric 等数据模型优先原样。
●AudioPlayer、播放队列、播放/暂停、上一首/下一首、Seek、循环/随机等行为以原项目为准。
●MP3/JLayer、FLAC、JSyn 等原项目音频依赖先验证，禁止无理由换播放器框架。
●API、音频、歌词必须与 UI 解耦。
●网络请求、下载、解码、歌词解析、FFT 等耗时任务不得阻塞 Minecraft Render/Main Thread。
6. UI：高保真，不自行设计
必须尽可能还原原播放器，包括：
●全屏播放器、搜索界面、搜索结果、歌单、歌单详情。
●专辑封面、背景、模糊、歌词、逐字歌词、进度条、控制栏。
●播放/暂停、上下首、播放模式、Seek、音量。
●HUD、音乐信息、歌词、频谱/可视化（原项目有则保留）。
●字体、字号、布局、间距、颜色、透明度、圆角、阴影、Hover/Press、动画和页面转场。
内部渲染实现可以不同，但视觉和交互结果必须尽可能一致。不得用原生 GuiButton 等简单控件替代原自定义 UI。
7. Tritium / 渲染层：只抽取实际依赖
不要把整个 Tritium 当作必须完整移植的前提。递归分析 NCM UI 实际使用的渲染类，只抽取必要子集。
典型需要适配的能力：
●RoundedRect / RoundedImage / Image / Text / Texture。
●Framebuffer / Stencil / Scroll / Clip。
●Animation / Easing / Interpolation。
●Shader / Blur / Bloom（原 UI 实际使用时才移植）。
●字体和纹理管理。
1.8.9 渲染后端优先使用 Minecraft 已有的 LWJGL 2 / GL11 / GlStateManager / Tessellator / TextureManager。
8. LWJGL：禁止暴力降级，也禁止无理由引入 LWJGL 3
不要把“LWJGL 3 → LWJGL 2”当成机械替换 import；也不要为了保留原代码而强行把 LWJGL 3 整套塞进 1.8.9。
●先扫描实际使用的 org.lwjgl.* API。
●普通 OpenGL 调用若有 LWJGL 2 等价物，可适配。
●GLFW、独立 Context、LWJGL 3 native/MemoryStack 等必须单独评估。
●播放器 UI 默认使用 Minecraft 已存在的 OpenGL Context。
●只有确认某功能无法等价适配时，才讨论局部 LWJGL 3 共存方案。
9. 1.8.9 硬性兼容边界
●目标：Minecraft 1.8.9 Forge；目标 Java：8。
●不得使用 Java 9+ API/语法。
●不得依赖现代 Minecraft Rendering API、Fabric、NeoForge。
●不得破坏 Minecraft 原有 OpenGL Context。
●网络/音频/FFT 等异步任务与渲染线程严格隔离。
10. 推荐目标架构
Minecraft 1.8.9 Forge└─ DeuteriumMusic   ├─ ncm/                 ← 原项目 API/数据/加密，最小兼容   ├─ audio/               ← 原 AudioPlayer/解码链   ├─ lyrics/              ← 原歌词逻辑   ├─ ui/                  ← 原 NCM UI 逻辑   └─ renderer/            ← 仅必要的 1.8.9 渲染适配       ├─ shapes       ├─ texture       ├─ text       ├─ stencil       ├─ framebuffer       └─ shader
不要把 API、播放器和 UI 写成一个互相调用的巨型类。
11. 每个模块的修改记录格式
模块：原文件：原依赖：原行为：分类：A直接复用 / B Java8兼容 / C Forge适配 / D必须重写 / E删除改动：改动原因：是否改变外部行为：验证：
12. 分阶段开发与验收
S0 · 源码审计：完成 NCM/API/Audio/UI/Renderer 依赖树；禁止直接开始大规模编码。
S1 · Forge 基础工程：干净 1.8.9 Forge 能启动，Mod 正常加载。
S2 · API：搜索/歌曲详情/播放地址/歌词链路可用；异步不阻塞主线程。
S3 · 音频：真实 MP3 播放、暂停、Seek、上下首、停止。
S4 · 基础 UI：全屏播放器、控制栏、封面、进度条、歌词。
S5 · 完整 UI：搜索、歌单、Coverflow、动画、HUD、频谱等按原项目逐项恢复。
S6 · 渲染高保真：圆角、裁剪、模糊、Shader 等原 UI 实际使用效果完成。
S7 · 回归：原项目 vs 1.8.9 逐项对照；clean build；干净客户端测试。
13. “完成”判定：禁止伪完成
●能打开 GUI 但没有真实播放，不算完成。
●只有本地播放、没有原 API 链路，不算完成。
●UI 能用但与原项目明显不同，不算高保真完成。
●编译成功但没有实际运行测试，不算完成。
●通过注释掉核心功能、删除异常路径来“修复”编译错误，不算完成。
14. 遇到问题时的决策树
原代码能在 1.8.9 使用？ → 直接复用。↓ 否只是 Java 版本问题？ → 做 Java 8 等价改写。↓ 否只是 Minecraft/渲染 API 不同？ → 建 Adapter，保持外部行为。↓ 否是 LWJGL 3 特有能力？ → 单独审计，不得暴力降级或直接引入。↓确实无法等价实现？ → 记录限制、提出最小降级方案，等待确认。
15. 给 Agent 的最终启动提示词（可直接复制）
你正在执行的是 Deuterium Music 的移植任务，不是新建播放器。先阅读并审计 Deuterium 原项目，再开始编码。原项目是唯一标杆：API、加密、数据模型、AudioPlayer、歌词、UI 布局、交互和视觉效果都应优先复用。网易云 API 必须直接复用原项目 tritium.ncm；不要自行寻找新的 API 或重写加密。UI 必须尽可能还原原播放器；允许重写底层 Renderer，但不允许改变上层行为。目标环境固定为 Minecraft 1.8.9 Forge + Java 8。不要升级 LWJGL，不要把 LWJGL 3 强塞进客户端；先审计实际 LWJGL 使用，再决定适配方案。每个模块先列出原文件、依赖、改动原因和验证方式。遇到无法兼容的问题，不要自行删除功能或设计替代产品，先记录问题并选择最小兼容方案。最终目标是：在 1.8.9 Forge 中得到一个与 Deuterium 原播放器在功能、UI 和交互上尽可能一致的独立 Mod。
附：外部官方构建参考
这些链接仅用于构建环境参考；不能因为参考工程使用更新版本就擅自升级本项目。
●Minecraft Forge 1.8.9 官方下载页：https://files.minecraftforge.net/net/minecraftforge/forge/index_1.8.9.html
●ForgeGradle 官方仓库：https://github.com/MinecraftForge/ForgeGradle
●Gradle 官方发行版：https://gradle.org/releases/


----------------------------以上的功能部分已完成，但你仍应遵循上述开发原则。下为新任务，任务分项并完成
DeuteriumMusic
AI 工作规划与开发需求
Minecraft 1.8.9 Forge · Java 8 · 个人使用
一、给 AI 的任务目标
基于 Deuterium 原项目，将其音乐播放器功能整理并适配为可独立使用的 Minecraft 1.8.9 Forge Mod，在尽可能保持原播放器功能、UI、交互和网易云数据逻辑的基础上，完成下面 5 项功能补全与问题修复。
你的职责不是重新设计产品，而是：审计原项目 → 制定移植方案 → 实现功能 → 解决 1.8.9 兼容问题 → 编译并验证。
二、最终必须交付的 5 项内容
1. 收藏功能：实现收藏/取消收藏歌曲、收藏/取消收藏歌单、将歌曲加入指定歌单。三者必须使用正确的业务逻辑，优先复用原项目网易云 API。
2.（ 歌词页面：修复歌词页播放进度显示错误，以及音量调节控件无法实际控制播放器的问题。）
对于这项，四个问题一起修复：下载进度条不能正确渲染、歌词页面播放进度条不能正确渲染、歌曲信息框播放进度不能正确渲染、歌词页面音量条不能正常使用
3. 中文搜索：集成 InputFix 1.8.x v2 解决 Minecraft 1.8.9 中文输入问题。不要改动原搜索业务。
4. Coverflow：调整 Coverflow 页面搜索框和歌曲名称尺寸，使其符合原 UI 比例并避免遮挡。
5. 响应式布局：修复窗口尺寸/GUI Scale 改变后控件不等比例布局造成的凸出、重叠和错位。
三、最高优先级原则：原项目就是标准答案
•	先读原项目源码，再决定怎么写；禁止凭功能名称猜测实现。
•	原项目已有的 API、数据结构、播放器状态、UI 和渲染逻辑，能复用就直接复用。
•	不要为了实现功能而重新设计 UI 或另建一套播放器架构。
•	如果原代码与 Forge 1.8.9 不兼容，只改不兼容的部分，并说明原因。
•	不得用假数据、假成功、固定时间、UI 假状态等方式完成需求。
•	本需求只规划这 5 项工作及其必要依赖，不要扩张成无关的客户端重构。
四、开始编码前必须完成的工作
1.	定位 Deuterium 音乐播放器的入口、UI、数据层、API 层、音频层和渲染层。
2.	确认歌曲喜欢、歌单操作、歌单收藏是否已有 API；分别记录调用链。
3.	确认歌词页如何获得当前歌曲、播放位置、总时长和音量。
4.	确认搜索框使用的 Minecraft 1.8.9 输入控件和事件链。
5.	确认 Coverflow 当前坐标/尺寸计算方式。
6.	找出窗口缩放后仍使用固定坐标的控件。
7.	列出必须直接复制、必须适配、必须重写的文件。
五、网易云功能规划
三项收藏相关需求必须严格拆开：
•	歌曲收藏：用户喜欢/取消喜欢单曲；优先使用原项目 like / likeList 等现有链路。
•	歌单收藏：用户收藏/取消收藏整个歌单；必须确认独立的歌单收藏接口，不能拿歌曲 like 接口代替。
•	加入歌单：把歌曲加入用户指定歌单；优先复用原项目 playlist 操作接口。
API 层原则：优先复用 CloudMusicApi、RequestUtil、OptionsUtil、CryptoUtil、DeviceIdGenerator 及原有 music 数据模型。不要自行寻找另一套网易云 API，也不要重新设计登录和加密系统。
六、歌词页面修复规划
•	播放进度必须来自真实 AudioPlayer 状态。
•	歌词时间同步、进度条和时间文字必须使用同一播放时间源。
•	拖动进度条必须实际 Seek。
•	音量控件必须实际调用播放器音量接口。
•	切换/关闭/重新打开歌词页后状态必须继续正确。
•	不得用歌词页面自己的计时器代替真实播放器状态。
七、中文输入规划
直接使用 InputFix-1.8.x-v2 作为项目中文输入修复组件。项目为个人使用，不以商业发布为目标。
•	目标是让系统中文输入法能够正常进入 Minecraft 1.8.9 的文本输入控件。
•	InputFix 只负责输入兼容，不修改 Deuterium 搜索 API 和搜索结果处理。
•	AI 应检查 InputFix 的 CoreMod/Transformer 加载方式，并确保与本项目 Forge 1.8.9 环境兼容。
•	不要自行重新实现一个输入法系统，除非 InputFix 与项目实际架构发生明确冲突。
八、UI 与缩放规划
•	Coverflow 搜索框：缩小到与原页面比例一致。
•	歌曲名称：限制字号和最大宽度，长标题必要时省略。
•	布局：基于当前 ScaledResolution/屏幕尺寸重新计算，而不是继续堆固定坐标。
•	窗口或 GUI Scale 改变后，核心控件必须重新布局。
•	控件不能越出屏幕、相互覆盖或覆盖文字/封面。
•	保持原 Deuterium UI 的视觉风格，不做重新设计。
九、1.8.9 技术边界
•	Minecraft：1.8.9。
•	Forge：优先使用 1.8.9-11.15.1.2318。
•	Java：8。
•	使用 Minecraft 自带的 1.8.9 OpenGL/LWJGL 环境；不要机械升级到 LWJGL 3。
•	HTTP/下载等耗时任务不能阻塞 Minecraft 主线程。
•	后台线程不得直接执行 OpenGL/UI 操作；结果回到 Minecraft 主线程更新 UI。
十、推荐工作顺序
8.	源码审计并建立模块/调用关系图。
9.	整理原项目可直接复用的 API、数据模型和 UI。
10.	完成收藏歌曲、收藏歌单、加入歌单。
11.	修复歌词进度与音量。
12.	接入 InputFix 中文输入。
13.	优化 Coverflow。
14.	重构必要的布局计算以解决缩放问题。
15.	编译并逐项回归测试。
16.	最后整理构建产物和修改说明。
十一、完成标准
☐ 收藏歌曲真实生效，取消后状态正确。
☐ 收藏歌单真实生效，取消后状态正确。
☐ 歌曲可以加入用户指定歌单。
☐ 歌词页显示真实播放进度并正确同步。
☐ 歌词页音量控件能够真实调节音量。
☐ 中文搜索框可以正常输入中文。
☐ Coverflow 搜索框和歌曲名称大小合理。
☐ 窗口/GUI Scale 改变后控件不凸出、不重叠、不严重错位。
☐ 项目能够在目标 Minecraft 1.8.9 Forge + Java 8 环境构建并运行。
十二、AI 工作方式
不要一次性盲目修改整个项目。每个阶段先定位 → 说明方案 → 修改 → 编译 → 验证 → 再进入下一阶段。
每阶段只需要向用户报告以下内容：
•	发现了什么。
•	准备修改什么。
•	修改了哪些文件。
•	是否复用了原项目代码。
•	是否遇到 1.8.9 兼容问题。
•	编译/运行是否通过。
•	下一阶段做什么。
十三、参考资源
•	Minecraft Forge 1.8.9：https://files.minecraftforge.net/net/minecraftforge/forge/index_1.8.9.html
•	InputFix：https://www.curseforge.com/minecraft/mc-mods/inputfix
•	InputFix 1.8.x-v2：https://www.curseforge.com/minecraft/mc-mods/inputfix/files/2216648
•	ForgeGradle：https://github.com/MinecraftForge/ForgeGradle
InputFix 直接作为项目组件使用；其具体加载方式、源码结构和许可证信息以其项目文件为准。本项目不以商业发布为目标。
十四、给 AI 的一句话总指令
以 Deuterium 原项目为唯一标杆，在 Minecraft 1.8.9 Forge + Java 8 上，只完成“收藏体系、歌词页修复、中文输入、Coverflow 优化、响应式布局”五项工作；先审计后编码，最大限度复用原代码和 API，不重新设计播放器，不扩大需求，每阶段验证后再继续，最终交付一个能够实际运行的个人使用版本。
-------------上述任务已完成，以下是新任务
CloudMusicV2歌词时间线严格审计与稳定修复方案 v2.0
目标：任何切歌/延迟/Seek/暂停等特殊情况下，歌词时间线与实际声音保持同一播放会话、同一音频时钟
1. 修复目标
不要“调歌词延迟”，要修复播放会话和时间线的正确性。
当前主问题是：歌词数据的异步生命周期、播放器对象生命周期和 UI 时间线没有形成严格一致性约束。必须改成“当前声音 = 当前 Playback Session = 当前歌词 Timeline”的一一对应关系。
2. 审计到的确定性问题
ID	源码位置	审计结论	风险
R1	CloudMusic.loadLyric()	异步 runAsync；完成后直接 initLyrics()，没有 songId/session 校验	旧歌词可晚到覆盖新歌
R2	stopExistingPlayThread()	只 interrupt + join 播放线程；无法取消已提交到线程池的歌词任务	旧异步任务继续运行
R3	playSong()	loadLyric(song) 在新 AudioPlayer 真正开始前执行	歌词可能先于声音激活
R4	initLyrics()	后台线程直接修改全局 lyrics/currentLyric，并调用 UI position 更新	存在数据竞态及线程边界问题
R5	currentLyric/currentlyPlaying/player	普通 static 字段跨线程读写，没有明确可见性协议	可能观察到混合状态
R6	AudioPlayer.getTotalTimeMillis()	duration() 先转 int 秒再乘 1000	小数秒全部丢失
R7	MusicLyricsPanel	currentTime 来自 player，totalTime 来自截断值	进度比例/Seek 时间线存在误差
R8	AudioSample.position()	源码明确警告并行播放时 position 可能来自任意并发播放实例	切歌时必须保证旧播放实例彻底失效
3. 最终一致性模型
每次开始播放一首新歌，都生成唯一 PlaybackSession。Session 至少绑定：sessionId、songId、AudioPlayer、歌词状态。
对象	必须绑定	禁止
音频 position	当前 Session + 当前 AudioPlayer	读取旧播放器
歌词结果	当前 Session + songId	直接写全局
currentLyric	当前 Timeline + positionMs	使用旧歌曲 currentLyric
进度条	当前 AudioPlayer 的 position/duration	使用 wall clock
Seek	当前 Session 的 AudioPlayer	只改 UI
4. 核心修复：PlaybackSession + Generation
推荐使用 AtomicLong generation；不要依赖 Thread.interrupt 判断歌词任务是否过期。
开始新歌：generation.incrementAndGet()；异步歌词任务捕获 localGeneration + songId；结果返回前验证 generation 与当前 songId；任何不匹配的结果直接 discard。
这是逻辑取消（logical cancellation），不要求线程池真正停止 HTTP/解析任务；只要求旧结果永远不能污染新状态。
5. 切歌严格时序
●先使旧 generation 失效。
●标记旧 Session inactive。
●停止旧 AudioPlayer，保证旧实例不再参与 position 读取。
●清理旧歌词的 active 状态。
●创建新 Session/generation。
●准备/创建新 AudioPlayer。
●真正开始播放后将 Session 标记为 AUDIO_ACTIVE。
●歌词可以先到或后到，但只能暂存于这个 Session。
●歌词提交时读取当前 AudioPlayer 的真实 positionMs，再计算 currentLyric。
6. 歌词异步加载：两阶段提交
后台线程只负责 fetch + parse + 构建完整 LyricResult，不能直接调用 initLyrics() 去改变全局 UI 状态。
推荐：后台 fetch/parse → 校验 session → 写入 Session.pendingLyrics → 回到 Minecraft 主线程 → applyLyricTimeline() → 读取 AudioPlayer.position() → 更新 currentLyric/动画。
这样还可以解决当前 initLyrics() 在后台线程里调用 MusicLyricsPanel.updateLyricPositionsImmediate() 的线程边界问题。
7. currentLyric 查找必须取消旧状态 fallback
当前 findCurrentLyric() 在第一句歌词时间晚于当前进度时，会回退到 currentLyric。如果 currentLyric 属于旧 Session，会造成跨歌污染。
●findCurrentLyric(progress) 只允许基于当前 Timeline 计算。
●第一句尚未到达时返回当前 Timeline 的明确首句/空状态。
●禁止用旧 Session currentLyric 作为 fallback。
8. 音频 position 是唯一真实时钟
声音是时间真相。
●歌词当前行：AudioPlayer.getCurrentTimeMillis()。
●逐字高亮：同一个 position。
●歌词滚动：同一个 position 派生。
●进度条：同一个 position/duration。
●Seek 后：重新读取真实 position。
●暂停/恢复：服从播放器实际 position，不用 wall clock 自行累加。
9. 修复 JSyn position 并发风险
AudioSample.position() 的源码注释明确说明：如果同一 AudioSample 曾被并行播放，position 可能来自任意并发播放实例。
●切歌时必须彻底停止旧 AudioSample。
●旧播放器必须 stop/cleanup 后，新播放器才成为 active player。
●歌词同步使用当前 Session 绑定的 AudioPlayer，不使用裸静态 CloudMusic.player 作为唯一引用。
●PlayThread 内部应保留本 Session 的 player 引用，并验证 Session 仍 active 后再同步歌词。
10. 修复 duration 精度
当前实现：duration() → int 秒 → ×1000。
要求：直接保留原始浮点秒数再换算毫秒，例如 round(player.duration() * 1000.0f)。
建议 positionMs / durationMs 在业务层统一使用 long 毫秒，避免 float 比较和累计误差。
11. 推荐 PlaybackSnapshot
建议增加不可变快照：sessionId、songId、positionMs、durationMs、playing、lyricsReady、seeking。
渲染一帧只获取一次 snapshot；本帧歌词、进度条、逐字高亮全部使用同一 snapshot，避免一帧内读取到不同歌曲。
12. Seek 严格处理
●拖动期间只做 UI 预览。
●松开后调用当前 Session 的 player.setPlaybackTime(targetMs)。
●Seek 后立即读取实际 player positionMs。
●用实际 positionMs 更新 currentLyric。
●重置歌词滚动/逐字显示状态。
●Seek 请求带 sessionId；如果期间切歌，旧 Seek 直接失效。
13. 暂停/恢复严格处理
●暂停不改变时间线基准。
●恢复从实际 player position 继续。
●不计算暂停持续时间并补到歌词时间线。
●如果底层恢复后 position 有轻微跳变，以最终 player position 为准。
14. 必测特殊场景
场景	预期
A → B	B 声音只能对应 B 歌词
A → B → C 快切	最终 active session 必须为 C，A/B 结果全部无效
A 歌词慢、B 歌词快	B 正常显示，A 返回后必须丢弃
A 声音先开始、A 歌词后返回	歌词到达后按当前 A position 对齐
A 歌词先返回、A 声音后开始	歌词暂存，音频激活后按 position 初始化
Seek 后立即切歌	旧 Seek 不得修改新歌
暂停长时间后恢复	歌词不得按 wall clock 向前跑
歌曲接近结束自动下一首	上一首歌词不得污染下一首
短歌曲/小数秒时长	duration 不丢失小数
关闭/重开歌词页	不得创建第二套同步时钟
15. 最小侵入式修改建议
●不要重写整个 CloudMusic/AudioPlayer。
●新增 PlaybackSession / generation。
●把 loadLyric(Music) 改为 loadLyric(Music, Session)。
●把 initLyrics 改成只接受已经验证的当前 Session 结果。
●currentLyric / currentlyPlaying / player 需要建立明确的可见性协议（volatile/主线程提交/快照，按实际实现选择）。
●修复 duration 精度。
●UI 读取 snapshot，不直接在同一帧多次跨线程读取可变全局状态。
16. 禁止的错误修复
●禁止固定 +100ms/-200ms 歌词补偿。
●禁止通过 Thread.sleep() 等待歌词稳定。
●禁止用 System.currentTimeMillis() 代替 audio position。
●禁止用 sleep 计数模拟播放时间。
●禁止只清空 lyrics 而不使旧任务失效。
●禁止只加 synchronized 而不处理 stale session。
●禁止通过重新打开 GUI 掩盖问题。
●禁止大规模改写播放器来解决一个时序 Bug。
17. 推荐实施顺序
1.增加 PlaybackSession + AtomicLong generation。
2.让歌词任务绑定 sessionId + songId。
3.切歌时先 invalidate 旧 session，再停旧播放器。
4.歌词结果提交前做双重校验。
5.将歌词提交改为两阶段提交，并在主线程安全更新 UI 状态。
6.修复 duration 精度。
7.统一 snapshot position/duration 给歌词与进度 UI。
8.修复 Seek 后同步。
9.最后做所有特殊场景回归。
18. 验收标准
●任意切歌顺序下，声音和歌词属于同一个 playback session。
●慢网络、歌词延迟、快速切歌不会出现旧歌词覆盖。
●新歌词到达时按当前真实音频 position 对齐。
●Seek 后无需等待下一轮循环即可重新同步。
●暂停/恢复不会产生 wall-clock 漂移。
●歌曲结尾自动下一首时没有跨歌歌词污染。
●进度条、时间显示、逐字高亮和歌词当前行共享同一 positionMs。
●duration 不再因整数截断产生明显偏差。
●后台线程不直接执行 OpenGL/UI。
●修复不会破坏已有播放、下载、歌单和 UI。
19. 给 Coding AI 的最终执行指令
这是一次严格的播放时序修复，不是 UI 微调。先修复 session/generation 竞态，再修复 duration 精度，然后统一 snapshot/Seek/暂停恢复。所有歌词时间都必须来自当前 Session 的真实 AudioPlayer.position；所有异步歌词结果必须经过 sessionId + songId 校验。旧结果必须能够被安全丢弃。不要使用固定毫秒补偿、wall clock、sleep 计数或重新打开 GUI 作为修复方式。只做必要的最小改动，不要重写整个播放器。完成后必须实际测试快速切歌、慢歌词、Seek、暂停/恢复、结尾和短歌曲。
20. 源码依据
审计来源：用户提供的 CloudMusicV2-master.zip。重点文件：
●tritium/ncm/music/CloudMusic.java
●tritium/ncm/music/AudioPlayer.java
●tritium/screens/ncm/MusicLyricsPanel.java
●tritium/utils/other/multithreading/MultiThreadingUtil.java
●repackage/processing/sound/AudioSample.java
●repackage/processing/sound/SoundFile.java
