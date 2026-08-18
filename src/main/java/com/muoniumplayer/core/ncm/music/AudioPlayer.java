package com.muoniumplayer.core.ncm.music;

import lombok.Getter;
import lombok.SneakyThrows;
import repackage.com.jsyn.exceptions.ChannelMismatchException;
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

    /**
     * Some downloaded files can be opened by the decoder but contain no usable
     * audio frames. AudioSample.position() performs a modulo by frames(), so an
     * empty sample must never reach the playback or rendering paths.
     */
    private boolean closed;

    public AudioPlayer(File file) {
        finished = false;
        this.player = loadUsableSoundFile(file);
        this.setListeners();
        this.closed = false;
    }

    public void setAudio(File file) {
        // Decode and validate before closing the currently playing song. A
        // corrupt/empty replacement must not destroy an otherwise usable player.
        SoundFile next = loadUsableSoundFile(file);
        next.setOnFinished(() -> finished = true);

        SoundFile previous = this.player;
        this.player = next;
        finished = false;
        this.closed = false;
        dispose(previous);
    }

    public void setListeners() {
        if (player != null)
            player.setOnFinished(() -> finished = true);
    }

    public void play() {
        if (!isUsable())
            return;

        finished = false;
        try {
            this.player.play();
            this.player.amp(volume);
        } catch (ChannelMismatchException mismatch) {
            // Preserve the existing retry path in CloudMusic for channel changes.
            throw mismatch;
        } catch (RuntimeException ignored) {
            // A decoder can become invalid between validation and playback.
        }
    }

    @SneakyThrows
    public void setPlaybackTime(float millis) {
        if (!isUsable())
            return;

        float total = getTotalTimeMillis();
        if (total <= 0.0f)
            return;

        float safeMillis = Math.max(0.0f, Math.min(total, millis));
        try {
            this.player.jump(safeMillis / 1000F);
            this.player.amp(volume);
        } catch (RuntimeException ignored) {
            // Seeking is best-effort. A malformed stream must not crash the UI.
        }
    }

    @SneakyThrows
    public void close() {
        closed = true;
        dispose(this.player);
    }

    @Getter
    private boolean finished;

    public void setAfterPlayed(Runnable runnable) {
        this.afterPlayed = runnable;
        if (this.player != null)
            this.player.setOnFinished(() -> {
                finished = true;
                if (runnable != null)
                    runnable.run();
            });
    }

    public float getTotalTimeSeconds() {
        return (int) (getTotalTimeMillis() / 1000.0f);
    }

    public float getCurrentTimeSeconds() {
        return (int) (getCurrentTimeMillis() / 1000);
    }

    public float getTotalTimeMillis() {
        // 直接由原始浮点秒换算毫秒（round 到最近毫秒），保留小数时长。
        if (!isUsable())
            return 0.0f;

        try {
            float duration = this.player.duration();
            if (Float.isNaN(duration) || Float.isInfinite(duration) || duration <= 0.0f)
                return 0.0f;
            return Math.max(0.0f, Math.round(duration * 1000.0f));
        } catch (RuntimeException ignored) {
            return 0.0f;
        }
    }

    public float getCurrentTimeMillis() {
        if (!isUsable())
            return 0.0f;

        try {
            float position = this.player.position() * 1000.0f;
            if (Float.isNaN(position) || Float.isInfinite(position))
                return 0.0f;

            float total = getTotalTimeMillis();
            if (total <= 0.0f)
                return 0.0f;
            return Math.max(0.0f, Math.min(total, position));
        } catch (RuntimeException ignored) {
            // AudioSample.position() uses `% frames()` and can throw when a
            // decoder produced an empty sample. Report a safe position.
            return 0.0f;
        }
    }

    public boolean isPausing() {
        return !isUsable() || !this.player.isPlaying();
    }

    public void setVolume(float volume) {
        this.volume = volume;
        if (isUsable()) {
            try {
                this.player.amp(this.getVolume());
            } catch (RuntimeException ignored) {
                // Apply again on the next successful playback operation.
            }
        }
    }

    public void pause() {
        if (isUsable()) {
            try {
                this.player.pause();
            } catch (RuntimeException ignored) {
                // Ignore malformed decoder state.
            }
        }
    }

    public void unpause() {
        this.play();
    }

    /**
     * Returns whether the decoder exposed a non-empty, measurable sample.
     */
    public boolean isUsable() {
        if (closed || player == null)
            return false;
        return isUsable(player);
    }

    private static SoundFile loadUsableSoundFile(File file) {
        if (file == null)
            throw new IllegalArgumentException("Audio file is null");

        SoundFile candidate = new SoundFile(file.getAbsolutePath());
        if (isUsable(candidate))
            return candidate;

        dispose(candidate);
        throw new IllegalStateException("Audio file contains no usable frames: " + file.getName());
    }

    private static boolean isUsable(SoundFile soundFile) {
        if (soundFile == null)
            return false;

        try {
            int frames = soundFile.frames();
            int sampleRate = soundFile.sampleRate();
            float duration = soundFile.duration();
            return frames > 0 && sampleRate > 0
                    && !Float.isNaN(duration)
                    && !Float.isInfinite(duration)
                    && duration > 0.0f;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void dispose(SoundFile soundFile) {
        if (soundFile == null)
            return;

        try {
            if (isUsable(soundFile))
                soundFile.jump(0.0f);
        } catch (RuntimeException ignored) {
        }
        try {
            soundFile.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            soundFile.cleanUp();
        } catch (RuntimeException ignored) {
        }
    }
}
