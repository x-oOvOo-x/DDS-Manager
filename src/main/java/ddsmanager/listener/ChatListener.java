package ddsmanager.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import ddsmanager.DdsManagerPlugin;

public final class ChatListener {
    private final DdsManagerPlugin plugin;
    public ChatListener(DdsManagerPlugin plugin) { this.plugin = plugin; }

    @Subscribe
    public void onChat(PlayerChatEvent event) {
        var player = event.getPlayer();
        var profile = plugin.sessions().get(player).orElse(null);
        if (profile == null) return;
        String server = player.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("proxy");
        if (plugin.config().features.chatLog) {
            plugin.chatLog().append(server, player.getUsername(), profile.channel, event.getMessage());
        }
        plugin.chatBridge().bridge(player, event.getMessage());
        // 不取消、不修改原 PlayerChatEvent，避免破坏当前子服的正常/签名聊天链路。
    }
}
