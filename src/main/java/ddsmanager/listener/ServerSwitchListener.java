package ddsmanager.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import ddsmanager.DdsManagerPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public final class ServerSwitchListener {
    private final DdsManagerPlugin plugin;

    public ServerSwitchListener(DdsManagerPlugin plugin) { this.plugin = plugin; }

    @Subscribe public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer(); plugin.protocol().attach(player);
        var connection = player.getCurrentServer().orElse(null);
        String current = connection == null ? "" : connection.getServerInfo().getName();
        String previous = event.getPreviousServer() == null ? "" : event.getPreviousServer().getServerInfo().getName();
        Component previousBadge = previous.isBlank() ? Component.empty() : plugin.presence().switchBadge(previous, plugin.switcher().command(previous), NamedTextColor.GRAY);
        plugin.presence().connected(player, current); plugin.protocol().settleTabViewer(player);
        if (plugin.config().features.syncTabList) { plugin.tabSync().refreshViewer(player); plugin.tabSync().refreshSubject(player); }
        var profile = plugin.sessions().get(player).orElse(null); if (profile == null) return;

        if (!current.isBlank()) {
            if (plugin.config().features.rememberLastServer) profile.lastServer = current;
            profile.lastSeenEpochMs = System.currentTimeMillis(); plugin.players().markDirty(profile); plugin.minimap().sync(player, current);
        }

        boolean bridge = plugin.config().features.bridgeJoinLeave;
        if (bridge && previous.isBlank()) plugin.chatBridge().broadcastPresence(Component.text(player.getUsername() + " 连接至 ", NamedTextColor.YELLOW)
                .append(plugin.presence().switchBadge(current, plugin.switcher().command(current), NamedTextColor.YELLOW)), player);
        else if (bridge && plugin.config().presence.showServerSwitches && !previous.equalsIgnoreCase(current))
            plugin.chatBridge().broadcastPresence(Component.text(player.getUsername() + " ", NamedTextColor.GRAY).append(previousBadge)
                    .append(Component.text(" ▸ ", NamedTextColor.GRAY)).append(plugin.presence().switchBadge(current, plugin.switcher().command(current), NamedTextColor.GRAY)), player);
    }
}
