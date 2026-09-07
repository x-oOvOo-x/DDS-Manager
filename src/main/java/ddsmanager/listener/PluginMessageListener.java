package ddsmanager.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import ddsmanager.DdsManagerPlugin;

public final class PluginMessageListener {
    private final DdsManagerPlugin plugin;
    public PluginMessageListener(DdsManagerPlugin plugin) { this.plugin = plugin; }
    @Subscribe public void onPluginMessage(PluginMessageEvent event) { plugin.minimap().handle(event); }
}
