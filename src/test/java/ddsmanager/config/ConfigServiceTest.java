package ddsmanager.config;

import com.google.gson.GsonBuilder;
import ddsmanager.DdsManagerPlugin;
import ddsmanager.data.AtomicJsonStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

final class ConfigServiceTest {
    @TempDir Path dir;
    @Test void failedSaveDoesNotPublishModifiedConfiguration() throws Exception {
        var logger = LoggerFactory.getLogger("test"); ConfigService service = service(logger);
        PluginConfig original = service.load(); DdsManagerPlugin plugin = new DdsManagerPlugin(null, logger, dir);
        set(plugin, "config", original); set(plugin, "configService", service); Files.createDirectory(dir.resolve("config.json.tmp"));
        assertThrows(IllegalStateException.class, () -> plugin.updateConfig(c -> c.features.whitelist = true));
        assertSame(original, plugin.config()); assertFalse(plugin.config().features.whitelist);
        Files.delete(dir.resolve("config.json.tmp")); plugin.updateConfig(c -> c.features.whitelist = true);
        assertTrue(plugin.config().features.whitelist); assertTrue(service.loadForReload().features.whitelist);
    }
    @Test void reloadFailurePreservesLiveConfigurationAndStartupFailsClosed() throws Exception {
        var logger = LoggerFactory.getLogger("test"); ConfigService service = service(logger);
        PluginConfig original = service.load(); DdsManagerPlugin plugin = new DdsManagerPlugin(null, logger, dir);
        set(plugin, "config", original); set(plugin, "configService", service); Files.writeString(dir.resolve("config.json"), "{broken");
        assertThrows(IllegalStateException.class, plugin::reload); assertSame(original, plugin.config()); assertTrue(service.load().features.whitelist);
        Files.delete(dir.resolve("config.json")); assertThrows(IOException.class, service::loadForReload);
    }
    @Test void generatedConfigIncludesChineseAndEnglishCommentsAndReloads() throws Exception {
        ConfigService service = service(LoggerFactory.getLogger("test")); service.load(); Path path = dir.resolve("config.json"); String text = Files.readString(path);
        for (String key : new String[]{"schemaVersion","features","whitelist","rememberLastServer","bridgeChat","chatLog","syncTabList","bridgeJoinLeave","minimapWorldSync","permissionBasedAccess","routing","respectInitialServer","fallbackServer","chat","defaultChannel","presence","showServerSwitches","showServerInTabName","showDimensionInTabName","serverPrefixes","serverLabels","dimensionSymbol","persistence","flushIntervalSeconds","cacheMinutes","administrators","serverGroups","language","defaultLocale","fallbackLocale","consoleLocale","detectClientLanguage"}) assertCommented(text, key);
        assertTrue(text.contains("// 是否启用 DDS 统一白名单"));
        assertTrue(text.contains("// Enable the DDS unified whitelist"));
        assertFalse(text.contains("showServerPrefix"));
        Files.writeString(path, text.replace("\"fallbackServer\": \"\"", "\"fallbackServer\": \"srv//blue\""));
        assertEquals("srv//blue", service.loadForReload().routing.fallbackServer);
    }

    @Test void dynamicEntriesAlsoHaveBilingualCommentsAndValuesArePreserved() throws Exception {
        ConfigService service = service(LoggerFactory.getLogger("test"));
        PluginConfig config = service.load();
        config.presence.serverLabels.put("survival", "<green>Survival</green>");
        config.administrators.add("00000000-0000-0000-0000-000000000001");
        config.serverGroups.put("lobby", java.util.Set.of("survival"));
        service.save(config);

        String text = Files.readString(dir.resolve("config.json"));
        assertTrue(text.contains("// 服务器 survival 的简写\n"));
        assertTrue(text.contains("// Abbreviation for server survival\n"));
        assertTrue(text.contains("// Full label template for server survival\n"));
        assertTrue(text.contains("// Administrator UUID: 00000000-0000-0000-0000-000000000001\n"));
        assertTrue(text.contains("// Server group lobby\n"));
        assertTrue(text.contains("// Server in this group: survival\n"));

        PluginConfig loaded = service.loadForReload();
        assertEquals("<green>Survival</green>", loaded.presence.serverLabels.get("survival"));
        assertTrue(loaded.administrators.contains("00000000-0000-0000-0000-000000000001"));
        assertTrue(loaded.serverGroups.get("lobby").contains("survival"));
    }

    private ConfigService service(org.slf4j.Logger logger) { return new ConfigService(dir, new AtomicJsonStore(new GsonBuilder().create()), logger); }
    private static void assertCommented(String text, String key) {
        String[] lines = text.split("\\R"); String token = "\"" + key + "\":";
        for (int i = 0; i < lines.length; i++) if (lines[i].contains(token)) {
            assertTrue(i >= 2, key + " missing bilingual comments");
            assertTrue(lines[i - 2].trim().startsWith("// "), key + " 缺少中文注释");
            assertTrue(lines[i - 1].trim().startsWith("// "), key + " missing English comment");
            assertTrue(lines[i - 2].codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN), key + " missing Chinese text");
            assertTrue(lines[i - 1].matches(".*[A-Za-z].*"), key + " missing English text");
            return;
        }
        fail("缺少配置项: " + key);
    }
    private static void set(Object target, String name, Object value) throws Exception { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
}
