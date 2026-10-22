package ddsmanager.data;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

final class AtomicJsonStoreTest {
    @TempDir Path dir;

    @Test void restoresValidBackupWhenPrimaryIsCorrupt() throws Exception {
        AtomicJsonStore store = new AtomicJsonStore(new GsonBuilder().create());
        Path path = dir.resolve("profile.json");
        store.write(path, new Value("old"));
        store.write(path, new Value("new"));
        Files.writeString(path, "{broken");

        assertEquals("old", store.read(path, Value.class).orElseThrow().text);
        assertEquals("old", store.read(path, Value.class).orElseThrow().text);
        assertTrue(Files.exists(path.resolveSibling("profile.json.corrupt")));
    }

    @Test void restoresBackupWhenPrimaryIsMissing() throws Exception {
        AtomicJsonStore store = new AtomicJsonStore(new GsonBuilder().create());
        Path path = dir.resolve("config.json"), backup = path.resolveSibling("config.json.bak");
        Files.writeString(backup, "{\"text\":\"backup\"}");

        assertEquals("backup", store.read(path, Value.class).orElseThrow().text);
        assertTrue(Files.exists(path));
    }

    private static final class Value {
        String text;
        Value(String text) { this.text = text; }
    }
}
