package ddsmanager.service;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import ddsmanager.config.PluginConfig;
import ddsmanager.data.MinimapWorldIdRegistry;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

public final class MinimapWorldSyncService {
    public static final MinecraftChannelIdentifier VOXEL = MinecraftChannelIdentifier.create("worldinfo", "world_id");
    public static final MinecraftChannelIdentifier XAERO_MAP = MinecraftChannelIdentifier.create("xaeroworldmap", "main");
    public static final MinecraftChannelIdentifier XAERO_MINIMAP = MinecraftChannelIdentifier.create("xaerominimap", "main");

    private final ProxyServer proxy;
    private final Supplier<PluginConfig> config;
    private final MinimapWorldIdRegistry ids;

    public MinimapWorldSyncService(ProxyServer proxy, Supplier<PluginConfig> config, MinimapWorldIdRegistry ids) {
        this.proxy = proxy; this.config = config; this.ids = ids;
    }

    public void registerChannels() { proxy.getChannelRegistrar().register(VOXEL, XAERO_MAP, XAERO_MINIMAP); }

    public void sync(Player player, String serverName) {
        if (!config.get().features.minimapWorldSync) return;
        byte[] serverBytes = serverName.getBytes(StandardCharsets.UTF_8);
        var address = proxy.getServer(serverName).map(s -> s.getServerInfo().getAddress()).orElse(null);
        byte[] xaero = ByteBuffer.allocate(5).put((byte) 0).putInt(ids.idFor(serverName, address)).array();
        player.sendPluginMessage(XAERO_MAP, xaero); player.sendPluginMessage(XAERO_MINIMAP, xaero);

        if (serverBytes.length <= 255) {
            ByteBuffer voxel = ByteBuffer.allocate(serverBytes.length + 2);
            voxel.put((byte) 0).put((byte) serverBytes.length).put(serverBytes); player.sendPluginMessage(VOXEL, voxel.array());
        }
    }

    public void handle(PluginMessageEvent event) {
        if (!VOXEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!config.get().features.minimapWorldSync) return;
        if (event.getSource() instanceof Player player) player.getCurrentServer().ifPresent(connection -> sync(player, connection.getServerInfo().getName()));
    }
}
