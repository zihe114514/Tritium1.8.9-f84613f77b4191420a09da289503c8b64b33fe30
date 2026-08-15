# S0 源码审计 — tritium.ncm 包（API / 加密 / 音乐数据）

审计范围：`Deuterium/src/main/java/tritium/ncm/`（含 `api/`、`math/`、`music/`、`music/dto/`）
审计方式：只读，逐文件完整 Read。日期 2026-08-14。

---

## 0. 一句话结论（先读）

- **加密链路完整自包含**：`CryptoUtil` + `math/DigestUtils` + `math/Hex` + `math/StringUtils` + `math/MessageDigestAlgorithms` + `JsonUtils` 即可独立完成 weapi / linuxapi / eapi。**所有密钥硬编码在 `CryptoUtil`，无外部加密依赖**。
- **API 与请求层可直接复用**（纯 Java 8 + JDK `HttpURLConnection`）：`CryptoUtil`、`DeviceIdGenerator`、`OptionsUtil`、`RequestUtil`、`StringUtils`、`api/CloudMusicApi`、`math/*`、`music/dto/*`、`music/Quality` 均为 P0 保留。
- **非 JDK 外部 jar 依赖清单**：`gson`、`lombok`（编译期）、`jna`（仅 DeviceIdGenerator）、`zxing`（仅 QRCodeGenerator）、`commons-io` + `commons-lang3`（CloudMusic 封面 / JsonUtils）。`today.opai.api` 仅 `CloudMusic.java` 1 处引用。
- `repackage.*`（JSyn / JLayer / JFLAC / Processing Sound / Jipes FFT）**不是外部 jar**，是 Deuterium 源码树内 bundled 的源码，可直接整体搬入。

---

## 1. 逐文件表

| 文件 | 分类 | 依赖（除 JDK） | 一句话职责 |
|---|---|---|---|
| `CryptoUtil.java` | **A 直接复用** | lombok `@Data`；`tritium.ncm.math.DigestUtils`、`Hex`；`tritium.utils.json.JsonUtils`；`javax.crypto`、`java.util.Base64` | weapi/linuxapi/eapi 构造 + AES/RSA + 响应解密；密钥常量所在地 |
| `DeviceIdGenerator.java` | **B Java8兼容**（+jna） | `com.sun.jna.platform.win32.*`（JNA）；`CloudMusicApi`（仅 javadoc 引用） | 采集 OS/CPU/MAC → SHA-256 生成设备 ID |
| `OptionsUtil.java` | **A 直接复用** | lombok `@UtilityClass`；`RequestUtil.RequestOptions` | 静态 COOKIE 管理 + 构造 RequestOptions |
| `RequestUtil.java` | **A 直接复用**（Java8） | `com.google.gson.JsonObject`；lombok `@Builder/@Data/@SneakyThrows`；`JsonUtils`；`tritium.ncm.StringUtils` | 原生 `HttpURLConnection` 发 POST；weapi/linuxapi/eapi/api 四通道封装 |
| `StringUtils.java` | **A 直接复用** | lombok `@UtilityClass` | `isBlank`/`isNotBlank` |
| `api/CloudMusicApi.java` | **A 直接复用** | gson `JsonArray/JsonElement/JsonObject`；lombok `@Getter/@NonNull/@SneakyThrows/@UtilityClass`；`DeviceIdGenerator`、`OptionsUtil`、`RequestUtil`、`JsonUtils` | 网易云 API 方法集（搜索/歌词/播放地址/登录/歌单） |
| `math/BinaryDecoder.java` | **A** | — | 接口 `byte[] decode(byte[])` |
| `math/BinaryEncoder.java` | **A** | — | 接口 `byte[] encode(byte[])` |
| `math/Charsets.java` | **A** | — | 常用 Charset 常量 + `toCharset` |
| `math/Decoder.java` | **A** | — | 接口 `Object decode(Object)` |
| `math/DecoderException.java` | **A** | — | 解码异常 |
| `math/DigestUtils.java` | **A** | `math.StringUtils`、`math.Hex`（自包内） | MD5/SHA-1 摘要工具（Apache Commons Codec 子集） |
| `math/Encoder.java` | **A** | — | 接口 `Object encode(Object)` |
| `math/EncoderException.java` | **A** | — | 编码异常 |
| `math/Hex.java` | **A** | `math.BinaryEncoder/Decoder/Encoder/DecoderException`（自包内） | Hex 编解码 |
| `math/MessageDigestAlgorithms.java` | **A** | — | 摘要算法名常量 |
| `math/StringUtils.java` | **A** | `math.Charsets`（自包内） | 字节<->字符串转换 |
| `music/AudioPlayer.java` | **C Forge适配** | `repackage.processing.sound.*`（SoundFile/FFT，bundled 源码）；`tritium.widget.impl.SpectrumVisualizer` | 用 Processing Sound + JSyn 播放音频、跳转、音量、FFT 回调 |
| `music/CloudMusic.java` | **D 必须重写**（核心逻辑抽取） | gson；lombok；`org.apache.commons.io.IOUtils`；`repackage.com.jsyn/javazoom.jl/org.kc7bfi.jflac`；`today.opai.api.enums.EnumChatColor`；大量 `tritium.*`（渲染/UI/网络/多线程） | 播放队列、歌词状态机、登录、封面加载/模糊、下载 |
| `music/Quality.java` | **A 直接复用** | lombok `@Getter` | 音质枚举 |
| `music/QRCodeGenerator.java` | **B Java8兼容**（+zxing） | `com.google.zxing.*`（外部 jar）；`tritium.rendering.TextureManager/DynamicTexture`、`Location`、`MultiThreadingUtil` | 生成登录二维码图片 |
| `music/dto/Album.java` | **A/B**（依赖 Location） | gson `@SerializedName`；lombok `@Data`；`tritium.utils.Location` | 专辑 DTO |
| `music/dto/Artist.java` | **A** | gson；lombok | 艺术家 DTO |
| `music/dto/Music.java` | **B Java8兼容**（`.toList()`） | gson；lombok；`CloudMusicApi`、`CloudMusic`、`Location`、`Tuple` | 歌曲 DTO + getPlayUrl/setLike/封面位置 |
| `music/dto/PlayList.java` | **B Java8兼容**（虚拟线程） | gson；lombok；`RequestUtil`、`CloudMusicApi`、`Location`、`JsonUtils`、`MultiThreadingUtil` | 歌单 DTO + 懒加载歌曲 |
| `music/dto/User.java` | **A/B**（依赖 Location） | gson；lombok；`CloudMusicApi`、`Location`、`JsonUtils` | 用户 DTO + playLists 分页 |

> `math/*` 全部为 Apache Commons Codec（ASF 许可）抽取的子集，纯 JDK（`java.security.MessageDigest`、`java.nio`），Java 8 兼容，包内自洽（DigestUtils → math.StringUtils + math.Hex）。

---

## 2. API 链路（CloudMusicApi 方法）

`CloudMusicApi` 是 `@UtilityClass`，全部 `static`。默认 crypto（`OptionsUtil.createOptions()` 不传参时）走 **eapi**（`RequestUtil` 中 `APP_CONF.encrypt=true` → 默认 `"eapi"`）。

| 方法 | endpoint | crypto | 关键参数 | 响应/返回模型 |
|---|---|---|---|---|
| `lyricNew(long id)` | `/api/song/lyric/v1` | eapi(默认) | id, cp=false, tv/lv/rv/kv/yv/ytv/yrv=0 | `RequestAnswer` |
| `loginStatus()` | `/api/w/nuser/account/get` | **weapi** | 空 map | 包装 status/data/cookie |
| `cloudSearch(keyWord, SearchType)` | `/api/cloudsearch/pc` | eapi | s, type, limit=100, offset=0, total=true | `result.songs[]` |
| `likeList(long uid)` | `/api/song/like/get` | eapi | uid | `ids[]` |
| `loginQrKey()` | `/api/login/qrcode/unikey` | eapi | type=3 | `data.unikey` |
| `loginQrCheck(String key)` | `/api/login/qrcode/client/login` | eapi | key, type=3 | body 含 code/nickname/avatarUrl/cookie |
| `songUrlV1(long id, String level)` | `/api/song/enhance/player/url/v1` | eapi | ids="[id]", level, encodeType=flac；(sky→immerseType=c51) | `data[0].url/type/code` |
| `like(long id, boolean like)` | `/api/radio/like` | **weapi** | alg=itembased, trackId, like, time=3 | `RequestAnswer` |
| `playlistTrackAll(long id, int s)` | `/api/v6/playlist/detail` → `/api/v3/song/detail` | eapi | id, n=100000, s；二次请求 c=[{id}...] | `songs[]` |
| `playlistUpdatePlaycount(long id)` | `/api/playlist/update/playcount` | eapi | id | `RequestAnswer` |
| `playlistTracks(op, trackId, musics)` | `/api/playlist/manipulate/tracks` | eapi | op, pid, trackIds(JSON数组字符串), imme=true | 512 时重试一次 |
| `userPlaylist(uid, limit, offset)` | `/api/user/playlist` | **weapi** | uid, limit, offset, includeVideo=true | `playlist[]` |
| `registerAnonimous()` | `/api/register/anonimous` | **weapi** | username=Base64(deviceId + " " + ncmDllEncodeId(deviceId)) | 匿名登录，写入 `RequestUtil.globalDeviceId` |
| `songDetail(long)` / `songDetail(List<Long>)` | `/api/v3/song/detail` | **weapi** | c=[{"id":...}] | `songs[]` |
| `recommendResource()` | `/api/v1/discovery/recommend/resource` | **weapi** | null | 每日推荐歌单 |
| `recommendSongs()` | `/api/v3/discovery/recommend/songs` | **weapi** | null | 每日推荐歌曲 |

`SearchType` 枚举：`Single=1, Album=10, Singer=100, Playlist=1000, User=1002, MV=1004, Lyric=1006, Radio=1009, Video=1014, All=1018, Sound=2000`。

加密入口在 `RequestUtil.createRequest`（按 `crypto` 分支调用 `CryptoUtil.weapi/linuxapi/eapi`），见 §3。

### 关键片段

```java
// CloudMusicApi.registerAnonimous 里的设备 ID 编码
private final String ID_XOR_KEY_1 = "3go8&$8*3*3h0k(2)2";
private String ncmDllEncodeId(String someId) {
    StringBuilder xoredString = new StringBuilder();
    for (int i = 0; i < someId.length(); i++) {
        char charCode = (char) (someId.charAt(i) ^ ID_XOR_KEY_1.charAt(i % ID_XOR_KEY_1.length()));
        xoredString.append(charCode);
    }
    MessageDigest md5 = MessageDigest.getInstance("MD5");
    return Base64.getEncoder().encodeToString(md5.digest(xoredString.toString().getBytes(StandardCharsets.UTF_8)));
}
```

---

## 3. 加密与密钥（CryptoUtil / DeviceIdGenerator / math）

### 3.1 CryptoUtil 实现

| 能力 | 实现 | 密钥/常量 |
|---|---|---|
| AES 加密 `aesEncrypt` | `AES/CBC/PKCS5Padding` 或 `AES/ECB/PKCS5Padding`；输出 base64 或 hex(大写) | key/iv 由调用方传入 |
| AES 解密 `aesDecrypt` | `AES/ECB/PKCS5Padding`（注意解密固定 ECB） | key 传入 |
| RSA 加密 `rsaEncrypt` | `RSA/ECB/NoPadding`，`X509EncodedKeySpec`，输出 hex | 硬编码公钥 `PUBLIC_KEY` |
| **weapi** | 随机 16 字符 secretKey → 第1层 `AES-CBC(PRESET_KEY, IV)` → 第2层 `AES-CBC(secretKey, IV)` → `rsaEncrypt(reverse(secretKey), PUBLIC_KEY)` 得 encSecKey | `PRESET_KEY="0CoJUm6Qyw8W8jud"`、`IV="0102030405060708"` |
| **linuxapi** | `AES-ECB(LINUX_API_KEY)`，hex 大写 → `eparams` | `LINUX_API_KEY="rFgB&h#%2?^eDg:Q"` |
| **eapi** | `digest=md5("nobody"+url+"use"+text+"md5forencrypt")`；`dataStr=url+"-36cd479b6b5-"+text+"-36cd479b6b5-"+digest`；`AES-ECB(EAPI_KEY)` hex → params | `EAPI_KEY="e82ckenh8dichen8"` |
| eapi 响应/请求解密 | `aesDecrypt(EAPI_KEY)` + 正则 `(.*?)-36cd479b6b5-(.*?)-36cd479b6b5-(.*)` | 同上 |

**所有密钥均为硬编码常量**（`IV`、`PRESET_KEY`、`LINUX_API_KEY`、`EAPI_KEY`、`PUBLIC_KEY`、`BASE62`），这是网易云公开的 weapi/linuxapi/eapi 标准密钥。

```java
public static WeapiResult weapi(Object data) {
    String text = JsonUtils.toJsonString(data);
    StringBuilder secretKey = new StringBuilder();
    for (int i = 0; i < 16; i++) secretKey.append(BASE62.charAt(random.nextInt(62)));
    String firstEncrypt = aesEncrypt(text, "cbc", PRESET_KEY, IV);
    String params = aesEncrypt(firstEncrypt, "cbc", secretKey.toString(), IV);
    String reversedKey = new StringBuilder(secretKey.toString()).reverse().toString();
    String encSecKey = rsaEncrypt(reversedKey, PUBLIC_KEY);
    ...
}
```

### 3.2 math/*

- `DigestUtils`：`md5/md5Hex/sha1/sha1Hex`（Apache Commons Codec 子集，含 ASF 许可头）。
- `Hex`：`encodeHexString/decodeHex`（大小写字母表，支持 byte[]/ByteBuffer/char[]）。
- `MessageDigestAlgorithms`：`MD5/SHA_1/SHA_224/SHA_256/...` 常量。
- `Charsets` / `StringUtils`（math）：UTF-8 等字节转换。
- 其余为 `Encoder/Decoder/BinaryEncoder/BinaryDecoder` 接口及两个异常类。

### 3.3 DeviceIdGenerator

- `SALT="Would you rather watch a tree grow or a knee grow"`。
- `collect()` 采集 `os.name/os.version/os.arch` + CPU 名（JNA `Advapi32Util.registryGetStringValue` 读注册表）+ 网卡 MAC（跳过 VMware `00:50:56`）。
- `generate()` = `SHA-256(SALT + fingerprint)` 的 hex 前 51 位。

---

## 4. 网络层（RequestUtil）

- **HTTP 库**：原生 `java.net.HttpURLConnection`（`URL.openConnection()`），无第三方 HTTP 客户端。
- **同步阻塞**：`createRequest` 全程同步（`connection.getResponseCode()` + 读流），**无任何异步**。连接/读取超时 30s。调用方负责放后台线程。
- **Cookie 管理**：全局静态 `OptionsUtil.COOKIE`（String），请求时 `cookieToMap` 解析，`putIfAbsent` 注入 `__remember_me/ntes_kaola_ad/_ntes_nuid/_ntes_nnid/WNMCID/WEVNSM/osver/deviceId/os/channel/appver`；非 login 接口加 `NMTID`。响应 `Set-Cookie` 收集进 `RequestAnswer.cookies`。登录后 `CloudMusic.loadNCM` 写 `OptionsUtil.setCookie`，退出写本地 `NCMCookie.txt`。
- **四通道**：
  - `weapi` → `https://music.163.com/weapi/<uri>`，表单 `params`+`encSecKey`。
  - `linuxapi` → `https://music.163.com/api/linux/forward`，表单 `eparams`。
  - `eapi` → `https://interfacepc.music.163.com/eapi/<uri>`，表单 `params`。
  - `api`（明文）→ `https://interfacepc.music.163.com/<uri>`，JSON body。
- **域名常量**：`APP_CONF.domain="https://music.163.com"`、`apiDomain="https://interfacepc.music.163.com"`、`encrypt=true`、`encryptResponse=false`。
- **User-Agent** 按 crypto/platform 映射（weapi=Edg/Chrome、linuxapi=Linux Chrome、api=pc/android/iphone 各平台）。

```java
String crypto = StringUtils.isNotBlank(options.getCrypto())
        ? options.getCrypto() : (APP_CONF.isEncrypt() ? "eapi" : "api");
```

---

## 5. 数据模型（dto 5 个类）

全部 `lombok @Data` + `final` 字段 + gson `@SerializedName`。

### Music
`name, mainTitle, additionalTitle, id, ar→artists(List<Artist>), alia→aliasName, al→album(Album), dt→duration, mark→featureFlag, publishTime, tns→translatedName`；`transient artistsName/translatedNames`。方法：`getCoverLocation/getBlurredCoverLocation/getSmallCoverLocation`、`getArtistsName`、`getTranslatedNames`、`getCoverUrl(size)`（`album.picUrl?param=<size>y<size>`）、`getPlayUrl()`（调 `songUrlV1`）、`setLike`、`isInstrumental/isDolbyAtmos/isDirty/isHiRes`（位标志 `STEREO=8192, INSTRUMENTAL=131072, DOLBY_ATMOS=262144, DIRTY=1048576, HIRES=17179869184L`）。

### Album
`id, name, picUrl, tns→translatedName`；`getCoverLocation()`。

### Artist
`id, name, tns→translatedName, alias→aliasName`。

### PlayList
`id, name, coverImgUrl→coverUrl, trackCount→count, playCount, creator(User), description, subscribed, createTime`；`transient musics/searchMode/musicsQueried/musicsLoaded`。方法：`getCoverLocation`、`getMusics()`（懒加载，`MultiThreadingUtil.runAsync` 调 `playlistTrackAll`）、`loadMusicsWithCallback`、`updPlayCount`、`addToList/removeFromList`。

### User
`userId→id, nickname→name, signature, vipType→vip, avatarUrl`；`getAvatarLocation`、`playLists(page, limit)`（分页调 `userPlaylist`，兼容 `picUrl`/`playcount` 字段别名）。

---

## 6. 外部依赖清单（非 JDK）

| 依赖 | 包 | 用途 | 使用文件 |
|---|---|---|---|
| gson | `com.google.gson.*` | JSON 解析/序列化、`@SerializedName`、`JsonObject/JsonArray` | RequestUtil、CloudMusicApi、CloudMusic、dto/*、JsonUtils |
| lombok | `lombok.*` | `@Data/@Builder/@UtilityClass/@Getter/@Setter/@SneakyThrows/@NonNull/@Cleanup`（编译期） | 几乎全部文件 |
| **jna** | `com.sun.jna.platform.win32.*` | 读注册表 CPU 名（Windows） | DeviceIdGenerator |
| **zxing** | `com.google.zxing.*` | 二维码生成 | QRCodeGenerator |
| **commons-io** | `org.apache.commons.io.IOUtils` | 封面字节流读入 byte[] | CloudMusic |
| **commons-lang3** | `org.apache.commons.lang3.*` | `StringUtils.abbreviateMiddle`、`Validate` | JsonUtils、Location（间接） |
| today.opai.api | `today.opai.api.*` | OpenAPI 框架 | 仅 CloudMusic（见 §7） |

**不是外部 jar（源码 bundled）**：`repackage.com.jsyn.*`、`repackage.javazoom.jl.*`、`repackage.org.kc7bfi.jflac.*`、`repackage.processing.sound.*`、`repackage.com.tagtraum.jipes.*`（FFT）、`repackage.com.softsynth.*` —— 位于 `Deuterium/src/main/java/repackage/`，共 185 个源文件，需一并迁入。

> 仓库内无任何依赖 jar（`build/libs` 只有产物），也无 gson/jna/zxing/commons 的 bundled 源码。Forge Mod 需在 `build.gradle` 显式引入 gson、jna、zxing、commons-io、commons-lang3，并保留 lombok 注解处理器。

---

## 7. today.opai.api 依赖

**仅 `music/CloudMusic.java` 1 个文件**：
- `import today.opai.api.enums.EnumChatColor;`（第 18 行）
- 通过 `SharedConstants.api`（`OpenAPI`）调用 `api.printMessage(EnumChatColor.RED + "...")`（`handleUnplayableSong` 内，第 696 行）。

其余全部文件（CryptoUtil / RequestUtil / OptionsUtil / DeviceIdGenerator / CloudMusicApi / math / dto / AudioPlayer / QRCodeGenerator / Quality）**不依赖** `today.opai.api`。

---

## 8. 线程模型

- **后台线程调用（不得在主线程）**：所有 `RequestUtil.createRequest` / `CloudMusicApi.*` 方法均为同步阻塞网络调用。原调用方用 `MultiThreadingUtil.runAsync` 包起来：`CloudMusic.loadLyric`、`PlayList.getMusics`、`CloudMusic.loadMusicCover`、`QRCodeGenerator.generateAndLoadTexture`、`CloudMusic.loadSmallCoverAsync` 等。
- **必须在主线程**：渲染/纹理操作（`TextureManager.loadTexture`、`Textures.loadTexture`）—— 原代码在 `runAsync` 内直接调用（潜在线程安全隐患），Forge 下需改为 `Minecraft.getMinecraft().addScheduledTask` 派发回主线程。
- **主线程调度**：`MultiThreadingUtil.runOnMainThread/runOnMainThreadBlocking` → `TritiumEventHandler.addScheduledTask`（Opai 框架），Forge 需替换。
- **播放线程**：`CloudMusic.playThread` 为 `PlayThread extends Thread`，循环 `Thread.sleep(10)` 轮询 + `player.getCurrentTimeMillis()` + `updateCurrentLyric`。
- **登录轮询**：`CloudMusic.qrCodeLogin()` 内 `while(true)` + `Thread.sleep(3000)` 轮询 `loginQrCheck`（应置于后台线程）。
- **未发现** `isCallingFromMainThread` 之类的守卫检查。

⚠️ `MultiThreadingUtil` 使用 `Executors.newVirtualThreadPerTaskExecutor()`（**Java 21 虚拟线程**），Forge 1.8.9 / Java 8 必须替换为固定线程池。

---

## 9. Java 8 / LWJGL 兼容性风险

### Java 9+ API（必须改写）
| 位置 | 用法 | Java 版本 | 改法 |
|---|---|---|---|
| `CloudMusic.java:93,152,375,547` | `List.getFirst()` | Java 21 `SequencedCollection` | `get(0)` |
| `CloudMusic.java:216` | `List.getLast()`（注释内，可忽略） | — | — |
| `CloudMusic.java:1089` | `InputStream.readAllBytes()` | Java 9 | 循环读 / `IOUtils.toByteArray` |
| `Music.java:99` | `Stream.toList()` | Java 16 | `collect(Collectors.toList())` |
| `DeviceIdGenerator.java:86-88` | `NetworkInterface.inetAddresses()`（Java 9）+ `Optional.isEmpty()`（Java 11） | Java 9/11 | `getInetAddresses()` Enumeration 遍历 + `!isPresent()` |
| `MultiThreadingUtil.java:20`（间接） | `Executors.newVirtualThreadPerTaskExecutor()` | Java 21 | 固定线程池 / `Executors.newCachedThreadPool` |

Java 8 可用（无需改）：`String.join`、`Map.getOrDefault/putIfAbsent`、`Collectors.toList`、`java.util.Base64`、`StandardCharsets`、`SecureRandom`、`@Deprecated`。

### LWJGL
本包内**未使用任何 `org.lwjgl.*`**。音频走 JSyn（javax.sound）+ Processing Sound，渲染在 `tritium.rendering`（本包之外）。无 LWJGL 3 直接引用，无 GLFW/MemoryStack 风险。`DeviceIdGenerator` 用 JNA（非 LWJGL），1.8.9 环境需打包 JNA 及其 Windows native（`jna-platform`）。

---

## 10. 结论

1. **能否整体直接复用？**
   - **API/加密/数据层可整体直接复用**：`CryptoUtil`、`DeviceIdGenerator`、`OptionsUtil`、`RequestUtil`、`StringUtils`、`api/CloudMusicApi`、`math/*`（11 文件）、`music/dto/*`（5 文件）、`music/Quality` —— 除少量 Java 9+ 语法外基本即插即用。
   - **`music/CloudMusic` 需重写抽取**（分类 D）：核心播放队列（PlayThread/prev/next/playMode）、歌词状态机（findCurrentLyric/updateCurrentLyric）、登录流程、封面/下载逻辑是 P0，但重度耦合 `tritium.rendering.*`、`tritium.screens.ncm.*`、`TritiumMusicExtension`、`today.opai.api`，必须按「播放/歌词核心」与「UI/渲染/框架」解耦后移植。
   - **`music/AudioPlayer` 需随 `repackage.*`（JSyn/Processing Sound/JLayer/JFLAC）+ `SpectrumVisualizer` 一起迁入**（分类 C），并验证 Java 8 与音频线程隔离。

2. **P0 必须保留**：`CryptoUtil`、`DeviceIdGenerator`、`OptionsUtil`、`RequestUtil`、`StringUtils`、`api/CloudMusicApi`、`math/DigestUtils`、`math/Hex`、`math/StringUtils`、`math/MessageDigestAlgorithms`、`math/Charsets` + 其余 math 接口/异常、`music/dto/*`、`music/Quality`、`music/CloudMusic`（抽取后的播放/歌词核心）、`repackage/*`（音频+FFT 库）。

3. **外部 jar 处理建议**：
   - `gson`、`commons-io`、`commons-lang3`、`lombok`：常规引入（`compile`/`annotationProcessor`）。
   - `jna`（+`jna-platform`）：仅 `DeviceIdGenerator` 读 CPU 名用；可保留（Windows 下需 native），或降级为纯 `System.getProperty`/无 CPU 名版本（D 类最小降级，需记录，deviceId 稳定性略降）。
   - `zxing`：仅二维码登录用；可打包 jar（`core`），或用 AWT 自绘二维码替代（分类 D，不推荐）。
   - `repackage.*`：源码 bundled，随代码迁入即可，无需额外 jar。

4. **加密链路是否完整自包含**：**是**。`CryptoUtil`（AES/RSA/weapi/linuxapi/eapi）+ `math/DigestUtils`（MD5）+ `math/Hex`（hex）+ `JsonUtils`（序列化）构成完整链路，密钥全部硬编码，无任何外部加密/第三方 API 依赖。

5. **关键适配点清单（Java 8）**：`getFirst/getLast` → `get(0)`；`readAllBytes` → 循环读；`Stream.toList` → `Collectors.toList`；`inetAddresses()`+`Optional.isEmpty()` → Enumeration 遍历；虚拟线程 → 固定线程池；主线程派发（`addScheduledTask`）→ Forge `Minecraft.addScheduledTask`。
