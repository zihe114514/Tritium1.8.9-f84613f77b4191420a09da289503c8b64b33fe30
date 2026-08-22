package com.muoniumplayer.core;

import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;

/**
 * 播放控制快捷键真正执行的那一段动作。
 *
 * <p>键位本身不在这里：暂停 / 继续注册成 Forge 的 {@code KeyBinding}（见
 * {@code MuoniumPlayerMod.keyTogglePause}），出现在原版「选项 → 控制 → MuoniumPlayer」分组里，
 * 和模组已有的上一曲 / 下一曲 / 音量键并列，由玩家自己改键，也由原版处理冲突提示。</p>
 *
 * <p>这样就不需要自己轮询键盘了。{@code KeyBinding.isPressed()} 消费的是 Minecraft 自己维护的
 * 按键计数，天然是上升沿触发，也不会像 {@code Keyboard.next()} 那样抢 LWJGL 的事件队列造成丢键；
 * 而 Minecraft 只在没有打开界面时喂给这些计数，所以聊天框、指令栏、搜索框里打字不会误触发。</p>
 *
 * <p>动作单独留在这里而不是写在 mod 主类里：播放器界面的空格键、控制栏按钮和这枚快捷键必须共用
 * 同一条暂停语义与同一套灵动岛反馈，否则三处的判空与提示迟早各自演化。</p>
 */
public final class MusicHotkeys {

    private MusicHotkeys() {
    }

    /**
     * 切换暂停 / 继续，并给出对应的灵动岛提示。
     *
     * <p>任何异常都被吞掉：快捷键处理跑在客户端 tick 上，绝不能拖垮主循环。</p>
     */
    public static void togglePlayback() {
        try {
            CloudMusic.PlayPauseResult result = CloudMusic.togglePlayPause();
            String name = trackName();
            switch (result) {
                case PAUSED:
                    DownloadDynamicIsland.showPlaybackPaused(name);
                    break;
                case RESUMED:
                    DownloadDynamicIsland.showPlaybackResumed(name);
                    break;
                default:
                    // 没有可操作的播放时也要给一句，否则用户会以为按键没生效。
                    DownloadDynamicIsland.showPlaybackToggleUnavailable();
                    break;
            }
        } catch (Throwable ignored) {
        }
    }

    private static String trackName() {
        try {
            return CloudMusic.currentlyPlaying == null ? null : CloudMusic.currentlyPlaying.getName();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
