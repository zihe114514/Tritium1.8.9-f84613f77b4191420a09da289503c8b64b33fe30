package com.muoniumplayer.core;

import com.google.common.collect.Queues;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import lombok.Getter;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.Logger;
import today.opai.api.events.EventRender2D;
import today.opai.api.interfaces.EventHandler;
import com.muoniumplayer.core.rendering.Framebuffer;
import com.muoniumplayer.core.rendering.DownloadDynamicIsland;
import com.muoniumplayer.core.rendering.animation.Interpolations;
import com.muoniumplayer.core.utils.logging.LogManager;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

/**
 * @author IzumiiKonata
 * Date: 2026/4/1 08:26
 */
public class MuoniumPlayerEventHandler implements EventHandler {

    @Getter
    private static final MuoniumPlayerEventHandler instance = new MuoniumPlayerEventHandler();

    @Getter
    private static final Logger logger = LogManager.getLogger("MuoniumPlayerEventHandler");

    private final Queue<FutureTask<?>> scheduledTasks = Queues.newArrayDeque();

    @Override
    public void onLoop() {
        synchronized (this.scheduledTasks) {
            while (!this.scheduledTasks.isEmpty()) {
                runTask((FutureTask<?>) this.scheduledTasks.poll(), logger);
            }
        }
    }

    private <V> V runTask(FutureTask<V> task, Logger logger) {
        try {
            task.run();
            return task.get();
        } catch (ExecutionException executionexception) {
            logger.fatal("Error executing task", executionexception);

            if (executionexception.getCause() instanceof OutOfMemoryError) {
                throw (OutOfMemoryError) executionexception.getCause();
            }
        } catch (InterruptedException interruptedexception) {
            logger.fatal("Error executing task", interruptedexception);
        }

        return null;
    }

    public static <V> ListenableFuture<V> addScheduledTask(Callable<V> callableToSchedule) {
        Validate.notNull(callableToSchedule);

        if (!MuoniumPlayerExtension.isCallingFromMainThread()) {
            ListenableFutureTask<V> listenablefuturetask = ListenableFutureTask.create(callableToSchedule);

            synchronized (instance.scheduledTasks) {
                instance.scheduledTasks.add(listenablefuturetask);
                return listenablefuturetask;
            }
        } else {
            try {
                return Futures.immediateFuture(callableToSchedule.call());
            } catch (Exception exception) {
                return Futures.immediateFailedFuture(exception);
            }
        }
    }

    public static ListenableFuture<Object> addScheduledTask(Runnable runnableToSchedule) {
        Validate.notNull(runnableToSchedule);
        return instance.addScheduledTask(Executors.callable(runnableToSchedule));
    }

    @Override
    public void onRender2D(EventRender2D event) {
        Framebuffer.updateMcFramebuffer();
        Interpolations.calcFrameDelta();

        DownloadDynamicIsland.render();
    }
}
