package ddsmanager.service;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import ddsmanager.config.PluginConfig;
import ddsmanager.data.PlayerProfile;
import ddsmanager.data.PlayerRepository;

import java.util.*;
import java.util.function.Supplier;

public final class AccessService {
    private static final String ADMIN_PERMISSION = "dds-manager.admin";
    private final Supplier<PluginConfig> config;

    public AccessService(Supplier<PluginConfig> config) { this.config = config; }

    public boolean isAdministrator(CommandSource source) {
        if (source.hasPermission(ADMIN_PERMISSION)) return true;
        if (!(source instanceof Player player)) return false;
        Set<String> administrators = config.get().administrators;
        return administrators != null && administrators.stream()
                .anyMatch(uuid -> uuid.equalsIgnoreCase(player.getUniqueId().toString()));
    }

    public boolean canManage(CommandSource source, String permission) {
        return isAdministrator(source) || (permission != null && source.hasPermission(permission));
    }

    public boolean canAccess(Player player, PlayerProfile profile, String serverName) {
        return evaluate(player, profile, serverName).allowed();
    }

    public Decision evaluate(Player player, PlayerProfile profile, String serverName) {
        PluginConfig cfg = config.get();
        if (!cfg.features.whitelist) return Decision.allow("access.reason.whitelist-off");
        if (player.hasPermission(ADMIN_PERMISSION)) return Decision.allow("access.reason.admin-node");
        if (isAdministrator(player)) return Decision.allow("access.reason.admin-uuid");
        if (player.hasPermission("dds-manager.bypass.whitelist")) return Decision.allow("access.reason.bypass");

        if (cfg.features.permissionBasedAccess) {
            String serverPermission = "dds-manager.server." + AccessScopes.permissionToken(serverName);
            if (player.hasPermission(serverPermission)) return Decision.allow("access.reason.permission",  serverPermission);
            for (var entry : cfg.serverGroups.entrySet()) {
                if (!contains(entry.getValue(), serverName)) continue;
                String groupPermission = "dds-manager.group." + AccessScopes.permissionToken(entry.getKey());
                if (player.hasPermission(groupPermission)) return Decision.allow("access.reason.permission",  groupPermission);
            }
        }

        var uuid = profile == null ? Optional.<UUID>empty() : PlayerRepository.parseUuid(profile.uuid);
        if (uuid.isEmpty() || !uuid.get().equals(player.getUniqueId()))
            return Decision.deny("access.reason.uuid-mismatch");
        return evaluateScopes(profile, serverName, cfg);
    }

    public Decision evaluateStored(PlayerProfile profile, String serverName) {
        PluginConfig cfg = config.get();
        if (!cfg.features.whitelist) return Decision.allow("access.reason.whitelist-off");
        if (profile == null || PlayerRepository.parseUuid(profile.uuid).isEmpty())
            return Decision.deny("access.reason.uuid-pending");
        if (cfg.administrators != null && cfg.administrators.stream().anyMatch(uuid -> uuid.equalsIgnoreCase(profile.uuid)))
            return Decision.allow("access.reason.admin-uuid");
        return evaluateScopes(profile, serverName, cfg);
    }

    private Decision evaluateScopes(PlayerProfile profile, String serverName, PluginConfig cfg) {
        if (profile.accessScopes == null) return Decision.deny("access.reason.no-grant");
        for (String scope : profile.accessScopes) {
            if (AccessScopes.isAll(scope)) return Decision.allow("access.reason.global");
            var directServer = AccessScopes.serverName(scope);
            if (directServer.isPresent() && directServer.get().equalsIgnoreCase(serverName))
                return Decision.allow("access.reason.server",  AccessScopes.server(serverName));
            var groupName = AccessScopes.groupName(scope);
            if (groupName.isPresent()) {
                Set<String> group = findGroup(groupName.get(), cfg.serverGroups);
                if (group != null && contains(group, serverName))
                    return Decision.allow("access.reason.group",  AccessScopes.group(groupName.get()));
            }
            if (scope != null && scope.equalsIgnoreCase(serverName))
                return Decision.allow("access.reason.legacy-server",  scope);
            Set<String> legacyGroup = findGroup(scope, cfg.serverGroups);
            if (legacyGroup != null && contains(legacyGroup, serverName))
                return Decision.allow("access.reason.legacy-group",  scope);
        }
        return Decision.deny("access.reason.no-match", serverName);
    }

    private static Set<String> findGroup(String name, Map<String, Set<String>> groups) {
        if (name == null) return null;
        for (var entry : groups.entrySet()) if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        return null;
    }

    private static boolean contains(Collection<String> values, String target) {
        return values != null && target != null && values.stream().anyMatch(v -> v != null && v.equalsIgnoreCase(target));
    }

    public record Decision(boolean allowed, String reasonKey, String reasonValue) {
        public static Decision allow(String key) { return new Decision(true, key, ""); }
        public static Decision allow(String key, String value) { return new Decision(true, key, value); }
        public static Decision deny(String key) { return new Decision(false, key, ""); }
        public static Decision deny(String key, String value) { return new Decision(false, key, value); }
    }
}
