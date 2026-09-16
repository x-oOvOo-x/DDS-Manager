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
            if (config == null) throw new IOException("配置文件不存在，保留当前配置");
            List<String> warnings = validate(config); config.normalize(); if (initialize) save(config);
            validationWarnings = List.copyOf(warnings); warnings.forEach(w -> logger.warn("DDS Manager config: {}", w)); return config;
        } catch (RuntimeException e) { throw new IOException("配置无效: " + errorDetail(e), e); }
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
        comment(s, 1, "配置格式版本，由 DDS 自动维护，请勿手动降低"); value(s, 1, "schemaVersion", c.schemaVersion, true);
        comment(s, 1, "功能总开关"); line(s, 1, "\"features\": {");
        comment(s, 2, "是否启用 DDS 统一白名单"); value(s, 2, "whitelist", c.features.whitelist, true);
        comment(s, 2, "是否记住玩家最后所在服务器并在下次连接时优先返回"); value(s, 2, "rememberLastServer", c.features.rememberLastServer, true);
        comment(s, 2, "是否启用 DDS Local/Global 跨服聊天桥接"); value(s, 2, "bridgeChat", c.features.bridgeChat, true);
        comment(s, 2, "是否记录聊天日志"); value(s, 2, "chatLog", c.features.chatLog, true);
        comment(s, 2, "是否同步跨服 Tab 列表与 Presence 信息"); value(s, 2, "syncTabList", c.features.syncTabList, true);
        comment(s, 2, "是否广播玩家进服、离服和切服提示"); value(s, 2, "bridgeJoinLeave", c.features.bridgeJoinLeave, true);
        comment(s, 2, "是否同步 Xaero/VoxelMap 使用的世界标识"); value(s, 2, "minimapWorldSync", c.features.minimapWorldSync, true);
        comment(s, 2, "是否允许通过 Velocity 权限节点授予服务器访问权限"); value(s, 2, "permissionBasedAccess", c.features.permissionBasedAccess, false); line(s, 1, "},");

        comment(s, 1, "连接路由设置"); line(s, 1, "\"routing\": {");
        comment(s, 2, "是否尊重 Velocity 首次选出的初始服务器"); value(s, 2, "respectInitialServer", c.routing.respectInitialServer, true);
        comment(s, 2, "无可用目标时使用的回退服务器名；留空表示不指定"); value(s, 2, "fallbackServer", c.routing.fallbackServer, false); line(s, 1, "},");

        comment(s, 1, "聊天设置；同服原生聊天保持子服原样，DDS 不追加服务器标签"); line(s, 1, "\"chat\": {");
        comment(s, 2, "新玩家默认聊天频道，可选 local 或 global"); value(s, 2, "defaultChannel", c.chat.defaultChannel, false); line(s, 1, "},");

        comment(s, 1, "跨服显示与服务器标签设置"); line(s, 1, "\"presence\": {");
        comment(s, 2, "是否显示玩家切换服务器的提示"); value(s, 2, "showServerSwitches", c.presence.showServerSwitches, true);
        comment(s, 2, "是否在 Tab 玩家名中显示服务器标签"); value(s, 2, "showServerInTabName", c.presence.showServerInTabName, true);
        comment(s, 2, "是否在 Tab 玩家名中显示维度状态符号"); value(s, 2, "showDimensionInTabName", c.presence.showDimensionInTabName, true);
        comment(s, 2, "服务器名到简写的映射；未配置完整标签时显示为 [简写]"); stringMap(s, 2, "serverPrefixes", c.presence.serverPrefixes, "服务器 %s 的简写", true);
        comment(s, 2, "服务器名到完整标签模板的映射；支持 DDS 受限 MiniMessage 风格，留空则使用 serverPrefixes"); stringMap(s, 2, "serverLabels", c.presence.serverLabels, "服务器 %s 的完整标签模板", true);
        comment(s, 2, "Tab 中统一使用的维度符号，颜色由主世界/下界/末地状态决定"); value(s, 2, "dimensionSymbol", c.presence.dimensionSymbol, false); line(s, 1, "},");

        comment(s, 1, "数据持久化设置"); line(s, 1, "\"persistence\": {");
        comment(s, 2, "脏数据定期写盘间隔，单位为秒，最小值 1"); value(s, 2, "flushIntervalSeconds", c.persistence.flushIntervalSeconds, true);
        comment(s, 2, "离线玩家缓存保留时间，单位为分钟，最小值 1"); value(s, 2, "cacheMinutes", c.persistence.cacheMinutes, false); line(s, 1, "},");

        comment(s, 1, "DDS 内置管理员的正版玩家 UUID 列表；管理员拥有全部管理权限并绕过 DDS 白名单"); stringList(s, 1, "administrators", c.administrators, "管理员 UUID", true);
        comment(s, 1, "服务器组配置；键为组名，值为该组包含的 Velocity 服务器名"); groupMap(s, 1, "serverGroups", c.serverGroups, false);
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
            comment(s, d + 1, "服务器组 " + e.getKey()); line(s, d + 1, JSON.toJson(e.getKey()) + ": [");
            int i = 0, n = e.getValue() == null ? 0 : e.getValue().size(); if (e.getValue() != null) for (String v : e.getValue()) { comment(s, d + 2, "该组包含服务器 " + v); line(s, d + 2, JSON.toJson(v) + (++i < n ? "," : "")); }
            line(s, d + 1, "]" + (++gi < gn ? "," : ""));
        }
        line(s, d, "}" + (comma ? "," : ""));
    }
    private static void value(StringBuilder s, int d, String key, Object value, boolean comma) { line(s, d, JSON.toJson(key) + ": " + JSON.toJson(value) + (comma ? "," : "")); }
    private static void comment(StringBuilder s, int d, String text) { line(s, d, "// " + text); }
    private static void line(StringBuilder s, int d, String text) { s.append("  ".repeat(d)).append(text).append('\n'); }

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
            else if (e.getValue().stream().anyMatch(v -> v == null || v.isBlank())) w.add(base + ": 含空服务器名，空值会被忽略");
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
