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
import ddsmanager.i18n.LanguageService;
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
    private AtomicJsonStore jsonStore; private LanguageService language; private ConfigService configService; private volatile PluginConfig config;
    private PlayerRepository players; private BanRepository bans; private PlayerSessionService sessions; private AccessService access;
    private ChatLogService chatLog; private AuditLogService audit; private ManagementTaskService managementTasks;
    private ConfirmationService confirmations; private ChatBridgeService chatBridge; private TabSyncService tabSync;
    private MinimapWorldSyncService minimap; private NetworkPresenceService presence; private ServerSwitchService switcher;
    private WhitelistTransferService whitelistTransfer; private UnifiedWhitelistService unifiedWhitelist; private VelocityProtocolBridge protocol;
    private volatile String unifiedWhitelistStatus = "Not synchronized"; private volatile boolean unifiedWhitelistHealthy;
    private volatile UnifiedWhitelistService.SyncResult unifiedWhitelistReport;
    private volatile String unifiedWhitelistFailure;
    private volatile int unifiedWhitelistWritten = -1;
    private ScheduledTask persistenceTask;

    @Inject public DdsManagerPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy; this.logger = logger; this.dataDirectory = dataDirectory;
    }

    @Subscribe public void onInitialize(ProxyInitializeEvent event) {
        jsonStore = new AtomicJsonStore(gson, logger); configService = new ConfigService(dataDirectory, jsonStore, logger); config = configService.load();
        language = new LanguageService(dataDirectory, logger, this::config); language.reload();
        players = new PlayerRepository(dataDirectory, jsonStore, logger); bans = new BanRepository(dataDirectory, jsonStore, logger); whitelistTransfer = new WhitelistTransferService(dataDirectory, jsonStore, players);
        unifiedWhitelist = new UnifiedWhitelistService(dataDirectory, jsonStore, players); synchronizeUnifiedWhitelist();
        sessions = new PlayerSessionService(); access = new AccessService(this::config); presence = new NetworkPresenceService(this::config, language);
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
        catch (IOException e) { throw new IllegalStateException("Configuration reload failed; keeping current config: " + e.getMessage(), e); }
        language.reload();
        players.rebuildIndex(); bans.reload(); synchronizeUnifiedWhitelist(); int disconnected = 0;
        for (var player : List.copyOf(proxy.getAllPlayers())) {
            var ban = bans.find(player.getUniqueId());
            if (ban.isPresent()) { player.disconnect(language.error(player, "ban.denied", "reason", ban.get().reason)); disconnected++; continue; }
            protocol.attach(player); PlayerProfile profile = players.loadForPlayer(player, config.chat.defaultChannel); sessions.put(player, profile); if (!config.features.whitelist) continue;
            boolean allowed = player.getCurrentServer().map(c -> access.canAccess(player, profile, c.getServerInfo().getName()))
                    .orElseGet(() -> proxy.getAllServers().stream().anyMatch(s -> access.canAccess(player, profile, s.getServerInfo().getName())));
            if (!allowed) { player.disconnect(language.error(player, "reload.disconnected")); disconnected++; }
        }
        tabSync.refreshAll(); schedulePersistence(); return disconnected;
    }
    private void synchronizeUnifiedWhitelist() {
        try { var result = unifiedWhitelist.initializeOrSync(config.chat.defaultChannel); unifiedWhitelistStatus = result.summary(); unifiedWhitelistHealthy = result.failed() == 0;
            unifiedWhitelistReport = result; unifiedWhitelistFailure = null; unifiedWhitelistWritten = -1;
            logger.debug("Unified whitelist {}", unifiedWhitelistStatus); }
        catch (Exception e) { unifiedWhitelistFailure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            unifiedWhitelistStatus = "Sync failed; keeping existing grants: " + unifiedWhitelistFailure;
            unifiedWhitelistReport = null; unifiedWhitelistWritten = -1; unifiedWhitelistHealthy = false;
            logger.error("Unable to synchronize {}", unifiedWhitelist.file(), e); }
    }

    public synchronized void updateConfig(Consumer<PluginConfig> change) {
        PluginConfig next = gson.fromJson(gson.toJson(config), PluginConfig.class); next.normalize(); change.accept(next); next.normalize();
        try { configService.save(next); }
        catch (IOException e) { throw new IllegalStateException("Config save failed; changes not applied: " + e.getMessage(), e); }
        config = next;
    }
    public LanguageService language() { return language; }
    public ProxyServer proxy() { return proxy; } public Logger logger() { return logger; } public PluginConfig config() { return config; }
    public PlayerRepository players() { return players; } public BanRepository bans() { return bans; } public PlayerSessionService sessions() { return sessions; } public AccessService access() { return access; }
    public ChatLogService chatLog() { return chatLog; } public ChatBridgeService chatBridge() { return chatBridge; } public TabSyncService tabSync() { return tabSync; }
    public AuditLogService audit() { return audit; } public ManagementTaskService managementTasks() { return managementTasks; } public ConfirmationService confirmations() { return confirmations; }
    public MinimapWorldSyncService minimap() { return minimap; } public NetworkPresenceService presence() { return presence; } public ServerSwitchService switcher() { return switcher; }
    public VelocityProtocolBridge protocol() { return protocol; } public WhitelistTransferService whitelistTransfer() { return whitelistTransfer; } public UnifiedWhitelistService unifiedWhitelist() { return unifiedWhitelist; }
    public int writeUnifiedWhitelist() throws IOException { int count = unifiedWhitelist.writeCurrent(config.chat.defaultChannel); unifiedWhitelistStatus = "Wrote " + count + " players"; unifiedWhitelistWritten = count; unifiedWhitelistFailure = null; unifiedWhitelistHealthy = true; return count; }
    public String unifiedWhitelistStatus() { return unifiedWhitelistStatus; } public boolean unifiedWhitelistHealthy() { return unifiedWhitelistHealthy; }
    public String localizedUnifiedWhitelistStatus(com.velocitypowered.api.command.CommandSource source) {
        if (unifiedWhitelistFailure != null) return language.format(source, "whitelist.sync.failed", "error", unifiedWhitelistFailure);
        if (unifiedWhitelistWritten >= 0) return language.format(source, "whitelist.sync.written", "written", unifiedWhitelistWritten);
        var report = unifiedWhitelistReport;
        if (report == null) return language.format(source, "whitelist.sync.pending");
        if (report.failed() > 0) return language.format(source, "whitelist.sync.partial", "added", report.added(), "removed", report.removed(), "failed", report.failed());
        if (report.created()) return language.format(source, "whitelist.sync.created", "written", report.playersWritten());
        return language.format(source, "whitelist.sync.success", "added", report.added(), "removed", report.removed(), "written", report.playersWritten());
    }
    public List<String> configWarnings() { return configService.validationWarnings(); }
}
