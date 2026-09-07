package ddsmanager.service;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionType;
import com.velocitypowered.api.proxy.Player;
import ddsmanager.config.PluginConfig;
import ddsmanager.util.ServerLabelFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class NetworkPresenceService {
    private static final long EXPECTATION_TTL_MS = 30_000L;
    private final Supplier<PluginConfig> config;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Expectation> expectedServers = new ConcurrentHashMap<>();

    public NetworkPresenceService(Supplier<PluginConfig> config) { this.config = config; }
    public void expectServer(Player player, String server) { if (server != null && !server.isBlank()) expectedServers.put(player.getUniqueId(), new Expectation(server, System.currentTimeMillis() + EXPECTATION_TTL_MS)); }
    public Optional<String> packetServer(Player player, boolean joinGame) { return resolvePacketServer(ChatBridgeService.serverName(player), currentExpectation(player.getUniqueId()).map(Expectation::server).orElse(null), joinGame); }
    static Optional<String> resolvePacketServer(String current, String expected, boolean joinGame) {
        String c = current == null ? "" : current.trim(), e = expected == null ? "" : expected.trim();
        if (joinGame && !e.isEmpty()) return Optional.of(e); if (!e.isEmpty() && !e.equalsIgnoreCase(c)) return Optional.empty(); return c.isEmpty() ? Optional.empty() : Optional.of(c);
    }
    public void connected(Player player, String server) {
        if (server == null || server.isBlank()) return; String value = server.trim();
        expectedServers.computeIfPresent(player.getUniqueId(), (ignored, e) -> e.server().equalsIgnoreCase(value) ? null : e);
    }
    public boolean update(Player player, String server, String... dimensionSignals) {
        if (server == null || server.isBlank()) return false;
        Detection d = Dimension.detect(dimensionSignals); State state = new State(server, d.dimension(), d.source()); UUID uuid = player.getUniqueId();
        while (true) { State old = states.get(uuid); if (state.equals(old)) return false; if (old == null) { if (states.putIfAbsent(uuid, state) == null) return true; } else if (states.replace(uuid, old, state)) return true; }
    }
    public boolean update(Player player, String server, DimensionType type, String worldName) { return update(player, server, dimensionSignals(type, worldName)); }
    public boolean recoverFromPacketEvents(Player player, String server) {
        try { var user = PacketEvents.getAPI().getPlayerManager().getUser(player); if (user == null) return false; DimensionType type = user.getDimensionType(); return type != null && update(player, server, type, ""); }
        catch (RuntimeException | LinkageError ignored) { return false; }
    }
    public String diagnostic(Player player) { State s = currentState(player); return s == null ? "未捕获维度数据" : s.dimension().displayName + " (" + s.source() + ")"; }
    public void clear(Player player) { UUID uuid = player.getUniqueId(); states.remove(uuid); expectedServers.remove(uuid); }

    public Component tabName(Player player) {
        PluginConfig.Presence p = config.get().presence; String server = ChatBridgeService.serverName(player); State state = currentState(player);
        Dimension dimension = state == null ? Dimension.UNKNOWN : state.dimension(); String source = state == null || state.source().isBlank() ? "等待 Join Game / Respawn 数据" : state.source();
        Component out = Component.empty();
        if (p.showDimensionInTabName) out = out.append(dimensionSymbol(dimension).hoverEvent(HoverEvent.showText(Component.text("维度: " + dimension.displayName + "\n标识: " + source, NamedTextColor.GRAY)))).append(Component.space());
        if (p.showServerInTabName) out = out.append(serverLabel(server).hoverEvent(HoverEvent.showText(Component.text("服务器: " + server, NamedTextColor.GRAY))));
        return out.append(Component.text(player.getUsername(), NamedTextColor.WHITE));
    }

    public Component serverBadge(Player player, String server, String switchCommand) {
        State state = states.get(player.getUniqueId()); Dimension dimension = state != null && state.server().equalsIgnoreCase(server) ? state.dimension() : Dimension.UNKNOWN;
        Component badge = serverLabel(server).hoverEvent(HoverEvent.showText(Component.text(server + "\n维度: " + dimension.displayName + (switchCommand == null ? "" : "\n点击切换服务器"), NamedTextColor.GRAY)));
        return switchCommand == null ? badge : badge.clickEvent(ClickEvent.runCommand(switchCommand));
    }
    public Component switchBadge(Player player, String server) {
        State state = states.get(player.getUniqueId()); Dimension dimension = state != null && state.server().equalsIgnoreCase(server) ? state.dimension() : Dimension.UNKNOWN;
        return Component.text(ServerLabelFormatter.plainText(config.get().presence.serverLabel(server)), NamedTextColor.GRAY).hoverEvent(HoverEvent.showText(Component.text("服务器: " + server + "\n维度: " + dimension.displayName, NamedTextColor.GRAY)));
    }
    public Component chatBadge(Player player, String switchCommand) { return serverBadge(player, ChatBridgeService.serverName(player), switchCommand).append(Component.space()); }
    private Component serverLabel(String server) { return ServerLabelFormatter.render(config.get().presence.serverLabel(server), NamedTextColor.GRAY); }
    private Component dimensionSymbol(Dimension d) { return Component.text(config.get().presence.dimensionSymbol, d.color); }
    private State currentState(Player player) { State s = states.get(player.getUniqueId()); return s == null || !s.server().equalsIgnoreCase(ChatBridgeService.serverName(player)) ? null : s; }
    private Optional<Expectation> currentExpectation(UUID uuid) {
        Expectation e = expectedServers.get(uuid); if (e == null) return Optional.empty(); if (e.expiresAtEpochMs() >= System.currentTimeMillis()) return Optional.of(e); expectedServers.remove(uuid, e); return Optional.empty();
    }
    static Dimension classify(String... signals) { return Dimension.detect(signals).dimension(); }
    private static String[] dimensionSignals(DimensionType type, String worldName) { return new String[]{safeSignal(() -> type == null ? null : type.getName()), safeSignal(() -> type == null ? null : type.getEffectsLocation()), safeSignal(() -> type == null ? null : type.getCardinalLight().getCodecName()), safeSignal(() -> type == null ? null : type.getSkybox().getCodecName()), worldName}; }
    private static String safeSignal(Supplier<?> signal) { try { Object v = signal.get(); return v == null ? "" : v.toString(); } catch (RuntimeException | LinkageError ignored) { return ""; } }

    private record State(String server, Dimension dimension, String source) {}
    private record Expectation(String server, long expiresAtEpochMs) {}
    private record Detection(Dimension dimension, String source) {}

    enum Dimension {
        OVERWORLD("主世界", NamedTextColor.GREEN), NETHER("下界", NamedTextColor.RED), END("末地", NamedTextColor.LIGHT_PURPLE), UNKNOWN("未知", NamedTextColor.GRAY);
        private final String displayName; private final NamedTextColor color;
        Dimension(String displayName, NamedTextColor color) { this.displayName = displayName; this.color = color; }
        private static Detection detect(String... signals) {
            String fallback = "";
            if (signals != null) for (String raw : signals) {
                if (raw == null || raw.isBlank()) continue; String value = raw.trim(); if (fallback.isEmpty()) fallback = value;
                String n = value.toLowerCase(Locale.ROOT); int colon = n.indexOf(':'); String path = colon >= 0 ? n.substring(colon + 1) : n;
                Dimension d = switch (path) { case "overworld", "overworld_caves" -> OVERWORLD; case "nether", "the_nether" -> NETHER; case "end", "the_end" -> END; default -> UNKNOWN; };
                if (d != UNKNOWN) return new Detection(d, value);
            }
            return new Detection(UNKNOWN, fallback);
        }
    }
}
