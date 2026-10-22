package ddsmanager.data;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class BanRepositoryTest {
    @TempDir Path dir;

    @Test void persistsUuidBanIndependentlyOfPlayerProfiles() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create());
        var players = new PlayerRepository(dir, store, LoggerFactory.getLogger("test"));
        var bans = new BanRepository(dir, store, LoggerFactory.getLogger("test"));
        UUID uuid = UUID.randomUUID();

        PlayerProfile profile = players.bindIdentity(uuid, "OldName", "global");
        profile.accessScopes.add("all");
        assertTrue(players.saveNow(profile));

        BanEntry created = bans.add(uuid, "OldName", "griefing", "CONSOLE").orElseThrow();
        assertEquals(uuid.toString(), created.uuid);
        assertEquals("griefing", created.reason);

        assertTrue(players.purgeByUsername("OldName"));
        assertTrue(players.findByUuid(uuid, "global").isEmpty());
        assertTrue(bans.find(uuid).isPresent());

        var reloaded = new BanRepository(dir, store, LoggerFactory.getLogger("test"));
        assertEquals("OldName", reloaded.find(uuid).orElseThrow().lastKnownName);
        assertEquals("griefing", reloaded.find(uuid).orElseThrow().reason);
    }

    @Test void removalIsPersistedAndDuplicateAddDoesNotOverwriteReason() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create());
        var bans = new BanRepository(dir, store, LoggerFactory.getLogger("test"));
        UUID uuid = UUID.randomUUID();

        assertTrue(bans.add(uuid, "", "first", "Admin").isPresent());
        assertTrue(bans.add(uuid, "Renamed", "second", "Other").isEmpty());
        assertEquals("first", bans.find(uuid).orElseThrow().reason);

        assertTrue(bans.remove(uuid).isPresent());
        assertTrue(bans.find(uuid).isEmpty());
        assertTrue(new BanRepository(dir, store, LoggerFactory.getLogger("test")).find(uuid).isEmpty());
    }

    @Test void sameLastKnownNameDoesNotMergeDifferentUuids() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create());
        var bans = new BanRepository(dir, store, LoggerFactory.getLogger("test"));
        UUID oldAccount = UUID.randomUUID(), newAccount = UUID.randomUUID();

        assertTrue(bans.add(oldAccount, "ReusedName", "old", "Admin").isPresent());
        assertTrue(bans.add(newAccount, "ReusedName", "new", "Admin").isPresent());

        assertEquals(2, bans.findByLastKnownName("reusedname").size());
        assertEquals("old", bans.find(oldAccount).orElseThrow().reason);
        assertEquals("new", bans.find(newAccount).orElseThrow().reason);
    }

    @Test void malformedBanFileFailsClosedUntilRepaired() throws Exception {
        Files.writeString(dir.resolve("bans.json"), "{ broken");
        var store = new AtomicJsonStore(new GsonBuilder().create());
        var bans = new BanRepository(dir, store, LoggerFactory.getLogger("test"));

        assertFalse(bans.healthy());
        assertThrows(IllegalStateException.class,
                () -> bans.add(UUID.randomUUID(), "", "reason", "CONSOLE"));

        Files.writeString(dir.resolve("bans.json"), "{\"schemaVersion\":1,\"bans\":[]}");
        assertTrue(bans.reload());
        assertTrue(bans.healthy());
    }

    @Test void corruptionAfterSuccessfulLoadPreservesKnownBanAndFailsClosed() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create());
        var bans = new BanRepository(dir, store, LoggerFactory.getLogger("test"));
        UUID banned = UUID.randomUUID(), newcomer = UUID.randomUUID();
        bans.add(banned, "OldName", "reason", "CONSOLE");
        Files.writeString(dir.resolve("bans.json"), "{invalid");
        assertFalse(bans.reload());
        assertFalse(bans.healthy());
        assertTrue(bans.find(banned).isPresent(), "last valid snapshot must not disappear");
        assertThrows(IllegalStateException.class, () -> bans.add(newcomer, "NewName", "reason", "CONSOLE"));
        Files.writeString(dir.resolve("bans.json"), "{\"schemaVersion\":1,\"bans\":[]}");
        assertTrue(bans.reload());
        assertTrue(bans.healthy());
        assertTrue(bans.find(banned).isEmpty());
    }


    @Test void malformedStructuresFailClosedAndKeepPreviousSnapshot() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create());
        var bans = new BanRepository(dir, store, LoggerFactory.getLogger("test"));
        UUID banned = UUID.randomUUID();
        bans.add(banned, "OldName", "persistent ban", "CONSOLE");
        String[] invalidDocuments = {
                "{\"schemaVersion\":1,\"bans\":null}",
                "{\"schemaVersion\":1}",
                "{\"bans\":[]}",
                "{}",
                "{\"schemaVersion\":null,\"bans\":[]}",
                "{\"schemaVersion\":1,\"bans\":[{\"uuid\":\"not-a-uuid\"}]}",
                "{\"schemaVersion\":1,\"bans\":[null]}"
        };
        for (String document : invalidDocuments) {
            Files.writeString(dir.resolve("bans.json"), document);
            assertFalse(bans.reload(), document);
            assertFalse(bans.healthy(), document);
            assertTrue(bans.find(banned).isPresent(), "valid in-memory snapshot must survive " + document);
            assertThrows(IllegalStateException.class, () -> bans.remove(banned), document);
        }

        Files.writeString(dir.resolve("bans.json"),
                "{\"schemaVersion\":1,\"bans\":[{\"uuid\":\"" + banned + "\",\"lastKnownName\":\"OldName\",\"reason\":\"persistent ban\",\"createdBy\":\"CONSOLE\",\"createdAtEpochMs\":1}]}");
        assertTrue(bans.reload());
        assertTrue(bans.healthy());
        assertEquals("persistent ban", bans.find(banned).orElseThrow().reason);
    }

    @Test void missingBanFileStartsEmptyAndFirstWritePersistsSchema() throws Exception {
        var store = new AtomicJsonStore(new GsonBuilder().create());
        var bans = new BanRepository(dir, store, LoggerFactory.getLogger("test"));
        assertTrue(bans.healthy());
        assertEquals(0, bans.size());
        UUID uuid = UUID.randomUUID();
        assertTrue(bans.add(uuid, "Tester", "reason", "CONSOLE").isPresent());
        String document = Files.readString(dir.resolve("bans.json"));
        assertEquals(BanRepository.SCHEMA_VERSION,
                com.google.gson.JsonParser.parseString(document).getAsJsonObject().get("schemaVersion").getAsInt());
        assertTrue(document.contains("\"bans\""));
        assertTrue(new BanRepository(dir, store, LoggerFactory.getLogger("test")).find(uuid).isPresent());
    }

    @Test void reasonIsNormalizedAndBounded() {
        assertEquals("a b", BanRepository.normalizeReason(" a\n\tb "));
        assertEquals("未提供原因", BanRepository.normalizeReason("   "));
        assertThrows(IllegalArgumentException.class,
                () -> BanRepository.normalizeReason("x".repeat(BanRepository.MAX_REASON_CODEPOINTS + 1)));
    }
}
