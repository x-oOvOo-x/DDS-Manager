package ddsmanager.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import ddsmanager.DdsManagerPlugin;
import ddsmanager.data.PlayerProfile;
import ddsmanager.data.PlayerRepository;
import ddsmanager.service.AccessScopes;
import ddsmanager.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class DdsCommand implements SimpleCommand {
    private static final String GROUP_NAME_PATTERN = "[A-Za-z0-9_.-]{1,32}";
    private static final int WHITELIST_PAGE_SIZE = 10;
    private final DdsManagerPlugin plugin;

    public DdsCommand(DdsManagerPlugin plugin) { this.plugin = plugin; }

    @Override public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) { help(invocation.source()); return; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(invocation.source());
            case "server" -> server(invocation.source(), args);
            case "reload" -> reload(invocation.source());
            case "channel" -> channel(invocation.source(), args);
            case "seen" -> seen(invocation.source(), args);
            case "player" -> player(invocation.source(), args);
            case "alert" -> alert(invocation.source(), args);
            case "whitelist" -> whitelist(invocation.source(), args);
            case "group" -> group(invocation.source(), args);
            case "prepare", "confirm" -> confirm(invocation.source(), args);
            default -> help(invocation.source());
        }
    }

    private void status(CommandSource source) {
        if (!require(source, "dds-manager.status")) return;
        var f = plugin.config().features;
        source.sendMessage(Messages.info("whitelist=" + f.whitelist + ", lastServer=" + f.rememberLastServer + ", chat=" + f.bridgeChat
                + ", tab=" + f.syncTabList + ", minimap=" + f.minimapWorldSync + ", admins=" + plugin.config().administrators.size() + ", groups=" + plugin.config().serverGroups.size()
                + ", tasks=" + plugin.managementTasks().queuedTasks() + ", logQueue=" + plugin.chatLog().queuedEntries()
                + ", logDropped=" + plugin.chatLog().droppedEntries() + ", auditDropped=" + plugin.audit().droppedEntries()));
    }

    private void server(CommandSource source, String[] args) {
        if (!require(source, "dds-manager.server.view")) return;
        if (args.length >= 2) {
            var server = plugin.proxy().getAllServers().stream().filter(s -> s.getServerInfo().getName().equalsIgnoreCase(args[1])).findFirst().orElse(null);
            if (server == null) { source.sendMessage(Messages.error("不存在服务器 " + args[1])); return; }
            String name = server.getServerInfo().getName(); List<String> groups = groupsForServer(name);
            source.sendMessage(Messages.info(name + ": online=" + server.getPlayersConnected().size() + ", address=" + server.getServerInfo().getAddress()
                    + ", groups=" + groups + ", fallback=" + name.equalsIgnoreCase(plugin.config().routing.fallbackServer)));
            if (!server.getPlayersConnected().isEmpty()) {
                Component line = Component.text("玩家: ", NamedTextColor.GRAY);
                for (Player player : server.getPlayersConnected()) line = line.append(link(player.getUsername(), "/dds player " + player.getUsername(), "点击查看玩家详情")).append(Component.space());
                source.sendMessage(line);
            }
            return;
        }
        var servers = plugin.proxy().getAllServers().stream().sorted(Comparator.comparing(s -> s.getServerInfo().getName(), String.CASE_INSENSITIVE_ORDER)).toList();
        source.sendMessage(Messages.info("服务器 " + servers.size() + " 个，在线玩家 " + plugin.proxy().getPlayerCount() + " 人。"));
        for (var server : servers) {
            String name = server.getServerInfo().getName(); List<String> groups = groupsForServer(name);
            String detail = "在线 " + server.getPlayersConnected().size() + "，分组 " + groups + (name.equalsIgnoreCase(plugin.config().routing.fallbackServer) ? "，Fallback" : "");
            source.sendMessage(Component.text("- ").append(link(name, "/dds server " + name, detail)).append(Component.text(": online=" + server.getPlayersConnected().size()
                    + (groups.isEmpty() ? "" : ", groups=" + groups) + (name.equalsIgnoreCase(plugin.config().routing.fallbackServer) ? ", fallback" : ""))));
        }
    }

    private List<String> groupsForServer(String serverName) {
        return plugin.config().serverGroups.entrySet().stream().filter(e -> e.getValue().stream().anyMatch(s -> s.equalsIgnoreCase(serverName)))
                .map(Map.Entry::getKey).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void reload(CommandSource source) {
        if (!require(source, "dds-manager.reload") || !confirmDangerous(source, new String[]{"reload"}, "重载DDS配置")) return;
        queue(source, "reload", () -> {
            int disconnected = plugin.reload(); List<String> warnings = plugin.configWarnings();
            String audit = "统一白名单" + plugin.unifiedWhitelistStatus() + "; warnings=" + warnings.size() + "; disconnected=" + disconnected;
            source.sendMessage(plugin.unifiedWhitelistHealthy() ? Messages.success("配置已重载。") : Messages.error("配置已重载，但统一白名单同步异常。"));
            plugin.audit().record(source, "RELOAD", audit);
        });
    }

    private void channel(CommandSource source, String[] args) {
        if (!(source instanceof Player p)) { source.sendMessage(Messages.error("只能由玩家执行。")); return; }
        var profile = plugin.sessions().get(p).orElse(null); if (profile == null) return;
        if (args.length == 1) { source.sendMessage(Messages.info("当前频道: " + profile.channel)); return; }
        if (!args[1].equalsIgnoreCase("local") && !args[1].equalsIgnoreCase("global")) { source.sendMessage(Messages.error("频道只能是 local 或 global。")); return; }
        profile.channel = args[1].toLowerCase(Locale.ROOT); plugin.players().markDirty(profile); source.sendMessage(Messages.success("已切换到 " + profile.channel));
    }

    private void seen(CommandSource source, String[] args) {
        if (args.length < 2) { source.sendMessage(Messages.info("用法: /dds seen <player>")); return; }
        if (!validateUsername(source, args[1])) return;
        plugin.players().findByUsername(args[1], plugin.config().chat.defaultChannel).ifPresentOrElse(profile -> {
            var online = onlinePlayer(profile);
            if (online.isPresent()) source.sendMessage(Messages.info(profile.username + " 当前在线于 " + online.get().getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("proxy")));
            else {
                String t = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(profile.lastSeenEpochMs));
                source.sendMessage(Messages.info(profile.username + " 最后在线: " + t + (profile.lastServer.isBlank() ? "" : "，最后服务器: " + profile.lastServer)));
            }
        }, () -> source.sendMessage(Messages.error("找不到玩家 " + args[1])));
    }

    private void player(CommandSource source, String[] args) {
        if (!require(source, "dds-manager.player.view")) return;
        if (args.length < 2) { source.sendMessage(Messages.info("用法: /dds player <player>")); return; }
        if (!validateUsername(source, args[1])) return;
        plugin.players().findByUsername(args[1], plugin.config().chat.defaultChannel).ifPresentOrElse(p -> showPlayer(source, p), () -> source.sendMessage(Messages.error("找不到玩家 " + args[1])));
    }

    private void alert(CommandSource source, String[] args) {
        if (!require(source, "dds-manager.broadcast")) return;
        if (args.length < 2) { source.sendMessage(Messages.info("用法: /dds alert <message...>")); return; }
        String text = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Component message = Component.text("[公告] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.YELLOW));
        plugin.proxy().getAllPlayers().forEach(p -> p.sendMessage(message)); source.sendMessage(Messages.success("公告已发送。"));
        plugin.audit().record(source, "ALERT", "recipients=" + plugin.proxy().getPlayerCount() + "; message=" + text);
    }

    private void whitelist(CommandSource source, String[] args) {
        if (args.length < 2) { whitelistHelp(source); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "status" -> { if (requireAny(source, "dds-manager.whitelist.view", "dds-manager.whitelist.manage")) source.sendMessage(Messages.info("网络白名单: " + (plugin.config().features.whitelist ? "开启" : "关闭") + "，permissionBasedAccess=" + plugin.config().features.permissionBasedAccess + "，whitelist.json=" + plugin.unifiedWhitelistStatus())); }
            case "on" -> {
                if (!require(source, "dds-manager.whitelist.manage") || !confirmDangerous(source, args, "开启网络白名单")) return;
                queue(source, "whitelist-on", () -> {
                    plugin.updateConfig(c -> c.features.whitelist = true); int disconnected = enforceOnlineAccess();
                    String result = "网络白名单已开启" + (disconnected == 0 ? "" : "，断开 " + disconnected + " 名无权限玩家");
                    source.sendMessage(Messages.success(result + "。")); plugin.audit().record(source, "WHITELIST_ON", result);
                });
            }
            case "off" -> {
                if (!require(source, "dds-manager.whitelist.manage") || !confirmDangerous(source, args, "关闭网络白名单")) return;
                queue(source, "whitelist-off", () -> { plugin.updateConfig(c -> c.features.whitelist = false); source.sendMessage(Messages.success("网络白名单已关闭。")); plugin.audit().record(source, "WHITELIST_OFF", "success"); });
            }
            case "add" -> { if (require(source, "dds-manager.whitelist.manage")) { String[] copy = args.clone(); queue(source, "whitelist-add", () -> mutateWhitelist(source, copy, true)); } }
            case "remove" -> {
                if (!require(source, "dds-manager.whitelist.manage") || !confirmDangerous(source, args, "移除白名单授权")) return;
                String[] copy = args.clone(); queue(source, "whitelist-remove", () -> mutateWhitelist(source, copy, false));
            }
            case "list" -> { if (requireAny(source, "dds-manager.whitelist.view", "dds-manager.whitelist.manage")) listWhitelist(source, args); }
            case "check" -> { if (requireAny(source, "dds-manager.whitelist.view", "dds-manager.whitelist.manage")) checkWhitelist(source, args); }
            case "export" -> { if (requireAny(source, "dds-manager.whitelist.export", "dds-manager.whitelist.manage")) { String[] copy = args.clone(); queue(source, "whitelist-export", () -> exportWhitelist(source, copy)); } }
            case "import" -> {
                if (!requireAny(source, "dds-manager.whitelist.import", "dds-manager.whitelist.manage") || !confirmDangerous(source, args, "导入白名单授权")) return;
                String[] copy = args.clone(); queue(source, "whitelist-import", () -> importWhitelist(source, copy));
            }
            default -> whitelistHelp(source);
        }
    }

    private void mutateWhitelist(CommandSource source, String[] args, boolean add) {
        if (args.length < 4) { whitelistMutationHelp(source, add); return; }
        String type = args[2].toLowerCase(Locale.ROOT); if (!add && type.equals("all")) { purgeWhitelist(source, args, 3); return; }
        String scope, legacyValue = null; int playerStart;
        switch (type) {
            case "all" -> { scope = AccessScopes.ALL; playerStart = 3; }
            case "server" -> {
                if (args.length < 5) { whitelistMutationHelp(source, add); return; }
                String target = add ? findRegisteredServerName(args[3]) : args[3]; if (target == null) { source.sendMessage(Messages.error("不存在服务器 " + args[3])); return; }
                scope = AccessScopes.server(target); legacyValue = target; playerStart = 4;
            }
            case "group" -> {
                if (args.length < 5) { whitelistMutationHelp(source, add); return; }
                String target = add ? findGroupName(args[3]) : args[3]; if (target == null) { source.sendMessage(Messages.error("不存在服务器组 " + args[3])); return; }
                scope = AccessScopes.group(target); legacyValue = target; playerStart = 4;
            }
            default -> { whitelistMutationHelp(source, add); return; }
        }
        if (args.length <= playerStart) { whitelistMutationHelp(source, add); return; }
        int changed = 0, invalid = 0, missing = 0, failed = 0, disconnected = 0;
        for (int i = playerStart; i < args.length; i++) {
            String username = args[i]; if (!PlayerRepository.isValidUsername(username)) { invalid++; source.sendMessage(Messages.error("无效 Java 玩家名: " + username)); continue; }
            PlayerProfile profile = add ? plugin.players().getOrCreateByUsername(username, plugin.config().chat.defaultChannel) : plugin.players().findByUsername(username, plugin.config().chat.defaultChannel).orElse(null);
            if (profile == null) { missing++; continue; }
            boolean modified = add ? profile.accessScopes.add(scope) : removeScope(profile, scope); if (!add && legacyValue != null) modified |= removeScope(profile, legacyValue); if (!modified) continue;
            try {
                if (!add && profile.accessScopes.isEmpty()) { if (plugin.players().purgeByUsername(profile.username)) { changed++; disconnected += disconnectPurged(profile.username); } else failed++; }
                else if (plugin.players().saveNow(profile)) { changed++; onlinePlayer(profile).ifPresent(p -> plugin.sessions().put(p, profile)); }
                else failed++;
            } catch (Exception e) { failed++; plugin.logger().error("Unable to update whitelist for {}", username, e); }
        }
        if (!add) disconnected += enforceOnlineAccess(); boolean fileSaved = !type.equals("all") || writeUnifiedWhitelist(source);
        String result = (add ? "添加" : "移除") + "完成，修改 " + changed + " 个玩家";
        if (missing > 0) result += "，未找到 " + missing + " 个玩家"; if (invalid > 0) result += "，忽略 " + invalid + " 个无效玩家名"; if (failed > 0) result += "，失败 " + failed + " 个"; if (disconnected > 0) result += "，断开 " + disconnected + " 名玩家"; if (!fileSaved) result += "，whitelist.json 写入失败";
        source.sendMessage(fileSaved && failed == 0 ? Messages.success(result + "。") : Messages.error(result + "。")); plugin.audit().record(source, add ? "WHITELIST_ADD" : "WHITELIST_REMOVE", result);
    }

    private void purgeWhitelist(CommandSource source, String[] args, int start) {
        int removed = 0, missing = 0, invalid = 0, failed = 0, disconnected = 0;
        for (int i = start; i < args.length; i++) {
            String username = args[i]; if (!PlayerRepository.isValidUsername(username)) { invalid++; source.sendMessage(Messages.error("无效 Java 玩家名: " + username)); continue; }
            try { if (plugin.players().purgeByUsername(username)) { removed++; disconnected += disconnectPurged(username); } else missing++; } catch (Exception e) { failed++; plugin.logger().error("Unable to purge whitelist profile {}", username, e); }
        }
        boolean fileSaved = writeUnifiedWhitelist(source); String result = "彻底移除完成，删除 " + removed + " 个 DDS 玩家档案";
        if (missing > 0) result += "，未找到 " + missing + " 个玩家"; if (invalid > 0) result += "，忽略 " + invalid + " 个无效玩家名"; if (failed > 0) result += "，失败 " + failed + " 个"; if (disconnected > 0) result += "，断开 " + disconnected + " 名在线玩家"; if (!fileSaved) result += "，whitelist.json 写入失败";
        source.sendMessage(fileSaved && failed == 0 ? Messages.success(result + "。") : Messages.error(result + "。")); plugin.audit().record(source, "WHITELIST_PURGE", result);
    }

    private int disconnectPurged(String username) {
        Player player = plugin.proxy().getPlayer(username).orElse(null); if (player == null) return 0;
        plugin.sessions().remove(player); plugin.presence().clear(player); plugin.switcher().clear(player); player.disconnect(Messages.error("你的 DDS Manager 白名单身份已被完全移除。")); return 1;
    }

    private void listWhitelist(CommandSource source, String[] args) {
        int page = 1;
        if (args.length >= 3) {
            try { page = Integer.parseInt(args[2]); }
            catch (NumberFormatException ignored) { if (!validateUsername(source, args[2])) return; plugin.players().findByUsername(args[2], plugin.config().chat.defaultChannel).ifPresentOrElse(p -> showPlayer(source, p), () -> source.sendMessage(Messages.error("找不到玩家 " + args[2]))); return; }
        }
        List<PlayerProfile> profiles = plugin.players().knownUsernames().stream().map(name -> plugin.players().findByUsername(name, plugin.config().chat.defaultChannel).orElse(null)).filter(Objects::nonNull).filter(p -> p.accessScopes != null && !p.accessScopes.isEmpty()).sorted(Comparator.comparing(p -> p.username, String.CASE_INSENSITIVE_ORDER)).toList();
        if (profiles.isEmpty()) { source.sendMessage(Messages.info("当前没有存储型白名单授权。")); return; }
        int pages = (profiles.size() + WHITELIST_PAGE_SIZE - 1) / WHITELIST_PAGE_SIZE; if (page < 1 || page > pages) { source.sendMessage(Messages.error("页码范围: 1-" + pages)); return; }
        int from = (page - 1) * WHITELIST_PAGE_SIZE, to = Math.min(from + WHITELIST_PAGE_SIZE, profiles.size());
        Component navigation = Component.text("存储型白名单授权 第 " + page + "/" + pages + " 页，共 " + profiles.size() + " 个玩家。", NamedTextColor.AQUA);
        if (page > 1) navigation = navigation.append(Component.space()).append(link("[上一页]", "/dds whitelist list " + (page - 1), "第 " + (page - 1) + " 页"));
        if (page < pages) navigation = navigation.append(Component.space()).append(link("[下一页]", "/dds whitelist list " + (page + 1), "第 " + (page + 1) + " 页")); source.sendMessage(navigation);
        for (PlayerProfile profile : profiles.subList(from, to)) source.sendMessage(Component.text("- ").append(link(profile.username, "/dds whitelist list " + profile.username, "点击查看玩家详情\n授权来源: " + displayScopes(profile))).append(Component.text(": " + displayScopes(profile))));
    }

    private void exportWhitelist(CommandSource source, String[] args) {
        String file = args.length >= 3 ? args[2] : "whitelist.json";
        try { var r = plugin.whitelistTransfer().exportSnapshot(file, plugin.config().chat.defaultChannel); source.sendMessage(Messages.success("已导出 " + r.players() + " 个玩家授权至 transfer/" + r.file() + "。")); plugin.audit().record(source, "WHITELIST_EXPORT", "file=" + r.file() + "; players=" + r.players()); }
        catch (Exception e) { source.sendMessage(Messages.error("白名单导出失败: " + safeError(e))); plugin.audit().record(source, "WHITELIST_EXPORT_FAILED", "file=" + file + "; error=" + safeError(e)); }
    }

    private void importWhitelist(CommandSource source, String[] args) {
        String file = args.length >= 3 ? args[2] : "whitelist.json";
        try {
            var r = plugin.whitelistTransfer().importSnapshot(file, plugin.config().chat.defaultChannel); boolean fileSaved = writeUnifiedWhitelist(source);
            String result = "白名单合并导入完成: 修改 " + r.playersChanged() + " 个玩家，新增 " + r.scopesAdded() + " 个授权" + (r.invalidPlayers() == 0 ? "" : "，忽略 " + r.invalidPlayers() + " 个无效玩家") + (r.invalidScopes() == 0 ? "" : "，忽略 " + r.invalidScopes() + " 个无效授权") + (r.failedSaves() == 0 ? "" : "，" + r.failedSaves() + " 个玩家保存失败") + (fileSaved ? "" : "，whitelist.json 写入失败");
            source.sendMessage(fileSaved && r.failedSaves() == 0 ? Messages.success(result + "。") : Messages.error(result + "。")); plugin.audit().record(source, "WHITELIST_IMPORT", "file=" + r.file() + "; players=" + r.playersChanged() + "; scopes=" + r.scopesAdded() + "; failed=" + r.failedSaves() + "; centralFile=" + fileSaved);
        } catch (Exception e) { source.sendMessage(Messages.error("白名单导入失败: " + safeError(e))); plugin.audit().record(source, "WHITELIST_IMPORT_FAILED", "file=" + file + "; error=" + safeError(e)); }
    }

    private boolean writeUnifiedWhitelist(CommandSource source) {
        try { int count = plugin.writeUnifiedWhitelist(); plugin.audit().record(source, "UNIFIED_WHITELIST_WRITE", "players=" + count); return true; }
        catch (Exception e) { plugin.logger().error("Unable to update unified whitelist", e); plugin.audit().record(source, "UNIFIED_WHITELIST_WRITE_FAILED", safeError(e)); return false; }
    }
    private static String safeError(Exception e) { return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage(); }

    private void checkWhitelist(CommandSource source, String[] args) {
        if (args.length < 4) { source.sendMessage(Messages.info("用法: /dds whitelist check <player> <server>")); return; }
        if (!validateUsername(source, args[2])) return; String serverName = findRegisteredServerName(args[3]); if (serverName == null) { source.sendMessage(Messages.error("不存在服务器 " + args[3])); return; }
        plugin.players().findByUsername(args[2], plugin.config().chat.defaultChannel).ifPresentOrElse(profile -> {
            var online = onlinePlayer(profile); var decision = online.map(player -> plugin.access().evaluate(player, profile, serverName)).orElseGet(() -> plugin.access().evaluateStored(profile, serverName));
            String suffix = online.isPresent() ? "" : "（玩家离线，仅检查存储白名单，不计算在线权限插件结果）"; source.sendMessage(Messages.info(profile.username + " -> " + serverName + ": " + (decision.allowed() ? "允许" : "拒绝") + "，原因: " + decision.reason() + suffix));
        }, () -> source.sendMessage(Messages.error("找不到玩家 " + args[2])));
    }

    private void group(CommandSource source, String[] args) {
        if (args.length < 2) { groupHelp(source); return; } String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) { if (requireAny(source, "dds-manager.group.view", "dds-manager.group.manage")) listGroups(source, args); return; }
        if (!require(source, "dds-manager.group.manage")) return;
        if ((action.equals("delete") || action.equals("remove")) && !confirmDangerous(source, args, action.equals("delete") ? "删除服务器组" : "从服务器组移除服务器")) return;
        String[] copy = args.clone();
        switch (action) {
            case "create" -> queue(source, "group-create", () -> createGroup(source, copy));
            case "delete" -> queue(source, "group-delete", () -> deleteGroup(source, copy));
            case "add" -> queue(source, "group-add", () -> addGroupServers(source, copy));
            case "remove" -> queue(source, "group-remove", () -> removeGroupServers(source, copy));
            default -> groupHelp(source);
        }
    }

    private void createGroup(CommandSource source, String[] args) {
        if (args.length < 3) { source.sendMessage(Messages.info("用法: /dds group create <group>")); return; }
        String name = args[2]; if (!validateGroupName(source, name)) return; if (findGroupName(name) != null) { source.sendMessage(Messages.error("服务器组已存在: " + name)); return; }
        plugin.updateConfig(c -> c.serverGroups.put(name, new java.util.concurrent.ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER))); source.sendMessage(Messages.success("已创建服务器组 " + name + "。")); plugin.audit().record(source, "GROUP_CREATE", "group=" + name);
    }

    private void deleteGroup(CommandSource source, String[] args) {
        if (args.length < 3) { source.sendMessage(Messages.info("用法: /dds group delete <group>")); return; }
        String name = findGroupName(args[2]); if (name == null) { source.sendMessage(Messages.error("不存在服务器组 " + args[2])); return; }
        plugin.updateConfig(c -> c.serverGroups.remove(name)); int cleaned = plugin.players().removeScopesFromAll(List.of(AccessScopes.group(name), name), plugin.config().chat.defaultChannel); int disconnected = plugin.config().features.whitelist ? enforceOnlineAccess() : 0;
        String result = "group=" + name + "; cleaned=" + cleaned + "; disconnected=" + disconnected; source.sendMessage(Messages.success("已删除服务器组 " + name + "，清理 " + cleaned + " 个玩家的组授权" + (disconnected == 0 ? "" : "，断开 " + disconnected + " 名失去当前服务器权限的在线玩家") + "。")); plugin.audit().record(source, "GROUP_DELETE", result);
    }

    private void addGroupServers(CommandSource source, String[] args) {
        if (args.length < 4) { source.sendMessage(Messages.info("用法: /dds group add <group> <server...>")); return; }
        String groupName = findGroupName(args[2]); if (groupName == null) { source.sendMessage(Messages.error("不存在服务器组 " + args[2] + "，请先执行 /dds group create " + args[2])); return; }
        List<String> servers = new ArrayList<>(); for (int i = 3; i < args.length; i++) { String server = findRegisteredServerName(args[i]); if (server == null) { source.sendMessage(Messages.error("不存在服务器 " + args[i] + "，未做任何修改。")); return; } servers.add(server); }
        Set<String> group = plugin.config().serverGroups.get(groupName); long changed = servers.stream().distinct().filter(server -> !group.contains(server)).count();
        plugin.updateConfig(c -> c.serverGroups.get(groupName).addAll(servers)); source.sendMessage(Messages.success("服务器组 " + groupName + " 已添加 " + changed + " 个服务器。")); plugin.audit().record(source, "GROUP_ADD_SERVERS", "group=" + groupName + "; changed=" + changed + "; servers=" + servers);
    }

    private void removeGroupServers(CommandSource source, String[] args) {
        if (args.length < 4) { source.sendMessage(Messages.info("用法: /dds group remove <group> <server...>")); return; }
        String groupName = findGroupName(args[2]); if (groupName == null) { source.sendMessage(Messages.error("不存在服务器组 " + args[2])); return; }
        Set<String> targets = new TreeSet<>(String.CASE_INSENSITIVE_ORDER); targets.addAll(Arrays.asList(Arrays.copyOfRange(args, 3, args.length)));
        long changed = plugin.config().serverGroups.get(groupName).stream().filter(targets::contains).count();
        plugin.updateConfig(c -> c.serverGroups.get(groupName).removeIf(targets::contains)); int disconnected = plugin.config().features.whitelist ? enforceOnlineAccess() : 0; source.sendMessage(Messages.success("服务器组 " + groupName + " 已移除 " + changed + " 个服务器" + (disconnected == 0 ? "" : "，断开 " + disconnected + " 名失去当前服务器权限的在线玩家") + "。")); plugin.audit().record(source, "GROUP_REMOVE_SERVERS", "group=" + groupName + "; changed=" + changed + "; disconnected=" + disconnected);
    }

    private void listGroups(CommandSource source, String[] args) {
        if (args.length >= 3) { String groupName = findGroupName(args[2]); if (groupName == null) { source.sendMessage(Messages.error("不存在服务器组 " + args[2])); return; } source.sendMessage(Messages.info(groupName + ": " + plugin.config().serverGroups.get(groupName))); return; }
        if (plugin.config().serverGroups.isEmpty()) { source.sendMessage(Messages.info("当前没有服务器组。")); return; }
        plugin.config().serverGroups.forEach((g, s) -> source.sendMessage(link(g, "/dds group list " + g, "服务器: " + s).append(Component.text(": " + s))));
    }

    private int enforceOnlineAccess() {
        int disconnected = 0;
        for (Player player : List.copyOf(plugin.proxy().getAllPlayers())) {
            PlayerProfile profile = plugin.players().findByUuid(player.getUniqueId(), plugin.config().chat.defaultChannel).orElse(null);
            if (profile == null) { if (plugin.sessions().get(player).isPresent()) { plugin.sessions().remove(player); plugin.presence().clear(player); plugin.switcher().clear(player); player.disconnect(Messages.error("你的 DDS Manager 白名单记录已被移除。")); disconnected++; } continue; }
            plugin.sessions().put(player, profile); var current = player.getCurrentServer().orElse(null); if (current == null) continue;
            if (!plugin.access().canAccess(player, profile, current.getServerInfo().getName())) { player.disconnect(Messages.error("白名单权限已更新，你已失去当前服务器的访问权限。")); disconnected++; }
        }
        return disconnected;
    }

    private Optional<Player> onlinePlayer(PlayerProfile profile) { return PlayerRepository.parseUuid(profile.uuid).flatMap(plugin.proxy()::getPlayer); }

    private void showPlayer(CommandSource source, PlayerProfile profile) {
        Optional<Player> online = onlinePlayer(profile);
        source.sendMessage(Messages.info(profile.username + " uuid=" + (profile.uuid.isBlank() ? "pending" : profile.uuid) + ", channel=" + profile.channel + ", lastServer=" + profile.lastServer));
        source.sendMessage(Component.text("授权: " + displayScopes(profile), NamedTextColor.GRAY).hoverEvent(HoverEvent.showText(Component.text("存储型授权来源: " + displayScopes(profile)))));
        boolean manage = has(source, "dds-manager.whitelist.manage"), all = hasScope(profile, AccessScopes.ALL);
        if (manage) source.sendMessage(Component.text("全网授权: " + (all ? "已有 " : "无 "), all ? NamedTextColor.GREEN : NamedTextColor.GRAY).append(all ? danger(source, "[彻底删除档案]", "dds whitelist remove all " + profile.username, "彻底删除 " + profile.username + " 的 DDS 身份档案") : link("[添加]", "/dds whitelist add all " + profile.username, "添加全网授权")));
        source.sendMessage(Component.text("服务器访问：", NamedTextColor.AQUA));
        for (var server : plugin.proxy().getAllServers().stream().sorted(Comparator.comparing(s -> s.getServerInfo().getName(), String.CASE_INSENSITIVE_ORDER)).toList()) {
            String name = server.getServerInfo().getName(); var decision = online.map(p -> plugin.access().evaluate(p, profile, name)).orElseGet(() -> plugin.access().evaluateStored(profile, name)); boolean direct = hasScope(profile, AccessScopes.server(name)) || hasScope(profile, name);
            Component line = Component.text("- ").append(Component.text(name, decision.allowed() ? NamedTextColor.GREEN : NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/dds server " + name)).hoverEvent(HoverEvent.showText(Component.text((decision.allowed() ? "允许" : "拒绝") + "\n授权来源: " + decision.reason()))));
            if (manage) line = line.append(Component.space()).append(direct ? danger(source, "[移除]", "dds whitelist remove server " + name + " " + profile.username, "移除 " + profile.username + " 的服务器授权 " + name) : link("[添加]", "/dds whitelist add server " + name + " " + profile.username, "添加服务器授权")); source.sendMessage(line);
        }
        if (!plugin.config().serverGroups.isEmpty()) {
            source.sendMessage(Component.text("服务器组授权：", NamedTextColor.AQUA));
            plugin.config().serverGroups.forEach((group, servers) -> {
                boolean direct = hasScope(profile, AccessScopes.group(group)) || hasScope(profile, group); Component line = Component.text("- " + group + " " + servers, direct ? NamedTextColor.GREEN : NamedTextColor.GRAY).hoverEvent(HoverEvent.showText(Component.text("组成员: " + servers)));
                if (manage) line = line.append(Component.space()).append(direct ? danger(source, "[移除]", "dds whitelist remove group " + group + " " + profile.username, "移除 " + profile.username + " 的服务器组授权 " + group) : link("[添加]", "/dds whitelist add group " + group + " " + profile.username, "添加服务器组授权")); source.sendMessage(line);
            });
        }
    }

    private void confirm(CommandSource source, String[] args) {
        if (args.length != 2) { source.sendMessage(Messages.error("确认请求无效或已过期。")); return; }
        plugin.confirmations().confirm(source, args[1]).ifPresentOrElse(action -> {
            plugin.audit().record(source, "CONFIRM_EXECUTED", action.description()); plugin.proxy().getCommandManager().executeAsync(source, action.command());
        }, () -> source.sendMessage(Messages.error("确认请求无效或已过期。")));
    }

    private void queue(CommandSource source, String name, Runnable task) {
        String[] permissions = managementPermissions(name);
        boolean accepted = plugin.managementTasks().submit(name,
                () -> (!(source instanceof Player p) || plugin.proxy().getPlayer(p.getUniqueId()).orElse(null) == p) && hasAny(source, permissions), () -> {
            try { task.run(); }
            catch (RuntimeException e) { source.sendMessage(Messages.error("管理任务失败: " + safeError(e))); plugin.audit().record(source, "TASK_FAILED", "task=" + name + "; error=" + safeError(e)); throw e; }
        }, () -> { source.sendMessage(Messages.error("管理任务已取消：会话已失效或权限已撤销。")); plugin.audit().record(source, "TASK_DENIED", name); });
        if (!accepted) source.sendMessage(Messages.error("管理任务队列已满或正在关闭，请稍后重试。"));
    }

    private static String[] managementPermissions(String task) {
        return switch (task) {
            case "reload" -> new String[]{"dds-manager.reload"};
            case "whitelist-on", "whitelist-off", "whitelist-add", "whitelist-remove" -> new String[]{"dds-manager.whitelist.manage"};
            case "whitelist-export" -> new String[]{"dds-manager.whitelist.export", "dds-manager.whitelist.manage"};
            case "whitelist-import" -> new String[]{"dds-manager.whitelist.import", "dds-manager.whitelist.manage"};
            case "group-create", "group-delete", "group-add", "group-remove" -> new String[]{"dds-manager.group.manage"};
            default -> throw new IllegalArgumentException("Unknown management task: " + task);
        };
    }

    private Component danger(CommandSource source, String label, String command, String description) {
        String token = plugin.confirmations().create(source, command, description);
        return Component.text(label, NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/dds confirm " + token)).hoverEvent(HoverEvent.showText(Component.text("危险操作：" + description + "\n点击确认执行")));
    }

    private boolean confirmDangerous(CommandSource source, String[] args, String description) {
        String command = "dds " + String.join(" ", args); if (plugin.confirmations().consumeApproval(source, command)) return true;
        String token = plugin.confirmations().create(source, command, description);
        source.sendMessage(Messages.error("危险操作：" + description + " ").append(Component.text("[Confirm]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/dds confirm " + token)).hoverEvent(HoverEvent.showText(Component.text("点击确认执行")))));
        plugin.audit().record(source, "CONFIRM_REQUESTED", description); return false;
    }

    private static Component link(String label, String command, String hover) { return Component.text(label, NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand(command)).hoverEvent(HoverEvent.showText(Component.text(hover))); }
    private static boolean hasScope(PlayerProfile profile, String scope) { return profile.accessScopes != null && profile.accessScopes.stream().anyMatch(value -> AccessScopes.equalsScope(value, scope)); }
    private boolean removeScope(PlayerProfile profile, String scope) { return profile.accessScopes.removeIf(value -> AccessScopes.equalsScope(value, scope)); }
    private String findRegisteredServerName(String input) { return plugin.proxy().getAllServers().stream().map(s -> s.getServerInfo().getName()).filter(n -> n.equalsIgnoreCase(input)).findFirst().orElse(null); }
    private String findGroupName(String input) { return plugin.config().serverGroups.keySet().stream().filter(n -> n.equalsIgnoreCase(input)).findFirst().orElse(null); }
    private boolean validateUsername(CommandSource source, String username) { if (PlayerRepository.isValidUsername(username)) return true; source.sendMessage(Messages.error("无效 Java 玩家名: " + username)); return false; }
    private boolean validateGroupName(CommandSource source, String groupName) { if (groupName != null && groupName.matches(GROUP_NAME_PATTERN)) return true; source.sendMessage(Messages.error("服务器组名称只能包含字母、数字、_、-、.，长度 1-32。")); return false; }
    private String displayScopes(PlayerProfile profile) { return profile.accessScopes == null || profile.accessScopes.isEmpty() ? "[]" : profile.accessScopes.stream().map(AccessScopes::display).sorted(String.CASE_INSENSITIVE_ORDER).toList().toString(); }
    private boolean has(CommandSource source, String permission) { return plugin.access().canManage(source, permission); }
    private boolean hasAny(CommandSource source, String... permissions) { for (String permission : permissions) if (plugin.access().canManage(source, permission)) return true; return false; }
    private boolean require(CommandSource source, String permission) { if (has(source, permission)) return true; source.sendMessage(Messages.error("缺少权限: " + permission)); plugin.audit().record(source, "PERMISSION_DENIED", permission); return false; }
    private boolean requireAny(CommandSource source, String... permissions) { if (hasAny(source, permissions)) return true; String required = String.join(" 或 ", permissions); source.sendMessage(Messages.error("缺少权限: " + required)); plugin.audit().record(source, "PERMISSION_DENIED", required); return false; }

    private void whitelistHelp(CommandSource source) {
        List<String> actions = whitelistActions(source); if (actions.isEmpty()) { requireAny(source, "dds-manager.whitelist.view", "dds-manager.whitelist.manage", "dds-manager.whitelist.export", "dds-manager.whitelist.import"); return; }
        source.sendMessage(Messages.info("/dds whitelist " + String.join("|", actions)));
        if (has(source, "dds-manager.whitelist.manage")) { source.sendMessage(Messages.info("/dds whitelist add|remove all <player...>")); source.sendMessage(Messages.info("/dds whitelist add|remove server <server> <player...>")); source.sendMessage(Messages.info("/dds whitelist add|remove group <group> <player...>")); }
    }
    private void whitelistMutationHelp(CommandSource source, boolean add) { String action = add ? "add" : "remove"; source.sendMessage(Messages.info("/dds whitelist " + action + " all <player...>")); source.sendMessage(Messages.info("/dds whitelist " + action + " server <server> <player...>")); source.sendMessage(Messages.info("/dds whitelist " + action + " group <group> <player...>")); }
    private void groupHelp(CommandSource source) { if (!hasAny(source, "dds-manager.group.view", "dds-manager.group.manage")) { requireAny(source, "dds-manager.group.view", "dds-manager.group.manage"); return; } source.sendMessage(Messages.info(has(source, "dds-manager.group.manage") ? "/dds group create|delete|add|remove|list" : "/dds group list [group]")); }
    private void help(CommandSource source) { source.sendMessage(Messages.info("/dds " + String.join(" | ", rootSuggestions(source)))); }
    @Override public boolean hasPermission(Invocation invocation) { return true; }

    @Override public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        CommandSource source = invocation.source(); String[] args = invocation.arguments();
        if (args.length <= 1) return completed(filter(rootSuggestions(source), args.length == 0 ? "" : args[0]));
        if (args[0].equalsIgnoreCase("channel") && args.length == 2) return completed(filter(List.of("local", "global"), args[1]));
        if (args[0].equalsIgnoreCase("seen") && args.length == 2) return completed(filter(visibleSeenPlayers(source), args[1]));
        if (args[0].equalsIgnoreCase("player") && args.length == 2 && has(source, "dds-manager.player.view")) return completed(filter(knownPlayers(), args[1]));
        if (args[0].equalsIgnoreCase("server") && args.length == 2 && has(source, "dds-manager.server.view")) return completed(filter(serverNames(), args[1]));
        if (args[0].equalsIgnoreCase("whitelist")) return suggestWhitelist(source, args); if (args[0].equalsIgnoreCase("group")) return suggestGroup(source, args); return completed(List.of());
    }

    private CompletableFuture<List<String>> suggestWhitelist(CommandSource source, String[] args) {
        if (args.length == 2) return completed(filter(whitelistActions(source), args[1])); String action = args[1].toLowerCase(Locale.ROOT);
        if ((action.equals("add") || action.equals("remove")) && has(source, "dds-manager.whitelist.manage")) {
            if (args.length == 3) return completed(filter(List.of("all", "server", "group"), args[2])); String type = args[2].toLowerCase(Locale.ROOT);
            if (type.equals("all")) return completed(filter(knownPlayers(), args[args.length - 1])); if (args.length == 4 && type.equals("server")) return completed(filter(serverNames(), args[3])); if (args.length == 4 && type.equals("group")) return completed(filter(groupNames(), args[3])); if (args.length >= 5 && (type.equals("server") || type.equals("group"))) return completed(filter(knownPlayers(), args[args.length - 1]));
        }
        if (action.equals("list") && args.length == 3 && hasAny(source, "dds-manager.whitelist.view", "dds-manager.whitelist.manage")) return completed(filter(knownPlayers(), args[2]));
        if (action.equals("check") && hasAny(source, "dds-manager.whitelist.view", "dds-manager.whitelist.manage")) { if (args.length == 3) return completed(filter(knownPlayers(), args[2])); if (args.length == 4) return completed(filter(serverNames(), args[3])); }
        if (action.equals("export") && args.length == 3 && hasAny(source, "dds-manager.whitelist.export", "dds-manager.whitelist.manage")) return completed(filter(plugin.whitelistTransfer().files(), args[2]));
        if (action.equals("import") && args.length == 3 && hasAny(source, "dds-manager.whitelist.import", "dds-manager.whitelist.manage")) return completed(filter(plugin.whitelistTransfer().files(), args[2])); return completed(List.of());
    }

    private CompletableFuture<List<String>> suggestGroup(CommandSource source, String[] args) {
        if (!hasAny(source, "dds-manager.group.view", "dds-manager.group.manage")) return completed(List.of());
        if (args.length == 2) return completed(filter(has(source, "dds-manager.group.manage") ? List.of("create", "delete", "add", "remove", "list") : List.of("list"), args[1]));
        String action = args[1].toLowerCase(Locale.ROOT); if (action.equals("list") && args.length == 3) return completed(filter(groupNames(), args[2])); if (!has(source, "dds-manager.group.manage")) return completed(List.of());
        if ((action.equals("delete") || action.equals("add") || action.equals("remove")) && args.length == 3) return completed(filter(groupNames(), args[2])); if ((action.equals("add") || action.equals("remove")) && args.length >= 4) return completed(filter(serverNames(), args[args.length - 1])); return completed(List.of());
    }

    private List<String> rootSuggestions(CommandSource source) {
        List<String> values = new ArrayList<>(); if (has(source, "dds-manager.status")) values.add("status"); if (has(source, "dds-manager.server.view")) values.add("server"); if (has(source, "dds-manager.reload")) values.add("reload"); values.add("channel"); values.add("seen"); if (has(source, "dds-manager.player.view")) values.add("player"); if (has(source, "dds-manager.broadcast")) values.add("alert"); if (!whitelistActions(source).isEmpty()) values.add("whitelist"); if (hasAny(source, "dds-manager.group.view", "dds-manager.group.manage")) values.add("group"); return values;
    }
    private List<String> whitelistActions(CommandSource source) {
        List<String> values = new ArrayList<>(); if (hasAny(source, "dds-manager.whitelist.view", "dds-manager.whitelist.manage")) { values.add("status"); values.add("list"); values.add("check"); } if (has(source, "dds-manager.whitelist.manage")) { values.add("on"); values.add("off"); values.add("add"); values.add("remove"); } if (hasAny(source, "dds-manager.whitelist.export", "dds-manager.whitelist.manage")) values.add("export"); if (hasAny(source, "dds-manager.whitelist.import", "dds-manager.whitelist.manage")) values.add("import"); return values;
    }
    private List<String> visibleSeenPlayers(CommandSource source) { if (has(source, "dds-manager.player.view")) return knownPlayers(); return plugin.proxy().getAllPlayers().stream().map(Player::getUsername).sorted(String.CASE_INSENSITIVE_ORDER).toList(); }
    private List<String> serverNames() { return plugin.proxy().getAllServers().stream().map(value -> value.getServerInfo().getName()).sorted(String.CASE_INSENSITIVE_ORDER).toList(); }
    private List<String> groupNames() { return plugin.config().serverGroups.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(); }
    private List<String> knownPlayers() { return plugin.players().knownUsernames().stream().toList(); }
    private static CompletableFuture<List<String>> completed(List<String> values) { return CompletableFuture.completedFuture(values); }
    private static List<String> filter(List<String> values, String remaining) { String lower = remaining.toLowerCase(Locale.ROOT); return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList(); }
}
