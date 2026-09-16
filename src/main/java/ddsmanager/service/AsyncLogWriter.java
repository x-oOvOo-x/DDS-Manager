package ddsmanager.service;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

final class AsyncLogWriter implements AutoCloseable {
    private final Logger logger;
    private final String name;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong(), lastWarning = new AtomicLong();

    AsyncLogWriter(String threadName, int capacity, Logger logger) {
        this.name = threadName; this.logger = logger;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity), r -> {
            Thread thread = new Thread(r, threadName); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    void append(Path path, String line) {
        try {
            executor.execute(() -> {
                try {
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) { logger.error("Failed to append {}", path, e); }
            });
        } catch (RejectedExecutionException e) {
            long count = dropped.incrementAndGet(), now = System.currentTimeMillis(), previous = lastWarning.get();
            if (now - previous >= 60_000 && lastWarning.compareAndSet(previous, now))
                logger.warn("{} queue is full; {} entries have been dropped", name, count);
        }
    }

    int queued() { return executor.getQueue().size(); }
    long dropped() { return dropped.get(); }

    @Override public void close() {
        executor.shutdown();
        try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); executor.shutdownNow(); }
    }
}
