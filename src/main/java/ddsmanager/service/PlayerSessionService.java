package ddsmanager.service;

import com.velocitypowered.api.proxy.Player;
import ddsmanager.data.PlayerProfile;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class PlayerSessionService {
    private final ConcurrentHashMap<UUID, Session> profiles = new ConcurrentHashMap<>();

    public void bind(Player player, PlayerProfile profile) { validate(player, profile); profiles.put(player.getUniqueId(), new Session(player, profile)); }
    public void put(Player player, PlayerProfile profile) {
        validate(player, profile);
        profiles.compute(player.getUniqueId(), (uuid, current) -> current == null || current.player() == player ? new Session(player, profile) : current);
    }
    public Optional<PlayerProfile> get(Player player) {
        Session session = profiles.get(player.getUniqueId());
        return session != null && session.player() == player ? Optional.of(session.profile()) : Optional.empty();
    }
    public Optional<PlayerProfile> remove(Player player) {
        AtomicReference<PlayerProfile> removed = new AtomicReference<>();
        profiles.computeIfPresent(player.getUniqueId(), (uuid, session) -> {
            if (session.player() != player) return session; removed.set(session.profile()); return null;
        });
        return Optional.ofNullable(removed.get());
    }
    public Set<UUID> onlineIds() { return Set.copyOf(profiles.keySet()); }
    private static void validate(Player player, PlayerProfile profile) {
        if (!player.getUniqueId().toString().equalsIgnoreCase(profile.uuid)) throw new IllegalArgumentException("Session profile UUID mismatch");
    }
    private record Session(Player player, PlayerProfile profile) {}
}
