package ddsmanager.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import ddsmanager.DdsManagerPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.concurrent.TimeUnit;

public final class ServerSwitchListener {
    private final DdsManagerPlugin plugin;

    public ServerSwitchListener(DdsManagerPlugin plugin) { this.plugin = plugin; }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        var connection = player.getCurrentServer().orElse(null);
        String current = connection == null ? "" : connection.getServerInfo().getName();
        String previous = event.getPreviousServer() == null ? "" : event.getPreviousServer().getServerInfo().getName();
        Component previousBadge = previous.isBlank() ? Component.empty() : plugin.presence().switchBadge(player, previous);
        plugin.presence().connected(player, current);
        var profile = plugin.sessions().get(player).orElse(null);
        if (profile == null) return;

        if (!current.isBlank()) {
            if (plugin.config().features.rememberLastServer) profile.lastServer = current;
            profile.lastSeenEpochMs = System.currentTimeMillis();
            plugin.players().markDirty(profile);
            plugin.minimap().sync(player, current);
        }

        boolean switchNotice = plugin.config().features.bridgeJoinLeave && plugin.config().presence.showServerSwitches
                && !previous.isBlank() && !previous.equalsIgnoreCase(current);
        if (plugin.config().features.bridgeJoinLeave && previous.isBlank())
            plugin.chatBridge().broadcastPresence(Component.text(player.getUsername() + " 加入了 [" + current + "]", NamedTextColor.GREEN), player);

        if (plugin.config().features.syncTabList || switchNotice) {
            plugin.proxy().getScheduler().buildTask(plugin, () -> {
                if (connection == null || plugin.proxy().getPlayer(player.getUniqueId()).orElse(null) != player
                        || player.getCurrentServer().orElse(null) != connection) return;
                if (plugin.presence().recoverFromPacketEvents(player, current))
                    plugin.logger().info("DDS Presence recovered: {} @ {} -> {}", player.getUsername(), current, plugin.presence().diagnostic(player));
                if (switchNotice) plugin.chatBridge().broadcastPresence(
                        Component.text(player.getUsername() + " ", NamedTextColor.GRAY).append(previousBadge)
                                .append(Component.text(" ➧ ", NamedTextColor.GRAY)).append(plugin.presence().switchBadge(player, current)), player);
                if (plugin.config().features.syncTabList) { plugin.tabSync().refreshViewer(player); plugin.tabSync().refreshSubject(player); }
            }).delay(250, TimeUnit.MILLISECONDS).schedule();
        }
    }
}
