package ddsmanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import ddsmanager.command.DdsCommand;
import ddsmanager.config.*;
import ddsmanager.data.*;
import ddsmanager.listener.*;
import ddsmanager.protocol.VelocityProtocolBridge;
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
    private final ProxyServer proxy; private final Logger logger; private final Path dataDirectory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private AtomicJsonStore jsonStore; private ConfigService configService; private volatile PluginConfig config;
    private PlayerRepository players; private PlayerSessionService sessions; private AccessService access;
    private ChatLogService chatLog; private AuditLogService audit; private ManagementTaskService managementTasks;
    private ConfirmationService confirmations; private ChatBridgeService chatBridge; private TabSyncService tabSync;
    private MinimapWorldSyncService minimap; private NetworkPresenceService presence; private ServerSwitchService switcher;
    private WhitelistTransferService whitelistTransfer; private UnifiedWhitelistService unifiedWhitelist; private VelocityProtocolBridge protocol;
    private volatile String unifiedWhitelistStatus = "尚未同步"; private volatile boolean unifiedWhitelistHealthy;
    private ScheduledTask persistenceTask;

    @Inject public DdsManagerPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy; this.logger = logger; this.dataDirectory = dataDirectory;
    }

    @Subscribe public void onInitialize(ProxyInitializeEvent event) {
        jsonStore = new AtomicJsonStore(gson, logger); configService = new ConfigService(dataDirectory, jsonStore, logger); config = configService.load();
        players = new PlayerRepository(dataDirectory, jsonStore, logger); whitelistTransfer = new WhitelistTransferService(dataDirectory, jsonStore, players);
        unifiedWhitelist = new UnifiedWhitelistService(dataDirectory, jsonStore, players); synchronizeUnifiedWhitelist();
        sessions = new PlayerSessionService(); access = new AccessService(this::config); presence = new NetworkPresenceService(this::config);
        switcher = new ServerSwitchService(); chatLog = new ChatLogService(dataDirectory, logger); audit = new AuditLogService(dataDirectory, logger);
        managementTasks = new ManagementTaskService(logger); confirmations = new ConfirmationService();
        chatBridge = new ChatBridgeService(proxy, sessions, this::config, presence, switcher); tabSync = new TabSyncService(proxy, this::config, presence, logger);
        minimap = new MinimapWorldSyncService(proxy, this::config, new MinimapWorldIdRegistry(dataDirectory, jsonStore, logger)); minimap.registerChannels();
        protocol = new VelocityProtocolBridge(this);
        proxy.getEventManager().register(this, new ConnectionListener(this)); proxy.getEventManager().register(this, new ServerSwitchListener(this));
        proxy.getEventManager().register(this, new ChatListener(this)); proxy.getEventManager().register(this, new PluginMessageListener(this));
        CommandManager manager = proxy.getCommandManager(); manager.register(manager.metaBuilder("dds").plugin(this).build(), new DdsCommand(this));
        schedulePersistence(); logger.debug("DDS Manager {} initialized with lightweight Velocity protocol presence", BuildInfo.VERSION);
    }

    private synchronized void schedulePersistence() {
        if (persistenceTask != null) persistenceTask.cancel();
        persistenceTask = proxy.getScheduler().buildTask(this, () -> { players.flushDirty(); players.evictOlderThanMinutes(config.persistence.cacheMinutes, sessions.onlineIds()); })
                .repeat(config.persistence.flushIntervalSeconds, TimeUnit.SECONDS).schedule();
    }

    @Subscribe public void onReload(ProxyReloadEvent event) {
        if (!managementTasks.submit("proxy-reload", () -> { int disconnected = reload(); audit.recordSystem("RELOAD", "Velocity reload; disconnected=" + disconnected); }))
            logger.warn("DDS reload rejected: management queue is full or stopping");
    }
    @Subscribe public void onShutdown(ProxyShutdownEvent event) {
        // Drain management work first: a queued reload may replace the persistence task or reattach protocol handlers.
        if (managementTasks != null) managementTasks.close();
        if (persistenceTask != null) { persistenceTask.cancel(); persistenceTask = null; }
        if (protocol != null) protocol.close(); if (players != null) players.flushDirty();
        if (chatLog != null) chatLog.close(); if (audit != null) audit.close();
    }

    public synchronized int reload() {
        try { config = configService.loadForReload(); }
        catch (IOException e) { throw new IllegalStateException("配置重载失败，保留当前配置: " + e.getMessage(), e); }
        players.rebuildIndex(); synchronizeUnifiedWhitelist(); int disconnected = 0;
        for (var player : List.copyOf(proxy.getAllPlayers())) {
            protocol.attach(player); PlayerProfile profile = players.loadForPlayer(player, config.chat.defaultChannel); sessions.put(player, profile); if (!config.features.whitelist) continue;
            boolean allowed = player.getCurrentServer().map(c -> access.canAccess(player, profile, c.getServerInfo().getName()))
                    .orElseGet(() -> proxy.getAllServers().stream().anyMatch(s -> access.canAccess(player, profile, s.getServerInfo().getName())));
            if (!allowed) { player.disconnect(ddsmanager.util.Messages.error("配置已重载，你已失去服务器访问权限。")); disconnected++; }
        }
        tabSync.refreshAll(); schedulePersistence(); return disconnected;
    }
    private void synchronizeUnifiedWhitelist() {
        try { var result = unifiedWhitelist.initializeOrSync(config.chat.defaultChannel); unifiedWhitelistStatus = result.summary(); unifiedWhitelistHealthy = result.failed() == 0; logger.debug("Unified whitelist {}", unifiedWhitelistStatus); }
        catch (Exception e) { unifiedWhitelistStatus = "同步失败，保留现有授权: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); unifiedWhitelistHealthy = false; logger.error("Unable to synchronize {}", unifiedWhitelist.file(), e); }
    }

    public synchronized void updateConfig(Consumer<PluginConfig> change) {
        PluginConfig next = gson.fromJson(gson.toJson(config), PluginConfig.class); next.normalize(); change.accept(next); next.normalize();
        try { configService.save(next); }
        catch (IOException e) { throw new IllegalStateException("配置保存失败，未应用修改: " + e.getMessage(), e); }
        config = next;
    }
    public ProxyServer proxy() { return proxy; } public Logger logger() { return logger; } public PluginConfig config() { return config; }
    public PlayerRepository players() { return players; } public PlayerSessionService sessions() { return sessions; } public AccessService access() { return access; }
    public ChatLogService chatLog() { return chatLog; } public ChatBridgeService chatBridge() { return chatBridge; } public TabSyncService tabSync() { return tabSync; }
    public AuditLogService audit() { return audit; } public ManagementTaskService managementTasks() { return managementTasks; } public ConfirmationService confirmations() { return confirmations; }
    public MinimapWorldSyncService minimap() { return minimap; } public NetworkPresenceService presence() { return presence; } public ServerSwitchService switcher() { return switcher; }
    public VelocityProtocolBridge protocol() { return protocol; } public WhitelistTransferService whitelistTransfer() { return whitelistTransfer; } public UnifiedWhitelistService unifiedWhitelist() { return unifiedWhitelist; }
    public int writeUnifiedWhitelist() throws IOException { int count = unifiedWhitelist.writeCurrent(config.chat.defaultChannel); unifiedWhitelistStatus = "已写入 " + count + " 名玩家"; unifiedWhitelistHealthy = true; return count; }
    public String unifiedWhitelistStatus() { return unifiedWhitelistStatus; } public boolean unifiedWhitelistHealthy() { return unifiedWhitelistHealthy; }
    public List<String> configWarnings() { return configService.validationWarnings(); }
}
