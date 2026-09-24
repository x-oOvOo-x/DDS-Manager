package ddsmanager.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.player.TabListEntry;
import ddsmanager.config.PluginConfig;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class TabSyncService {
    private final ProxyServer proxy; private final Supplier<PluginConfig> config; private final NetworkPresenceService presence; private final Logger logger;
    private final Map<UUID, Set<UUID>> managed = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Component>> decorated = new ConcurrentHashMap<>();
    public TabSyncService(ProxyServer proxy, Supplier<PluginConfig> config, NetworkPresenceService presence, Logger logger) { this.proxy = proxy; this.config = config; this.presence = presence; this.logger = logger; }

    public void refreshAll() { if (!config.get().features.syncTabList) { clearAll(); return; } proxy.getAllPlayers().forEach(this::refreshViewer); }
    public void refreshViewer(Player viewer) { refreshViewer(viewer, false); }
    public void reapplyViewer(Player viewer) { refreshViewer(viewer, true); }

    private void refreshViewer(Player viewer, boolean forceDisplay) {
        if (!config.get().features.syncTabList) { clearViewer(viewer); return; }
        TabState state = state(viewer); Set<UUID> online = new HashSet<>();
        for (Player subject : proxy.getAllPlayers()) {
            online.add(subject.getUniqueId());
            refreshEntry(viewer, subject, state.tab(), state.managed(), state.decorated(), forceDisplay);
        }
        for (UUID uuid : Set.copyOf(state.managed())) if (!online.contains(uuid)) {
            state.tab().removeEntry(uuid); state.managed().remove(uuid); state.decorated().remove(uuid);
        }
        state.decorated().keySet().removeIf(uuid -> !online.contains(uuid));
    }

    public void refreshViewerSubjects(Player viewer, Collection<UUID> subjects) {
        if (!config.get().features.syncTabList || subjects == null || subjects.isEmpty()) return;
        TabState state = state(viewer);
        for (UUID uuid : new LinkedHashSet<>(subjects)) {
            proxy.getPlayer(uuid).ifPresentOrElse(
                    subject -> refreshEntry(viewer, subject, state.tab(), state.managed(), state.decorated(), false),
                    () -> {
                        if (state.managed().remove(uuid)) state.tab().removeEntry(uuid);
                        state.decorated().remove(uuid);
                    });
        }
    }

    public void refreshSubject(Player subject) {
        if (!config.get().features.syncTabList) return;
        for (Player viewer : proxy.getAllPlayers()) {
            TabState state = state(viewer);
            refreshEntry(viewer, subject, state.tab(), state.managed(), state.decorated(), false);
        }
    }

    public boolean refreshSubjectDisplay(Player subject) {
        if (!config.get().features.syncTabList) return true;
        UUID uuid = subject.getUniqueId(); PluginConfig.Presence p = config.get().presence; boolean selfReady = true;
        Component display = p.showServerInTabName || p.showDimensionInTabName ? presence.tabName(subject) : null;
        for (Player viewer : proxy.getAllPlayers()) {
            TabState state = state(viewer); var existing = state.tab().getEntry(uuid);
            if (existing.isPresent()) { applyDisplay(existing.get(), state.decorated(), uuid, display, false); continue; }
            if (ChatBridgeService.serverName(viewer).equalsIgnoreCase(ChatBridgeService.serverName(subject))) {
                state.decorated().remove(uuid); if (viewer.getUniqueId().equals(uuid)) selfReady = false; continue;
            }
            refreshEntry(viewer, subject, state.tab(), state.managed(), state.decorated(), false);
        }
        return selfReady;
    }

    private TabState state(Player viewer) {
        UUID uuid = viewer.getUniqueId();
        return new TabState(viewer.getTabList(), managed.computeIfAbsent(uuid, ignored -> ConcurrentHashMap.newKeySet()),
                decorated.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>()));
    }

    private void refreshEntry(Player viewer, Player subject, TabList tab, Set<UUID> ours, Map<UUID, Component> styled, boolean forceDisplay) {
        UUID uuid = subject.getUniqueId(); boolean remote = !ChatBridgeService.serverName(viewer).equalsIgnoreCase(ChatBridgeService.serverName(subject));
        PluginConfig.Presence p = config.get().presence;
        Component display = p.showServerInTabName || p.showDimensionInTabName ? presence.tabName(subject) : null;
        var existing = tab.getEntry(uuid); int fallbackLatency = existing.map(TabListEntry::getLatency).orElse(0);
        int latency = displayLatency(subject.getPing(), fallbackLatency);
        if (existing.isPresent()) {
            TabListEntry entry = existing.get();
            if (entry.getLatency() != latency) entry.setLatency(latency);
            if (remote && !entry.isListed()) entry.setListed(true);
            applyDisplay(entry, styled, uuid, display, forceDisplay);
            if (remote) ours.add(uuid); else ours.remove(uuid);
            return;
        }
        if (!remote) { ours.remove(uuid); styled.remove(uuid); return; }
        try {
            TabListEntry.Builder builder = TabListEntry.builder().tabList(tab).profile(subject.getGameProfile()).latency(latency).gameMode(0).listed(true);
            if (display != null) { builder.displayName(display); styled.put(uuid, display); }
            tab.addEntry(builder.build()); ours.add(uuid);
        } catch (RuntimeException e) { logger.debug("Unable to add remote tab entry {} -> {}", subject.getUsername(), viewer.getUsername(), e); }
    }

    static int displayLatency(long ping, int fallback) { if (ping >= 0) return (int) Math.min(Integer.MAX_VALUE, ping); return Math.max(0, fallback); }

    private static void applyDisplay(TabListEntry entry, Map<UUID, Component> styled, UUID uuid, Component next, boolean force) {
        Component previous = styled.get(uuid);
        if (next != null) {
            if (force || entry.getDisplayNameComponent().filter(next::equals).isEmpty()) entry.setDisplayName(next);
            styled.put(uuid, next);
        } else {
            if (previous != null && (force || entry.getDisplayNameComponent().filter(previous::equals).isPresent())) entry.setDisplayName(null);
            styled.remove(uuid);
        }
    }

    public void removeSubject(UUID subject) {
        for (Player viewer : proxy.getAllPlayers()) {
            Set<UUID> ours = managed.get(viewer.getUniqueId()); if (ours != null && ours.remove(subject)) viewer.getTabList().removeEntry(subject);
            Map<UUID, Component> styled = decorated.get(viewer.getUniqueId()); if (styled != null) styled.remove(subject);
        }
    }

    public void clearAll() { proxy.getAllPlayers().forEach(this::clearViewer); }
    public void clearViewer(Player viewer) {
        TabList tab = viewer.getTabList(); Set<UUID> ours = managed.remove(viewer.getUniqueId()); Map<UUID, Component> styled = decorated.remove(viewer.getUniqueId());
        if (styled != null) styled.forEach((uuid, previous) -> {
            if (ours != null && ours.contains(uuid)) return;
            tab.getEntry(uuid).ifPresent(entry -> { if (entry.getDisplayNameComponent().filter(previous::equals).isPresent()) entry.setDisplayName(null); });
        });
        if (ours != null) ours.forEach(tab::removeEntry);
    }

    private record TabState(TabList tab, Set<UUID> managed, Map<UUID, Component> decorated) {}
}
