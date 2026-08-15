package today.opai.api.enums;

/**
 * 对应原项目 today.opai.api.enums.EnumChatColor。
 * toString() 返回 Minecraft 颜色码前缀，供 {@code EnumChatColor.RED + "文本"} 直接拼接。
 */
public enum EnumChatColor {

    BLACK("§0"),
    DARK_BLUE("§1"),
    DARK_GREEN("§2"),
    DARK_AQUA("§3"),
    DARK_RED("§4"),
    DARK_PURPLE("§5"),
    GOLD("§6"),
    GRAY("§7"),
    DARK_GRAY("§8"),
    BLUE("§9"),
    GREEN("§a"),
    AQUA("§b"),
    RED("§c"),
    LIGHT_PURPLE("§d"),
    YELLOW("§e"),
    WHITE("§f");

    private final String code;

    EnumChatColor(String code) {
        this.code = code;
    }

    @Override
    public String toString() {
        return code;
    }
}
