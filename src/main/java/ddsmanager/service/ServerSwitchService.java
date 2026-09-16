package ddsmanager.service;

import com.velocitypowered.api.proxy.Player;

public final class ServerSwitchService {
    public String command(String serverName) {
        if (serverName == null || serverName.isBlank()) throw new IllegalArgumentException("serverName");
        return "/server " + serverName.trim();
    }

    public void clear(Player player) {}
}
