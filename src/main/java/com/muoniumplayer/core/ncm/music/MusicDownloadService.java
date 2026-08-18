package com.muoniumplayer.core.ncm.music;

import lombok.SneakyThrows;
import com.muoniumplayer.core.MuoniumPlayerExtension;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.utils.network.HttpUtils;
import com.muoniumplayer.core.utils.other.WrappedInputStream;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * Download implementation and its existing progress publication contract.
 *
 * <p>Both the in-player download state and Dynamic Island notifications are
 * published at their original points in the transfer lifecycle.</p>
 */
final class MusicDownloadService {

    private MusicDownloadService() {
    }

    @SneakyThrows
    static void downloadMusic(String playUrl, File music) {
        MuoniumPlayerExtension.getInstance().musicInfo.downloading = true;
        MuoniumPlayerExtension.getInstance().musicInfo.downloadProgress = 0;
        MuoniumPlayerExtension.getInstance().musicInfo.downloadSpeed = "0 b/s";
        DownloadDynamicIsland.beginDownload();

        try {
            InputStream stream = new WrappedInputStream(HttpUtils.get(playUrl, null),
                    new WrappedInputStream.ProgressListener() {
                        com.muoniumplayer.core.utils.timing.Timer timer = new com.muoniumplayer.core.utils.timing.Timer();

                        @Override
                        public void onProgress(double progress) {
                            MuoniumPlayerExtension.getInstance().musicInfo.downloadProgress = progress;
                            DownloadDynamicIsland.updateProgress(progress);
                            if (progress >= 1) {
                                MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
                            }
                        }

                        final long kilo = 1024;
                        final long mega = kilo * kilo;
                        final long giga = mega * kilo;
                        final long tera = giga * kilo;

                        String getSize(long size) {
                            String text;
                            double kb = (double) size / kilo;
                            double mb = kb / kilo;
                            double gb = mb / kilo;
                            double tb = gb / kilo;
                            if (size < kilo) {
                                text = size + " Bytes";
                            } else if (size < mega) {
                                text = String.format("%.2f", kb) + " KB";
                            } else if (size < giga) {
                                text = String.format("%.2f", mb) + " MB";
                            } else if (size < tera) {
                                text = String.format("%.2f", gb) + " GB";
                            } else {
                                text = String.format("%.2f", tb) + " TB";
                            }
                            return text;
                        }

                        int lastBytesRead = 0;

                        @Override
                        public void bytesRead(int bytesRead) {
                            int checkDelay = 500;
                            if (timer.isDelayed(checkDelay)) {
                                timer.reset();
                                int diff = (bytesRead - lastBytesRead) * (1000 / checkDelay);
                                String speed = getSize(diff) + "/s";
                                MuoniumPlayerExtension.getInstance().musicInfo.downloadSpeed = speed;
                                DownloadDynamicIsland.updateSpeed(speed);
                                lastBytesRead = bytesRead;
                            }
                        }
                    });

            OutputStream output = Files.newOutputStream(music.toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            writeTo(stream, output);
            output.close();
            MuoniumPlayerExtension.getInstance().musicInfo.downloadProgress = 1.0;
            MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
            DownloadDynamicIsland.finishDownload();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            MuoniumPlayerExtension.getInstance().musicInfo.downloading = false;
            DownloadDynamicIsland.cancelDownload();
            music.delete();
        }
    }

    @SneakyThrows
    static void writeTo(InputStream source, OutputStream destination) {
        byte[] buffer = new byte[1024];
        int length;
        while ((length = source.read(buffer)) != -1) {
            destination.write(buffer, 0, length);
        }
        destination.flush();
    }
}