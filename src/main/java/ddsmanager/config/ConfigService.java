package ddsmanager.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import ddsmanager.data.AtomicJsonStore;
import ddsmanager.util.ServerLabelFormatter;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class ConfigService {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path path; private final AtomicJsonStore store; private final Logger logger;
    private volatile List<String> validationWarnings = List.of();
    public ConfigService(Path dataDirectory, AtomicJsonStore store, Logger logger) { path = dataDirectory.resolve("config.json"); this.store = store; this.logger = logger; }

    public PluginConfig load() {
        try { return read(true); }
        catch (IOException e) {
            validationWarnings = List.of("$: " + errorDetail(e)); logger.error("Failed to load config {}, using safe defaults", path, e);
            PluginConfig fallback = new PluginConfig(); fallback.features.whitelist = true; fallback.normalize(); return fallback;
        }
    }
    public PluginConfig loadForReload() throws IOException { return read(false); }
    private PluginConfig read(boolean initialize) throws IOException {
        try {
            PluginConfig config = store.read(path, PluginConfig.class, ConfigService::stripComments).orElseGet(() -> initialize ? new PluginConfig() : null);
            if (config == null) throw new IOException("Config file not found; keeping current configuration");
            List<String> warnings = validate(config); config.normalize(); if (initialize) save(config);
            validationWarnings = List.copyOf(warnings); warnings.forEach(w -> logger.warn("DDS Manager config: {}", w)); return config;
        } catch (RuntimeException e) { throw new IOException("Invalid configuration: " + errorDetail(e), e); }
    }
    public void save(PluginConfig config) throws IOException { store.writeText(path, render(config)); }
    public List<String> validationWarnings() { return validationWarnings; }

    static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length()); boolean string = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string) { out.append(c); if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') string = false; continue; }
            if (c == '"') { string = true; out.append(c); continue; }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') { i += 2; while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++; i--; continue; }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') { i += 2; while (i + 1 < text.length() && (text.charAt(i) != '*' || text.charAt(i + 1) != '/')) { if (text.charAt(i) == '\n' || text.charAt(i) == '\r') out.append(text.charAt(i)); i++; } i++; continue; }
            out.append(c);
        }
        return out.toString();
    }

    static String render(PluginConfig c) {
        StringBuilder s = new StringBuilder("{\n");
        comment(s, 1, "Schema version, managed by DDS; do not decrease manually"); value(s, 1, "schemaVersion", c.schemaVersion, true);
        comment(s, 1, "Feature switches"); line(s, 1, "\"features\": {");
        comment(s, 2, "Enable unified DDS whitelist"); value(s, 2, "whitelist", c.features.whitelist, true);
        comment(s, 2, "Remember players' last server and try it when they reconnect"); value(s, 2, "rememberLastServer", c.features.rememberLastServer, true);
        comment(s, 2, "Bridge local/global chat across servers"); value(s, 2, "bridgeChat", c.features.bridgeChat, true);
        comment(s, 2, "Write chat logs"); value(s, 2, "chatLog", c.features.chatLog, true);
        comment(s, 2, "Synchronize cross-server Tab list and presence"); value(s, 2, "syncTabList", c.features.syncTabList, true);
        comment(s, 2, "Broadcast join, leave and server-switch notices"); value(s, 2, "bridgeJoinLeave", c.features.bridgeJoinLeave, true);
        comment(s, 2, "Synchronize world identifiers for Xaero/VoxelMap"); value(s, 2, "minimapWorldSync", c.features.minimapWorldSync, true);
        comment(s, 2, "Allow Velocity permission nodes to grant server access"); value(s, 2, "permissionBasedAccess", c.features.permissionBasedAccess, false); line(s, 1, "},");

        comment(s, 1, "Message locale settings: prefer the player locale, otherwise use defaults"); line(s, 1, "\"language\": {");
        value(s, 2, "defaultLocale", c.language.defaultLocale, true);
        value(s, 2, "fallbackLocale", c.language.fallbackLocale, true);
        value(s, 2, "consoleLocale", c.language.consoleLocale, true);
        value(s, 2, "detectClientLanguage", c.language.detectClientLanguage, false); line(s, 1, "},");

        comment(s, 1, "Connection routing"); line(s, 1, "\"routing\": {");
        comment(s, 2, "Respect the initial server selected by Velocity"); value(s, 2, "respectInitialServer", c.routing.respectInitialServer, true);
        comment(s, 2, "Fallback server when no destination is available; blank means none"); value(s, 2, "fallbackServer", c.routing.fallbackServer, false); line(s, 1, "},");

        comment(s, 1, "Chat settings; native same-server chat is unchanged by DDS"); line(s, 1, "\"chat\": {");
        comment(s, 2, "Default chat channel for new players: local or global"); value(s, 2, "defaultChannel", c.chat.defaultChannel, false); line(s, 1, "},");

        comment(s, 1, "Cross-server presence and server labels"); line(s, 1, "\"presence\": {");
        comment(s, 2, "Show server-switch notices"); value(s, 2, "showServerSwitches", c.presence.showServerSwitches, true);
        comment(s, 2, "Show server labels in Tab names"); value(s, 2, "showServerInTabName", c.presence.showServerInTabName, true);
        comment(s, 2, "Show dimension symbols in Tab names"); value(s, 2, "showDimensionInTabName", c.presence.showDimensionInTabName, true);
        comment(s, 2, "Map server names to short labels; shown as [prefix] if no full label is set"); stringMap(s, 2, "serverPrefixes", c.presence.serverPrefixes, "Short label for server %s", true);
        comment(s, 2, "Full server label templates; supports restricted DDS MiniMessage formatting"); stringMap(s, 2, "serverLabels", c.presence.serverLabels, "Full label template for server %s", true);
        comment(s, 2, "Dimension symbol used in Tab; its color depends on dimension"); value(s, 2, "dimensionSymbol", c.presence.dimensionSymbol, false); line(s, 1, "},");

        comment(s, 1, "Persistence settings"); line(s, 1, "\"persistence\": {");
        comment(s, 2, "Write-back interval in seconds, minimum 1"); value(s, 2, "flushIntervalSeconds", c.persistence.flushIntervalSeconds, true);
        comment(s, 2, "Offline player cache time in minutes, minimum 1"); value(s, 2, "cacheMinutes", c.persistence.cacheMinutes, false); line(s, 1, "},");

        comment(s, 1, "UUIDs of built-in administrators; they bypass DDS whitelist and have full management rights"); stringList(s, 1, "administrators", c.administrators, "Administrator UUID", true);
        comment(s, 1, "Server groups: each group maps to Velocity server names"); groupMap(s, 1, "serverGroups", c.serverGroups, false);
        return s.append("}\n").toString();
    }

    private static void stringMap(StringBuilder s, int d, String key, Map<String, String> map, String note, boolean comma) {
        line(s, d, JSON.toJson(key) + ": {"); int i = 0, n = map == null ? 0 : map.size();
        if (map != null) for (var e : map.entrySet()) { comment(s, d + 1, note.formatted(e.getKey())); value(s, d + 1, e.getKey(), e.getValue(), ++i < n); }
        line(s, d, "}" + (comma ? "," : ""));
    }
    private static void stringList(StringBuilder s, int d, String key, Collection<String> values, String note, boolean comma) {
        line(s, d, JSON.toJson(key) + ": ["); int i = 0, n = values == null ? 0 : values.size();
        if (values != null) for (String v : values) { comment(s, d + 1, note + "：" + v); line(s, d + 1, JSON.toJson(v) + (++i < n ? "," : "")); }
        line(s, d, "]" + (comma ? "," : ""));
    }
    private static void groupMap(StringBuilder s, int d, String key, Map<String, Set<String>> groups, boolean comma) {
        line(s, d, JSON.toJson(key) + ": {"); int gi = 0, gn = groups == null ? 0 : groups.size();
        if (groups != null) for (var e : groups.entrySet()) {
            comment(s, d + 1, "Server group " + e.getKey()); line(s, d + 1, JSON.toJson(e.getKey()) + ": [");
            int i = 0, n = e.getValue() == null ? 0 : e.getValue().size(); if (e.getValue() != null) for (String v : e.getValue()) { comment(s, d + 2, "Server included: " + v); line(s, d + 2, JSON.toJson(v) + (++i < n ? "," : "")); }
            line(s, d + 1, "]" + (++gi < gn ? "," : ""));
        }
        line(s, d, "}" + (comma ? "," : ""));
    }
    private static void value(StringBuilder s, int d, String key, Object value, boolean comma) { line(s, d, JSON.toJson(key) + ": " + JSON.toJson(value) + (comma ? "," : "")); }
    private static void comment(StringBuilder s, int d, String text) { line(s, d, "// " + text); }
    private static void line(StringBuilder s, int d, String text) { s.append("  ".repeat(d)).append(text).append('\n'); }

    private static List<String> validate(PluginConfig c) {
        List<String> w = new ArrayList<>();
        if (c.schemaVersion != 2) w.add("$.schemaVersion: Only version 2 is supported; upgraded");
        if (c.features == null) w.add("$.features: Missing or null; restored default");
        if (c.language == null) w.add("$.language: missing, defaults restored");
        if (c.routing == null) w.add("$.routing: Missing or null; restored default");
        if (c.chat == null) w.add("$.chat: Missing or null; restored default");
        else if (!"local".equalsIgnoreCase(c.chat.defaultChannel) && !"global".equalsIgnoreCase(c.chat.defaultChannel)) w.add("$.chat.defaultChannel: Must be local or global; switched to local");
        if (c.presence == null) w.add("$.presence: Missing or null; restored default"); else validatePresence(c.presence, w);
        if (c.persistence == null) w.add("$.persistence: Missing or null; restored default"); else {
            if (c.persistence.flushIntervalSeconds < 1) w.add("$.persistence.flushIntervalSeconds: Must be >= 1; adjusted to 1");
            if (c.persistence.cacheMinutes < 1) w.add("$.persistence.cacheMinutes: Must be >= 1; adjusted to 1");
        }
        if (c.administrators == null) w.add("$.administrators: Missing or null; restored an empty list"); else {
            int i = 0; for (String admin : c.administrators) { if (!PluginConfig.isValidUuid(admin)) w.add("$.administrators[" + i + "]: Must be a valid UUID; entry ignored"); i++; }
        }
        if (c.serverGroups == null) w.add("$.serverGroups: Missing or null; restored empty object"); else for (var e : c.serverGroups.entrySet()) {
            String name = e.getKey(), base = "$.serverGroups." + (name == null ? "<null>" : name);
            if (!PluginConfig.isValidGroupName(name)) w.add(base + ": Group name must be 1-32 letters, digits or _, -, .; entry ignored");
            if (e.getValue() == null) w.add(base + ": Server list null; reset to empty list");
            else if (e.getValue().stream().anyMatch(v -> v == null || v.isBlank())) w.add(base + ": Contains blank server names; blank entries ignored");
        }
        return w;
    }

    private static void validatePresence(PluginConfig.Presence p, List<String> w) {
        if (p.serverPrefixes == null) w.add("$.presence.serverPrefixes: Missing or null; restored default"); else {
            Set<String> prefixes = new HashSet<>();
            for (var e : p.serverPrefixes.entrySet()) {
                String server = e.getKey(), prefix = e.getValue(), base = "$.presence.serverPrefixes." + (server == null ? "<null>" : server);
                if (server == null || server.isBlank()) w.add(base + ": Server name cannot be blank; entry ignored");
                if (!PluginConfig.isValidServerPrefix(prefix)) w.add(base + ": Prefix must be 1-12 non-whitespace characters without brackets; entry ignored");
                else if (!prefixes.add(prefix.trim().toLowerCase(Locale.ROOT))) w.add(base + ": Prefix " + prefix.trim() + " duplicates another prefix; hover will show the actual server name");
            }
        }
        if (p.serverLabels == null) w.add("$.presence.serverLabels: Missing or null; restored empty object and using [serverPrefix]"); else for (var e : p.serverLabels.entrySet()) {
            String server = e.getKey(), label = e.getValue(), base = "$.presence.serverLabels." + (server == null ? "<null>" : server);
            if (server == null || server.isBlank()) w.add(base + ": Server name cannot be blank; entry ignored");
            if (!PluginConfig.isValidServerLabelTemplate(label)) w.add(base + ": Label must contain 1-128 characters without control characters; entry ignored");
            else if (!ServerLabelFormatter.isValid(label)) w.add(base + ": Invalid MiniMessage style; plain text will be used");
        }
        if (!PluginConfig.isValidDimensionSymbol(p.dimensionSymbol)) w.add("$.presence.dimensionSymbol: Dimension symbol must contain 1-4 visible characters; restored ⁑");
    }

    private static String errorDetail(Throwable error) {
        String detail = error.getClass().getSimpleName();
        for (Throwable t = error; t != null; t = t.getCause()) if (t.getMessage() != null && !t.getMessage().isBlank()) detail = t.getMessage();
        return detail.replace('\n', ' ').replace('\r', ' ');
    }
}
