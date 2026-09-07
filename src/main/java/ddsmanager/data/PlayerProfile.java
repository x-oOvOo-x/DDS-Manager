package ddsmanager.data;

import ddsmanager.config.PluginConfig;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;

public final class PlayerProfile {
    public static final int SCHEMA_VERSION = 3;
    public int schemaVersion = SCHEMA_VERSION;
    public volatile String username = "", uuid = "", lastServer = "", channel = "global";
    public volatile long lastSeenEpochMs = System.currentTimeMillis();
    public Set<String> accessScopes = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);

    public PlayerProfile snapshot() {
        PlayerProfile copy = new PlayerProfile(); copy.schemaVersion = schemaVersion; copy.uuid = uuid; copy.username = username;
        copy.lastServer = lastServer; copy.channel = channel; copy.lastSeenEpochMs = lastSeenEpochMs;
        if (accessScopes != null) copy.accessScopes.addAll(accessScopes); return copy;
    }

    public void normalize(String fallbackUsername, String defaultChannel) {
        schemaVersion = SCHEMA_VERSION; username = username == null || username.isBlank() ? fallbackUsername : username;
        uuid = uuid == null ? "" : uuid.trim(); lastServer = lastServer == null ? "" : lastServer;
        channel = PluginConfig.normalizeChannel(channel == null ? defaultChannel : channel); if (lastSeenEpochMs <= 0) lastSeenEpochMs = System.currentTimeMillis();
        Set<String> normalized = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);
        if (accessScopes != null) accessScopes.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).forEach(normalized::add);
        accessScopes = normalized;
    }
}
