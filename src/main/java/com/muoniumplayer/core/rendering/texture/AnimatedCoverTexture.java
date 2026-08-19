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
        super(normalize(sourceFrames.get(0), sourceFrames.get(0).getWidth(), sourceFrames.get(0).getHeight()), true, true);
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

    @Override
    public int getGlTextureId() {
        advanceFrameIfDue();
        return super.getGlTextureId();
    }

    private void advanceFrameIfDue() {
        if (frames.size() < 2 || !MuoniumPlayerExtension.isCallingFromMainThread()) {
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
