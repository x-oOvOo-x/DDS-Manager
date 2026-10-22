package ddsmanager.data;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class MinimapWorldIdRegistryTest {
    @TempDir Path dir;

    @Test void firstAssignmentKeepsLegacyXaeroIdAndPersists() {
        int expected = MinimapWorldIdRegistry.legacyId("survival");
        var registry = registry(); assertEquals(expected, registry.idFor("survival", address(25566)));
        assertEquals(expected, registry().idFor("survival", address(25599)));
    }

    @Test void renameOnSameEndpointReusesPersistentId() {
        var registry = registry(); int original = registry.idFor("survival", address(25566));
        assertEquals(original, registry.idFor("survival-new", address(25566)));
        assertEquals(original, registry().idFor("survival-new", address(25566)));
    }

    @Test void backendMoveThenRenameKeepsId() {
        var registry = registry(); int original = registry.idFor("creative", address(25567));
        assertEquals(original, registry.idFor("creative", address(25577)));
        assertEquals(original, registry.idFor("creative-new", address(25577)));
    }

    @Test void differentBackendsReceiveDifferentIds() {
        var registry = registry();
        assertNotEquals(registry.idFor("survival", address(25566)), registry.idFor("creative", address(25567)));
    }

    private MinimapWorldIdRegistry registry() {
        return new MinimapWorldIdRegistry(dir, new AtomicJsonStore(new GsonBuilder().setPrettyPrinting().create()), LoggerFactory.getLogger("test"));
    }
    private static InetSocketAddress address(int port) { return InetSocketAddress.createUnresolved("127.0.0.1", port); }
}
