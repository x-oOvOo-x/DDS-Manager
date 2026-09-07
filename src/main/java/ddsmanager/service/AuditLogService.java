package ddsmanager.service;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.OffsetDateTime;

public final class AuditLogService implements AutoCloseable {
    private final Path logDir;
    private final AsyncLogWriter writer;

    public AuditLogService(Path dataDirectory, Logger logger) {
        logDir = dataDirectory.resolve("logs");
        writer = new AsyncLogWriter("dds-manager-audit-log", 2048, logger);
    }

    public void record(CommandSource source, String action, String detail) {
        record(source instanceof Player p ? p.getUsername() + "/" + p.getUniqueId() : "CONSOLE", action, detail);
    }

    public void recordSystem(String action, String detail) { record("SYSTEM", action, detail); }

    private void record(String actor, String action, String detail) {
        OffsetDateTime now = OffsetDateTime.now();
        String line = "%s\t%s\t%s\t%s%n".formatted(now, clean(actor), clean(action), clean(detail));
        writer.append(logDir.resolve("audit-" + now.toLocalDate() + ".log"), line);
    }

    public int queuedEntries() { return writer.queued(); }
    public long droppedEntries() { return writer.dropped(); }
    @Override public void close() { writer.close(); }
    private static String clean(String value) { return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' '); }
}
