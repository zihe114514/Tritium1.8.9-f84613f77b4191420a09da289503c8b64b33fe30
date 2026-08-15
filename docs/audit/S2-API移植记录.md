# S2 · 网易云 API 链路移植记录

日期：2026-08-14
目标：S2 阶段「搜索 / 歌曲详情 / 播放地址 / 歌词链路可用；异步不阻塞主线程」。
范围界定：仅移植 **纯 API 链**（无 Minecraft / 无 UI / 无音频依赖）。`CloudMusic.java`、`dto/*`、`AudioPlayer`、`QRCodeGenerator` 因依赖音频/渲染/UI 层，归入 S3/S4。

## 一、移植文件清单（19 个，全部原样复用或最小 Java 8 改写）

| 模块 | 原文件 | 分类 | 改动 |
|---|---|---|---|
| ncm.math.* (11) | DigestUtils / Hex / MessageDigestAlgorithms / Charsets / StringUtils / BinaryDecoder / BinaryEncoder / Decoder / Encoder / DecoderException / EncoderException | **A 直接复用** | 无（纯 Java 8 Apache Commons Codec 子集） |
| utils.json | JsonUtils | **A 直接复用** | 无（gson + commons-lang3，均在 MC 1.8.9 类路径） |
| ncm | StringUtils / OptionsUtil / CryptoUtil | **A 直接复用** | 无 |
| ncm | RequestUtil | **B Java8 兼容** | 见下方降级清单 1、2 |
| ncm.api | CloudMusicApi | **B Java8 兼容** | 见下方降级清单 1 |
| ncm | DeviceIdGenerator | **B Java8 兼容** | 见下方降级清单 3、4、5 |
| ncm.music | Quality | **A 直接复用** | 无 |

## 二、Java 9+/10+/15+ API 降级清单（关键修改记录）

原项目实际构建目标为 **Java 21**，除语法（switch 表达式等，S4 涉及）外还使用了多处 Java 9+ 才有的 *API*。本次已定位并降级：

| # | 原代码 | 位置 | 问题 | Java 8 等价改写 |
|---|---|---|---|---|
| 1 | `StringBuilder.isEmpty()` | CloudMusicApi.songDetail、RequestUtil.cookieMapToString | `CharSequence.isEmpty()` 为 Java 15 新增默认方法 | `sb.length() != 0` |
| 2 | `URLEncoder.encode(String, Charset)` | RequestUtil.cookieMapToString、buildFormData | `encode(String,Charset)` 重载为 Java 10 新增；Java 8 仅有 `encode(String,String)` | `URLEncoder.encode(x, StandardCharsets.UTF_8.name())` |
| 3 | `NetworkInterface.networkInterfaces()` | DeviceIdGenerator.collect | 返回 `Stream` 的方法为 Java 9 新增；Java 8 仅有 `getNetworkInterfaces()` 返回 `Enumeration` | `Collections.list(NetworkInterface.getNetworkInterfaces()).forEach(...)` |
| 4 | `Optional.isEmpty()` | DeviceIdGenerator.collect（与 #3 同处） | `Optional.isEmpty()` 为 Java 11 新增 | `!...isPresent()` |
| 5 | `NetworkInterface.inetAddresses()` | DeviceIdGenerator.collect | 返回 `Stream` 的方法为 Java 9 新增；Java 8 仅有 `getInetAddresses()` 返回 `Enumeration` | `getInetAddresses().hasMoreElements()` |

> 说明：#3/#4/#5 均为「是否有网卡地址」判断，改写后语义完全一致（存在地址 = 枚举非空）。

## 三、新增运行时依赖（build.gradle）

| 依赖 | 版本 | 用途 | 说明 |
|---|---|---|---|
| net.java.dev.jna:jna | 5.14.0 | DeviceIdGenerator 读注册表 CPU 名 | Java 8 兼容版本 |
| net.java.dev.jna:jna-platform | 5.14.0 | `com.sun.jna.platform.win32.Advapi32Util` / `WinReg` | 同上 |

> 原代码对 JNA 调用已做 `catch (Exception ignored)` 兜底：即便运行时 JNA 不可用，仅指纹少一项 CPU 名，设备 ID 仍可用（优雅降级为原代码既有行为）。

## 四、验证

- **clean build**：`BUILD SUCCESSFUL`（主产物 `deuteriummusic-1.0.2.jar` 含全部 ncm 类）。
- **真实 API 冒烟测试**（`src/test/java/com/deuterium/music/NcmApiSmokeTest.java`，`gradlew smokeTest`，不启动 Minecraft）：

| 链路 | 结果 |
|---|---|
| 设备 ID（JNA + 指纹） | 生成成功 `18A015E8...` |
| cloudSearch("周杰伦", Single) | HTTP 200，返回 100 首 |
| songDetail(id) | HTTP 200 |
| songUrlV1(id, "standard") | HTTP 200，`code=200 type=mp3`，返回真实 mp3 地址 |
| lyricNew(id) | HTTP 200，返回逐字歌词（YRC） |

- **异步不阻塞**：API 链为纯 `HttpURLConnection` + 30s 超时，无任何 MC/Render 线程依赖；请求封装于 `RequestUtil.createRequest` 同步方法，由上层 `MultiThreadingUtil.runAsync`（S3 移植）调度到后台线程，符合「异步与渲染线程隔离」要求。

## 五、遗留 / 后续

- lombok 注解（`@UtilityClass`/`@Data`/`@Builder`/`@SneakyThrows`/`@Getter`/`@NonNull`）已在 Java 8 + lombok 1.18.30 下编译通过，无需 delombok。
- `commons-lang3`（JsonUtils 用 `StringUtils.abbreviateMiddle`）由 MC 1.8.9 类路径提供，未新增依赖。
- S3 将移植 `dto/*`（Album/Artist/User/PlayList/Music）、`CloudMusic.java`（状态/播放线程）、`AudioPlayer` 及 repackage 音频库；`Music.getPlayUrl()` 依赖 `CloudMusic.quality`，届时一并解决。
