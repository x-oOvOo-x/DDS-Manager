package ddsmanager.data;

import com.google.gson.Gson;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;

public final class AtomicJsonStore {
    private final Gson gson;
    private final Logger logger;

    public AtomicJsonStore(Gson gson) { this(gson, null); }
    public AtomicJsonStore(Gson gson, Logger logger) { this.gson = gson; this.logger = logger; }

    public synchronized <T> Optional<T> read(Path path, Class<T> type) throws IOException {
        Path backup = sibling(path, ".bak");
        if (!Files.exists(path)) {
            if (!Files.exists(backup)) return Optional.empty();
            T value = readRequired(backup, type); restore(path, backup, false); return Optional.of(value);
        }
        try { return Optional.of(readRequired(path, type)); }
        catch (IOException primary) {
            if (!Files.exists(backup)) throw primary;
            T value;
            try { value = readRequired(backup, type); }
            catch (IOException secondary) { primary.addSuppressed(secondary); throw primary; }
            restore(path, backup, true); return Optional.of(value);
        }
    }

    public synchronized void write(Path path, Object value) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = sibling(path, ".tmp"), backup = sibling(path, ".bak");
        try (var writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            gson.toJson(value, writer);
        }
        if (Files.exists(path)) Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        move(tmp, path);
    }

    private <T> T readRequired(Path path, Class<T> type) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            T value = gson.fromJson(reader, type);
            if (value == null) throw new IOException("JSON document is null: " + path.getFileName());
            return value;
        } catch (RuntimeException e) { throw new IOException("Invalid JSON: " + path.getFileName(), e); }
    }

    private void restore(Path path, Path backup, boolean quarantine) throws IOException {
        Path tmp = sibling(path, ".restore.tmp"), corrupt = sibling(path, ".corrupt");
        Files.copy(backup, tmp, StandardCopyOption.REPLACE_EXISTING);
        if (quarantine && Files.exists(path)) Files.move(path, corrupt, StandardCopyOption.REPLACE_EXISTING);
        move(tmp, path);
        if (logger != null) logger.warn("Recovered {} from backup{}", path,
                quarantine ? "; damaged file kept as " + corrupt.getFileName() : "");
    }

    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static Path sibling(Path path, String suffix) { return path.resolveSibling(path.getFileName() + suffix); }
}
