package ddsmanager.service;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import ddsmanager.config.PluginConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.function.Supplier;

public final class ChatBridgeService {
    private final ProxyServer proxy;
    private final PlayerSessionService sessions;
    private final Supplier<PluginConfig> config;
    private final NetworkPresenceService presence;
    private final ServerSwitchService switcher;

    public ChatBridgeService(ProxyServer proxy, PlayerSessionService sessions, Supplier<PluginConfig> config,
                             NetworkPresenceService presence, ServerSwitchService switcher) {
        this.proxy = proxy; this.sessions = sessions; this.config = config; this.presence = presence; this.switcher = switcher;
    }

    public void bridge(Player sender, String message) {
        if (!config.get().features.bridgeChat) return;
        var profile = sessions.get(sender).orElse(null);
        if (profile == null || !"global".equalsIgnoreCase(profile.channel)) return;

        String server = serverName(sender);
        Component component = Component.text()
                .append(presence.chatBadge(sender, switcher.command(server)))
                .append(Component.text("<" + sender.getUsername() + "> ", NamedTextColor.GRAY))
                .append(Component.text(message, NamedTextColor.GRAY)).build();

        for (Player recipient : proxy.getAllPlayers()) {
            if (recipient.equals(sender) || sameServer(sender, recipient)) continue;
            var target = sessions.get(recipient).orElse(null);
            if (target != null && "global".equalsIgnoreCase(target.channel)) recipient.sendMessage(component);
        }
    }

    public void broadcastPresence(Component message, Player excluded) {
        for (Player recipient : proxy.getAllPlayers())
            if (excluded == null || !recipient.equals(excluded)) recipient.sendMessage(message);
    }

    public static String serverName(Player player) {
        return player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("proxy");
    }

    private static boolean sameServer(Player a, Player b) { return serverName(a).equalsIgnoreCase(serverName(b)); }
}
