package ddsmanager.service;

import com.velocitypowered.api.proxy.Player;
import ddsmanager.config.PluginConfig;
import ddsmanager.i18n.LanguageService;
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
    private final LanguageService language;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Expectation> expectedServers = new ConcurrentHashMap<>();

    public NetworkPresenceService(Supplier<PluginConfig> config, LanguageService language) { this.config = config; this.language = language; }
    public void expectServer(Player player, String server) {
        if (server == null || server.isBlank()) return;
        UUID uuid = player.getUniqueId();
        expectedServers.put(uuid, new Expectation(server.trim(), System.currentTimeMillis() + EXPECTATION_TTL_MS, states.get(uuid)));
    }
    public Optional<String> packetServer(Player player, boolean joinGame) { return resolvePacketServer(ChatBridgeService.serverName(player), currentExpectation(player.getUniqueId()).map(Expectation::server).orElse(null), joinGame); }
    static Optional<String> resolvePacketServer(String current, String expected, boolean joinGame) {
        String c = current == null ? "" : current.trim(), e = expected == null ? "" : expected.trim();
        if (joinGame && !e.isEmpty()) return Optional.of(e); if (!e.isEmpty() && !e.equalsIgnoreCase(c)) return Optional.empty(); return c.isEmpty() ? Optional.empty() : Optional.of(c);
    }
    public void connected(Player player, String server) {
        if (server == null || server.isBlank()) return;
        UUID uuid = player.getUniqueId(); String value = server.trim(); Expectation expected = expectedServers.remove(uuid);
        if (expected != null && !expected.server().equalsIgnoreCase(value)) restorePreviousState(uuid, expected);
    }
    public void connectionFailed(Player player, String server) {
        if (server == null || server.isBlank()) return;
        UUID uuid = player.getUniqueId(); String value = server.trim();
        expectedServers.computeIfPresent(uuid, (ignored, expected) -> {
            if (!expected.server().equalsIgnoreCase(value)) return expected;
            restorePreviousState(uuid, expected); return null;
        });
    }
    public boolean update(Player player, String server, String... dimensionSignals) {
        if (server == null || server.isBlank()) return false;
        Detection d = Dimension.detect(dimensionSignals); State state = new State(server.trim(), d.dimension(), d.source()); UUID uuid = player.getUniqueId();
        while (true) { State old = states.get(uuid); if (state.equals(old)) return false; if (old == null) { if (states.putIfAbsent(uuid, state) == null) return true; } else if (states.replace(uuid, old, state)) return true; }
    }
    public String diagnostic(Player player) { return diagnostic(player, ChatBridgeService.serverName(player)); }
    public String diagnostic(Player player, String server) { State s = stateForServer(player, server); return s == null ? "No dimension data" : s.dimension().key + " (" + s.source() + ")"; }
    public void clear(Player player) { UUID uuid = player.getUniqueId(); states.remove(uuid); expectedServers.remove(uuid); }

    public Component tabName(Player player, Player viewer) {
        PluginConfig.Presence p = config.get().presence; String server = ChatBridgeService.serverName(player); State state = stateForServer(player, server);
        Dimension dimension = state == null ? Dimension.UNKNOWN : state.dimension(); String source = state == null || state.source().isBlank() ? language.format(viewer, "presence.dimension-waiting") : state.source();
        Component out = Component.empty();
        if (p.showDimensionInTabName) out = out.append(dimensionSymbol(dimension).hoverEvent(HoverEvent.showText(language.styled(viewer, "presence.tab-dimension", "dimension", language.format(viewer, dimension.key), "signal", source)))).append(Component.space());
        if (p.showServerInTabName) out = out.append(serverLabel(server).hoverEvent(HoverEvent.showText(language.styled(viewer, "presence.tab-server", "server", server))));
        return out.append(Component.text(player.getUsername(), NamedTextColor.WHITE));
    }

    public Component serverBadge(Player player, Player viewer, String server, String switchCommand) {
        State state = stateForServer(player, server); Dimension dimension = state == null ? Dimension.UNKNOWN : state.dimension();
        Component badge = serverLabel(server).hoverEvent(HoverEvent.showText(language.styled(viewer, "presence.server-hover", "server", server, "dimension", language.format(viewer, dimension.key), "click", switchCommand == null ? "" : language.format(viewer, "presence.click"))));
        return switchCommand == null ? badge : badge.clickEvent(ClickEvent.runCommand(switchCommand));
    }
    public Component switchBadge(Player player, String server) { return switchBadge(player, server, null, NamedTextColor.GRAY); }
    public Component switchBadge(Player viewer, String server, String switchCommand, NamedTextColor color) {
        Component badge = ServerLabelFormatter.render(config.get().presence.serverLabel(server), color)
                .hoverEvent(HoverEvent.showText(language.styled(viewer, "presence.switch-hover", "server", server, "click", switchCommand == null ? "" : language.format(viewer, "presence.click"))));
        return switchCommand == null ? badge : badge.clickEvent(ClickEvent.runCommand(switchCommand));
    }
    public Component chatBadge(Player player, Player viewer, String switchCommand) { return serverBadge(player, viewer, ChatBridgeService.serverName(player), switchCommand).append(Component.space()); }
    private Component serverLabel(String server) { return ServerLabelFormatter.render(config.get().presence.serverLabel(server), NamedTextColor.GRAY); }
    private Component dimensionSymbol(Dimension d) { return Component.text(config.get().presence.dimensionSymbol, d.color); }
    private State currentState(Player player) { return stateForServer(player, ChatBridgeService.serverName(player)); }
    private State stateForServer(Player player, String server) {
        String value = server == null ? "" : server.trim(); State s = states.get(player.getUniqueId());
        return s == null || value.isEmpty() || !s.server().equalsIgnoreCase(value) ? null : s;
    }
    private Optional<Expectation> currentExpectation(UUID uuid) {
        Expectation expected = expectedServers.get(uuid); if (expected == null) return Optional.empty();
        if (expected.expiresAtEpochMs() >= System.currentTimeMillis()) return Optional.of(expected);
        if (expectedServers.remove(uuid, expected)) restorePreviousState(uuid, expected);
        return Optional.empty();
    }
    private void restorePreviousState(UUID uuid, Expectation expected) {
        State current = states.get(uuid);
        if (current == null || !current.server().equalsIgnoreCase(expected.server())) return;
        State previous = expected.previousState();
        if (previous == null) states.remove(uuid, current); else states.replace(uuid, current, previous);
    }
    static Dimension classify(String... signals) { return Dimension.detect(signals).dimension(); }

    private record State(String server, Dimension dimension, String source) {}
    private record Expectation(String server, long expiresAtEpochMs, State previousState) {}
    private record Detection(Dimension dimension, String source) {}

    enum Dimension {
        OVERWORLD("presence.dimension.overworld", NamedTextColor.GREEN), NETHER("presence.dimension.nether", NamedTextColor.RED), END("presence.dimension.end", NamedTextColor.LIGHT_PURPLE), UNKNOWN("presence.dimension.unknown", NamedTextColor.GRAY);
        private final String key; private final NamedTextColor color;
        Dimension(String key, NamedTextColor color) { this.key = key; this.color = color; }
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
