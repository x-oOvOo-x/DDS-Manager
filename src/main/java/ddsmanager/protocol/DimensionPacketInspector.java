package ddsmanager.protocol;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/** Extracts dimension signals from Velocity-decoded JoinGame/Respawn models without version tables. */
public final class DimensionPacketInspector {
    public enum Kind { JOIN_GAME, RESPAWN }
    public record Capture(Kind kind, String registryIdentifier, String levelName, String source) {
        public String[] signals() { return new String[]{registryIdentifier, levelName}; }
    }

    private DimensionPacketInspector() {}

    public static Optional<Capture> inspect(Object packet) {
        if (packet == null) return Optional.empty(); Kind kind = kind(packet); if (kind == null) return Optional.empty();
        Object info = first(readAny(packet, "getDimensionInfo", "dimensionInfo"), readAny(packet, "getCommonPlayerSpawnInfo", "commonPlayerSpawnInfo"));
        String registry = firstText(readAny(info, "getRegistryIdentifier", "registryIdentifier"), readAny(info, "dimensionType", "dimensionTypeId", "getDimensionType"));
        String level = firstText(readAny(info, "getLevelName", "levelName"), readAny(info, "dimension", "dimensionName", "getDimension"), readAny(packet, "getLevelName", "levelName", "dimensionName"));
        if (registry.isBlank() && level.isBlank()) level = legacyDimension(readAny(packet, "getDimension", "dimension"));
        return Optional.of(new Capture(kind, registry, level, packet.getClass().getName()));
    }

    private static Kind kind(Object packet) {
        for (Class<?> type = packet.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getSimpleName();
            if (name.equals("JoinGamePacket") || name.equals("ClientboundLoginPacket") || name.equals("GameJoinS2CPacket")) return Kind.JOIN_GAME;
            if (name.equals("RespawnPacket") || name.equals("ClientboundRespawnPacket") || name.equals("PlayerRespawnS2CPacket")) return Kind.RESPAWN;
        }
        return null;
    }

    private static Object readAny(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) {
            try { Method method = target.getClass().getMethod(name); if (method.getParameterCount() == 0) return method.invoke(target); }
            catch (ReflectiveOperationException | RuntimeException ignored) {}
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
        }
        return null;
    }
    private static Object first(Object... values) { for (Object value : values) if (value != null) return value; return null; }
    private static String firstText(Object... values) { for (Object value : values) { String text = text(value); if (!text.isBlank()) return text; } return ""; }
    private static String legacyDimension(Object value) {
        if (!(value instanceof Number number)) return text(value);
        return switch (number.intValue()) { case -1 -> "minecraft:the_nether"; case 0 -> "minecraft:overworld"; case 1 -> "minecraft:the_end"; default -> "legacy:" + number.intValue(); };
    }
    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
}
