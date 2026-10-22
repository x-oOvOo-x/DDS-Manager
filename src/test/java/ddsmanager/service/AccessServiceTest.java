package ddsmanager.service;

import com.velocitypowered.api.proxy.Player;
import ddsmanager.config.PluginConfig;
import ddsmanager.data.PlayerProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class AccessServiceTest {
    @Test void configuredUuidAdministratorManagesAndBypassesWhitelist() {
        UUID uuid = UUID.randomUUID();
        PluginConfig config = new PluginConfig();
        config.features.whitelist = true;
        config.administrators.add(uuid.toString());
        config.normalize();
        AccessService service = new AccessService(() -> config);
        Player player = player(uuid, Set.of());
        PlayerProfile profile = profile(uuid);

        assertTrue(service.isAdministrator(player));
        assertTrue(service.canManage(player, "dds-manager.whitelist.manage"));
        var decision = service.evaluate(player, profile, "survival");
        assertTrue(decision.allowed());
        assertEquals("access.reason.admin-uuid", decision.reasonKey());
    }

    @Test void externalAdminAndServerPermissionRemainSupported() {
        UUID adminUuid = UUID.randomUUID(), memberUuid = UUID.randomUUID();
        PluginConfig config = new PluginConfig();
        config.features.whitelist = true;
        config.normalize();
        AccessService service = new AccessService(() -> config);

        assertTrue(service.isAdministrator(player(adminUuid, Set.of("dds-manager.admin"))));
        assertTrue(service.evaluate(player(memberUuid, Set.of("dds-manager.server.survival")),
                profile(memberUuid), "survival").allowed());
    }

    @Test void serverAccessNeverGrantsManagementPermission() {
        UUID uuid = UUID.randomUUID();
        PluginConfig config = new PluginConfig();
        config.normalize();
        AccessService service = new AccessService(() -> config);
        Player player = player(uuid, Set.of("dds-manager.server.survival"));

        assertFalse(service.isAdministrator(player));
        assertFalse(service.canManage(player, "dds-manager.status"));
        assertFalse(service.canManage(player, "dds-manager.whitelist.manage"));
    }

    @Test void explicitlyGrantedFineGrainedPermissionAllowsOnlyThatManagementCapability() {
        PluginConfig config = new PluginConfig();
        config.normalize();
        AccessService service = new AccessService(() -> config);
        Player player = player(UUID.randomUUID(), Set.of("dds-manager.status"));

        assertTrue(service.canManage(player, "dds-manager.status"));
        assertFalse(service.canManage(player, "dds-manager.reload"));
    }

    @Test void configuredAdministratorDoesNotMatchAnotherUuid() {
        UUID configured = UUID.randomUUID(), other = UUID.randomUUID();
        PluginConfig config = new PluginConfig();
        config.features.whitelist = true;
        config.administrators.add(configured.toString());
        config.normalize();
        AccessService service = new AccessService(() -> config);

        assertFalse(service.isAdministrator(player(other, Set.of())));
        assertFalse(service.evaluate(player(other, Set.of()), profile(other), "survival").allowed());
    }


    @Test void banPermissionsAreDistinctFromViewingAndServerAccess() {
        AccessService service = new AccessService(PluginConfig::new);
        UUID uuid = UUID.randomUUID();
        Player viewer = player(uuid, Set.of("dds-manager.ban.view"));
        Player manager = player(uuid, Set.of("dds-manager.ban.manage"));
        Player serverOnly = player(uuid, Set.of("dds-manager.server.survival"));

        assertTrue(service.canManage(viewer, "dds-manager.ban.view"));
        assertFalse(service.canManage(viewer, "dds-manager.ban.manage"));
        assertTrue(service.canManage(manager, "dds-manager.ban.manage"));
        assertFalse(service.canManage(serverOnly, "dds-manager.ban.manage"));
    }

    private static PlayerProfile profile(UUID uuid) {
        PlayerProfile profile = new PlayerProfile();
        profile.uuid = uuid.toString();
        profile.username = "Tester";
        return profile;
    }

    private static Player player(UUID uuid, Set<String> permissions) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getUsername" -> "Tester";
                    case "hasPermission" -> permissions.contains((String) args[0]);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
