package ddsmanager.data;

import com.google.gson.GsonBuilder;
import ddsmanager.config.PluginConfig;
import ddsmanager.service.AccessScopes;
import ddsmanager.service.AccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

final class PlayerRepositoryTest {
    @TempDir Path dir;

    @Test void migratesLegacyUsernameProfileAndSurvivesRename() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create()); Files.createDirectories(dir.resolve("players")); UUID uuid = UUID.randomUUID();
        PlayerProfile legacy = new PlayerProfile(); legacy.schemaVersion = 1; legacy.username = "OldName"; legacy.uuid = uuid.toString(); legacy.accessScopes.add(AccessScopes.ALL);
        store.write(dir.resolve("players/oldname.json"), legacy);
        var repo = new PlayerRepository(dir, store, LoggerFactory.getLogger("test"));
        assertFalse(Files.exists(dir.resolve("players/oldname.json"))); assertTrue(Files.exists(dir.resolve("players/" + uuid + ".json")));
        assertEquals(uuid.toString(), repo.findByUsername("OldName", "global").orElseThrow().uuid);
        PlayerProfile renamed = repo.bindIdentity(uuid, "NewName", "global"); repo.flushDirty();
        assertTrue(renamed.accessScopes.contains(AccessScopes.ALL)); assertTrue(repo.findByUsername("OldName", "global").isEmpty());
        assertEquals(uuid.toString(), repo.findByUsername("NewName", "global").orElseThrow().uuid);
        var reloaded = new PlayerRepository(dir, store, LoggerFactory.getLogger("test"));
        assertEquals("NewName", reloaded.findByUuid(uuid, "global").orElseThrow().username);
        assertTrue(reloaded.findByUsername("NewName", "global").orElseThrow().accessScopes.contains(AccessScopes.ALL));
    }

    @Test void pendingNameDoesNotAuthorizeUntilUuidBinding() {
        var store = new AtomicJsonStore(new GsonBuilder().create()); var repo = new PlayerRepository(dir, store, LoggerFactory.getLogger("test"));
        PlayerProfile pending = repo.getOrCreateByUsername("FreshPlayer", "global"); pending.accessScopes.add(AccessScopes.ALL); assertTrue(repo.saveNow(pending));
        PluginConfig config = new PluginConfig(); config.features.whitelist = true; AccessService access = new AccessService(() -> config);
        assertFalse(access.evaluateStored(pending, "survival").allowed());
        UUID uuid = UUID.randomUUID(); PlayerProfile bound = repo.bindIdentity(uuid, "FreshPlayer", "global");
        assertEquals(uuid.toString(), bound.uuid); assertTrue(bound.accessScopes.contains(AccessScopes.ALL)); assertTrue(access.evaluateStored(bound, "survival").allowed());
        assertFalse(Files.exists(dir.resolve("pending/freshplayer.json"))); assertTrue(Files.exists(dir.resolve("players/" + uuid + ".json")));
    }

    @Test void purgeCompletelyDeletesUuidProfileAndArtifacts() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create()); var repo = new PlayerRepository(dir, store, LoggerFactory.getLogger("test")); UUID uuid = UUID.randomUUID();
        PlayerProfile profile = repo.bindIdentity(uuid, "EraseMe", "global"); profile.accessScopes.add(AccessScopes.ALL); assertTrue(repo.saveNow(profile));
        profile.channel = "local"; assertTrue(repo.saveNow(profile));
        Path file = dir.resolve("players/" + uuid + ".json"); assertTrue(Files.exists(file)); assertTrue(Files.exists(file.resolveSibling(file.getFileName() + ".bak")));
        Files.writeString(file.resolveSibling(file.getFileName() + ".corrupt"), "damaged");
        Files.writeString(file.resolveSibling(file.getFileName() + ".restore.tmp"), "temporary");
        assertTrue(repo.purgeByUsername("EraseMe"));
        assertFalse(Files.exists(file)); assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".bak"))); assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".corrupt"))); assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".restore.tmp")));
        assertTrue(repo.findByUuid(uuid, "global").isEmpty()); assertTrue(repo.findByUsername("EraseMe", "global").isEmpty()); assertFalse(repo.knownUsernames().contains("EraseMe"));
    }

    @Test void removingLastScopePurgesUuidAndPendingProfiles() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create()); var repo = new PlayerRepository(dir, store, LoggerFactory.getLogger("test"));
        UUID uuid = UUID.randomUUID(); PlayerProfile bound = repo.bindIdentity(uuid, "Bound", "global"); bound.accessScopes.add(AccessScopes.group("builders")); assertTrue(repo.saveNow(bound));
        PlayerProfile pending = repo.getOrCreateByUsername("Pending", "global"); pending.accessScopes.add("builders"); assertTrue(repo.saveNow(pending));
        assertEquals(2, repo.removeScopesFromAll(List.of(AccessScopes.group("builders"), "builders"), "global"));
        assertTrue(repo.findByUsername("Bound", "global").isEmpty()); assertTrue(repo.findByUsername("Pending", "global").isEmpty());
        assertFalse(Files.exists(dir.resolve("players/" + uuid + ".json"))); assertFalse(Files.exists(dir.resolve("pending/pending.json")));
    }

    @Test void groupCleanupVisitsEveryUuidEvenWhenNamesCollide() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create()); var repo = new PlayerRepository(dir, store, LoggerFactory.getLogger("test"));
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        PlayerProfile a = repo.bindIdentity(first, "ReusedName", "global"); a.accessScopes.add(AccessScopes.group("builders")); assertTrue(repo.saveNow(a));
        PlayerProfile b = repo.bindIdentity(second, "ReusedName", "global"); b.accessScopes.add(AccessScopes.group("builders")); assertTrue(repo.saveNow(b));

        assertEquals(2, repo.removeScopesFromAll(List.of(AccessScopes.group("builders")), "global"));
        assertTrue(repo.findByUuid(first, "global").isEmpty()); assertTrue(repo.findByUuid(second, "global").isEmpty());
        assertFalse(Files.exists(dir.resolve("players/" + first + ".json"))); assertFalse(Files.exists(dir.resolve("players/" + second + ".json")));
    }
    @Test void indexesAndRestoresBackupOnlyUuidAndPendingProfiles() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create()); UUID uuid = UUID.randomUUID();
        PlayerProfile bound = new PlayerProfile(); bound.uuid = uuid.toString(); bound.username = "BoundBackup"; bound.accessScopes.add(AccessScopes.ALL);
        PlayerProfile pending = new PlayerProfile(); pending.username = "PendingBackup"; pending.accessScopes.add(AccessScopes.group("builders"));
        Path boundPath = dir.resolve("players/" + uuid + ".json"), pendingPath = dir.resolve("pending/pendingbackup.json");
        store.write(boundPath.resolveSibling(boundPath.getFileName() + ".bak"), bound);
        store.write(pendingPath.resolveSibling(pendingPath.getFileName() + ".bak"), pending);
        var repo = new PlayerRepository(dir, store, LoggerFactory.getLogger("test"));
        assertTrue(repo.findByUuid(uuid, "global").orElseThrow().accessScopes.contains(AccessScopes.ALL));
        assertTrue(repo.findByUsername("PendingBackup", "global").orElseThrow().accessScopes.contains(AccessScopes.group("builders")));
        assertTrue(Files.exists(boundPath)); assertTrue(Files.exists(pendingPath));
    }

    @Test void legacyBackupCannotRestoreRevokedScopesAfterMigration() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create()); UUID uuid = UUID.randomUUID();
        PlayerProfile legacy = new PlayerProfile(); legacy.uuid = uuid.toString(); legacy.username = "Legacy"; legacy.accessScopes.add(AccessScopes.ALL);
        Path path = dir.resolve("players/legacy.json"); store.write(path, legacy); store.write(path, legacy);
        var repo = new PlayerRepository(dir, store, LoggerFactory.getLogger("test"));
        assertFalse(Files.exists(path)); assertFalse(Files.exists(path.resolveSibling("legacy.json.bak")));
        var profile = repo.findByUuid(uuid, "global").orElseThrow(); profile.accessScopes.clear(); assertTrue(repo.saveNow(profile));
        repo.rebuildIndex(); assertTrue(repo.findByUuid(uuid, "global").orElseThrow().accessScopes.isEmpty());
    }
    @Test void staleProfileCannotResurrectAfterPurgeOrOverwriteReload() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create()); var repo = new PlayerRepository(dir, store, LoggerFactory.getLogger("test"));
        UUID uuid = UUID.randomUUID(); PlayerProfile old = repo.bindIdentity(uuid, "Stale", "global"); old.accessScopes.add("all"); assertTrue(repo.saveNow(old));
        assertTrue(repo.purgeByUsername("Stale")); repo.markDirty(old); assertFalse(repo.saveNow(old)); repo.flushDirty();
        assertTrue(repo.findByUuid(uuid, "global").isEmpty()); assertFalse(Files.exists(dir.resolve("players/" + uuid + ".json")));
        PlayerProfile beforeReload = repo.bindIdentity(uuid, "Stale", "global"); assertTrue(repo.saveNow(beforeReload)); repo.rebuildIndex();
        beforeReload.accessScopes.add("all"); repo.markDirty(beforeReload); assertFalse(repo.saveNow(beforeReload));
        assertTrue(repo.findByUuid(uuid, "global").orElseThrow().accessScopes.isEmpty());
    }
    @Test void currentSchemaDoesNotRewriteAndOnlineProfileIsNotEvicted() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create()); UUID uuid = UUID.randomUUID();
        PlayerProfile profile = new PlayerProfile(); profile.username = "Current"; profile.uuid = uuid.toString();
        Path path = dir.resolve("players/" + uuid + ".json"); store.write(path, profile);
        var repo = new PlayerRepository(dir, store, LoggerFactory.getLogger("test")); repo.rebuildIndex();
        assertFalse(Files.exists(path.resolveSibling(path.getFileName() + ".bak")));
        PlayerProfile online = repo.findByUuid(uuid, "global").orElseThrow(); repo.evictOlderThanMinutes(-1, Set.of(uuid));
        assertSame(online, repo.findByUuid(uuid, "global").orElseThrow());
        Set<String> scopes = online.accessScopes; assertTrue(repo.saveNow(online)); assertSame(scopes, online.accessScopes);
    }
}
