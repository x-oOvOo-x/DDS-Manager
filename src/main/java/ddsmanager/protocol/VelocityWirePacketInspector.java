package ddsmanager.protocol;

import io.netty.buffer.ByteBuf;

import java.lang.reflect.*;
import java.util.*;

/** Decodes DDS-relevant clientbound wire packets using Velocity's active encoder registry. */
final class VelocityWirePacketInspector {
    enum Role { JOIN_GAME, RESPAWN, PLAYER_INFO, OTHER }
    record Inspection(Role role, Object packet) {}
    private record PacketSpec(Role role, Class<?> type) {}
    private final Map<Object, Map<Integer, PacketSpec>> cache = new IdentityHashMap<>();

    Optional<Inspection> inspect(Object encoder, ByteBuf encoded) throws ReflectiveOperationException {
        if (encoder == null || encoded == null || !encoded.isReadable()) return Optional.empty();
        Object registry = readField(encoder, "registry"); if (registry == null) return Optional.empty();
        ByteBuf input = encoded.duplicate(); int packetId = readVarInt(input); PacketSpec spec = spec(registry, packetId);
        if (spec.role == Role.OTHER) return Optional.empty();
        Object packet = createPacket(registry, packetId);
        if (packet == null && spec.type != null) packet = instantiate(spec.type);
        if (packet == null) return spec.role == Role.PLAYER_INFO ? Optional.of(new Inspection(spec.role, null)) : Optional.empty();
        decode(packet, input, encoder, registry); return Optional.of(new Inspection(spec.role, packet));
    }

    private PacketSpec spec(Object registry, int packetId) throws ReflectiveOperationException {
        Map<Integer, PacketSpec> byId = cache.computeIfAbsent(registry, ignored -> discover(registry));
        PacketSpec known = byId.get(packetId); if (known != null) return known;
        Object packet = createPacket(registry, packetId); PacketSpec spec = packet == null ? new PacketSpec(Role.OTHER, null) : new PacketSpec(role(packet.getClass()), packet.getClass());
        byId.put(packetId, spec); return spec;
    }

    /** Velocity keeps encode-only packets (notably Respawn) in packetClassToId even when createPacket(id) returns null. */
    private static Map<Integer, PacketSpec> discover(Object registry) {
        Map<Integer, PacketSpec> out = new HashMap<>();
        try {
            Object value = readField(registry, "packetClassToId");
            if (value instanceof Map<?, ?> map) for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof Class<?> type) || !(entry.getValue() instanceof Number id)) continue;
                Role role = role(type); if (role != Role.OTHER) out.put(id.intValue(), new PacketSpec(role, type));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        return out;
    }

    private static Role role(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) switch (current.getSimpleName()) {
            case "JoinGamePacket", "ClientboundLoginPacket", "GameJoinS2CPacket" -> { return Role.JOIN_GAME; }
            case "RespawnPacket", "ClientboundRespawnPacket", "PlayerRespawnS2CPacket" -> { return Role.RESPAWN; }
            case "LegacyPlayerListItemPacket", "UpsertPlayerInfoPacket", "RemovePlayerInfoPacket" -> { return Role.PLAYER_INFO; }
        }
        return Role.OTHER;
    }

    private static Object instantiate(Class<?> type) throws ReflectiveOperationException {
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); return constructor.newInstance();
    }

    private static Object createPacket(Object registry, int packetId) throws ReflectiveOperationException {
        Method method = findMethod(registry.getClass(), "createPacket", 1); if (method == null) return null;
        return method.invoke(registry, packetId);
    }

    private static void decode(Object packet, ByteBuf input, Object encoder, Object registry) throws ReflectiveOperationException {
        Object direction = invokeNoArg(encoder, "getDirection"); if (direction == null) direction = readField(encoder, "direction");
        Object version = readField(registry, "version"); if (version == null) version = invokeNoArg(registry, "getVersion");
        if (direction == null || version == null) throw new IllegalStateException("Velocity encoder registry metadata unavailable");
        Method decode = findMethod(packet.getClass(), "decode", 3); if (decode == null) throw new NoSuchMethodException(packet.getClass().getName() + ".decode(ByteBuf, direction, version)");
        decode.invoke(packet, input, direction, version);
    }

    private static Method findMethod(Class<?> type, String name, int parameters) {
        for (Method method : type.getMethods()) if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) for (Method method : current.getDeclaredMethods()) if (method.getName().equals(name) && method.getParameterCount() == parameters) { method.setAccessible(true); return method; }
        return null;
    }
    private static Object invokeNoArg(Object target, String name) throws ReflectiveOperationException { Method method = findMethod(target.getClass(), name, 0); return method == null ? null : method.invoke(target); }
    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); } catch (NoSuchFieldException ignored) {}
        return null;
    }

    static int readVarInt(ByteBuf buf) {
        int value = 0, position = 0;
        while (buf.isReadable()) { byte current = buf.readByte(); value |= (current & 0x7F) << position; if ((current & 0x80) == 0) return value; position += 7; if (position >= 35) throw new IllegalArgumentException("Minecraft VarInt is too large"); }
        throw new IllegalArgumentException("Incomplete Minecraft VarInt");
    }
}
