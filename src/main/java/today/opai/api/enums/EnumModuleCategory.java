package today.opai.api.enums;

/**
 * 对应原项目 today.opai.api.enums.EnumModuleCategory。
 * 播放器实际只用到 MISC 与 VISUAL，此处保留常用分类以维持 API 表面。
 */
public enum EnumModuleCategory {
    COMBAT,
    MOVEMENT,
    RENDER,
    PLAYER,
    WORLD,
    VISUAL,
    MISC
}
