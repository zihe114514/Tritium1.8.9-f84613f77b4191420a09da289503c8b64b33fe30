# Deuterium 音频栈审计报告（S0 · 只读）

> 范围：`Deuterium/src/main/java/repackage/`（第三方音频库全集）
> 入口：`tritium/ncm/music/AudioPlayer.java`、`tritium/ncm/music/CloudMusic.java`
> 结论口径：A 直接复用 / B Java8 兼容改写 / C Forge 适配 / D 必须重写 / E 可删除

---

## 1. 入口追踪：播放一首歌的完整调用链

### AudioPlayer 实际 import 的 repackage 类

```java
import repackage.processing.sound.*;   // 实际用到: SoundFile, FFT, JSynFFT
```

- `SoundFile` — 播放器主体
- `FFT`（`new FFT(128, callback)`）— 频谱分析器
- `JSynFFT`（`JSynFFT.FFT_SIZE = 4096`、`JSynFFT.FFTCalcCallback`）— 实时 FFT 单元

### CloudMusic 实际 import 的 repackage 类

```java
import repackage.com.jsyn.exceptions.ChannelMismatchException;   // 播放异常时重下
import repackage.javazoom.jl.converter.Converter;               // MP3→WAV（死代码路径）
import repackage.org.kc7bfi.jflac.*;                            // FLAC→WAV（死代码路径）
```

> **关键发现**：`CloudMusic.convertFlacToWav()` 与 `CloudMusic.convertMp3ToWav()` 是 **private 且全仓库无调用点**（grep 只命中定义处），属于死代码。CloudMusic 里那 6 个 jFLAC import（`FLACDecoder`/`PCMProcessor`/`StreamInfo`/`ByteData`/`WavWriter`）以及 JLayer `Converter` 的 import，**只服务于这段死代码**。真实的解码发生在 `SampleLoader` 内部（见下）。

### 播放链（下载 → 解码 → 引擎 → 输出）

```
CloudMusic.play(list, idx)
  └─ PlayThread.run()
       └─ getMusicFile(): 下载 mp3/flac/wav 到 MusicCache/（原样，不做转换）
            └─ initializePlayer() → new AudioPlayer(file) 或 player.setAudio(file)
                 └─ new SoundFile(path)
                      └─ SampleLoader.loadStreamedFloatSample(file)   ← 解码入口
                           ├─ .wav  → RafSampleLoader → RafWAVEFileParser → RafFloatSample
                           ├─ .flac → BufferedSampleLoader.loadFromFlacStream
                           │           → FlacAudioFileReader + Flac2PcmAudioInputStream (jFLAC sound.spi)
                           │           → BufferedFloatSample
                           └─ .mp3  → javazoom.jl.converter.Converter.convert()（内存中 MP3→WAV 字节）
                                      → CustomSampleLoader → WAVEFileParser → FloatSample
                      └─ initiatePlayer()（AudioSample）
                           → VariableRateStereoReader / VariableRateMonoReader（JSyn unitgen）
                           → JSynCircuit(player.output)（mono 时加 JSynProcessor 做 pan）
                           → SoundObject 注册进 Engine.getEngine() 单例
       └─ AudioPlayer.setListeners(): fft.input(this.player)
            └─ FFT.setInput → Engine.add(JSynFFT) → 接上 SoundFile 电路输出
                 └─ JSynFFT 用 jipes FFTFactory.JavaFFT 做 FFT → callback → SpectrumVisualizer.processFFT()
       └─ AudioPlayer.play() → player.play() → AudioSample.playInternal()
            ├─ SoundObject.play() → Engine.play(circuit) → 连到 ChannelOut/Multiply 输出总线
            └─ QueueDataCommand 入队 → synth.queueCommand(cmd)
  └─ Engine 单例（首次创建时）:
       ├─ AudioDeviceFactory.createAudioDeviceManager() → new JavaSoundAudioDevice()  ← JavaSound
       ├─ selectOutputDevice() 用 AudioSystem.getMixerInfo() 探测设备
       └─ startSynth() → synth.start(44100, -1, 0, outDev, outChannels)
            └─ SynthesisEngine.start() → 起 EngineThread（daemon，MAX_PRIORITY）
                 └─ 循环: generateNextBuffer() 跑 unit graph → audioOutputStream.write(interleaved)
                      └─ JavaSoundOutputStream.write() → SourceDataLine.write()  ← 最终出声
```

---

## 2. 各库作用与必要性（逐库）

### 2.1 JSyn（`com/jsyn/`）— 合成引擎，必需（A）

提供：unit generator 图、数据队列、端口连接、文件解析、时间调度。播放器强依赖。

| 子包 | 用到的类 | 结论 |
|---|---|---|
| `com.jsyn` | `JSyn`（工厂）、`Synthesizer`（接口） | A |
| `engine` | `SynthesisEngine` | A |
| `devices` | `AudioDeviceFactory`、`AudioDeviceManager`、`AudioDeviceInputStream`、`AudioDeviceOutputStream`、`javasound.JavaSoundAudioDevice` | A（输出层，见 §3） |
| `exceptions` | `ChannelMismatchException` | A（CloudMusic 播放异常重试用） |
| `io` | `AudioInputStream`、`AudioOutputStream`（设备流基类） | A |
| `ports` | 全部（`UnitInputPort`/`UnitOutputPort`/`QueueDataCommand`/`QueueDataEvent`/`UnitDataQueuePort`/`UnitDataQueueCallback`/`PortBlockPart`/`UnitBlockPort`/`UnitVariablePort`/`SequentialDataCrossfade`/`UnitPort`/`ConnectableInput`/`ConnectableOutput`/`GettablePort`/`SettablePort`/`InputMixingBlockPart`/`UnitSpectral*`） | A（`UnitSpectralInputPort`/`UnitSpectralOutputPort` 及其 `Spectrum` 依赖仅在 spectral 端口内，未接入播放链，可视为 E，但体积小） |
| `unitgen` | `VariableRateDataReader`/`VariableRateMonoReader`/`VariableRateStereoReader`、`SequentialDataReader`/`SequentialDataWriter`、`FixedRateStereoWriterSpecial`/`FixedRateStereoWriterToMono`（JSynFFT 基类）、`ChannelOut`、`Multiply`、`TwoInDualOut`、`Circuit`、`UnitGenerator`/`UnitSource`/`UnitSink`/`UnitFilter`/`UnitBinaryOperator` | A |
| `unitgen.PeakFollower` | 仅被 dead 类 `processing.sound.Amplitude` 引用 | E |
| `data` | `FloatSample`、`SequentialDataCommon`（基类）、`SequentialData`（接口）、`AudioSample`（FloatSample 基类） | A；`ShortSample` 仅被 `VariableRateMonoReader` 的 import/javadoc 引用，可 E |
| `util` | `SampleLoader`（`loadStreamedFloatSample`）、`AudioSampleLoader` | A |
| `util.FourierMath` | 仅被 `FFT.analyzeSample(...)` 静态方法引用，而该方法全仓库无调用点 | E（见 §6） |
| `util.soundfile` | `WAVEFileParser`、`IFFParser`、`CustomSampleLoader`、`ChunkHandler`、`AudioFileParser`（基类）、`streamed.raf.*`、`streamed.buffered.*` | A；`AIFFFileParser` 仅被 `CustomSampleLoader` 编译期引用（AIF 永不出现），可 E |

### 2.2 JLayer（`javazoom/jl/`）— MP3 解码 + 转 WAV，必需（A）

- `converter.Converter`：MP3→WAV 字节（`.mp3` 分支真实调用 `converter.convert(InputStream, null, null)`）。连带 `converter.*`（RiffFile/RiffStream/WaveFile/WaveStream/WaveFileObuffer/WaveStreamObuffer/SeekableByteArrayOutputStream）。
- `decoder.*`：完整 MP3 解码器（`Decoder`→`LayerIII/LayerII/LayerI`、`Bitstream`、`Header`、`SynthesisFilter`、`SampleBuffer`、`OutputChannels`、`HuffCodeTab` 等）。全部 A。
- ⚠️ 本库含 Java 14+ switch 表达式（见 §5），需 B 改写。

### 2.3 jFLAC（`org/kc7bfi/jflac/`）— FLAC 解码，必需（A，部分 E）

真实播放路径（`.flac`）走的是 **`sound.spi` 子包**，它内部拉起 `FLACDecoder` 与底层 frame/io/util/metadata：

| 子包 | 用到的类 | 结论 |
|---|---|---|
| `FLACDecoder`、`PCMProcessor`、`PCMProcessors`、`DecodeError`、`Constants`、`FrameDecodeException`、`FixedPredictor`、`LPCPredictor`、`ChannelData` | 解码必需 | A |
| `frame.*`（`Frame`/`Header`/`Channel`/`ChannelConstant`/`ChannelFixed`/`ChannelLPC`/`ChannelVerbatim`/`EntropyCodingMethod`/`EntropyPartitionedRice*`） | `Flac2PcmAudioInputStream.fill()` 调用 `decoder.readNextFrame()`/`decodeFrame()` | A（`ChannelFixed` 含 switch 表达式，需 B） |
| `io.*`（`BitInputStream`/`BitOutputStream`/`RandomFileInputStream`） | `FlacAudioFileReader` 用 `BitInputStream`/`BitOutputStream`；`RandomFileInputStream` 被 `FLACDecoder.seek()` 的 `instanceof` 引用（编译期必需） | A |
| `metadata.*`（至少 `StreamInfo`/`Metadata`，连带 `SeekTable`/`VorbisComment` 等） | `StreamInfo` 直接使用 | A |
| `util.*`（`ByteData`/`CRC16`/`CRC8`/`BitMath`/`RiceCodes`/`RingBuffer`/`PCMDecoder`/`LittleEndianDataOutput`） | 解码必需 | A；**`WavWriter` 仅被死代码 `convertFlacToWav` 使用 → E** |
| `sound.spi.*`（`Flac2PcmAudioInputStream`/`FlacAudioFileReader`/`FlacAudioFormat`/`FlacEncoding`/`FlacFileFormatType`/`FlacFormatConversionProvider`/`RingedAudioInputStream`） | FLAC 流式转 PCM 必需 | A |
| `FLACEncoder` | 编码器，播放器不用 | E |

### 2.4 processing.sound（`processing/sound/`）— JSyn 包装层，部分必需

| 类 | 结论 |
|---|---|
| `SoundFile`、`AudioSample`、`SoundObject`、`Analyzer`、`FFT`、`JSynFFT`、`JSynCircuit`、`JSynProcessor`、`Engine` | A（核心播放/FFT 链路） |
| `Effect`、`Modulator` | A（`Engine.setModulation` 与 `JSynCircuit`/`SoundObject` 的泛型/类型签名引用，编译必需，运行时不用） |
| `Sound`（门面类）、`Amplitude`、`Waveform` | E（全仓库无调用点） |

### 2.5 jipes（`com/tagtraum/jipes/math/`）— FFT，必需（A）

- `FFTFactory.JavaFFT`（内部类，纯 Java radix-2 FFT）、`Transform`（接口）。被 `JSynFFT` 实例化用于**实时频谱**（见 §6）。A。

### 2.6 softsynth（`com/softsynth/shared/time/`）— 时间调度，必需（A）

- `ScheduledCommand`、`ScheduledQueue`、`TimeStamp`。被 `SynthesisEngine` 的 `scheduleCommand`/`queueCommand`/`processScheduledCommands` 使用。A。

---

## 3. 音频输出方式：JavaSound，不是 OpenAL

**结论：播放器通过 JavaSound 的 `SourceDataLine` 把 PCM 送出去，大量使用 `javax.sound.sampled`。**

证据链：
- `Engine.createDefaultAudioDeviceManager()` → `Class.forName("javax.sound.sampled.AudioSystem")` + `AudioDeviceFactory.createAudioDeviceManager()`。
- `AudioDeviceFactory` 只加载 `JavaSoundAudioDevice`（PortAudio 分支在 `Engine.createAudioDeviceManager(portAudio)` 里被删空，实际只有一个 `throw`，永远走 JavaSound）。
- `JavaSoundAudioDevice.JavaSoundOutputStream`：`AudioSystem.getMixerInfo()`/`getMixer()`/`getLine()`/`SourceDataLine.open()`，`write()` 内把 float→16bit little-endian short→`line.write(byte[])`。
- `SynthesisEngine.EngineThread` 起后台线程循环 `generateNextBuffer()` → `audioOutputStream.write(...)`。

**在 Minecraft 1.8.9 环境是否可用？**

- `javax.sound.sampled` 是 JDK 8 标准库（rt.jar → java.desktop 模块），**1.8.9 Forge 的运行时 JRE 里一定存在**，不依赖任何外部 jar。
- MC 1.8.9 自身的音效用 LWJGL2 OpenAL（Paulscode 库），与 JavaSound 是两套**独立**的音频 API：JavaSound 直接走操作系统音频设备（Windows 走 WASAPI/DSound），OpenAL 走 OpenAL 设备。二者可**同时打开设备并存**（各自独立句柄），不存在"JavaSound 被 MC 禁用"的问题。
- 实际风险点（非阻塞，但需记录）：
  1. 音量独立：JavaSound 输出不经过 MC 的 `GameSettings` 主音量/音效音量，需在 Mod 内自管音量（原项目已用 `player.amp(volume)` 自管）。
  2. 设备争用：个别系统/声卡可能对多进程或多 API 同时独占有限制（罕见）。
  3. 延迟：Windows 下 JavaSound 建议输出延迟 0.08s，比 OpenAL 稍大，但作为音乐播放可接受。
  4. 无空间音效/距离衰减：纯 2D 立体声，符合"音乐播放"需求。

**是否需要改写输出层？** 技术上**不必**（JavaSound 可用）。是否改写取决于目标：若要求"接入 MC 音量系统/音效总线"，才需要把 `AudioDeviceManager` 换成 OpenAL/Paulscode 适配器（见 §7）。

---

## 4. 外部依赖：自包含，仅 lombok

对全目录 `import` 扫描结果：

| 依赖 | 性质 | 说明 |
|---|---|---|
| `lombok`（`@Getter`/`@SneakyThrows`/`@Cleanup`） | **编译期注解处理器**，非运行时 jar | 遍布各文件；构建时需 lombok，产物无需 |
| `javax.sound.sampled` / `javax.sound.sampled.spi` | **JDK 内置** | 非外部 jar |
| `java.*` | JDK 内置 | — |
| `processing.core.*` | **未出现** | Processing 的 PApplet 依赖已被剥离 |
| `org.lwjgl` / `net.minecraft` / `com.google.gson` / `org.apache.commons` | **未出现** | 无 |

**结论：`repackage/` 完全自包含**，运行时零外部 jar；唯一构建期依赖是 lombok（需在 ForgeGradle 的 annotationProcessor/compile 阶段可用）。

---

## 5. Java 8 兼容性：**不兼容，需改写**

**`repackage/` 当前不是 Java 8 代码**，混入了 Java 14+ / 16+ 语法（且都在真实解码/播放链上）：

### (a) switch 表达式（Java 14+，`case X -> Y` / 逗号多标签）

| 文件 | 位置 | 是否在播放链 |
|---|---|---|
| `javazoom/jl/decoder/Bitstream.java` | 335-339 | ✅ MP3 解码 |
| `javazoom/jl/decoder/OutputChannels.java` | 71-77 | ✅ MP3 解码 |
| `javazoom/jl/decoder/Header.java` | 556、675-693 | ✅ MP3 解码 |
| `javazoom/jl/converter/RiffFile.java` | 417-423 | ✅ MP3 转 WAV |
| `javazoom/jl/converter/RiffStream.java` | 310-316 | ✅ MP3 转 WAV |
| `com/jsyn/util/soundfile/IFFParser.java` | 265-273 | ✅ WAV 解析 |
| `org/kc7bfi/jflac/frame/ChannelFixed.java` | 66-70 | ✅ FLAC 解码 |

### (b) instanceof 模式匹配（Java 16+，`x instanceof T y`）

| 文件 | 位置 | 是否在播放链 |
|---|---|---|
| `javazoom/jl/decoder/OutputChannels.java` | 113 | ✅ |
| `org/kc7bfi/jflac/FLACDecoder.java` | 412 | ✅ |
| `com/jsyn/ports/SequentialDataCrossfade.java` | 86、95 | ✅（`QueueDataCommand` 会实例化它） |

### (c) 误报澄清（不是问题）

- `UnitDataQueuePort.java` 的 `blocks.getLast()`：`blocks` 是 `LinkedList`，用的是 `LinkedList.getLast()`（JDK 1.2+），非 Java 21 的 `SequencedCollection.getLast()`。

### (d) 未发现

`var`、`record`、`List.of`/`Map.of`/`Set.of`、text block、`String.strip`/`formatted`、`Stream.toList()` 均未命中。

### 相邻发现（在 tritium 入口，非 repackage，供参考）

- `CloudMusic.java` 用了 `List.getFirst()`（Java 21）和 `InputStream.readAllBytes()`（Java 9）——属于入口文件自身的 Java 8 兼容问题，需一并处理（本次审计范围外，但影响移植）。

**改写量评估**：switch 表达式 + instanceof 模式匹配总计约 10 处文件、30+ 处语法点，均可机械改写为经典 `switch` 语句 / `if-else` + 显式类型转换，属 B（Java 8 兼容）类最小改动，不改行为。

---

## 6. FFT / 频谱：走 jipes，不走 JSyn 的 FourierMath

- **实时频谱可视化**：`AudioPlayer` → `processing.sound.FFT` → `JSynFFT`（`FixedRateStereoWriterToMono` 子类，用 `dataQueue` 持续填充 4096 点环形缓冲）→ **`com.tagtraum.jipes.math.FFTFactory.JavaFFT`（jipes）** 做 FFT → `JSynFFT.FFTCalcCallback.onFFT()` → `tritium.widget.impl.SpectrumVisualizer.processFFT()`（Bark 频带聚合，属 tritium，不在 repackage）。
- **`com/jsyn/util/FourierMath`（JSyn 自带 FFT）**：只被 `processing.sound.FFT.analyzeSample(...)` 静态方法调用；该方法全仓库无调用点，**频谱可视化实际不用它** → 判 E（若删，需一并删掉 `FFT.analyzeSample` 那三个静态方法，属小改）。

---

## 7. 结论

### 7.1 分类汇总

| 库 / 组件 | 结论 |
|---|---|
| JSyn 核心（engine/devices/ports/unitgen 主链/data/util/soundfile） | **A 直接复用**，可整体搬走 |
| JLayer `converter` + `decoder` | **A + B**（含 switch 表达式需改写） |
| jFLAC 解码链（FLACDecoder/frame/io/metadata/util/sound.spi） | **A + B**（`ChannelFixed` switch、`FLACDecoder` instanceof 需改写） |
| processing.sound 核心（SoundFile/AudioSample/SoundObject/Analyzer/FFT/JSynFFT/JSynCircuit/JSynProcessor/Engine/Effect/Modulator） | **A** |
| jipes `FFTFactory`/`Transform` | **A** |
| softsynth `ScheduledCommand`/`ScheduledQueue`/`TimeStamp` | **A** |
| lombok 注解 | 构建期依赖（B，ForgeGradle 需启用注解处理） |
| `processing.sound.{Sound, Amplitude, Waveform}` | **E 删除** |
| `com.jsyn.util.FourierMath` | **E 删除**（连带删 `FFT.analyzeSample` 静态方法） |
| `com.jsyn.unitgen.PeakFollower`、`com.jsyn.data.ShortSample` | **E 删除** |
| `com.jsyn.util.soundfile.AIFFFileParser` | E（需从 `CustomSampleLoader` 去掉分支） |
| `org.kc7bfi.jflac.FLACEncoder`、`org.kc7bfi.jflac.util.WavWriter` | **E 删除** |
| `CloudMusic.convertFlacToWav` / `convertMp3ToWav`（及对应 import） | E（死代码，可删，删后 CloudMusic 的 jFLAC/Converter import 大幅减少） |

### 7.2 音频输出层在 1.8.9 的适配方案（不写代码）

| 方案 | 做法 | 风险 / 代价 |
|---|---|---|
| **方案 1（推荐先做）：保留 JavaSound 直接运行** | 什么都不改，`JavaSoundAudioDevice` 原样跑 | JavaSound 是 JDK 内置，在 1.8.9 Forge JRE 可用，与 OpenAL/Paulscode 并存。代价：音量不入 MC 主音量、无空间音效、~80ms 输出延迟、极个别设备独占冲突。**最小改动、最快跑通**。 |
| **方案 2：实现 OpenAL/Paulscode 版 `AudioDeviceManager` 适配器** | JSyn 输出已抽象为 `AudioDeviceManager` + `AudioDeviceOutputStream`，且 `AudioDeviceFactory.setInstance()` 支持自定义设备；写一个用 LWJGL2 OpenAL `SourceDataLine`→OpenAL Source/Buffer 流式写入的实现，替换 `JavaSoundAudioDevice` | 可接入 MC 音量/音效总线；需处理 OpenAL 缓冲循环、流式喂数据、阻塞语义与 JSyn `EngineThread` 的对接，工作量中等，风险可控（不动 JSyn 引擎与解码层）。 |
| **方案 3：弃用 JSyn 实时引擎，输出层整体换 LWJGL2 OpenAL** | 只用 JSyn/JLayer/jFLAC 解码出 `FloatSample`（PCM），用 OpenAL 直接播放 | 丢失 JSyn 的队列/seek/rate/volume/完成回调语义，需重写播放控制，**违背"优先原样移植"**，不推荐。 |
| **方案 4：改用 Paulscode 自带 JLayer codec** | 复用 Paulscode 的 `CodecJLayer` 播放 MP3 | Paulscode 对 FLAC/高品质支持弱，且会引入"换播放框架"的合规风险（CLAUDE.md 禁止无理由换框架）。不推荐。 |

**建议路径**：S3 阶段先用**方案 1** 跑通真实 MP3/FLAC 播放与频谱（验证原 API 链路），S6/S7 再按需评估**方案 2**（`AudioDeviceManager` 适配器）以接入 MC 音量。

### 7.3 移植到 1.8.9 前必须先做的事

1. 把 §5 的 switch 表达式 + instanceof 模式匹配改写为 Java 8 语法（10 处文件，机械改写，不改行为）。
2. 在 ForgeGradle 里启用 lombok 注解处理（或把 `@Getter`/`@SneakyThrows`/`@Cleanup` 手写展开）。
3. 删除 E 类死代码（可选，先删 `CloudMusic.convertFlacToWav/convertMp3ToWav` 及其 jFLAC/Converter import）。
4. 确认 `Engine` 单例的 `AudioSystem` 探测在 MC 线程外初始化（原代码在首次 `new SoundFile`/`new FFT` 时触发，发生在 `PlayThread`，非 Render 线程，符合"不阻塞主线程"要求）。
