package ddsmanager.service;

import com.velocitypowered.api.proxy.Player;
import ddsmanager.config.PluginConfig;
import ddsmanager.i18n.LanguageService;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class NetworkPresenceServiceTest {
    @TempDir Path languageFolder;

    private NetworkPresenceService presence(PluginConfig config) {
        config.language.defaultLocale = "zh_CN";
        config.normalize();
        LanguageService language = new LanguageService(languageFolder, LoggerFactory.getLogger("test"), () -> config);
        language.reload();
        return new NetworkPresenceService(() -> config, language);
    }

    @Test void joinGameUsesExpectedServerBeforeVelocityUpdatesCurrentServer() {
        assertEquals(Optional.of("survival"), NetworkPresenceService.resolvePacketServer("", "survival", true));
        assertEquals(Optional.of("creative"), NetworkPresenceService.resolvePacketServer("survival", "creative", true));
    }
    @Test void respawnFromOldServerIsIgnoredDuringSwitch() {
        assertEquals(Optional.empty(), NetworkPresenceService.resolvePacketServer("survival", "creative", false));
        assertEquals(Optional.of("survival"), NetworkPresenceService.resolvePacketServer("survival", null, false));
    }
    @Test void targetDiagnosticWorksBeforeVelocityUpdatesCurrentServer() {
        PluginConfig config = new PluginConfig(); config.normalize();
        NetworkPresenceService service = presence(config); Player player = player(UUID.randomUUID());
        assertTrue(service.update(player, "survival", "minecraft:overworld"));
        assertEquals("等待 Join Game / Respawn 数据", service.diagnostic(player));
        assertEquals("主世界 (minecraft:overworld)", service.diagnostic(player, "survival"));
    }
    @Test void failedTargetRestoresPreviousPresenceAndClearsExpectation() {
        PluginConfig config = new PluginConfig(); config.normalize();
        NetworkPresenceService service = presence(config); Player player = player(UUID.randomUUID());
        assertTrue(service.update(player, "survival", "minecraft:overworld"));
        service.expectServer(player, "mirror"); assertEquals(Optional.of("mirror"), service.packetServer(player, true));
        assertTrue(service.update(player, "mirror", "minecraft:the_nether"));
        service.connectionFailed(player, "mirror");
        assertEquals("主世界 (minecraft:overworld)", service.diagnostic(player, "survival"));
        assertEquals("等待 Join Game / Respawn 数据", service.diagnostic(player, "mirror"));
        assertEquals(Optional.of("proxy"), service.packetServer(player, true));
    }
    @Test void fallbackConnectionRollsBackFailedTargetPresence() {
        PluginConfig config = new PluginConfig(); config.normalize();
        NetworkPresenceService service = presence(config); Player player = player(UUID.randomUUID());
        assertTrue(service.update(player, "survival", "minecraft:overworld"));
        service.expectServer(player, "mirror"); assertTrue(service.update(player, "mirror", "minecraft:the_nether"));
        service.connected(player, "survival");
        assertEquals("主世界 (minecraft:overworld)", service.diagnostic(player, "survival"));
        assertEquals(Optional.of("proxy"), service.packetServer(player, true));
    }
    @Test void dimensionSymbolIsCompactAndConfigurable() {
        PluginConfig config = new PluginConfig(); config.presence.dimensionSymbol = "│"; config.normalize();
        assertEquals("│", config.presence.dimensionSymbol); assertTrue(PluginConfig.isValidDimensionSymbol("▏")); assertFalse(PluginConfig.isValidDimensionSymbol("ABCDE"));
    }
    @Test void unchangedPresenceIsNotReportedAsAnotherUpdate() {
        PluginConfig config = new PluginConfig(); config.normalize();
        NetworkPresenceService service = presence(config); Player player = player(UUID.randomUUID());
        assertTrue(service.update(player, "survival", "minecraft:overworld")); assertFalse(service.update(player, "survival", "minecraft:overworld"));
        assertTrue(service.update(player, "survival", "minecraft:the_nether")); assertFalse(service.update(player, "survival", "minecraft:the_nether"));
    }
    @Test void dimensionTypeAndEffectsTakePriorityOverCustomWorldKey() {
        assertEquals(NetworkPresenceService.Dimension.NETHER, NetworkPresenceService.classify("custom:dimension_type", "minecraft:the_nether", "custom:world"));
        assertEquals(NetworkPresenceService.Dimension.OVERWORLD, NetworkPresenceService.classify("minecraft:overworld", "custom:world"));
        assertEquals(NetworkPresenceService.Dimension.END, NetworkPresenceService.classify("minecraft:the_end"));
        assertEquals(NetworkPresenceService.Dimension.NETHER, NetworkPresenceService.classify("nether"));
        assertEquals(NetworkPresenceService.Dimension.END, NetworkPresenceService.classify("end"));
        assertEquals(NetworkPresenceService.Dimension.UNKNOWN, NetworkPresenceService.classify("custom:moon"));
    }
    private static Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getCurrentServer" -> Optional.empty();
            default -> null;
        });
    }
}
