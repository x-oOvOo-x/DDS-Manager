package ddsmanager.protocol;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

final class VelocityProtocolBridgeTest {
    @Test void recognizesTypedPresenceAndPlayerInfoFamilies() {
        assertTrue(DimensionPacketInspector.inspect(new JoinGamePacket()).isPresent());
        assertTrue(DimensionPacketInspector.inspect(new RespawnPacket()).isPresent());
        assertTrue(DimensionPacketInspector.inspect(new Object()).isEmpty());
        assertTrue(VelocityProtocolBridge.mayReplaceTabDisplay(new LegacyPlayerListItemPacket(1, UUID.randomUUID())));
        assertTrue(VelocityProtocolBridge.mayReplaceTabDisplay(new UpsertPlayerInfoPacket(Set.of(Action.UPDATE_GAME_MODE), UUID.randomUUID())));
        assertTrue(VelocityProtocolBridge.mayReplaceTabDisplay(new RemovePlayerInfoPacket(Set.of(UUID.randomUUID()))));
        assertFalse(VelocityProtocolBridge.mayReplaceTabDisplay(new Object()));
    }

    @Test void playerInfoChangeTargetsOnlyAffectedSubjects() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var upsert = VelocityProtocolBridge.playerInfoChange(new UpsertPlayerInfoPacket(Set.of(Action.UPDATE_DISPLAY_NAME), first, second));
        assertNotNull(upsert); assertEquals(Set.of(first, second), upsert.subjects());
        assertTrue(upsert.affectsPresentation()); assertFalse(upsert.localGameModeChange(first));

        var latency = VelocityProtocolBridge.playerInfoChange(new UpsertPlayerInfoPacket(Set.of(Action.UPDATE_LATENCY), first));
        assertNotNull(latency); assertFalse(latency.affectsPresentation());

        var gameMode = VelocityProtocolBridge.playerInfoChange(new UpsertPlayerInfoPacket(Set.of(Action.UPDATE_GAME_MODE), first));
        assertNotNull(gameMode); assertTrue(gameMode.affectsPresentation()); assertTrue(gameMode.localGameModeChange(first)); assertFalse(gameMode.localGameModeChange(second));

        var remove = VelocityProtocolBridge.playerInfoChange(new RemovePlayerInfoPacket(Set.of(second)));
        assertNotNull(remove); assertEquals(Set.of(second), remove.subjects()); assertTrue(remove.affectsPresentation());
    }

    @Test void legacyActionsMapToModernRepairSemantics() {
        UUID uuid = UUID.randomUUID();
        var gameMode = VelocityProtocolBridge.playerInfoChange(new LegacyPlayerListItemPacket(1, uuid));
        assertNotNull(gameMode); assertEquals(Set.of(uuid), gameMode.subjects()); assertTrue(gameMode.localGameModeChange(uuid));
        var latency = VelocityProtocolBridge.playerInfoChange(new LegacyPlayerListItemPacket(2, uuid));
        assertNotNull(latency); assertFalse(latency.affectsPresentation());
        var unknown = VelocityProtocolBridge.playerInfoChange(new LegacyPlayerListItemPacket(99, uuid));
        assertNotNull(unknown); assertTrue(unknown.conservative());
    }

    @Test void settlesEveryActiveSubjectAndRemovesExpiredEntries() {
        long now = 10_000L; UUID first = UUID.randomUUID(), second = UUID.randomUUID(), expired = UUID.randomUUID();
        Map<UUID, Long> deadlines = new ConcurrentHashMap<>(Map.of(first, now, second, now + 1, expired, now - 1));
        assertEquals(Set.of(first, second), VelocityProtocolBridge.activeSettlingIds(deadlines, now));
        assertEquals(Set.of(first, second), deadlines.keySet());
    }

    @Test void viewerSettlementIsOneShotAndDropsExpiredEntries() {
        long now = 10_000L; UUID active = UUID.randomUUID(), expired = UUID.randomUUID();
        Map<UUID, Long> deadlines = new ConcurrentHashMap<>(Map.of(active, now + 1, expired, now - 1));
        assertTrue(VelocityProtocolBridge.consumeSettling(deadlines, active, now));
        assertFalse(VelocityProtocolBridge.consumeSettling(deadlines, active, now));
        assertFalse(VelocityProtocolBridge.consumeSettling(deadlines, expired, now));
        assertTrue(deadlines.isEmpty());
    }

    static final class JoinGamePacket { private final DimensionInfo dimensionInfo = new DimensionInfo(); }
    static final class RespawnPacket { private final DimensionInfo dimensionInfo = new DimensionInfo(); }
    static final class DimensionInfo { private final String registryIdentifier = "minecraft:overworld", levelName = "minecraft:overworld"; }

    enum Action { ADD_PLAYER, UPDATE_GAME_MODE, UPDATE_LISTED, UPDATE_LATENCY, UPDATE_DISPLAY_NAME }

    static final class LegacyPlayerListItemPacket {
        private final int action; private final List<Item> items;
        LegacyPlayerListItemPacket(int action, UUID... subjects) { this.action = action; this.items = Arrays.stream(subjects).map(Item::new).toList(); }
        public int getAction() { return action; } public List<Item> getItems() { return items; }
        static final class Item { private final UUID uuid; Item(UUID uuid) { this.uuid = uuid; } public UUID getUuid() { return uuid; } }
    }

    static final class UpsertPlayerInfoPacket {
        private final Set<Action> actions; private final List<Entry> entries;
        UpsertPlayerInfoPacket(Set<Action> actions, UUID... subjects) { this.actions = actions; this.entries = Arrays.stream(subjects).map(Entry::new).toList(); }
        public Set<Action> getActions() { return actions; } public List<Entry> getEntries() { return entries; }
        static final class Entry { private final UUID profileId; Entry(UUID profileId) { this.profileId = profileId; } public UUID getProfileId() { return profileId; } }
    }

    static final class RemovePlayerInfoPacket {
        private final Set<UUID> profilesToRemove;
        RemovePlayerInfoPacket(Set<UUID> profilesToRemove) { this.profilesToRemove = profilesToRemove; }
        public Set<UUID> getProfilesToRemove() { return profilesToRemove; }
    }
}
