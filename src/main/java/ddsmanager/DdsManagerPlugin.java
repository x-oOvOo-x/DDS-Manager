package ddsmanager;

import com.github.retrooper.packetevents.PacketEvents;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.retrooper.packetevents.velocity.factory.VelocityPacketEventsBuilder;
import ddsmanager.command.DdsCommand;
import ddsmanager.config.*;
import ddsmanager.data.*;
import ddsmanager.listener.*;
import ddsmanager.service.*;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

@Plugin(id = "dds-manager", name = "DDS Manager", version = BuildInfo.VERSION,
        description = "Lightweight management for Java-only Velocity networks", authors = {"x-oOvOo-x"})
public final class DdsManagerPlugin {
    private static final long PRESENCE_REFRESH_MILLIS = 250L;
    private final ProxyServer proxy; private final Logger logger; private final PluginContainer pluginContainer; private final Path dataDirectory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private AtomicJsonStore jsonStore; private ConfigService configService; private volatile PluginConfig config;
    private PlayerRepository players; private PlayerSessionService sessions; private AccessService access;
    private ChatLogService chatLog; private AuditLogService audit; private ManagementTaskService managementTasks;
    private ConfirmationService confirmations; private ChatBridgeService chatBridge; private TabSyncService tabSync;
    private MinimapWorldSyncService minimap; private NetworkPresenceService presence; private ServerSwitchService switcher;
    private WhitelistTransferService whitelistTransfer; private UnifiedWhitelistService unifiedWhitelist;
    private volatile String unifiedWhitelistStatus = "尚未同步"; private volatile boolean unifiedWhitelistHealthy;
    private PacketPresenceListener packetPresence; private ScheduledTask persistenceTask; private ScheduledTask presenceRefreshTask;

    @Inject public DdsManagerPlugin(ProxyServer proxy, Logger logger, PluginContainer pluginContainer, @DataDirectory Path dataDirectory) {
        this.proxy = proxy; this.logger = logger; this.pluginContainer = pluginContainer; this.dataDirectory = dataDirectory;
    }

    @Subscribe public void onInitialize(ProxyInitializeEvent event) {
        initPacketEvents(); jsonStore = new AtomicJsonStore(gson, logger); configService = new ConfigService(dataDirectory, jsonStore, logger); config = configService.load();
        players = new PlayerRepository(dataDirectory, jsonStore, logger); whitelistTransfer = new WhitelistTransferService(dataDirectory, jsonStore, players);
        unifiedWhitelist = new UnifiedWhitelistService(dataDirectory, jsonStore, players); synchronizeUnifiedWhitelist();
        sessions = new PlayerSessionService(); access = new AccessService(this::config); presence = new NetworkPresenceService(this::config);
        switcher = new ServerSwitchService(); chatLog = new ChatLogService(dataDirectory, logger); audit = new AuditLogService(dataDirectory, logger);
        managementTasks = new ManagementTaskService(logger); confirmations = new ConfirmationService();
        chatBridge = new ChatBridgeService(proxy, sessions, this::config, presence, switcher); tabSync = new TabSyncService(proxy, this::config, presence, logger);
        minimap = new MinimapWorldSyncService(proxy, this::config); minimap.registerChannels();
        proxy.getEventManager().register(this, new ConnectionListener(this)); proxy.getEventManager().register(this, new ServerSwitchListener(this));
        proxy.getEventManager().register(this, new ChatListener(this)); proxy.getEventManager().register(this, new PluginMessageListener(this));
        packetPresence = new PacketPresenceListener(this); PacketEvents.getAPI().getEventManager().registerListener(packetPresence);
        CommandManager manager = proxy.getCommandManager();
        manager.register(manager.metaBuilder("dds").plugin(this).build(), new DdsCommand(this));
        schedulePersistence(); schedulePresenceRefresh(); logger.info("DDS Manager {} initialized with embedded PacketEvents presence", BuildInfo.VERSION);
    }

    private void initPacketEvents() {
        if (proxy.getPluginManager().getPlugin("packetevents").isPresent()) throw new IllegalStateException("DDS Manager 已内嵌 PacketEvents，请移除外部 PacketEvents.jar 后重启 Velocity。");
        PacketEvents.setAPI(VelocityPacketEventsBuilder.build(proxy, pluginContainer, logger, dataDirectory.resolve("packetevents")));
        PacketEvents.getAPI().getSettings().checkForUpdates(false); PacketEvents.getAPI().load(); PacketEvents.getAPI().init();
    }

    private synchronized void schedulePersistence() {
        if (persistenceTask != null) persistenceTask.cancel();
        persistenceTask = proxy.getScheduler().buildTask(this, () -> { players.flushDirty(); players.evictOlderThanMinutes(config.persistence.cacheMinutes, sessions.onlineIds()); })
                .repeat(config.persistence.flushIntervalSeconds, TimeUnit.SECONDS).schedule();
    }
    private synchronized void schedulePresenceRefresh() {
        if (presenceRefreshTask != null) { presenceRefreshTask.cancel(); presenceRefreshTask = null; }
        if (!config.features.syncTabList) return;
        presenceRefreshTask = proxy.getScheduler().buildTask(this, () -> {
            for (var player : List.copyOf(proxy.getAllPlayers())) try {
                String server = ChatBridgeService.serverName(player); if (server.isBlank() || !presence.recoverFromPacketEvents(player, server)) continue;
                tabSync.refreshSubject(player);
            } catch (RuntimeException | LinkageError e) { logger.debug("Unable to synchronize DDS Presence for {}", player.getUsername(), e); }
        }).delay(PRESENCE_REFRESH_MILLIS, TimeUnit.MILLISECONDS).repeat(PRESENCE_REFRESH_MILLIS, TimeUnit.MILLISECONDS).schedule();
    }

    @Subscribe public void onReload(ProxyReloadEvent event) {
        if (!managementTasks.submit("proxy-reload", () -> { int disconnected = reload(); audit.recordSystem("RELOAD", "Velocity reload; disconnected=" + disconnected); }))
            logger.warn("DDS reload rejected: management queue is full or stopping");
    }
    @Subscribe public void onShutdown(ProxyShutdownEvent event) {
        if (persistenceTask != null) persistenceTask.cancel(); if (presenceRefreshTask != null) presenceRefreshTask.cancel();
        if (packetPresence != null && PacketEvents.getAPI() != null) PacketEvents.getAPI().getEventManager().unregisterListener(packetPresence);
        if (PacketEvents.getAPI() != null) PacketEvents.getAPI().terminate(); if (managementTasks != null) managementTasks.close();
        if (players != null) players.flushDirty(); if (chatLog != null) chatLog.close(); if (audit != null) audit.close();
    }

    public synchronized int reload() {
        try { config = configService.loadForReload(); }
        catch (IOException e) { throw new IllegalStateException("配置重载失败，保留当前配置: " + e.getMessage(), e); }
        players.rebuildIndex(); synchronizeUnifiedWhitelist(); int disconnected = 0;
        for (var player : List.copyOf(proxy.getAllPlayers())) {
            PlayerProfile profile = players.loadForPlayer(player, config.chat.defaultChannel); sessions.put(player, profile); if (!config.features.whitelist) continue;
            boolean allowed = player.getCurrentServer().map(c -> access.canAccess(player, profile, c.getServerInfo().getName()))
                    .orElseGet(() -> proxy.getAllServers().stream().anyMatch(s -> access.canAccess(player, profile, s.getServerInfo().getName())));
            if (!allowed) { player.disconnect(ddsmanager.util.Messages.error("配置已重载，你已失去服务器访问权限。")); disconnected++; }
        }
        tabSync.refreshAll(); schedulePersistence(); schedulePresenceRefresh(); return disconnected;
    }
    private void synchronizeUnifiedWhitelist() {
        try { var result = unifiedWhitelist.initializeOrSync(config.chat.defaultChannel); unifiedWhitelistStatus = result.summary(); unifiedWhitelistHealthy = result.failed() == 0; logger.info("Unified whitelist {}", unifiedWhitelistStatus); }
        catch (Exception e) { unifiedWhitelistStatus = "同步失败，保留现有授权: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); unifiedWhitelistHealthy = false; logger.error("Unable to synchronize {}", unifiedWhitelist.file(), e); }
    }

    public synchronized void updateConfig(Consumer<PluginConfig> change) {
        PluginConfig next = gson.fromJson(gson.toJson(config), PluginConfig.class); next.normalize(); change.accept(next);
        try { configService.save(next); }
        catch (IOException e) { throw new IllegalStateException("配置保存失败，未应用修改: " + e.getMessage(), e); }
        config = next;
    }
    public ProxyServer proxy() { return proxy; } public Logger logger() { return logger; } public PluginConfig config() { return config; }
    public PlayerRepository players() { return players; } public PlayerSessionService sessions() { return sessions; } public AccessService access() { return access; }
    public ChatLogService chatLog() { return chatLog; } public ChatBridgeService chatBridge() { return chatBridge; } public TabSyncService tabSync() { return tabSync; }
    public AuditLogService audit() { return audit; } public ManagementTaskService managementTasks() { return managementTasks; } public ConfirmationService confirmations() { return confirmations; }
    public MinimapWorldSyncService minimap() { return minimap; } public NetworkPresenceService presence() { return presence; } public ServerSwitchService switcher() { return switcher; }
    public WhitelistTransferService whitelistTransfer() { return whitelistTransfer; } public UnifiedWhitelistService unifiedWhitelist() { return unifiedWhitelist; }
    public int writeUnifiedWhitelist() throws IOException { int count = unifiedWhitelist.writeCurrent(config.chat.defaultChannel); unifiedWhitelistStatus = "已写入 " + count + " 名玩家"; unifiedWhitelistHealthy = true; return count; }
    public String unifiedWhitelistStatus() { return unifiedWhitelistStatus; } public boolean unifiedWhitelistHealthy() { return unifiedWhitelistHealthy; }
    public List<String> configWarnings() { return configService.validationWarnings(); }
}
