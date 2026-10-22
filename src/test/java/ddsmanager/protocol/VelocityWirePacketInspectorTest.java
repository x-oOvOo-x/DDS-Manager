package ddsmanager.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class VelocityWirePacketInspectorTest {
    @Test void decodesEncodeOnlyRespawnFromRegistryClassToIdMap() throws Exception {
        var inspector = new VelocityWirePacketInspector(); var encoder = new FakeEncoder();
        ByteBuf wire = Unpooled.buffer().writeByte(0x2A).writeByte(1);
        var result = inspector.inspect(encoder, wire).orElseThrow();
        assertEquals(VelocityWirePacketInspector.Role.RESPAWN, result.role());
        assertEquals("minecraft:the_nether", DimensionPacketInspector.inspect(result.packet()).orElseThrow().levelName());
        assertEquals(0, wire.readerIndex(), "inspection must not consume the real outbound buffer");
    }

    @Test void decodesPlayerInfoForTargetedRepair() throws Exception {
        UUID uuid = new UUID(0L, 7L);
        ByteBuf wire = Unpooled.buffer().writeByte(0x05).writeByte(1).writeLong(uuid.getMostSignificantBits()).writeLong(uuid.getLeastSignificantBits());
        var result = new VelocityWirePacketInspector().inspect(new FakeEncoder(), wire).orElseThrow();
        assertEquals(VelocityWirePacketInspector.Role.PLAYER_INFO, result.role()); assertNotNull(result.packet());
        var change = VelocityProtocolBridge.playerInfoChange(result.packet());
        assertNotNull(change); assertEquals(Set.of(uuid), change.subjects()); assertTrue(change.localGameModeChange(uuid));
        assertEquals(0, wire.readerIndex(), "inspection must not consume the real outbound buffer");
    }

    @Test void ignoresUnknownPacket() throws Exception { assertTrue(new VelocityWirePacketInspector().inspect(new FakeEncoder(), Unpooled.buffer().writeByte(0x03)).isEmpty()); }
    @Test void readsMultiByteMinecraftVarInt() { assertEquals(300, VelocityWirePacketInspector.readVarInt(Unpooled.buffer().writeByte(0xAC).writeByte(0x02))); }

    enum FakeDirection { CLIENTBOUND }
    static final class FakeVersion {}
    static final class FakeEncoder { private final FakeRegistry registry = new FakeRegistry(); public FakeDirection getDirection() { return FakeDirection.CLIENTBOUND; } }
    static final class FakeRegistry {
        private final FakeVersion version = new FakeVersion();
        private final Map<Class<?>, Integer> packetClassToId = Map.of(RespawnPacket.class, 0x2A, UpsertPlayerInfoPacket.class, 0x05);
        public Object createPacket(int id) { return id == 0x05 ? new UpsertPlayerInfoPacket() : null; }
    }

    static final class RespawnPacket {
        private DimensionInfo dimensionInfo;
        public RespawnPacket() {}
        public void decode(ByteBuf buf, FakeDirection direction, FakeVersion version) { dimensionInfo = new DimensionInfo(buf.readUnsignedByte() == 1 ? "minecraft:the_nether" : "minecraft:overworld"); }
    }

    static final class UpsertPlayerInfoPacket {
        private Set<Action> actions = Set.of(); private List<Entry> entries = List.of();
        public void decode(ByteBuf buf, FakeDirection direction, FakeVersion version) {
            actions = Set.of(buf.readUnsignedByte() == 1 ? Action.UPDATE_GAME_MODE : Action.UPDATE_LATENCY);
            entries = List.of(new Entry(new UUID(buf.readLong(), buf.readLong())));
        }
        public Set<Action> getActions() { return actions; } public List<Entry> getEntries() { return entries; }
        static final class Entry { private final UUID profileId; Entry(UUID profileId) { this.profileId = profileId; } public UUID getProfileId() { return profileId; } }
    }

    enum Action { UPDATE_GAME_MODE, UPDATE_LATENCY }
    static final class DimensionInfo { private final String registryIdentifier = "", levelName; DimensionInfo(String levelName) { this.levelName = levelName; } }
}
