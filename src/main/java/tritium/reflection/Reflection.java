package tritium.reflection;

import today.opai.api.OpenAPI;

/**
 * 原项目 tritium.reflection.Reflection 用于向外部 Opai 客户端的 "Dynamic Island" 水印叠加歌词，
 * 依赖 {@code me.fan87.nativeinstrumentation.NativeInstrumentation} + ASM 字节码重定义 Opai 水印类，
 * 并在运行时动态反射定位 ClassFinder/CommonReflectionClasses 等 Opai 内部类。
 *
 * 该功能是 Opai 客户端特有（macOS/动态岛歌词水印），独立 1.8.9 Forge Mod 不存在该客户端、水印或动态岛，
 * 且独立 Mod 的歌词由 MusicLyricsPanel 渲染。故此处按 CLAUDE.md §14 决策树归为 E（可删除）：
 * DYNAMIC_ISLAND_SUPPORTED 恒为 false，init 为空操作。对外只保留原项目实际引用的两个静态成员。
 */
public class Reflection {

    /** 原项目通过 NativeInstrumentation 反射探测 Opai 客户端是否支持动态岛；独立 Mod 恒为 false。 */
    public static boolean DYNAMIC_ISLAND_SUPPORTED = false;

    /** 原项目注册一个 10 帧后异步执行 initDynIsland 的事件处理器；独立 Mod 无需任何动作。 */
    public static void init(OpenAPI api) {
        // no-op：动态岛为 Opai 客户端功能，独立 Mod 不提供。
    }
}
