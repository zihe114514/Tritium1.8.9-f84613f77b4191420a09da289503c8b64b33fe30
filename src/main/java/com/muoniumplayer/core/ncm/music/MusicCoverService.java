package com.muoniumplayer.core.ncm.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Cleanup;
import com.muoniumplayer.core.ncm.api.CloudMusicApi;
import com.muoniumplayer.core.ncm.music.dto.Music;
import org.apache.commons.io.IOUtils;
import com.muoniumplayer.core.rendering.GaussianKernel;
import com.muoniumplayer.core.rendering.TextureManager;
import com.muoniumplayer.core.rendering.texture.AnimatedCoverTexture;
import com.muoniumplayer.core.rendering.texture.DynamicTexture;
import com.muoniumplayer.core.rendering.texture.ITextureObject;
import com.muoniumplayer.core.rendering.texture.Textures;
import com.muoniumplayer.core.utils.Location;
import com.muoniumplayer.core.utils.network.HttpUtils;
import com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Node;

/**
 * Asynchronous music-cover retrieval, texture upload and lyric-background blur generation.
 *
 * <p>This service intentionally preserves the existing asynchronous task boundaries: the main
 * cover texture is uploaded as soon as it downloads, while the blurred lyric texture is produced
 * on a separate task.</p>
 */
final class MusicCoverService {

    private static final Kernel GAUSSIAN_KERNEL = new Kernel(41, 41, GaussianKernel.generate(41));
    private static final int MAX_DYNAMIC_COVER_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DYNAMIC_COVER_DIMENSION = 512;
    private static final int MAX_DYNAMIC_COVER_FRAMES = 24;
    private static final Set<String> DYNAMIC_COVER_ATTEMPTS = new HashSet<String>();

    private MusicCoverService() {
    }

    static void loadMusicCover(Music music, boolean forceReload) {
        Location musicCover = music.getCoverLocation();
        Location musicCoverSmall = music.getSmallCoverLocation();
        Location musicCoverBlur = music.getBlurredCoverLocation();
        TextureManager textureManager = TextureManager.getInstance();

        if (shouldLoadCover(textureManager, musicCover, forceReload)) {
            loadMainCoverAsync(music, musicCover, musicCoverBlur);
        }
        if (shouldLoadCover(textureManager, musicCoverSmall, forceReload)) {
            loadSmallCoverAsync(music, musicCoverSmall);
        }
    }

    /**
     * Fetches the optional NetEase dynamic-cover metadata only for the active
     * NetEase song. Any response/decoder failure intentionally leaves the
     * ordinary static cover untouched.
     */
    static void loadDynamicMusicCover(final Music music) {
        if (music == null || !music.isNetease() || music.getId() <= 0L) {
            return;
        }
        final Location dynamicLocation = music.getDynamicCoverLocation();
        final TextureManager textureManager = TextureManager.getInstance();
        if (textureManager.getTexture(dynamicLocation) != null) {
            return;
        }
        synchronized (DYNAMIC_COVER_ATTEMPTS) {
            if (!DYNAMIC_COVER_ATTEMPTS.add(music.getStableKey())) {
                return;
            }
        }

        MultiThreadingUtil.runAsync(() -> {
            try {
                JsonObject response = CloudMusicApi.songDynamicCover(music.getId()).toJsonObject();
                String imageUrl = findDynamicCoverUrl(response);
                if (imageUrl.isEmpty() || isVideoUrl(imageUrl)) {
                    return;
                }
                byte[] imageBytes;
                try (InputStream stream = HttpUtils.downloadStream(imageUrl, 2)) {
                    imageBytes = readAtMost(stream, MAX_DYNAMIC_COVER_BYTES);
                }
                ITextureObject texture = decodeDynamicCover(imageBytes);
                if (texture == null) {
                    return;
                }
                MultiThreadingUtil.runOnMainThread(() -> {
                    // The user might already have switched songs while this request completed.
                    if (CloudMusic.currentlyPlaying == music) {
                        TextureManager.getInstance().loadTexture(dynamicLocation, texture);
                    }
                });
            } catch (Throwable ignored) {
                // Dynamic artwork is purely decorative. Static artwork remains the guaranteed fallback.
            }
        });
    }

    static BufferedImage gaussianBlur(BufferedImage image, int blur) {
        Map<RenderingHints.Key, Object> map = new HashMap<>();
        map.put(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        map.put(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        map.put(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        RenderingHints hints = new RenderingHints(map);

        ConvolveOp operation = new ConvolveOp(GAUSSIAN_KERNEL, ConvolveOp.EDGE_NO_OP, hints);
        BufferedImage filtered = operation.filter(image, null);
        BufferedImage output = new BufferedImage(filtered.getWidth(), filtered.getHeight(), filtered.getType());
        Graphics2D graphics = (Graphics2D) output.getGraphics();
        graphics.setRenderingHints(map);
        graphics.drawImage(filtered, -blur, -blur, filtered.getWidth() + blur * 2,
                filtered.getHeight() + blur * 2, null);
        return output;
    }

    private static boolean shouldLoadCover(TextureManager textureManager, Location coverLocation,
                                           boolean forceReload) {
        return textureManager.getTexture(coverLocation) == null || forceReload;
    }

    private static void loadMainCoverAsync(Music music, Location musicCover, Location musicCoverBlur) {
        MultiThreadingUtil.runAsync(() -> {
            try {
                @Cleanup
                InputStream coverStream = HttpUtils.downloadStream(music.getCoverUrl(320), 5);
                byte[] imageData = IOUtils.toByteArray(coverStream);
                BufferedImage coverImage = DynamicTexture.readImage(new ByteArrayInputStream(imageData));
                if (coverImage != null) {
                    loadCoverTextures(coverImage, musicCover, musicCoverBlur);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private static void loadCoverTextures(BufferedImage coverImage, Location musicCover, Location musicCoverBlur) {
        Textures.loadTexture(musicCover, coverImage);
        MultiThreadingUtil.runAsync(() -> {
            BufferedImage inputImage = new BufferedImage(coverImage.getWidth(), coverImage.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            inputImage.setRGB(0, 0, coverImage.getWidth(), coverImage.getHeight(),
                    coverImage.getRGB(0, 0, coverImage.getWidth(), coverImage.getHeight(), null, 0,
                            coverImage.getWidth()), 0, coverImage.getWidth());
            Textures.loadTexture(musicCoverBlur, gaussianBlur(inputImage, 31));
        });
    }

    private static void loadSmallCoverAsync(Music music, Location musicCoverSmall) {
        MultiThreadingUtil.runAsync(() -> {
            InputStream smallCoverStream = HttpUtils.downloadStream(music.getCoverUrl(128), 5);
            BufferedImage smallCoverImage = DynamicTexture.readImage(smallCoverStream);
            Textures.loadTexture(musicCoverSmall, smallCoverImage);
        });
    }

    private static ITextureObject decodeDynamicCover(byte[] imageBytes) throws IOException {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, false);
                int count;
                try {
                    count = Math.min(MAX_DYNAMIC_COVER_FRAMES, Math.max(1, reader.getNumImages(true)));
                } catch (Throwable ignored) {
                    count = 1;
                }
                List<BufferedImage> frames = new ArrayList<BufferedImage>(count);
                List<Long> durations = new ArrayList<Long>(count);
                for (int index = 0; index < count; index++) {
                    BufferedImage frame = reader.read(index);
                    if (frame == null) {
                        continue;
                    }
                    frames.add(scaleDown(frame));
                    durations.add(readFrameDuration(reader.getImageMetadata(index)));
                }
                if (frames.isEmpty()) {
                    return null;
                }
                return frames.size() > 1 ? new AnimatedCoverTexture(frames, durations)
                        : new DynamicTexture(frames.get(0), true, true);
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage scaleDown(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int max = Math.max(width, height);
        if (max <= MAX_DYNAMIC_COVER_DIMENSION && source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        double scale = max <= MAX_DYNAMIC_COVER_DIMENSION ? 1.0 : MAX_DYNAMIC_COVER_DIMENSION / (double) max;
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static long readFrameDuration(IIOMetadata metadata) {
        if (metadata == null) {
            return 100L;
        }
        try {
            Node root = metadata.getAsTree("javax_imageio_gif_image_1.0");
            Node child = root.getFirstChild();
            while (child != null) {
                if ("GraphicControlExtension".equals(child.getNodeName()) && child.getAttributes() != null
                        && child.getAttributes().getNamedItem("delayTime") != null) {
                    return Math.max(40L, Long.parseLong(child.getAttributes().getNamedItem("delayTime").getNodeValue()) * 10L);
                }
                child = child.getNextSibling();
            }
        } catch (Throwable ignored) {
            // Non-GIF image readers do not expose the GIF metadata tree.
        }
        return 100L;
    }

    private static byte[] readAtMost(InputStream stream, int maxBytes) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("dynamic cover exceeds size limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String findDynamicCoverUrl(JsonElement element) {
        List<DynamicUrlCandidate> candidates = new ArrayList<DynamicUrlCandidate>();
        collectUrlCandidates(element, "", candidates);
        DynamicUrlCandidate best = null;
        for (DynamicUrlCandidate candidate : candidates) {
            if (isVideoUrl(candidate.url)) {
                continue;
            }
            if (best == null || candidate.score > best.score) {
                best = candidate;
            }
        }
        return best == null ? "" : best.url;
    }

    private static void collectUrlCandidates(JsonElement element, String path, List<DynamicUrlCandidate> candidates) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String value = element.getAsString();
            if (value.startsWith("http://") || value.startsWith("https://")) {
                String lowerPath = path.toLowerCase();
                int score = 1;
                if (lowerPath.contains("dynamic")) score += 100;
                if (lowerPath.contains("cover")) score += 40;
                if (lowerPath.contains("image") || lowerPath.contains("pic")) score += 25;
                if (lowerPath.contains("url")) score += 10;
                candidates.add(new DynamicUrlCandidate(value, score));
            }
            return;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                collectUrlCandidates(array.get(index), path + "[]", candidates);
            }
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                collectUrlCandidates(entry.getValue(), path + "." + entry.getKey(), candidates);
            }
        }
    }

    private static boolean isVideoUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        return lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".flv") || lower.contains(".webm");
    }

    private static final class DynamicUrlCandidate {
        private final String url;
        private final int score;

        private DynamicUrlCandidate(String url, int score) {
            this.url = url;
            this.score = score;
        }
    }
}
