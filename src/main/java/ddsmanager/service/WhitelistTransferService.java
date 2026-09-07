package ddsmanager.service;

import ddsmanager.data.AtomicJsonStore;
import ddsmanager.data.PlayerProfile;
import ddsmanager.data.PlayerRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class WhitelistTransferService {
    private static final String FILE_PATTERN = "[A-Za-z0-9_.-]{1,64}";
    private final Path dir;
    private final AtomicJsonStore store;
    private final PlayerRepository players;

    public WhitelistTransferService(Path dataDirectory, AtomicJsonStore store, PlayerRepository players) {
        dir = dataDirectory.resolve("transfer"); this.store = store; this.players = players;
    }

    public ExportResult exportSnapshot(String requestedName, String defaultChannel) throws IOException {
        Path path = resolve(requestedName); Snapshot snapshot = new Snapshot(); snapshot.schemaVersion = 2; snapshot.profiles = new ArrayList<>();
        for (PlayerProfile profile : players.allProfiles(defaultChannel)) {
            if (profile.accessScopes == null || profile.accessScopes.isEmpty()) continue;
            snapshot.profiles.add(new Grant(profile.uuid, profile.username, new TreeSet<>(profile.accessScopes)));
        }
        snapshot.profiles.sort(Comparator.comparing(Grant::username, String.CASE_INSENSITIVE_ORDER).thenComparing(Grant::uuid));
        store.write(path, snapshot); return new ExportResult(path.getFileName().toString(), snapshot.profiles.size());
    }

    public ImportResult importSnapshot(String requestedName, String defaultChannel) throws IOException {
        Path path = resolve(requestedName);
        Snapshot snapshot = store.read(path, Snapshot.class).orElseThrow(() -> new IOException("导入文件不存在或为空: " + path.getFileName()));
        List<Grant> grants = validate(snapshot); // Validate the whole document before changing any authorization.
        int changed = 0, added = 0, failed = 0;
        for (Grant grant : grants) {
            if (grant.scopes().isEmpty()) continue;
            PlayerProfile profile = grant.uuid().isEmpty() ? players.getOrCreateByUsername(grant.username(), defaultChannel)
                    : players.getOrCreateByUuid(UUID.fromString(grant.uuid()), grant.username(), defaultChannel);
            int before = profile.accessScopes.size(); profile.accessScopes.addAll(grant.scopes()); int delta = profile.accessScopes.size() - before;
            if (delta == 0) continue;
            if (players.saveNow(profile)) { changed++; added += delta; } else failed++;
        }
        return new ImportResult(path.getFileName().toString(), changed, added, 0, 0, failed);
    }

    private static List<Grant> validate(Snapshot snapshot) throws IOException {
        List<Grant> source;
        if (snapshot.schemaVersion == 1) {
            if (snapshot.players == null) throw new IOException("白名单快照缺少 players 字段");
            source = snapshot.players.entrySet().stream().map(e -> new Grant("", e.getKey(), e.getValue())).toList();
        } else if (snapshot.schemaVersion == 2) {
            if (snapshot.profiles == null) throw new IOException("白名单快照缺少 profiles 数组");
            source = snapshot.profiles;
        } else throw new IOException("不支持的白名单快照版本: " + snapshot.schemaVersion);
        List<Grant> result = new ArrayList<>(); Set<String> identities = new HashSet<>();
        for (Grant grant : source) {
            if (grant == null || !PlayerRepository.isValidUsername(grant.username())) throw new IOException("白名单快照包含无效玩家名");
            if (grant.uuid() == null) throw new IOException("白名单快照缺少 UUID，pending 请使用空字符串");
            String uuid = grant.uuid().trim();
            if (!uuid.isEmpty()) uuid = PlayerRepository.parseUuid(uuid).orElseThrow(() -> new IOException("无效 UUID: " + grant.uuid())).toString();
            String identity = uuid.isEmpty() ? "pending:" + grant.username().toLowerCase(Locale.ROOT) : uuid;
            if (!identities.add(identity)) throw new IOException("白名单快照包含重复身份: " + identity);
            if (grant.scopes() == null) throw new IOException("白名单快照缺少 scopes: " + grant.username());
            Set<String> scopes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (String scope : grant.scopes()) {
                String normalized = normalizeScope(scope);
                if (normalized == null) throw new IOException("无效授权: " + grant.username());
                scopes.add(normalized);
            }
            result.add(new Grant(uuid, grant.username(), scopes));
        }
        return result;
    }

    public List<String> files() {
        try {
            Files.createDirectories(dir);
            try (var stream = Files.list(dir)) {
                return stream.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                        .filter(n -> n.matches(FILE_PATTERN) && n.toLowerCase(Locale.ROOT).endsWith(".json"))
                        .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            }
        } catch (IOException ignored) { return List.of(); }
    }

    private Path resolve(String requestedName) throws IOException {
        String name = requestedName == null || requestedName.isBlank() ? "whitelist.json" : requestedName.trim();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) name += ".json";
        if (!name.matches(FILE_PATTERN)) throw new IOException("文件名只能包含字母、数字、_、-、.，长度 1-64");
        Files.createDirectories(dir); return dir.resolve(name);
    }

    private static String normalizeScope(String scope) {
        if (scope == null) return null; String value = scope.trim();
        if (value.isEmpty() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) return null;
        if (AccessScopes.isAll(value)) return AccessScopes.ALL;
        var server = AccessScopes.serverName(value); if (server.isPresent()) return AccessScopes.server(server.get());
        var group = AccessScopes.groupName(value); if (group.isPresent()) return AccessScopes.group(group.get());
        return value; // Preserve legacy bare scopes.
    }

    public record ExportResult(String file, int players) {}
    public record ImportResult(String file, int playersChanged, int scopesAdded, int invalidPlayers, int invalidScopes, int failedSaves) {}
    public record Grant(String uuid, String username, Set<String> scopes) {}
    public static final class Snapshot {
        public int schemaVersion = 1; // Missing version belongs to the legacy name-based format.
        public Map<String, Set<String>> players;
        public List<Grant> profiles;
    }
}
