package ddsmanager.service;

import com.google.gson.GsonBuilder;
import ddsmanager.data.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class WhitelistTransferServiceTest {
    @TempDir Path dir;
    private final AtomicJsonStore store = new AtomicJsonStore(new GsonBuilder().create());
    private PlayerRepository repository(Path path) { return new PlayerRepository(path, store, LoggerFactory.getLogger("test")); }

    @Test void snapshotRoundTripPreservesCollidingUuidsAndPending() throws Exception {
        PlayerRepository source = repository(dir); UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        PlayerProfile a = source.bindIdentity(first, "SameName", "global"); a.accessScopes.add("server:first"); assertTrue(source.saveNow(a));
        PlayerProfile b = source.bindIdentity(second, "SameName", "global"); b.accessScopes.add("server:second"); assertTrue(source.saveNow(b));
        PlayerProfile pending = source.getOrCreateByUsername("Future", "global"); pending.accessScopes.add("all"); assertTrue(source.saveNow(pending));
        WhitelistTransferService export = new WhitelistTransferService(dir, store, source);
        assertEquals(3, export.exportSnapshot("snapshot", "global").players());
        Path destination = dir.resolve("restored"); PlayerRepository target = repository(destination);
        Files.createDirectories(destination.resolve("transfer")); Files.copy(dir.resolve("transfer/snapshot.json"), destination.resolve("transfer/snapshot.json"));
        assertEquals(3, new WhitelistTransferService(destination, store, target).importSnapshot("snapshot", "global").playersChanged());
        assertEquals(Set.of("server:first"), target.findByUuid(first, "global").orElseThrow().accessScopes);
        assertEquals(Set.of("server:second"), target.findByUuid(second, "global").orElseThrow().accessScopes);
        assertEquals("", target.findByUsername("Future", "global").orElseThrow().uuid);
    }
    @Test void uuidImportDoesNotGrantToAccountReusingOldNameOrMergeItsPending() throws Exception {
        PlayerRepository target = repository(dir); UUID original = UUID.randomUUID(), reused = UUID.randomUUID();
        PlayerProfile current = target.bindIdentity(reused, "OldName", "global"); assertTrue(target.saveNow(current));
        var snapshot = new WhitelistTransferService.Snapshot(); snapshot.schemaVersion = 2;
        snapshot.profiles = List.of(new WhitelistTransferService.Grant(original.toString(), "OldName", Set.of("all")));
        store.write(dir.resolve("transfer/uuid.json"), snapshot);
        new WhitelistTransferService(dir, store, target).importSnapshot("uuid", "global");
        assertTrue(target.findByUuid(reused, "global").orElseThrow().accessScopes.isEmpty());
        assertEquals(Set.of("all"), target.findByUuid(original, "global").orElseThrow().accessScopes);
        assertEquals(reused.toString(), target.findByUsername("OldName", "global").orElseThrow().uuid);
    }
    @Test void renamedUuidKeepsCurrentNameOnImport() throws Exception {
        PlayerRepository target = repository(dir); UUID uuid = UUID.randomUUID();
        target.bindIdentity(uuid, "NewName", "global");
        var snapshot = new WhitelistTransferService.Snapshot(); snapshot.schemaVersion = 2;
        snapshot.profiles = List.of(new WhitelistTransferService.Grant(uuid.toString(), "OldName", Set.of("all")));
        store.write(dir.resolve("transfer/rename.json"), snapshot);
        new WhitelistTransferService(dir, store, target).importSnapshot("rename", "global");
        assertEquals("NewName", target.findByUuid(uuid, "global").orElseThrow().username);
        assertTrue(target.findByUsername("OldName", "global").isEmpty());
    }
    @Test void invalidDocumentDoesNotApplyEarlierValidGrants() throws Exception {
        PlayerRepository target = repository(dir);
        var snapshot = new WhitelistTransferService.Snapshot(); snapshot.schemaVersion = 2;
        snapshot.profiles = List.of(new WhitelistTransferService.Grant("", "Valid", Set.of("all")), new WhitelistTransferService.Grant("1-1-1-1-1", "Invalid", Set.of("all")));
        store.write(dir.resolve("transfer/bad.json"), snapshot);
        assertThrows(IOException.class, () -> new WhitelistTransferService(dir, store, target).importSnapshot("bad", "global"));
        assertTrue(target.allProfiles("global").isEmpty());
    }
    @Test void acceptsLegacySnapshotAndRejectsMissingPlayers() throws Exception {
        PlayerRepository target = repository(dir); WhitelistTransferService service = new WhitelistTransferService(dir, store, target);
        Files.createDirectories(dir.resolve("transfer")); Files.writeString(dir.resolve("transfer/legacy.json"), "{\"players\":{\"Legacy\":[\"all\"]}}");
        assertEquals(1, service.importSnapshot("legacy", "global").playersChanged());
        Files.writeString(dir.resolve("transfer/empty.json"), "{}"); assertThrows(IOException.class, () -> service.importSnapshot("empty", "global"));
        assertThrows(IOException.class, () -> service.exportSnapshot("../escape", "global"));
    }
}
