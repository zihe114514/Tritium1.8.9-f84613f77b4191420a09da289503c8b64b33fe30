package com.muoniumplayer.core.rendering.texture;

import com.muoniumplayer.core.MuoniumPlayerExtension;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight animated image texture for remote dynamic album covers.
 * Frames are decoded off-thread and the texture advances only from the render
 * thread, keeping all OpenGL uploads on Minecraft's main thread.
 */
public final class AnimatedCoverTexture extends DynamicTexture {

    private final List<int[]> frames;
    private final List<Long> frameDurations;
    private int currentFrame;
    private long nextFrameAt;

    public AnimatedCoverTexture(List<BufferedImage> sourceFrames, List<Long> sourceDurations) {
        // clearable 必须是 false:DynamicTexture 在首次上传后会把 dynamicTextureData 置空,而这里每换一帧
        // 都要往那个数组里拷像素再上传,置空之后第一次换帧就会 NPE。
        super(normalize(sourceFrames.get(0), sourceFrames.get(0).getWidth(), sourceFrames.get(0).getHeight()), false, true);
        int width = getWidth();
        int height = getHeight();
        this.frames = new ArrayList<int[]>(sourceFrames.size());
        this.frameDurations = new ArrayList<Long>(sourceFrames.size());
        for (int index = 0; index < sourceFrames.size(); index++) {
            BufferedImage frame = normalize(sourceFrames.get(index), width, height);
            int[] pixels = new int[width * height];
            frame.getRGB(0, 0, width, height, pixels, 0, width);
            this.frames.add(pixels);
            long duration = sourceDurations != null && index < sourceDurations.size()
                    ? sourceDurations.get(index) : 100L;
            this.frameDurations.add(Math.max(40L, Math.min(2000L, duration)));
        }
        this.nextFrameAt = System.currentTimeMillis() + this.frameDurations.get(0);
    }

    /** 帧数,1 表示实际上是静态图。 */
    public int getFrameCount() {
        return frames.size();
    }

    /** 帧数据占用的堆内存,用于日志与预算核对。 */
    public long getHeapBytes() {
        return (long) frames.size() * getWidth() * getHeight() * 4L;
    }

    @Override
    public int getGlTextureId() {
        advanceFrameIfDue();
        return super.getGlTextureId();
    }

    private void advanceFrameIfDue() {
        if (frames.size() < 2 || dynamicTextureData == null
                || !MuoniumPlayerExtension.isCallingFromMainThread()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextFrameAt) {
            return;
        }
        currentFrame = (currentFrame + 1) % frames.size();
        System.arraycopy(frames.get(currentFrame), 0, dynamicTextureData, 0, dynamicTextureData.length);
        updateDynamicTexture();
        nextFrameAt = now + frameDurations.get(currentFrame);
    }

    private static BufferedImage normalize(BufferedImage source, int width, int height) {
        if (source.getWidth() == width && source.getHeight() == height && source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }
}
