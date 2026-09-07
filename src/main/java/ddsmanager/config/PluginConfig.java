package ddsmanager.config;

import java.util.*;
import java.util.concurrent.*;

public final class PluginConfig {
    private static final String GROUP_NAME_PATTERN = "[A-Za-z0-9_.-]{1,32}";
    public int schemaVersion = 2;
    public Features features = new Features(); public Routing routing = new Routing(); public Chat chat = new Chat();
    public Presence presence = new Presence(); public Persistence persistence = new Persistence();
    public Set<String> administrators = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);
    public Map<String, Set<String>> serverGroups = new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);

    public void normalize() {
        schemaVersion = 2; features = features == null ? new Features() : features; routing = routing == null ? new Routing() : routing;
        chat = chat == null ? new Chat() : chat; presence = presence == null ? new Presence() : presence; persistence = persistence == null ? new Persistence() : persistence;
        routing.fallbackServer = safe(routing.fallbackServer); chat.defaultChannel = normalizeChannel(chat.defaultChannel); presence.normalize();
        persistence.flushIntervalSeconds = Math.max(1, persistence.flushIntervalSeconds); persistence.cacheMinutes = Math.max(1, persistence.cacheMinutes);
        Set<String> admins = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);
        if (administrators != null) administrators.stream().filter(Objects::nonNull).map(String::trim).filter(PluginConfig::isValidUuid).map(v -> UUID.fromString(v).toString()).forEach(admins::add);
        administrators = admins;
        Map<String, Set<String>> groups = new ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER);
        if (serverGroups != null) serverGroups.forEach((name, servers) -> {
            if (!isValidGroupName(name)) return; Set<String> values = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);
            if (servers != null) servers.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).forEach(values::add);
            groups.computeIfAbsent(name.trim(), ignored -> new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER)).addAll(values);
        });
        serverGroups = groups;
    }

    public static String normalizeChannel(String value) { return "global".equalsIgnoreCase(value) ? "global" : "local"; }
    public static boolean isValidGroupName(String value) { return value != null && value.trim().matches(GROUP_NAME_PATTERN); }
    public static boolean isValidUuid(String value) { if (value == null || value.isBlank()) return false; try { return UUID.fromString(value.trim()).toString().equalsIgnoreCase(value.trim()); } catch (IllegalArgumentException ignored) { return false; } }
    public static boolean isValidServerPrefix(String value) {
        if (value == null) return false; String v = value.trim();
        return !v.isEmpty() && v.codePointCount(0, v.length()) <= 12 && v.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c) || c == '[' || c == ']');
    }
    public static boolean isValidServerLabelTemplate(String value) { if (value == null) return false; String v = value.trim(); return !v.isEmpty() && v.codePointCount(0, v.length()) <= 128 && v.codePoints().noneMatch(Character::isISOControl); }
    public static boolean isValidDimensionSymbol(String value) { if (value == null) return false; String v = value.trim(); return !v.isEmpty() && v.codePointCount(0, v.length()) <= 4 && v.codePoints().noneMatch(Character::isISOControl); }
    private static String safe(String value) { return value == null ? "" : value.trim(); }

    public static final class Features {
        public boolean whitelist = false, rememberLastServer = true, bridgeChat = true, chatLog = false, syncTabList = true,
                bridgeJoinLeave = true, minimapWorldSync = true, permissionBasedAccess = true;
    }
    public static final class Routing { public boolean respectInitialServer = true; public String fallbackServer = ""; }
    public static final class Chat { public String defaultChannel = "global"; public boolean showServerPrefix = true; }

    public static final class Presence {
        public boolean showServerSwitches = true, showServerInTabName = true, showDimensionInTabName = true;
        public Map<String, String> serverPrefixes = defaultPrefixes();
        public Map<String, String> serverLabels = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        public String dimensionSymbol = "⁑";

        public String serverPrefix(String server) { if (server != null) for (var e : serverPrefixes.entrySet()) if (e.getKey().equalsIgnoreCase(server)) return e.getValue(); return server == null || server.isBlank() ? "?" : server; }
        public String serverLabel(String server) { if (server != null) for (var e : serverLabels.entrySet()) if (e.getKey().equalsIgnoreCase(server)) return e.getValue(); return "[" + serverPrefix(server) + "]"; }
        private void normalize() {
            if (serverPrefixes == null) serverPrefixes = defaultPrefixes(); else {
                Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                serverPrefixes.forEach((server, prefix) -> { if (server != null && !server.isBlank() && isValidServerPrefix(prefix)) values.put(server.trim(), prefix.trim()); }); serverPrefixes = values;
            }
            Map<String, String> labels = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (serverLabels != null) serverLabels.forEach((server, label) -> { if (server != null && !server.isBlank() && isValidServerLabelTemplate(label)) labels.put(server.trim(), label.trim()); }); serverLabels = labels;
            dimensionSymbol = isValidDimensionSymbol(dimensionSymbol) ? dimensionSymbol.trim() : "⁑";
        }
        private static Map<String, String> defaultPrefixes() { Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); map.put("survival", "S"); map.put("creative", "C"); map.put("mirror", "M"); return map; }
    }

    public static final class Persistence { public int flushIntervalSeconds = 5, cacheMinutes = 15; }
}
