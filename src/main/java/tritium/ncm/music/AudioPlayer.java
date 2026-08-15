package tritium.ncm.music;

import lombok.Getter;
import lombok.SneakyThrows;
import repackage.processing.sound.SoundFile;

import java.io.File;

/**
 * 播放视频里面的音频
 */
public class AudioPlayer {
    public SoundFile player;
    public Runnable afterPlayed;

    @Getter
    public float volume = 0.25f;

    public AudioPlayer(File file) {
        finished = false;

        this.player = new SoundFile(file.getAbsolutePath());
        this.setListeners();
    }

    public void setAudio(File file) {
        this.close();

        this.player = new SoundFile(file.getAbsolutePath());
        this.setListeners();
        finished = false;
    }

    public void setListeners() {
        player.setOnFinished(() -> finished = true);
    }

    public void play() {
        finished = false;
        this.player.play();
        this.player.amp(volume);
    }

    @SneakyThrows
    public void setPlaybackTime(float millis) {
        this.player.jump(millis / 1000F);
        this.player.amp(volume);
    }

    @SneakyThrows
    public void close() {
        this.player.jump(0);
        player.stop();
        player.cleanUp();
    }

    @Getter
    private boolean finished;

    public void setAfterPlayed(Runnable runnable) {
        this.afterPlayed = runnable;
        this.player.setOnFinished(() -> {
            finished = true;
            runnable.run();
        });
    }

    public float getTotalTimeSeconds() {
        return (int) this.player.duration();
    }

    public float getCurrentTimeSeconds() {
        return (int) (getCurrentTimeMillis() / 1000);
    }

    public float getTotalTimeMillis() {
        // 修复 R6：duration() 先转 int 秒再乘 1000 会丢失小数秒；
        // 直接由原始浮点秒换算毫秒（round 到最近毫秒），保留小数时长。
        return Math.round(this.player.duration() * 1000.0f);
    }

    public float getCurrentTimeMillis() {
        return this.player.position() * 1000;
    }

    public boolean isPausing() {
        return !this.player.isPlaying();
    }

    public void setVolume(float volume) {
        this.volume = volume;
        this.player.amp(this.getVolume());
    }

    public void pause() {
        this.player.pause();
    }

    public void unpause() {
        this.play();
    }
}
