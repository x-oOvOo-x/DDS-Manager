package ddsmanager.service;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import ddsmanager.config.PluginConfig;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.zip.CRC32;

public final class MinimapWorldSyncService {
    public static final MinecraftChannelIdentifier VOXEL = MinecraftChannelIdentifier.create("worldinfo", "world_id");
    public static final MinecraftChannelIdentifier XAERO_MAP = MinecraftChannelIdentifier.create("xaeroworldmap", "main");
    public static final MinecraftChannelIdentifier XAERO_MINIMAP = MinecraftChannelIdentifier.create("xaerominimap", "main");

    private final ProxyServer proxy;
    private final Supplier<PluginConfig> config;

    public MinimapWorldSyncService(ProxyServer proxy, Supplier<PluginConfig> config) {
        this.proxy = proxy;
        this.config = config;
    }

    public void registerChannels() {
        proxy.getChannelRegistrar().register(VOXEL, XAERO_MAP, XAERO_MINIMAP);
    }

    public void sync(Player player, String serverName) {
        if (!config.get().features.minimapWorldSync) return;
        byte[] serverBytes = serverName.getBytes(StandardCharsets.UTF_8);

        CRC32 crc32 = new CRC32();
        crc32.update(serverBytes);
        byte[] xaero = ByteBuffer.allocate(5).put((byte) 0).putInt((int) crc32.getValue()).array();
        player.sendPluginMessage(XAERO_MAP, xaero);
        player.sendPluginMessage(XAERO_MINIMAP, xaero);

        if (serverBytes.length <= 255) {
            ByteBuffer voxel = ByteBuffer.allocate(serverBytes.length + 2);
            voxel.put((byte) 0).put((byte) serverBytes.length).put(serverBytes);
            player.sendPluginMessage(VOXEL, voxel.array());
        }
    }

    public void handle(PluginMessageEvent event) {
        if (!VOXEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!config.get().features.minimapWorldSync) return;
        if (event.getSource() instanceof Player player) {
            player.getCurrentServer().ifPresent(connection -> sync(player, connection.getServerInfo().getName()));
        }
    }
}
