package com.muoniumplayer.core.utils.other.multithreading;

import lombok.SneakyThrows;
import com.muoniumplayer.core.MuoniumPlayerEventHandler;
import com.muoniumplayer.core.utils.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * @author IzumiiKonata
 * @since 2024/12/28 17:57
 */
public class MultiThreadingUtil {

    public static final Logger LOGGER = new Logger("MultiThreadingUtil");

    // 原项目为 Java 21 虚拟线程（Executors.newVirtualThreadPerTaskExecutor）。
    // Java 8 等价：缓存线程池 + 守护线程（每任务一线程、无界、空闲回收），外部行为一致。
    private static final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    });

    @SneakyThrows
    public static CompletableFuture<Void> runAsync(Runnable runnable) {

        if (runnable == null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("Runnable is null"));
            return future;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        FutureTaskWrapper wrapper = new FutureTaskWrapper(runnable, future);

        executor.submit(wrapper);

        return future;
    }

    @SneakyThrows
    public static <T> T runOnMainThreadBlocking(Supplier<T> supplier) {
        return MuoniumPlayerEventHandler.addScheduledTask(supplier::get).get();
    }

    public static void runOnMainThread(Runnable runnable) {
        MuoniumPlayerEventHandler.addScheduledTask(runnable);
    }

    private static class FutureTaskWrapper implements Runnable {
        private final Runnable runnable;
        private final CompletableFuture<Void> future;

        public FutureTaskWrapper(Runnable runnable, CompletableFuture<Void> future) {
            this.runnable = runnable;
            this.future = future;
        }

        @Override
        public void run() {
            try {
                runnable.run();
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
    }
}
