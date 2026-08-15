package tritium.ncm.music;

import tritium.screens.ncm.LyricLine;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次"播放会话"，把 当前声音 = 当前 PlaybackSession = 当前歌词 Timeline 绑定为一对一。
 *
 * 每次开始播放一首新歌都生成一个唯一 Session；用 AtomicLong generation 做逻辑取消（logical
 * cancellation）。异步歌词任务捕获 {@link #sessionId} + {@link #songId}，提交前必须通过
 * {@link #isActive()} 双重校验，任何不匹配的旧结果直接丢弃，不污染新歌状态。
 *
 * 注意：{@code player} 是单例复用的 AudioPlayer（切歌时 {@code setAudio()} 已彻底 stop/cleanUp
 * 旧 SoundFile），本字段仅用于把"当前会话"与"当前播放器实例"显式绑定，方便歌词同步读取真实时钟。
 */
public class PlaybackSession {

    public final long sessionId;
    public final long songId;
    public final AtomicBoolean active = new AtomicBoolean(true);

    /** 真正开始播放后才置 true；歌词先到/后到都只暂存于本 Session。 */
    public volatile boolean audioActive = false;

    /** 两阶段提交：后台只 fetch+parse，结果暂存于此，由主线程 applyLyricTimeline() 提交。 */
    public volatile List<LyricLine> pendingLyrics = null;

    /** 本 Session 绑定的 AudioPlayer（单例复用）。 */
    public volatile AudioPlayer player = null;

    public PlaybackSession(long sessionId, long songId) {
        this.sessionId = sessionId;
        this.songId = songId;
    }

    /** 会话是否仍为当前 generation 且未被 invalidate。 */
    public boolean isActive() {
        return active.get() && CloudMusic.generation.get() == sessionId;
    }

    public void invalidate() {
        active.set(false);
    }
}
