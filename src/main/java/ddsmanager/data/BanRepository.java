package ddsmanager.data;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class BanRepository {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_REASON_CODEPOINTS = 256;
    private static final int MAX_ACTOR_CODEPOINTS = 128;

    private final Path file;
    private final AtomicJsonStore store;
    private final Logger logger;
    private volatile Map<UUID, BanEntry> bans = Map.of();
    private volatile boolean healthy = true;

    public BanRepository(Path dataDirectory, AtomicJsonStore store, Logger logger) {
        this.file = dataDirectory.resolve("bans.json");
        this.store = store;
        this.logger = logger;
        reload();
    }

    public synchronized boolean reload() {
        try {
            BanFile document = store.read(file, BanFile.class).orElseGet(BanRepository::emptyFile);
            bans = Map.copyOf(validate(document));
            healthy = true;
            return true;
        } catch (IOException | RuntimeException e) {
            healthy = false;
            logger.error("Unable to load UUID ban list {}; new logins will be denied until it is repaired", file, e);
            return false;
        }
    }

    public boolean healthy() { return healthy; }
    public Path file() { return file; }
    public int size() { return bans.size(); }

    public Optional<BanEntry> find(UUID uuid) {
        BanEntry entry = bans.get(uuid);
        return entry == null ? Optional.empty() : Optional.of(entry.snapshot());
    }

    public List<BanEntry> findByLastKnownName(String username) {
        if (!PlayerRepository.isValidUsername(username)) return List.of();
        return bans.values().stream()
                .filter(entry -> entry.lastKnownName.equalsIgnoreCase(username))
                .map(BanEntry::snapshot)
                .sorted(Comparator.comparing(entry -> entry.uuid))
                .toList();
    }

    public List<BanEntry> all() {
        return bans.values().stream().map(BanEntry::snapshot)
                .sorted(Comparator.comparing((BanEntry entry) -> entry.lastKnownName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(entry -> entry.uuid))
                .toList();
    }

    public synchronized Optional<BanEntry> add(UUID uuid, String lastKnownName, String reason, String createdBy) throws IOException {
        ensureHealthy();
        if (bans.containsKey(uuid)) return Optional.empty();

        BanEntry entry = new BanEntry();
        entry.uuid = uuid.toString();
        entry.lastKnownName = normalizeName(lastKnownName);
        entry.reason = normalizeReason(reason);
        entry.createdBy = normalizeActor(createdBy);
        entry.createdAtEpochMs = System.currentTimeMillis();

        Map<UUID, BanEntry> next = mutableCopy();
        next.put(uuid, entry);
        persist(next);
        bans = Map.copyOf(next);
        return Optional.of(entry.snapshot());
    }

    public synchronized Optional<BanEntry> remove(UUID uuid) throws IOException {
        ensureHealthy();
        BanEntry existing = bans.get(uuid);
        if (existing == null) return Optional.empty();

        Map<UUID, BanEntry> next = mutableCopy();
        next.remove(uuid);
        persist(next);
        bans = Map.copyOf(next);
        return Optional.of(existing.snapshot());
    }

    public static String normalizeReason(String value) {
        String normalized = cleanText(value);
        if (normalized.isBlank()) normalized = "未提供原因";
        if (normalized.codePointCount(0, normalized.length()) > MAX_REASON_CODEPOINTS)
            throw new IllegalArgumentException("封禁原因最多 " + MAX_REASON_CODEPOINTS + " 个字符");
        return normalized;
    }

    private void ensureHealthy() {
        if (!healthy) throw new IllegalStateException("UUID ban list is unavailable; repair bans.json and reload first");
    }

    private Map<UUID, BanEntry> mutableCopy() {
        Map<UUID, BanEntry> result = new LinkedHashMap<>();
        bans.forEach((uuid, entry) -> result.put(uuid, entry.snapshot()));
        return result;
    }

    private void persist(Map<UUID, BanEntry> values) throws IOException {
        BanFile document = emptyFile();
        document.bans = values.values().stream().map(BanEntry::snapshot)
                .sorted(Comparator.comparing(entry -> entry.uuid)).toList();
        store.write(file, document);
    }

    private static BanFile emptyFile() {
        BanFile document = new BanFile();
        document.schemaVersion = SCHEMA_VERSION;
        document.bans = List.of();
        return document;
    }

    private static Map<UUID, BanEntry> validate(BanFile document) {
        if (document.schemaVersion == null || document.schemaVersion != SCHEMA_VERSION)
            throw new IllegalArgumentException("Missing or unsupported bans.json schemaVersion " + document.schemaVersion);
        if (document.bans == null)
            throw new IllegalArgumentException("Missing or null bans.json bans array");

        Map<UUID, BanEntry> result = new LinkedHashMap<>();
        List<BanEntry> entries = document.bans;
        for (BanEntry raw : entries) {
            if (raw == null) throw new IllegalArgumentException("bans.json contains a null entry");
            UUID uuid = parseUuid(raw.uuid);
            if (result.containsKey(uuid)) throw new IllegalArgumentException("Duplicate banned UUID " + uuid);

            BanEntry entry = raw.snapshot();
            entry.uuid = uuid.toString();
            entry.lastKnownName = normalizeName(entry.lastKnownName);
            entry.reason = normalizeReason(entry.reason);
            entry.createdBy = normalizeActor(entry.createdBy);
            if (entry.createdAtEpochMs < 0) throw new IllegalArgumentException("Negative ban timestamp for " + uuid);
            result.put(uuid, entry);
        }
        return result;
    }

    private static UUID parseUuid(String value) {
        return PlayerRepository.parseUuid(value)
                .orElseThrow(() -> new IllegalArgumentException("Invalid banned UUID: " + value));
    }

    private static String normalizeName(String value) {
        String name = value == null ? "" : value.trim();
        if (!name.isEmpty() && !PlayerRepository.isValidUsername(name))
            throw new IllegalArgumentException("Invalid Java username in ban entry: " + name);
        return name;
    }

    private static String normalizeActor(String value) {
        String actor = cleanText(value);
        if (actor.isBlank()) actor = "UNKNOWN";
        if (actor.codePointCount(0, actor.length()) > MAX_ACTOR_CODEPOINTS)
            throw new IllegalArgumentException("Ban actor is too long");
        return actor;
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        boolean previousSpace = false;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            i += Character.charCount(cp);
            boolean space = Character.isWhitespace(cp) || Character.isISOControl(cp);
            if (space) {
                if (!previousSpace && !out.isEmpty()) out.append(' ');
                previousSpace = true;
            } else {
                out.appendCodePoint(cp);
                previousSpace = false;
            }
        }
        return out.toString().trim();
    }

    private static final class BanFile {
        // Leave fields unset when Gson reads JSON: missing fields must not look like valid defaults.
        Integer schemaVersion;
        List<BanEntry> bans;
    }
}
