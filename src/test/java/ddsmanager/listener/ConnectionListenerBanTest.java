package ddsmanager.listener;

import com.google.gson.GsonBuilder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import ddsmanager.DdsManagerPlugin;
import ddsmanager.config.PluginConfig;
import ddsmanager.data.AtomicJsonStore;
import ddsmanager.data.BanRepository;
import ddsmanager.i18n.LanguageService;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class ConnectionListenerBanTest {
    @TempDir Path dir;

    @Test void bannedAdminWithWhitelistBypassCannotLogIn() throws Exception {
        UUID uuid = UUID.randomUUID();
        PluginConfig cfg = new PluginConfig();
        cfg.features.whitelist = true;
        cfg.administrators.add(uuid.toString());
        cfg.normalize();

        BanRepository bans = repository();
        bans.add(uuid, "Admin", "test ban", "CONSOLE");
        ConnectionListener listener = listener(bans, cfg);
        LoginEvent event = login(player(uuid, true));
        listener.onLogin(event);

        assertFalse(event.getResult().isAllowed(), "UUID ban must precede admin and whitelist bypass");
        assertTrue(event.getResult().getReasonComponent().isPresent());

        LoginEvent permitted = login(player(UUID.randomUUID(), true));
        listener.onLogin(permitted);
        assertTrue(permitted.getResult().isAllowed(), "valid non-banned UUID must pass ban gate");
    }

    @Test void brokenBanListBlocksEveryNewLoginEvenWithAdminPermissions() throws Exception {
        UUID admin = UUID.randomUUID(), other = UUID.randomUUID();
        PluginConfig cfg = new PluginConfig();
        cfg.features.whitelist = false;
        cfg.administrators.add(admin.toString());
        BanRepository bans = repository();
        Files.writeString(dir.resolve("bans.json"), "{\"schemaVersion\":1,\"bans\":null}");
        assertFalse(bans.reload());
        ConnectionListener listener = listener(bans, cfg);

        for (UUID uuid : new UUID[]{admin, other}) {
            LoginEvent attempt = login(player(uuid, true));
            listener.onLogin(attempt);
            assertFalse(attempt.getResult().isAllowed(), "invalid datastore must deny " + uuid);
            assertTrue(attempt.getResult().getReasonComponent().isPresent());
        }
    }

    @Test void offlineIdentityIsRejectedAndPriorPluginDenialIsPreserved() throws Exception {
        ConnectionListener listener = listener(repository(), new PluginConfig());

        LoginEvent offline = login(player(UUID.randomUUID(), false));
        listener.onLogin(offline);
        assertFalse(offline.getResult().isAllowed(), "online-mode identity is mandatory");

        LoginEvent previouslyDenied = login(player(UUID.randomUUID(), true));
        Component reason = Component.text("Already rejected");
        previouslyDenied.setResult(ResultedEvent.ComponentResult.denied(reason));
        listener.onLogin(previouslyDenied);
        assertEquals(reason, previouslyDenied.getResult().getReasonComponent().orElseThrow(),
                "DDS must not override another plugin's denial");
    }

    private BanRepository repository() {
        return new BanRepository(dir, new AtomicJsonStore(new GsonBuilder().create()),
                LoggerFactory.getLogger("test"));
    }

    private ConnectionListener listener(BanRepository bans, PluginConfig cfg) throws Exception {
        var logger = LoggerFactory.getLogger("test");
        DdsManagerPlugin plugin = new DdsManagerPlugin(null, logger, dir);
        LanguageService language = new LanguageService(dir, logger, () -> cfg);
        language.reload();
        set(plugin, "language", language);
        set(plugin, "config", cfg);
        set(plugin, "bans", bans);
        return new ConnectionListener(plugin);
    }

    // Velocity 3.x/4.x expose either LoginEvent(Player) or LoginEvent(Player, String).
    private static LoginEvent login(Player player) throws Exception {
        for (Constructor<?> constructor : LoginEvent.class.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length < 1 || types[0] != Player.class) continue;
            boolean compatible = true;
            for (int i = 1; i < types.length; i++) compatible &= types[i] == String.class;
            if (!compatible) continue;
            Object[] args = new Object[types.length];
            args[0] = player;
            return (LoginEvent) constructor.newInstance(args);
        }
        throw new AssertionError("Unsupported Velocity LoginEvent constructor");
    }

    private static Player player(UUID uuid, boolean onlineMode) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "isOnlineMode" -> onlineMode;
                    case "getEffectiveLocale" -> Locale.US;
                    case "getUsername" -> "Tester";
                    case "hasPermission" -> true;
                    default -> null;
                });
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = DdsManagerPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
