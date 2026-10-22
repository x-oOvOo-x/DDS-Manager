package ddsmanager.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlayerProfileTest {
    @Test void profileNormalizationKeepsIdentityAndChannelValid() {
        PlayerProfile p = new PlayerProfile(); p.username = ""; p.uuid = " 1234 "; p.channel = "invalid"; p.normalize("Steve", "global");
        assertEquals(3, p.schemaVersion); assertEquals("Steve", p.username); assertEquals("1234", p.uuid); assertEquals("local", p.channel);
    }
}
