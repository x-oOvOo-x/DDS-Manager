package ddsmanager.service;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class AccessScopes {
    public static final String ALL = "all";
    private static final String SERVER_PREFIX = "server:";
    private static final String GROUP_PREFIX = "group:";

    private AccessScopes() {
    }

    public static String server(String serverName) {
        return SERVER_PREFIX + clean(serverName);
    }

    public static String group(String groupName) {
        return GROUP_PREFIX + clean(groupName);
    }

    public static boolean isAll(String scope) {
        return scope != null && ALL.equalsIgnoreCase(scope.trim());
    }

    public static Optional<String> serverName(String scope) {
        return valueAfterPrefix(scope, SERVER_PREFIX);
    }

    public static Optional<String> groupName(String scope) {
        return valueAfterPrefix(scope, GROUP_PREFIX);
    }

    public static boolean equalsScope(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    public static String display(String scope) {
        if (scope == null || scope.isBlank()) return "";
        if (isAll(scope)) return "all";
        var server = serverName(scope);
        if (server.isPresent()) return "server:" + server.get();
        var group = groupName(scope);
        if (group.isPresent()) return "group:" + group.get();
        return "legacy:" + scope.trim();
    }

    public static String permissionToken(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    private static Optional<String> valueAfterPrefix(String scope, String prefix) {
        if (scope == null) return Optional.empty();
        String value = scope.trim();
        if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) return Optional.empty();
        String result = value.substring(prefix.length()).trim();
        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    private static String clean(String value) {
        String result = Objects.requireNonNull(value, "value").trim();
        if (result.isEmpty()) throw new IllegalArgumentException("Access scope value cannot be blank");
        return result;
    }
}
