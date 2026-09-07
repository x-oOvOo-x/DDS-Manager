package ddsmanager.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.chat.message.*;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionType;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.velocitypowered.api.proxy.Player;
import ddsmanager.DdsManagerPlugin;
import ddsmanager.service.ChatBridgeService;
import net.kyori.adventure.text.Component;

public final class PacketPresenceListener extends PacketListenerAbstract {
    private final DdsManagerPlugin plugin;
    public PacketPresenceListener(DdsManagerPlugin plugin) { super(PacketListenerPriority.NORMAL); this.plugin = plugin; }

    @Override public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.CHAT_MESSAGE) { decorateChat(event); return; }
        boolean joinGame = event.getPacketType() == PacketType.Play.Server.JOIN_GAME;
        if (!joinGame && event.getPacketType() != PacketType.Play.Server.RESPAWN) return;
        Object target = event.getPlayer(); Player player = target instanceof Player p ? p : event.getUser().getUUID() == null ? null : plugin.proxy().getPlayer(event.getUser().getUUID()).orElse(null);
        if (player == null) return;

        String world; DimensionType dimensionType;
        if (joinGame) { WrapperPlayServerJoinGame packet = new WrapperPlayServerJoinGame(event); world = packet.getWorldName(); dimensionType = safeDimensionType(packet::getDimensionType); }
        else { WrapperPlayServerRespawn packet = new WrapperPlayServerRespawn(event); world = packet.getWorldName().orElse(""); dimensionType = safeDimensionType(packet::getDimensionType); }
        String server = plugin.presence().packetServer(player, joinGame).orElse(""); if (server.isBlank()) return;
        DimensionType typeSnapshot = dimensionType; String worldSnapshot = world;
        event.getTasksAfterSend().add(() -> plugin.proxy().getScheduler().buildTask(plugin, () -> plugin.proxy().getPlayer(player.getUniqueId()).ifPresent(current -> {
            if (current != player || !plugin.presence().packetServer(current, joinGame).filter(server::equalsIgnoreCase).isPresent()) return;
            if (worldSnapshot.isBlank() && typeSnapshot == null || !plugin.presence().update(current, server, typeSnapshot, worldSnapshot)) return;
            plugin.logger().info("DDS Presence: {} @ {} -> {}", current.getUsername(), server, plugin.presence().diagnostic(current));
            if (plugin.config().features.syncTabList) plugin.tabSync().refreshSubject(current);
        })).schedule());
    }

    private void decorateChat(PacketSendEvent event) {
        if (!plugin.config().features.bridgeChat || !plugin.config().chat.showServerPrefix) return;
        try {
            ChatMessage message = new WrapperPlayServerChatMessage(event).getMessage();
            if (!(message instanceof ChatMessage_v1_16 identified) || identified.getSenderUUID() == null) return;
            Player sender = plugin.proxy().getPlayer(identified.getSenderUUID()).orElse(null);
            if (sender == null || plugin.sessions().get(sender).filter(p -> "global".equalsIgnoreCase(p.channel)).isEmpty()) return;
            String server = ChatBridgeService.serverName(sender); Component badge = plugin.presence().chatBadge(sender, plugin.switcher().command(server));
            if (message instanceof ChatMessage_v1_19_3 modern) { var f = modern.getChatFormatting(); if (f != null) f.setName(badge.append(f.getName())); }
            else if (message instanceof ChatMessage_v1_19_1 modern) { var f = modern.getChatFormatting(); if (f != null) f.setName(badge.append(f.getName())); }
            else if (message instanceof ChatMessage_v1_19 modern) modern.setSenderDisplayName(badge.append(modern.getSenderDisplayName()));
            else identified.setChatContent(badge.append(identified.getChatContent()));
        } catch (RuntimeException | LinkageError e) { plugin.logger().debug("Unable to decorate native chat with DDS server badge", e); }
    }

    private static DimensionType safeDimensionType(java.util.function.Supplier<DimensionType> supplier) { try { return supplier.get(); } catch (RuntimeException | LinkageError ignored) { return null; } }
}
