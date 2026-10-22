package ddsmanager.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class DimensionPacketInspectorTest {
    @Test void readsModernJoinGameThroughPublicDimensionInfoGetter() {
        var capture = DimensionPacketInspector.inspect(new JoinGamePacket(new DimensionInfo("minecraft:the_nether", "minecraft:the_nether"))).orElseThrow();
        assertEquals(DimensionPacketInspector.Kind.JOIN_GAME, capture.kind());
        assertArrayEquals(new String[]{"minecraft:the_nether", "minecraft:the_nether"}, capture.signals());
    }

    @Test void readsModernRespawnThroughPrivateDimensionInfoField() {
        var capture = DimensionPacketInspector.inspect(new RespawnPacket(new DimensionInfo("", "minecraft:the_end"))).orElseThrow();
        assertEquals(DimensionPacketInspector.Kind.RESPAWN, capture.kind()); assertEquals("", capture.registryIdentifier()); assertEquals("minecraft:the_end", capture.levelName());
    }

    @Test void readsNestedSpawnInfoWithoutProtocolBranch() {
        var capture = DimensionPacketInspector.inspect(new ClientboundRespawnPacket(new CommonPlayerSpawnInfo("minecraft:the_nether"))).orElseThrow();
        assertEquals(DimensionPacketInspector.Kind.RESPAWN, capture.kind()); assertEquals("minecraft:the_nether", capture.levelName());
    }

    @Test void mapsLegacyNumericDimensionsOnlyAsFallback() {
        assertEquals("minecraft:overworld", DimensionPacketInspector.inspect(new JoinGamePacket(0)).orElseThrow().levelName());
        assertEquals("minecraft:the_nether", DimensionPacketInspector.inspect(new RespawnPacket(-1)).orElseThrow().levelName());
        assertEquals("minecraft:the_end", DimensionPacketInspector.inspect(new RespawnPacket(1)).orElseThrow().levelName());
    }

    @Test void ignoresUnrelatedPackets() { assertTrue(DimensionPacketInspector.inspect(new Object()).isEmpty()); }

    static final class JoinGamePacket {
        private final DimensionInfo info; private final int dimension;
        JoinGamePacket(DimensionInfo info) { this.info = info; this.dimension = 0; }
        JoinGamePacket(int dimension) { this.info = null; this.dimension = dimension; }
        public DimensionInfo getDimensionInfo() { return info; }
        public int getDimension() { return dimension; }
    }
    static class RespawnPacket {
        private final DimensionInfo dimensionInfo; private final int dimension;
        RespawnPacket(DimensionInfo info) { this.dimensionInfo = info; this.dimension = 0; }
        RespawnPacket(int dimension) { this.dimensionInfo = null; this.dimension = dimension; }
    }
    static final class ClientboundRespawnPacket {
        private final CommonPlayerSpawnInfo commonPlayerSpawnInfo;
        ClientboundRespawnPacket(CommonPlayerSpawnInfo info) { this.commonPlayerSpawnInfo = info; }
    }
    static final class CommonPlayerSpawnInfo {
        private final String dimension;
        CommonPlayerSpawnInfo(String dimension) { this.dimension = dimension; }
        public String dimension() { return dimension; }
    }
    static final class DimensionInfo {
        private final String registryIdentifier, levelName;
        DimensionInfo(String registryIdentifier, String levelName) { this.registryIdentifier = registryIdentifier; this.levelName = levelName; }
        public String getRegistryIdentifier() { return registryIdentifier; }
        public String getLevelName() { return levelName; }
    }
}
