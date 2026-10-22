package ddsmanager.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import ddsmanager.config.PluginConfig;
import ddsmanager.i18n.LanguageService;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

final class TabSyncServiceTest {
    @TempDir Path languageFolder;

    private NetworkPresenceService presence(PluginConfig config) {
        LanguageService language = new LanguageService(languageFolder, LoggerFactory.getLogger("test"), () -> config);
        language.reload();
        return new NetworkPresenceService(() -> config, language);
    }

    @Test void skipsUnchangedPacketsAndRepairsBackendDisplayReplacement() {
        PluginConfig config = config();
        AtomicReference<Component> display = new AtomicReference<>();
        AtomicInteger latency = new AtomicInteger(), displayWrites = new AtomicInteger(), latencyWrites = new AtomicInteger();
        TabListEntry entry = entry(display, latency, displayWrites, latencyWrites);
        TabList tab = proxy(TabList.class, (p, method, args) -> method.getName().equals("getEntry") ? Optional.of(entry) : null);
        UUID uuid = UUID.randomUUID(); Player player = player(uuid, tab, 42L);
        ProxyServer server = server(List.of(player));
        TabSyncService service = service(server, config);

        service.refreshViewer(player); Component decorated = display.get(); assertNotNull(decorated);
        service.refreshViewer(player); assertEquals(1, displayWrites.get()); assertEquals(1, latencyWrites.get());
        display.set(Component.text("Backend override")); service.refreshSubject(player); assertEquals(decorated, display.get()); assertEquals(2, displayWrites.get());

        config.presence.showServerInTabName = false; config.presence.showDimensionInTabName = false;
        service.refreshViewer(player); assertNull(display.get()); assertEquals(3, displayWrites.get());
    }

    @Test void reapplyViewerForcesDisplayAfterLocalGameModeRewrite() {
        PluginConfig config = config();
        AtomicReference<Component> display = new AtomicReference<>(); AtomicInteger displayWrites = new AtomicInteger();
        TabListEntry entry = entry(display, new AtomicInteger(), displayWrites, new AtomicInteger());
        TabList tab = proxy(TabList.class, (p, method, args) -> method.getName().equals("getEntry") ? Optional.of(entry) : null);
        Player player = player(UUID.randomUUID(), tab, 42L); TabSyncService service = service(server(List.of(player)), config);

        service.refreshViewer(player); Component expected = display.get(); assertNotNull(expected); assertEquals(1, displayWrites.get());
        service.reapplyViewer(player);
        assertEquals(expected, display.get()); assertEquals(2, displayWrites.get(), "local game-mode repair must re-send the DDS display even when Velocity state still matches");
    }

    @Test void targetedViewerRepairDoesNotScanUnrelatedSubjects() {
        PluginConfig config = config(); UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
        AtomicReference<Component> firstDisplay = new AtomicReference<>(), secondDisplay = new AtomicReference<>();
        AtomicInteger firstWrites = new AtomicInteger(), secondWrites = new AtomicInteger();
        TabListEntry firstEntry = entry(firstDisplay, new AtomicInteger(), firstWrites, new AtomicInteger());
        TabListEntry secondEntry = entry(secondDisplay, new AtomicInteger(), secondWrites, new AtomicInteger());
        Map<UUID, TabListEntry> entries = Map.of(firstId, firstEntry, secondId, secondEntry);
        TabList tab = proxy(TabList.class, (p, method, args) -> method.getName().equals("getEntry") ? Optional.ofNullable(entries.get(args[0])) : null);
        Player first = player(firstId, tab, 10L), second = player(secondId, tab, 20L);
        ProxyServer server = server(List.of(first, second)); TabSyncService service = service(server, config);

        service.refreshViewer(first); assertEquals(1, firstWrites.get()); assertEquals(1, secondWrites.get());
        secondDisplay.set(Component.text("Backend override"));
        service.refreshViewerSubjects(first, Set.of(secondId));
        assertEquals(1, firstWrites.get(), "unrelated entry must not be rewritten");
        assertEquals(2, secondWrites.get()); assertNotEquals(Component.text("Backend override"), secondDisplay.get());
    }

    @Test void displayOnlyDimensionRefreshDoesNotTouchLatency() {
        PluginConfig config = config();
        AtomicReference<Component> display = new AtomicReference<>(); AtomicInteger latency = new AtomicInteger(), displayWrites = new AtomicInteger(), latencyWrites = new AtomicInteger();
        TabListEntry entry = entry(display, latency, displayWrites, latencyWrites);
        TabList tab = proxy(TabList.class, (p, method, args) -> method.getName().equals("getEntry") ? Optional.of(entry) : null);
        Player player = player(UUID.randomUUID(), tab, 42L); ProxyServer server = server(List.of(player));
        NetworkPresenceService presence = presence(config); TabSyncService service = new TabSyncService(server, () -> config, presence, LoggerFactory.getLogger("test"));

        assertTrue(presence.update(player, "proxy", "minecraft:overworld")); service.refreshViewer(player); Component overworld = display.get();
        assertTrue(presence.update(player, "proxy", "minecraft:the_nether")); assertTrue(service.refreshSubjectDisplay(player));
        assertNotEquals(overworld, display.get()); assertEquals(2, displayWrites.get()); assertEquals(1, latencyWrites.get());
    }

    @Test void displayRefreshReportsTemporaryMissingSelfEntry() {
        PluginConfig config = config();
        AtomicReference<Optional<TabListEntry>> current = new AtomicReference<>(Optional.empty());
        TabList tab = proxy(TabList.class, (p, method, args) -> method.getName().equals("getEntry") ? current.get() : null);
        UUID uuid = UUID.randomUUID(); Player player = player(uuid, tab, 0L); ProxyServer server = server(List.of(player));
        TabSyncService service = service(server, config);

        assertFalse(service.refreshSubjectDisplay(player));
        TabListEntry entry = proxy(TabListEntry.class, (p, method, args) -> method.getName().equals("getDisplayNameComponent") ? Optional.empty() : null); current.set(Optional.of(entry));
        assertTrue(service.refreshSubjectDisplay(player));
    }

    @Test void neverEmitsNegativeLatencyForRemoteEntries() {
        assertEquals(42, TabSyncService.displayLatency(42, -1)); assertEquals(84, TabSyncService.displayLatency(-1, 84));
        assertEquals(0, TabSyncService.displayLatency(-1, -1)); assertEquals(Integer.MAX_VALUE, TabSyncService.displayLatency(Long.MAX_VALUE, 0));
    }

    private static PluginConfig config() {
        PluginConfig config = new PluginConfig(); config.normalize(); config.features.syncTabList = true; return config;
    }

    private TabSyncService service(ProxyServer server, PluginConfig config) {
        return new TabSyncService(server, () -> config, presence(config), LoggerFactory.getLogger("test"));
    }

    private static ProxyServer server(List<Player> players) {
        Map<UUID, Player> byId = new HashMap<>(); players.forEach(player -> byId.put(player.getUniqueId(), player));
        return proxy(ProxyServer.class, (p, method, args) -> switch (method.getName()) {
            case "getAllPlayers" -> players;
            case "getPlayer" -> Optional.ofNullable(byId.get(args[0]));
            default -> null;
        });
    }

    private static TabListEntry entry(AtomicReference<Component> display, AtomicInteger latency, AtomicInteger displayWrites, AtomicInteger latencyWrites) {
        return proxy(TabListEntry.class, (p, method, args) -> switch (method.getName()) {
            case "getDisplayNameComponent" -> Optional.ofNullable(display.get());
            case "setDisplayName" -> { display.set((Component) args[0]); displayWrites.incrementAndGet(); yield p; }
            case "getLatency" -> latency.get();
            case "setLatency" -> { latency.set((int) args[0]); latencyWrites.incrementAndGet(); yield p; }
            case "isListed" -> true;
            case "setListed" -> p;
            default -> null;
        });
    }

    private static Player player(UUID uuid, TabList tab, long ping) {
        return proxy(Player.class, (p, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid; case "getUsername" -> "Player"; case "getCurrentServer" -> Optional.empty(); case "getPing" -> ping; case "getTabList" -> tab; default -> null;
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler)); }
}
