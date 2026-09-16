package ddsmanager.service;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.*;

public final class ChatLogService implements AutoCloseable {
    private final Path logDir;
    private final AsyncLogWriter writer;

    public ChatLogService(Path dataDirectory, Logger logger) {
        logDir = dataDirectory.resolve("logs");
        writer = new AsyncLogWriter("dds-manager-chat-log", 4096, logger);
    }

    public void append(String server, String username, String channel, String message) {
        OffsetDateTime now = OffsetDateTime.now();
        String safe = clean(message);
        String line = "[%s] [%s] [%s] <%s> %s%n".formatted(now, clean(server), clean(channel), clean(username), safe);
        writer.append(logDir.resolve("chat-" + now.toLocalDate() + ".log"), line);
    }

    public int queuedEntries() { return writer.queued(); }
    public long droppedEntries() { return writer.dropped(); }
    @Override public void close() { writer.close(); }
    private static String clean(String value) { return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' '); }
}
