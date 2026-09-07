package ddsmanager.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import ddsmanager.DdsManagerPlugin;
import ddsmanager.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class ConnectionListener {
    private final DdsManagerPlugin plugin;
    public ConnectionListener(DdsManagerPlugin plugin) { this.plugin = plugin; }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        if (!player.isOnlineMode()) { player.disconnect(Messages.error("DDS Manager UUID 白名单要求通过 Velocity 正版身份验证。")); return; }
        var profile = plugin.players().loadForPlayer(player, plugin.config().chat.defaultChannel); plugin.sessions().bind(player, profile);
        if (plugin.config().features.whitelist) {
            boolean hasAnyAccessibleServer = plugin.proxy().getAllServers().stream().anyMatch(server -> plugin.access().canAccess(player, profile, server.getServerInfo().getName()));
            if (!hasAnyAccessibleServer) player.disconnect(Messages.error("你没有任何可访问的服务器。"));
        }
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer(); var profile = plugin.sessions().get(player).orElse(null); if (profile == null) return;
        RegisteredServer initial = event.getInitialServer().orElse(null);
        if (plugin.config().routing.respectInitialServer && initial != null && plugin.access().canAccess(player, profile, initial.getServerInfo().getName())) return;
        if (plugin.config().features.rememberLastServer && profile.lastServer != null && !profile.lastServer.isBlank()) {
            var remembered = plugin.proxy().getServer(profile.lastServer).orElse(null); if (remembered != null && plugin.access().canAccess(player, profile, remembered.getServerInfo().getName())) { event.setInitialServer(remembered); return; }
        }
        String fallback = plugin.config().routing.fallbackServer;
        if (fallback != null && !fallback.isBlank()) {
            var fallbackServer = plugin.proxy().getServer(fallback).orElse(null); if (fallbackServer != null && plugin.access().canAccess(player, profile, fallbackServer.getServerInfo().getName())) { event.setInitialServer(fallbackServer); return; }
        }
        plugin.proxy().getAllServers().stream().filter(server -> plugin.access().canAccess(player, profile, server.getServerInfo().getName())).findFirst().ifPresent(event::setInitialServer);
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) return; Player player = event.getPlayer(); var profile = plugin.sessions().get(player).orElse(null);
        if (profile == null) { event.setResult(ServerPreConnectEvent.ServerResult.denied()); return; }
        var target = event.getResult().getServer().orElse(event.getOriginalServer()); String targetName = target.getServerInfo().getName(); var decision = plugin.access().evaluate(player, profile, targetName);
        if (!decision.allowed()) { event.setResult(ServerPreConnectEvent.ServerResult.denied()); player.sendMessage(Messages.error("你没有访问服务器 " + targetName + " 的权限。")); return; }
        plugin.presence().expectServer(player, targetName);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        if (plugin.proxy().getPlayer(player.getUniqueId()).filter(current -> current != player).isPresent()) return;
        plugin.sessions().remove(player).ifPresent(profile -> {
            profile.lastSeenEpochMs = System.currentTimeMillis(); plugin.players().saveNow(profile);
            player.getCurrentServer().ifPresent(connection -> { if (plugin.config().features.bridgeJoinLeave) plugin.chatBridge().broadcastPresence(Component.text(player.getUsername() + " 离开了 [" + connection.getServerInfo().getName() + "]", NamedTextColor.YELLOW), player); });
        });
        plugin.presence().clear(player); plugin.switcher().clear(player); plugin.tabSync().removeSubject(player.getUniqueId()); plugin.tabSync().clearViewer(player);
    }
}
