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
    public void refreshViewer(Player viewer) {
        if (!config.get().features.syncTabList) { clearViewer(viewer); return; }
        TabList tab = viewer.getTabList(); Set<UUID> ours = managed.computeIfAbsent(viewer.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
        Map<UUID, Component> styled = decorated.computeIfAbsent(viewer.getUniqueId(), ignored -> new ConcurrentHashMap<>()); Set<UUID> online = new HashSet<>();
        for (Player subject : proxy.getAllPlayers()) { online.add(subject.getUniqueId()); refreshEntry(viewer, subject, tab, ours, styled); }
        for (UUID uuid : Set.copyOf(ours)) if (!online.contains(uuid)) { tab.removeEntry(uuid); ours.remove(uuid); styled.remove(uuid); }
        styled.keySet().removeIf(uuid -> !online.contains(uuid));
    }
    public void refreshSubject(Player subject) {
        if (!config.get().features.syncTabList) return;
        for (Player viewer : proxy.getAllPlayers()) {
            TabList tab = viewer.getTabList(); Set<UUID> ours = managed.computeIfAbsent(viewer.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
            Map<UUID, Component> styled = decorated.computeIfAbsent(viewer.getUniqueId(), ignored -> new ConcurrentHashMap<>()); refreshEntry(viewer, subject, tab, ours, styled);
        }
    }

    private void refreshEntry(Player viewer, Player subject, TabList tab, Set<UUID> ours, Map<UUID, Component> styled) {
        UUID uuid = subject.getUniqueId(); boolean remote = !ChatBridgeService.serverName(viewer).equalsIgnoreCase(ChatBridgeService.serverName(subject));
        PluginConfig.Presence p = config.get().presence;
        Component display = p.showServerInTabName || p.showDimensionInTabName ? presence.tabName(subject) : null;
        int latency = (int) Math.min(Integer.MAX_VALUE, Math.max(-1L, subject.getPing())); var existing = tab.getEntry(uuid);
        if (existing.isPresent()) { if (existing.get().getLatency() != latency) existing.get().setLatency(latency); applyDisplay(existing.get(), styled, uuid, display); if (remote) ours.add(uuid); else ours.remove(uuid); return; }
        if (!remote) { ours.remove(uuid); styled.remove(uuid); return; }
        try {
            TabListEntry.Builder builder = TabListEntry.builder().tabList(tab).profile(subject.getGameProfile()).latency(latency).gameMode(0).listed(true);
            if (display != null) { builder.displayName(display); styled.put(uuid, display); }
            tab.addEntry(builder.build()); ours.add(uuid);
        } catch (RuntimeException e) { logger.debug("Unable to add remote tab entry {} -> {}", subject.getUsername(), viewer.getUsername(), e); }
    }
    private static void applyDisplay(TabListEntry entry, Map<UUID, Component> styled, UUID uuid, Component next) {
        Component previous = styled.get(uuid);
        if (next != null) { if (entry.getDisplayNameComponent().filter(next::equals).isEmpty()) entry.setDisplayName(next); styled.put(uuid, next); }
        else { if (previous != null && entry.getDisplayNameComponent().filter(previous::equals).isPresent()) entry.setDisplayName(null); styled.remove(uuid); }
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
        if (styled != null) styled.forEach((uuid, previous) -> { if (ours != null && ours.contains(uuid)) return; tab.getEntry(uuid).ifPresent(entry -> { if (entry.getDisplayNameComponent().filter(previous::equals).isPresent()) entry.setDisplayName(null); }); });
        if (ours != null) ours.forEach(tab::removeEntry);
    }
}
