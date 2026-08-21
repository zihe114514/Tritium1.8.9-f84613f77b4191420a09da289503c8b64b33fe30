package com.muoniumplayer.core.ncm.music;

/**
 * 当前播放器所使用的音乐平台。该枚举属于宿主 MOD，避免 UI 直接依赖 Cadence 的实现类型。
 */
public enum MusicPlatform {
    NETEASE("网易云", 0xE94747),
    QQ("QQ音乐", 0x31C27C),
    GD("GD音乐台", 0x3D5AFE);

    private final String displayName;
    private final int brandColor;

    MusicPlatform(String displayName, int brandColor) {
        this.displayName = displayName;
        this.brandColor = brandColor;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getBrandColor() {
        return brandColor;
    }

    public top.fpsmaster.music.MusicSource toCadenceSource() {
        return this == QQ ? top.fpsmaster.music.MusicSource.QQ : top.fpsmaster.music.MusicSource.NETEASE;
    }

    public static MusicPlatform fromCadence(top.fpsmaster.music.MusicSource source) {
        return source == top.fpsmaster.music.MusicSource.QQ ? QQ : NETEASE;
    }
}
