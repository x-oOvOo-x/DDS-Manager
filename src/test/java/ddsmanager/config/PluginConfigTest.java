package ddsmanager.config;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

final class PluginConfigTest {
    @Test void normalizeDropsInvalidGroupsAndCleansServerNames() {
        PluginConfig c = new PluginConfig(); c.serverGroups = new LinkedHashMap<>();
        c.serverGroups.put(" builders ", new LinkedHashSet<>(Arrays.asList(" survival ", "", null, "creative")));
        c.serverGroups.put("BUILDERS", Set.of("minigame")); c.serverGroups.put("bad group", Set.of("survival")); c.serverGroups.put("", Set.of("survival"));
        c.normalize(); assertEquals(Set.of("builders"), c.serverGroups.keySet()); assertEquals(Set.of("creative", "minigame", "survival"), c.serverGroups.get("builders"));
    }
    @Test void normalizeKeepsOnlyCanonicalAdministratorUuids() {
        PluginConfig c = new PluginConfig(); UUID valid = UUID.randomUUID();
        c.administrators = new LinkedHashSet<>(Arrays.asList(" " + valid.toString().toUpperCase(Locale.ROOT) + " ", "Steve", "", null)); c.normalize();
        assertEquals(2, c.schemaVersion); assertEquals(Set.of(valid.toString()), c.administrators);
    }
    @Test void rejectsAbbreviatedAdministratorUuid() { assertFalse(PluginConfig.isValidUuid("1-1-1-1-1")); }
    @Test void normalizeRepairsInvalidChannelAndPersistenceBounds() {
        PluginConfig c = new PluginConfig(); c.chat.defaultChannel = "invalid"; c.persistence.flushIntervalSeconds = 0; c.persistence.cacheMinutes = -5; c.normalize();
        assertEquals("local", c.chat.defaultChannel); assertEquals(1, c.persistence.flushIntervalSeconds); assertEquals(1, c.persistence.cacheMinutes);
    }
    @Test void customServerLabelsOverrideLegacyPrefixWithoutBreakingFallback() {
        PluginConfig c = new PluginConfig(); c.presence.serverPrefixes.put("survival", "SURV");
        c.presence.serverLabels.put(" survival ", " <gradient:#55ff55:#00ffaa><bold>◆ S</bold></gradient> "); c.presence.serverLabels.put("bad", "line\nbreak"); c.normalize();
        assertEquals("<gradient:#55ff55:#00ffaa><bold>◆ S</bold></gradient>", c.presence.serverLabel("SURVIVAL")); assertEquals("[C]", c.presence.serverLabel("creative")); assertFalse(c.presence.serverLabels.containsKey("bad"));
    }
    @Test void tabDisplayDefaultsUseCompactDimensionSymbol() {
        PluginConfig c = new PluginConfig(); assertTrue(c.presence.showServerInTabName && c.presence.showDimensionInTabName); assertEquals("⁑", c.presence.dimensionSymbol);
        c.presence.dimensionSymbol = " │ "; c.normalize(); assertEquals("│", c.presence.dimensionSymbol);
        c.presence.dimensionSymbol = "ABCDE"; c.normalize(); assertEquals("⁑", c.presence.dimensionSymbol);
    }
}
