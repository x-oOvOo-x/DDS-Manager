package ddsmanager.service;

import org.slf4j.Logger;

import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public final class ManagementTaskService implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Logger logger;

    public ManagementTaskService(Logger logger) {
        this.logger = logger;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), r -> {
            Thread thread = new Thread(r, "dds-manager-management"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public boolean submit(String name, Runnable task) { return submit(name, () -> true, task, () -> {}); }
    public boolean submit(String name, BooleanSupplier authorized, Runnable task, Runnable denied) {
        try {
            executor.execute(() -> {
                try { if (authorized.getAsBoolean()) task.run(); else denied.run(); }
                catch (RuntimeException e) { logger.error("Management task {} failed", name, e); }
            });
            return true;
        } catch (RejectedExecutionException e) { return false; }
    }

    public int queuedTasks() { return executor.getQueue().size(); }

    @Override public void close() {
        executor.shutdown();
        try { if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); executor.shutdownNow(); }
    }
}
