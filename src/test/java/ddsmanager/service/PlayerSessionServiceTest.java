package ddsmanager.service;

import com.velocitypowered.api.proxy.Player;
import ddsmanager.data.PlayerProfile;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

final class PlayerSessionServiceTest {
    @Test void oldConnectionCannotReadReplaceOrRemoveNewSession() {
        UUID uuid = UUID.randomUUID(); Player old = player(uuid), current = player(uuid);
        PlayerProfile first = profile(uuid), second = profile(uuid); PlayerSessionService sessions = new PlayerSessionService();
        sessions.bind(old, first); sessions.bind(current, second); sessions.put(old, first);
        assertTrue(sessions.get(old).isEmpty()); assertTrue(sessions.remove(old).isEmpty());
        assertSame(second, sessions.get(current).orElseThrow()); assertSame(second, sessions.remove(current).orElseThrow());
        assertTrue(sessions.onlineIds().isEmpty());
    }
    @Test void rejectsMismatchedIdentity() {
        PlayerSessionService sessions = new PlayerSessionService();
        assertThrows(IllegalArgumentException.class, () -> sessions.bind(player(UUID.randomUUID()), profile(UUID.randomUUID())));
    }
    private static PlayerProfile profile(UUID uuid) { PlayerProfile profile = new PlayerProfile(); profile.uuid = uuid.toString(); return profile; }
    private static Player player(UUID uuid) { return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, m, a) -> m.getName().equals("getUniqueId") ? uuid : null); }
}
