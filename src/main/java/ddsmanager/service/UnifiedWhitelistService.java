package ddsmanager.service;

import ddsmanager.data.AtomicJsonStore;
import ddsmanager.data.PlayerProfile;
import ddsmanager.data.PlayerRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class UnifiedWhitelistService {
    private final Path file;
    private final AtomicJsonStore store;
    private final PlayerRepository players;

    public UnifiedWhitelistService(Path dataDirectory, AtomicJsonStore store, PlayerRepository players) {
        this.file = dataDirectory.resolve("whitelist.json");
        this.store = store;
        this.players = players;
    }

    public synchronized SyncResult initializeOrSync(String defaultChannel) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        if (!Files.exists(file) && !Files.exists(backup)) {
            int playersWritten = writeCurrent(defaultChannel);
            return new SyncResult(true, 0, 0, playersWritten, 0);
        }
        return synchronize(defaultChannel);
    }

    public synchronized SyncResult synchronize(String defaultChannel) throws IOException {
        Document document = store.read(file, Document.class)
                .orElseThrow(() -> new IOException("统一白名单文件不存在: " + file.getFileName()));
        if (document.whitelist == null) throw new IOException("whitelist.json 缺少 whitelist 数组");

        TreeMap<String, String> desired = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String raw : document.whitelist) {
            String username = raw == null ? "" : raw.trim();
            if (!PlayerRepository.isValidUsername(username))
                throw new IOException("whitelist.json 包含无效 Java 玩家名: " + String.valueOf(raw));
            desired.putIfAbsent(username, username);
        }

        int added = 0, removed = 0, failed = 0;
        for (PlayerProfile profile : players.allProfiles(defaultChannel)) {
            if (!hasAll(profile) || desired.containsKey(profile.username)) continue;
            profile.accessScopes.removeIf(AccessScopes::isAll);
            try {
                if (players.saveOrPurge(profile)) removed++;
                else failed++;
            } catch (IOException e) {
                failed++;
            }
        }

        for (String username : desired.values()) {
            PlayerProfile profile = players.findByUsername(username, defaultChannel)
                    .orElseGet(() -> players.getOrCreateByUsername(username, defaultChannel));
            if (hasAll(profile)) continue;
            profile.accessScopes.add(AccessScopes.ALL);
            if (players.saveNow(profile)) added++;
            else failed++;
        }

        int written = failed == 0 ? writeCurrent(defaultChannel) : 0;
        return new SyncResult(false, added, removed, written, failed);
    }

    public synchronized int writeCurrent(String defaultChannel) throws IOException {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (PlayerProfile profile : players.allProfiles(defaultChannel))
            if (hasAll(profile) && PlayerRepository.isValidUsername(profile.username)) names.add(profile.username);
        Document document = new Document();
        document.whitelist = new ArrayList<>(names);
        store.write(file, document);
        return names.size();
    }

    public Path file() { return file; }

    private static boolean hasAll(PlayerProfile profile) {
        return profile.accessScopes != null && profile.accessScopes.stream().anyMatch(AccessScopes::isAll);
    }

    public record SyncResult(boolean created, int added, int removed, int playersWritten, int failed) {
        public String summary() {
            if (failed > 0) return "部分失败（新增 " + added + "，移除 " + removed + "，失败 " + failed + "）";
            if (created) return "已创建并写入 " + playersWritten + " 名玩家";
            return "已同步（新增 " + added + "，移除 " + removed + "，共 " + playersWritten + " 名）";
        }
    }

    public static final class Document {
        public List<String> whitelist;
    }
}
