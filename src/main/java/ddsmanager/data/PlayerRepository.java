package ddsmanager.data;

import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerRepository {
    private static final String USERNAME_PATTERN = "[A-Za-z0-9_]{1,16}";
    private final Path playersDir, pendingDir;
    private final AtomicJsonStore store;
    private final Logger logger;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> pendingCache = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameIndex = new ConcurrentHashMap<>();
    private final Set<UUID> knownUuids = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingNames = ConcurrentHashMap.newKeySet();
    private final Set<PlayerProfile> retired = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final Set<String> dirtyPending = ConcurrentHashMap.newKeySet();

    public PlayerRepository(Path dataDirectory, AtomicJsonStore store, Logger logger) {
        playersDir = dataDirectory.resolve("players"); pendingDir = dataDirectory.resolve("pending");
        this.store = store; this.logger = logger; rebuildIndex();
    }

    public synchronized void rebuildIndex() {
        flushDirty();
        if (!dirty.isEmpty() || !dirtyPending.isEmpty()) {
            logger.warn("Skipped player index rebuild because {} UUID and {} pending profiles are still unsaved", dirty.size(), dirtyPending.size());
            return;
        }
        cache.values().forEach(e -> retired.add(e.profile)); pendingCache.values().forEach(e -> retired.add(e.profile));
        cache.clear(); pendingCache.clear(); nameIndex.clear(); knownUuids.clear(); pendingNames.clear();
        try {
            Files.createDirectories(playersDir); Files.createDirectories(pendingDir);
            migrateLegacyUsernameFiles(); indexUuidProfiles(); indexPendingProfiles();
        } catch (IOException e) { logger.error("Unable to rebuild player index", e); }
    }

    public PlayerProfile loadForPlayer(Player player, String defaultChannel) { return bindIdentity(player.getUniqueId(), player.getUsername(), defaultChannel); }

    public synchronized PlayerProfile bindIdentity(UUID uuid, String username, String defaultChannel) {
        Objects.requireNonNull(uuid, "uuid"); String nameKey = key(username);
        PlayerProfile profile = loadByUuid(uuid, username, defaultChannel).orElseGet(() -> newProfile(username, defaultChannel));
        PlayerProfile pending = loadPending(nameKey, username, defaultChannel).orElse(null);
        if (pending != null) merge(profile, pending);
        String oldName = profile.username;
        profile.uuid = uuid.toString(); profile.username = username; profile.normalize(username, defaultChannel);
        if (isValidUsername(oldName) && !oldName.equalsIgnoreCase(username)) nameIndex.remove(key(oldName), uuid);
        cache.put(uuid, new CacheEntry(profile)); knownUuids.add(uuid); nameIndex.put(nameKey, uuid); dirty.add(uuid);
        if (pending != null && saveNow(profile)) deletePending(nameKey);
        return profile;
    }

    public Optional<PlayerProfile> findByUsername(String username, String defaultChannel) {
        String nameKey = key(username); UUID uuid = nameIndex.get(nameKey);
        return uuid != null ? loadByUuid(uuid, username, defaultChannel) : loadPending(nameKey, username, defaultChannel);
    }

    public Optional<PlayerProfile> findByUuid(UUID uuid, String defaultChannel) { return loadByUuid(uuid, "", defaultChannel); }

    public synchronized PlayerProfile getOrCreateByUsername(String username, String defaultChannel) {
        return findByUsername(username, defaultChannel).orElseGet(() -> {
            String nameKey = key(username); PlayerProfile profile = newProfile(username, defaultChannel);
            pendingCache.put(nameKey, new CacheEntry(profile)); pendingNames.add(nameKey); dirtyPending.add(nameKey); return profile;
        });
    }

    public synchronized PlayerProfile getOrCreateByUuid(UUID uuid, String username, String defaultChannel) {
        Objects.requireNonNull(uuid, "uuid"); key(username);
        return loadByUuid(uuid, username, defaultChannel).orElseGet(() -> {
            PlayerProfile profile = newProfile(username, defaultChannel); profile.uuid = uuid.toString(); profile.lastSeenEpochMs = 1;
            cache.put(uuid, new CacheEntry(profile)); knownUuids.add(uuid); dirty.add(uuid); indexName(uuid, profile); return profile;
        });
    }

    private void indexName(UUID uuid, PlayerProfile profile) {
        if (!isValidUsername(profile.username)) return;
        nameIndex.compute(key(profile.username), (name, current) -> {
            if (current == null || current.equals(uuid)) return uuid;
            CacheEntry other = cache.get(current);
            return other != null && profile.lastSeenEpochMs > other.profile.lastSeenEpochMs ? uuid : current;
        });
    }

    public Set<String> knownUsernames() {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        profiles("global").forEach(p -> { if (isValidUsername(p.username)) names.add(p.username); });
        return names;
    }

    public List<PlayerProfile> allProfiles(String defaultChannel) { return List.copyOf(profiles(defaultChannel)); }

    private List<PlayerProfile> profiles(String defaultChannel) {
        List<PlayerProfile> result = new ArrayList<>();
        for (UUID uuid : List.copyOf(knownUuids)) loadByUuid(uuid, "", defaultChannel).ifPresent(result::add);
        for (String nameKey : List.copyOf(pendingNames)) loadPending(nameKey, nameKey, defaultChannel).ifPresent(result::add);
        return result;
    }

    public synchronized boolean purgeByUsername(String username) throws IOException {
        String nameKey = key(username); UUID uuid = nameIndex.get(nameKey);
        Path pending = pendingPath(nameKey), legacy = playersDir.resolve(nameKey + ".json");
        boolean existed = uuid != null || pendingNames.contains(nameKey) || pendingCache.containsKey(nameKey)
                || existsDataFiles(pending) || existsDataFiles(legacy);
        if (uuid != null) deleteDataFiles(uuidPath(uuid));
        deleteDataFiles(pending); deleteDataFiles(legacy);
        if (!existed) return false;
        if (uuid != null) {
            retire(cache.remove(uuid)); knownUuids.remove(uuid); dirty.remove(uuid);
            nameIndex.entrySet().removeIf(e -> e.getValue().equals(uuid));
        }
        retire(pendingCache.remove(nameKey)); pendingNames.remove(nameKey); dirtyPending.remove(nameKey); nameIndex.remove(nameKey);
        return true;
    }

    public int removeScopeFromAll(String scope, String defaultChannel) { return removeScopesFromAll(List.of(scope), defaultChannel); }

    public synchronized int removeScopesFromAll(Collection<String> scopes, String defaultChannel) {
        int changed = 0;
        for (PlayerProfile profile : profiles(defaultChannel)) {
            if (profile.accessScopes == null) continue;
            boolean modified = profile.accessScopes.removeIf(v -> v != null && scopes.stream().anyMatch(s -> s != null && v.equalsIgnoreCase(s)));
            if (!modified) continue;
            try {
                boolean saved = profile.accessScopes.isEmpty() ? purgeProfile(profile) : saveNow(profile);
                if (saved) changed++;
            } catch (IOException e) { logger.error("Unable to remove scopes from {}", profile.username, e); }
        }
        return changed;
    }

    public synchronized boolean saveOrPurge(PlayerProfile profile) throws IOException {
        return profile.accessScopes == null || profile.accessScopes.isEmpty() ? purgeProfile(profile) : saveNow(profile);
    }

    private synchronized boolean purgeProfile(PlayerProfile profile) throws IOException {
        Optional<UUID> parsed = parseUuid(profile.uuid);
        if (parsed.isPresent()) {
            UUID uuid = parsed.get(); Path path = uuidPath(uuid); boolean existed = knownUuids.contains(uuid) || cache.containsKey(uuid) || existsDataFiles(path);
            deleteDataFiles(path); retire(cache.remove(uuid)); knownUuids.remove(uuid); dirty.remove(uuid);
            nameIndex.entrySet().removeIf(e -> e.getValue().equals(uuid));
            return existed;
        }
        String nameKey = key(profile.username); Path path = pendingPath(nameKey);
        boolean existed = pendingNames.contains(nameKey) || pendingCache.containsKey(nameKey) || existsDataFiles(path);
        deleteDataFiles(path); retire(pendingCache.remove(nameKey)); pendingNames.remove(nameKey); dirtyPending.remove(nameKey);
        return existed;
    }

    public synchronized void markDirty(PlayerProfile profile) {
        if (retired.contains(profile)) return;
        Optional<UUID> uuid = parseUuid(profile.uuid);
        if (uuid.isPresent()) {
            UUID id = uuid.get(); cache.put(id, new CacheEntry(profile)); knownUuids.add(id);
            indexName(id, profile); dirty.add(id);
        } else {
            String nameKey = key(profile.username); pendingCache.put(nameKey, new CacheEntry(profile)); pendingNames.add(nameKey); dirtyPending.add(nameKey);
        }
    }

    public synchronized boolean saveNow(PlayerProfile profile) {
        if (retired.contains(profile)) return false;
        Optional<UUID> parsed = parseUuid(profile.uuid);
        try {
            if (parsed.isPresent()) {
                UUID uuid = parsed.get(); profile.uuid = uuid.toString(); PlayerProfile snapshot = profile.snapshot(); snapshot.normalize(profile.username, "global");
                store.write(uuidPath(uuid), snapshot); dirty.remove(uuid); cache.put(uuid, new CacheEntry(profile)); knownUuids.add(uuid);
                if (isValidUsername(profile.username)) {
                    String nameKey = key(profile.username);
                    nameIndex.entrySet().removeIf(e -> e.getValue().equals(uuid) && !e.getKey().equals(nameKey)); indexName(uuid, profile);
                }
            } else {
                String nameKey = key(profile.username);
                if (profile.accessScopes == null || profile.accessScopes.isEmpty()) { deletePending(nameKey); return true; }
                profile.uuid = ""; PlayerProfile snapshot = profile.snapshot(); snapshot.normalize(profile.username, "global"); store.write(pendingPath(nameKey), snapshot);
                dirtyPending.remove(nameKey); pendingCache.put(nameKey, new CacheEntry(profile)); pendingNames.add(nameKey);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            markDirty(profile); logger.error("Unable to save player profile {}, queued for retry", profile.username, e); return false;
        }
    }

    public synchronized void flushDirty() {
        for (UUID uuid : List.copyOf(dirty)) { CacheEntry e = cache.get(uuid); if (e != null) saveNow(e.profile); else dirty.remove(uuid); }
        for (String nameKey : List.copyOf(dirtyPending)) { CacheEntry e = pendingCache.get(nameKey); if (e != null) saveNow(e.profile); else dirtyPending.remove(nameKey); }
    }

    public void evictOlderThanMinutes(int minutes) { evictOlderThanMinutes(minutes, Set.of()); }
    public synchronized void evictOlderThanMinutes(int minutes, Set<UUID> online) {
        long cutoff = System.currentTimeMillis() - minutes * 60_000L;
        cache.entrySet().removeIf(e -> {
            if (online.contains(e.getKey()) || dirty.contains(e.getKey()) || e.getValue().lastAccess >= cutoff) return false;
            retire(e.getValue()); return true;
        });
        pendingCache.entrySet().removeIf(e -> {
            if (dirtyPending.contains(e.getKey()) || e.getValue().lastAccess >= cutoff) return false;
            retire(e.getValue()); return true;
        });
    }
    private void retire(CacheEntry entry) { if (entry != null) retired.add(entry.profile); }

    public static boolean isValidUsername(String username) { return username != null && username.matches(USERNAME_PATTERN); }

    public static Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try { UUID uuid = UUID.fromString(value.trim()); return uuid.toString().equalsIgnoreCase(value.trim()) ? Optional.of(uuid) : Optional.empty(); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    private synchronized Optional<PlayerProfile> loadByUuid(UUID uuid, String fallbackUsername, String defaultChannel) {
        CacheEntry cached = cache.get(uuid);
        if (cached != null) { cached.lastAccess = System.currentTimeMillis(); return Optional.of(cached.profile); }
        Path path = uuidPath(uuid); if (!Files.exists(path) && !Files.exists(sibling(path, ".bak"))) return Optional.empty();
        try {
            Optional<PlayerProfile> loaded = store.read(path, PlayerProfile.class);
            loaded.ifPresent(profile -> {
                profile.uuid = uuid.toString(); profile.normalize(fallbackUsername, defaultChannel); cache.put(uuid, new CacheEntry(profile)); knownUuids.add(uuid);
                indexName(uuid, profile);
            });
            return loaded;
        } catch (IOException | RuntimeException e) { logger.error("Unable to load UUID player profile {}", path, e); return Optional.empty(); }
    }

    private synchronized Optional<PlayerProfile> loadPending(String nameKey, String fallbackUsername, String defaultChannel) {
        CacheEntry cached = pendingCache.get(nameKey);
        if (cached != null) { cached.lastAccess = System.currentTimeMillis(); return Optional.of(cached.profile); }
        Path path = pendingPath(nameKey); if (!Files.exists(path) && !Files.exists(sibling(path, ".bak"))) return Optional.empty();
        try {
            Optional<PlayerProfile> loaded = store.read(path, PlayerProfile.class);
            loaded.ifPresent(profile -> { profile.uuid = ""; profile.normalize(fallbackUsername, defaultChannel); pendingCache.put(nameKey, new CacheEntry(profile)); pendingNames.add(nameKey); });
            return loaded;
        } catch (IOException | RuntimeException e) { logger.error("Unable to load pending player profile {}", path, e); return Optional.empty(); }
    }

    private void migrateLegacyUsernameFiles() throws IOException {
        try (var stream = Files.list(playersDir)) {
            for (Path path : stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")).toList()) {
                String filename = path.getFileName().toString(), stem = filename.substring(0, filename.length() - 5);
                if (parseUuid(stem).isPresent()) continue;
                if (!isValidUsername(stem)) { logger.warn("Ignoring invalid legacy player data filename {}", path); continue; }
                try {
                    PlayerProfile legacy = store.read(path, PlayerProfile.class).orElse(null); if (legacy == null) continue;
                    legacy.normalize(stem, "global"); if (!isValidUsername(legacy.username)) legacy.username = stem;
                    Optional<UUID> uuid = parseUuid(legacy.uuid); if (uuid.isPresent()) migrateToUuid(path, uuid.get(), legacy); else migrateToPending(path, legacy);
                } catch (IOException | RuntimeException e) { logger.warn("Unable to migrate legacy player file {}", path, e); }
            }
        }
    }

    private void migrateToUuid(Path legacyPath, UUID uuid, PlayerProfile legacy) throws IOException {
        Path target = uuidPath(uuid); PlayerProfile merged = legacy;
        if (Files.exists(target) || Files.exists(sibling(target, ".bak"))) {
            PlayerProfile existing = store.read(target, PlayerProfile.class).orElse(null);
            if (existing != null) { existing.uuid = uuid.toString(); existing.normalize("", "global"); merged = merge(existing, legacy); }
        }
        merged.uuid = uuid.toString(); merged.normalize(legacy.username, "global"); store.write(target, merged); deleteDataFiles(legacyPath);
        logger.info("Migrated player {} to UUID profile {}", merged.username, uuid);
    }

    private void migrateToPending(Path legacyPath, PlayerProfile legacy) throws IOException {
        String nameKey = key(legacy.username); Path target = pendingPath(nameKey); PlayerProfile merged = legacy;
        if (Files.exists(target) || Files.exists(sibling(target, ".bak"))) {
            PlayerProfile existing = store.read(target, PlayerProfile.class).orElse(null);
            if (existing != null) { existing.uuid = ""; existing.normalize(legacy.username, "global"); merged = merge(existing, legacy); }
        }
        merged.uuid = ""; merged.normalize(legacy.username, "global"); store.write(target, merged); deleteDataFiles(legacyPath);
        logger.info("Moved unresolved legacy player {} to pending UUID binding", merged.username);
    }

    private void indexUuidProfiles() throws IOException {
        Map<String, Long> seen = new HashMap<>();
        for (Path path : profilePaths(playersDir)) {
            String filename = path.getFileName().toString(), stem = filename.substring(0, filename.length() - 5); Optional<UUID> parsed = parseUuid(stem);
            if (parsed.isEmpty()) continue; UUID uuid = parsed.get();
            try {
                PlayerProfile profile = store.read(path, PlayerProfile.class).orElse(null); if (profile == null) continue;
                boolean rewrite = profile.schemaVersion != PlayerProfile.SCHEMA_VERSION || !uuid.toString().equalsIgnoreCase(profile.uuid);
                profile.uuid = uuid.toString(); profile.normalize("", "global"); knownUuids.add(uuid); cache.put(uuid, new CacheEntry(profile));
                if (isValidUsername(profile.username)) {
                    String nameKey = key(profile.username);
                    if (profile.lastSeenEpochMs >= seen.getOrDefault(nameKey, Long.MIN_VALUE)) { nameIndex.put(nameKey, uuid); seen.put(nameKey, profile.lastSeenEpochMs); }
                }
                if (rewrite) store.write(path, profile);
            } catch (IOException | RuntimeException e) { logger.warn("Unable to index UUID player file {}", path, e); }
        }
    }

    private void indexPendingProfiles() throws IOException {
        for (Path path : profilePaths(pendingDir)) {
            String filename = path.getFileName().toString(), nameKey = filename.substring(0, filename.length() - 5).toLowerCase(Locale.ROOT);
            if (!isValidUsername(nameKey)) { logger.warn("Ignoring invalid pending player data filename {}", path); continue; }
            try {
                PlayerProfile pending = store.read(path, PlayerProfile.class).orElse(null); if (pending == null) continue;
                boolean rewrite = pending.schemaVersion != PlayerProfile.SCHEMA_VERSION || (pending.uuid == null || !pending.uuid.isBlank()); pending.uuid = ""; pending.normalize(nameKey, "global");
                UUID known = nameIndex.get(nameKey);
                if (known != null) {
                    PlayerProfile profile = loadByUuid(known, pending.username, "global").orElse(null);
                    if (profile != null) { merge(profile, pending); if (saveNow(profile)) deletePending(nameKey); continue; }
                }
                pendingCache.put(nameKey, new CacheEntry(pending)); pendingNames.add(nameKey); if (rewrite) store.write(path, pending);
            } catch (IOException | RuntimeException e) { logger.warn("Unable to index pending player file {}", path, e); }
        }
    }

    private void deletePending(String nameKey) {
        retire(pendingCache.remove(nameKey)); pendingNames.remove(nameKey); dirtyPending.remove(nameKey);
        try { deleteDataFiles(pendingPath(nameKey)); } catch (IOException e) { logger.warn("Unable to delete pending profile {}", nameKey, e); }
    }

    private static PlayerProfile merge(PlayerProfile base, PlayerProfile extra) {
        if (extra.accessScopes != null) base.accessScopes.addAll(extra.accessScopes);
        if (extra.lastSeenEpochMs > base.lastSeenEpochMs) {
            base.lastSeenEpochMs = extra.lastSeenEpochMs;
            if (extra.lastServer != null && !extra.lastServer.isBlank()) base.lastServer = extra.lastServer;
            if (extra.channel != null && !extra.channel.isBlank()) base.channel = extra.channel;
            if (isValidUsername(extra.username)) base.username = extra.username;
        } else if (!isValidUsername(base.username) && isValidUsername(extra.username)) base.username = extra.username;
        return base;
    }

    private PlayerProfile newProfile(String username, String defaultChannel) {
        PlayerProfile profile = new PlayerProfile(); profile.username = username; profile.channel = defaultChannel; profile.normalize(username, defaultChannel); return profile;
    }

    private static List<Path> profilePaths(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile).map(path -> {
                String name = path.getFileName().toString();
                return name.endsWith(".json.bak") ? path.resolveSibling(name.substring(0, name.length() - 4)) : path;
            }).filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")).distinct().toList();
        }
    }

    private static boolean existsDataFiles(Path path) {
        return Files.exists(path) || Files.exists(sibling(path, ".bak")) || Files.exists(sibling(path, ".tmp"))
                || Files.exists(sibling(path, ".restore.tmp")) || Files.exists(sibling(path, ".corrupt"));
    }
    private static boolean deleteDataFiles(Path path) throws IOException {
        boolean deleted = Files.deleteIfExists(sibling(path, ".bak"));
        deleted |= Files.deleteIfExists(sibling(path, ".tmp"));
        deleted |= Files.deleteIfExists(sibling(path, ".restore.tmp"));
        deleted |= Files.deleteIfExists(sibling(path, ".corrupt"));
        deleted |= Files.deleteIfExists(path);
        return deleted;
    }
    private static Path sibling(Path path, String suffix) { return path.resolveSibling(path.getFileName() + suffix); }
    private Path uuidPath(UUID uuid) { return playersDir.resolve(uuid + ".json"); }
    private Path pendingPath(String nameKey) { return pendingDir.resolve(nameKey + ".json"); }

    private static String key(String username) {
        String value = username == null ? "" : username.trim();
        if (!isValidUsername(value)) throw new IllegalArgumentException("Invalid Java username: " + username);
        return value.toLowerCase(Locale.ROOT);
    }

    private static final class CacheEntry {
        private final PlayerProfile profile; private volatile long lastAccess = System.currentTimeMillis();
        private CacheEntry(PlayerProfile profile) { this.profile = profile; }
    }
}
