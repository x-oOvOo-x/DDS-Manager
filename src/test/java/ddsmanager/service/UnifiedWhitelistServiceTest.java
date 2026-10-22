package ddsmanager.service;

import com.google.gson.GsonBuilder;
import ddsmanager.data.AtomicJsonStore;
import ddsmanager.data.PlayerProfile;
import ddsmanager.data.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class UnifiedWhitelistServiceTest {
    @TempDir Path dir;

    @Test void createsSimpleFileFromExistingGlobalScopes() throws Exception {
        var store = store();
        var players = players(store);
        PlayerProfile alice = players.bindIdentity(UUID.randomUUID(), "Alice", "global");
        alice.accessScopes.add(AccessScopes.ALL);
        assertTrue(players.saveNow(alice));
        PlayerProfile bob = players.bindIdentity(UUID.randomUUID(), "Bob", "global");
        bob.accessScopes.add(AccessScopes.server("survival"));
        assertTrue(players.saveNow(bob));

        var service = new UnifiedWhitelistService(dir, store, players);
        var result = service.initializeOrSync("global");

        assertTrue(result.created());
        assertEquals(1, result.playersWritten());
        var document = store.read(dir.resolve("whitelist.json"), UnifiedWhitelistService.Document.class).orElseThrow();
        assertEquals(List.of("Alice"), document.whitelist);
    }

    @Test void editedFileReplacesOnlyGlobalScopeAndCreatesPendingNames() throws Exception {
        var store = store();
        var players = players(store);
        PlayerProfile alice = players.bindIdentity(UUID.randomUUID(), "Alice", "global");
        alice.accessScopes.add(AccessScopes.ALL);
        alice.accessScopes.add(AccessScopes.server("survival"));
        assertTrue(players.saveNow(alice));
        PlayerProfile bob = players.bindIdentity(UUID.randomUUID(), "Bob", "global");
        bob.accessScopes.add(AccessScopes.ALL);
        assertTrue(players.saveNow(bob));

        var document = new UnifiedWhitelistService.Document();
        document.whitelist = List.of("Alice", "Carol");
        store.write(dir.resolve("whitelist.json"), document);
        var result = new UnifiedWhitelistService(dir, store, players).synchronize("global");

        assertEquals(1, result.added());
        assertEquals(1, result.removed());
        assertTrue(players.findByUsername("Alice", "global").orElseThrow().accessScopes.contains(AccessScopes.server("survival")));
        assertTrue(players.findByUsername("Bob", "global").isEmpty());
        PlayerProfile carol = players.findByUsername("Carol", "global").orElseThrow();
        assertTrue(carol.accessScopes.contains(AccessScopes.ALL));
        assertTrue(carol.uuid.isBlank());
    }

    @Test void invalidFileDoesNotPartiallyChangeProfiles() throws Exception {
        var store = store();
        var players = players(store);
        PlayerProfile alice = players.bindIdentity(UUID.randomUUID(), "Alice", "global");
        alice.accessScopes.add(AccessScopes.ALL);
        assertTrue(players.saveNow(alice));
        var document = new UnifiedWhitelistService.Document();
        document.whitelist = List.of("Carol", "Bad Name");
        store.write(dir.resolve("whitelist.json"), document);

        var service = new UnifiedWhitelistService(dir, store, players);
        assertThrows(Exception.class, () -> service.synchronize("global"));
        assertTrue(players.findByUsername("Alice", "global").orElseThrow().accessScopes.contains(AccessScopes.ALL));
        assertTrue(players.findByUsername("Carol", "global").isEmpty());
    }

    private AtomicJsonStore store() { return new AtomicJsonStore(new GsonBuilder().setPrettyPrinting().create()); }
    private PlayerRepository players(AtomicJsonStore store) {
        return new PlayerRepository(dir, store, LoggerFactory.getLogger("test"));
    }
}
