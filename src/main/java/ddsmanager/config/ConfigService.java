package ddsmanager.config;

import ddsmanager.data.AtomicJsonStore;
import ddsmanager.util.ServerLabelFormatter;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class ConfigService {
    private final Path path; private final AtomicJsonStore store; private final Logger logger;
    private volatile List<String> validationWarnings = List.of();
    public ConfigService(Path dataDirectory, AtomicJsonStore store, Logger logger) { path = dataDirectory.resolve("config.json"); this.store = store; this.logger = logger; }

    public PluginConfig load() {
        try { return read(true); }
        catch (IOException e) {
            validationWarnings = List.of("$: " + errorDetail(e));
            logger.error("Failed to load config {}, using safe defaults", path, e);
            PluginConfig fallback = new PluginConfig(); fallback.features.whitelist = true; fallback.normalize(); return fallback;
        }
    }
    public PluginConfig loadForReload() throws IOException { return read(false); }
    private PluginConfig read(boolean initialize) throws IOException {
        try {
            PluginConfig config = store.read(path, PluginConfig.class).orElseGet(() -> initialize ? new PluginConfig() : null);
            if (config == null) throw new IOException("配置文件不存在，保留当前配置");
            List<String> warnings = validate(config); config.normalize();
            if (initialize) save(config);
            validationWarnings = List.copyOf(warnings); warnings.forEach(w -> logger.warn("DDS Manager config: {}", w)); return config;
        } catch (RuntimeException e) { throw new IOException("配置无效: " + errorDetail(e), e); }
    }
    public void save(PluginConfig config) throws IOException { store.write(path, config); }
    public List<String> validationWarnings() { return validationWarnings; }

    private static List<String> validate(PluginConfig c) {
        List<String> w = new ArrayList<>();
        if (c.schemaVersion != 2) w.add("$.schemaVersion: 仅支持 2，已升级");
        if (c.features == null) w.add("$.features: 缺失或为 null，已恢复默认值");
        if (c.routing == null) w.add("$.routing: 缺失或为 null，已恢复默认值");
        if (c.chat == null) w.add("$.chat: 缺失或为 null，已恢复默认值");
        else if (!"local".equalsIgnoreCase(c.chat.defaultChannel) && !"global".equalsIgnoreCase(c.chat.defaultChannel)) w.add("$.chat.defaultChannel: 只能是 local/global，已回退为 local");
        if (c.presence == null) w.add("$.presence: 缺失或为 null，已恢复默认值"); else validatePresence(c.presence, w);
        if (c.persistence == null) w.add("$.persistence: 缺失或为 null，已恢复默认值"); else {
            if (c.persistence.flushIntervalSeconds < 1) w.add("$.persistence.flushIntervalSeconds: 必须 >= 1，已修正为 1");
            if (c.persistence.cacheMinutes < 1) w.add("$.persistence.cacheMinutes: 必须 >= 1，已修正为 1");
        }
        if (c.administrators == null) w.add("$.administrators: 缺失或为 null，已恢复为空列表"); else {
            int i = 0; for (String admin : c.administrators) { if (!PluginConfig.isValidUuid(admin)) w.add("$.administrators[" + i + "]: 必须是正版玩家 UUID，该项会被忽略"); i++; }
        }
        if (c.serverGroups == null) w.add("$.serverGroups: 缺失或为 null，已恢复为空对象"); else for (var e : c.serverGroups.entrySet()) {
            String name = e.getKey(), base = "$.serverGroups." + (name == null ? "<null>" : name);
            if (!PluginConfig.isValidGroupName(name)) w.add(base + ": 组名只能包含字母、数字、_、-、.，长度 1-32，该项会被忽略");
            if (e.getValue() == null) w.add(base + ": 服务器列表为 null，已恢复为空列表");
            else if (e.getValue().stream().anyMatch(s -> s == null || s.isBlank())) w.add(base + ": 含空服务器名，空值会被忽略");
        }
        return w;
    }

    private static void validatePresence(PluginConfig.Presence p, List<String> w) {
        if (p.serverPrefixes == null) w.add("$.presence.serverPrefixes: 缺失或为 null，已恢复默认值"); else {
            Set<String> prefixes = new HashSet<>();
            for (var e : p.serverPrefixes.entrySet()) {
                String server = e.getKey(), prefix = e.getValue(), base = "$.presence.serverPrefixes." + (server == null ? "<null>" : server);
                if (server == null || server.isBlank()) w.add(base + ": 服务器名不能为空，该项会被忽略");
                if (!PluginConfig.isValidServerPrefix(prefix)) w.add(base + ": 前缀须为 1-12 个非空白字符且不能包含 []，该项会被忽略");
                else if (!prefixes.add(prefix.trim().toLowerCase(Locale.ROOT))) w.add(base + ": 前缀 " + prefix.trim() + " 与其他服务器重复，Hover 会显示真实服务器名");
            }
        }
        if (p.serverLabels == null) w.add("$.presence.serverLabels: 缺失或为 null，已恢复为空对象并沿用 [serverPrefix]"); else for (var e : p.serverLabels.entrySet()) {
            String server = e.getKey(), label = e.getValue(), base = "$.presence.serverLabels." + (server == null ? "<null>" : server);
            if (server == null || server.isBlank()) w.add(base + ": 服务器名不能为空，该项会被忽略");
            if (!PluginConfig.isValidServerLabelTemplate(label)) w.add(base + ": 标签须为 1-128 个字符且不能包含控制字符，该项会被忽略");
            else if (!ServerLabelFormatter.isValid(label)) w.add(base + ": MiniMessage 样式无效，将按纯文本显示");
        }
        if (!PluginConfig.isValidDimensionSymbol(p.dimensionSymbol)) w.add("$.presence.dimensionSymbol: 维度符号须为 1-4 个可见字符，已恢复为 ⁑");
    }

    private static String errorDetail(Throwable error) {
        String detail = error.getClass().getSimpleName();
        for (Throwable t = error; t != null; t = t.getCause()) if (t.getMessage() != null && !t.getMessage().isBlank()) detail = t.getMessage();
        return detail.replace('\n', ' ').replace('\r', ' ');
    }
}
