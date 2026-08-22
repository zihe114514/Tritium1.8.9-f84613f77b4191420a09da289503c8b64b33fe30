package com.muoniumplayer.core.ncm.music;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg 可执行文件的定位与校验。
 *
 * <p>动态封面(网易云返回的是 MP4)需要一个外部解码器,项目里不打包二进制,所以按固定顺序找一个能用的
 * ffmpeg:系统属性 / 环境变量 → 游戏目录下的 {@code config/muonium/bin} → PATH → 各平台常见安装位置
 * (含 winget 的 Links 目录:游戏进程启动时 PATH 已经固定,用户在游戏运行期间安装的 ffmpeg 不会出现在
 * 继承来的 PATH 里)。</p>
 *
 * <p>找不到不是错误:动态封面纯属装饰,没有它就一直用静态封面。因此结果会缓存,并且"没找到"只缓存 5
 * 分钟——用户可能在游戏运行期间才装上 ffmpeg。校验用 {@code ffmpeg -version},带超时,避免某个同名程序
 * 卡住调用线程。</p>
 */
final class FfmpegSupport {

    private static final long MISSING_RECHECK_MILLIS = 5L * 60L * 1_000L;
    private static final long VERSION_TIMEOUT_MILLIS = 4_000L;

    private static final Object LOCK = new Object();
    private static String executable;
    private static long nextProbeAtMillis;
    private static boolean warned;

    private FfmpegSupport() {
    }

    /** 可用的 ffmpeg 命令,不可用时返回 {@code null}。 */
    static String executable() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (executable != null) return executable;
            if (now < nextProbeAtMillis) return null;
            nextProbeAtMillis = now + MISSING_RECHECK_MILLIS;
            for (String candidate : candidates()) {
                if (verify(candidate)) {
                    executable = candidate;
                    System.out.println("[Music/Cover] Using ffmpeg at " + candidate + " for animated covers");
                    return executable;
                }
            }
            return null;
        }
    }

    static boolean isAvailable() {
        return executable() != null;
    }

    /** 只在整个会话里提示一次,免得每首歌都刷一行。 */
    static void warnMissingOnce() {
        synchronized (LOCK) {
            if (warned) return;
            warned = true;
        }
        System.out.println("[Music/Cover] Animated cover skipped: ffmpeg not found. Install ffmpeg and put it on PATH,"
                + " or drop the binary into config/muonium/bin, or set -Dmuonium.ffmpeg=<path>.");
    }

    private static List<String> candidates() {
        List<String> candidates = new ArrayList<String>();
        addCandidate(candidates, System.getProperty("muonium.ffmpeg"));
        addCandidate(candidates, System.getenv("MUONIUM_FFMPEG"));
        addCandidate(candidates, System.getenv("FFMPEG_PATH"));

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String binaryName = windows ? "ffmpeg.exe" : "ffmpeg";
        // 游戏目录内的私有位置:用户不想改系统 PATH 时把二进制丢进来就能用。
        addCandidate(candidates, new File(new File("config", "muonium"), "bin/" + binaryName).getPath());
        addCandidate(candidates, "ffmpeg");

        if (windows) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.trim().isEmpty()) {
                addCandidate(candidates, localAppData + "\\Microsoft\\WinGet\\Links\\ffmpeg.exe");
            }
            if (localAppData != null && !localAppData.trim().isEmpty()) {
                addCandidate(candidates, localAppData + "\\Programs\\ffmpeg\\bin\\ffmpeg.exe");
            }
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles != null && !programFiles.trim().isEmpty()) {
                addCandidate(candidates, programFiles + "\\ffmpeg\\bin\\ffmpeg.exe");
            }
            addCandidate(candidates, "C:\\ffmpeg\\bin\\ffmpeg.exe");
        } else {
            addCandidate(candidates, "/usr/bin/ffmpeg");
            addCandidate(candidates, "/usr/local/bin/ffmpeg");
            addCandidate(candidates, "/opt/homebrew/bin/ffmpeg");
        }
        return candidates;
    }

    private static void addCandidate(List<String> candidates, String candidate) {
        if (candidate == null) return;
        String trimmed = candidate.trim();
        if (trimmed.isEmpty() || candidates.contains(trimmed)) return;
        candidates.add(trimmed);
    }

    /**
     * {@code ffmpeg -version} 必须在超时内正常退出。带路径分隔符的候选先检查文件是否存在,避免为一堆
     * 不存在的路径反复付出进程创建的代价。
     */
    private static boolean verify(String candidate) {
        if (candidate.indexOf('/') >= 0 || candidate.indexOf('\\') >= 0) {
            File file = new File(candidate);
            if (!file.isFile()) return false;
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(candidate, "-hide_banner", "-version");
            builder.redirectErrorStream(true);
            process = builder.start();
            drainQuietly(process.getInputStream());
            if (!process.waitFor(VERSION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (process != null) {
                process.destroy();
                try {
                    if (!process.waitFor(200L, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }
    }

    /** 必须把输出读干:管道缓冲区填满时子进程会阻塞在写上,永远等不到退出。 */
    private static void drainQuietly(InputStream stream) {
        if (stream == null) return;
        try {
            byte[] buffer = new byte[4096];
            while (stream.read(buffer) >= 0) {
                // 只是丢弃,版本号本身用不上。
            }
        } catch (Throwable ignored) {
        }
    }
}
