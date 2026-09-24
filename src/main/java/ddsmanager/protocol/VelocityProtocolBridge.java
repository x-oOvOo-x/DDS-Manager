package ddsmanager.protocol;

import com.velocitypowered.api.proxy.Player;
import ddsmanager.DdsManagerPlugin;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lightweight per-player protocol injection for DDS presence and Tab repair. */
public final class VelocityProtocolBridge implements AutoCloseable {
    private static final String WIRE_HANDLER = "dds-manager-protocol-wire", OBJECT_HANDLER = "dds-manager-protocol-object", ENCODER = "minecraft-encoder";
    private static final long TAB_SETTLE_TTL_MS = 2_000L;
    private static final Set<String> PRESENTATION_ACTIONS = Set.of("ADD_PLAYER", "REMOVE_PLAYER", "UPDATE_GAME_MODE", "UPDATE_LISTED", "UPDATE_DISPLAY_NAME");
    private final DdsManagerPlugin plugin;
    private final Set<UUID> unsupportedLogged = ConcurrentHashMap.newKeySet(), failureLogged = ConcurrentHashMap.newKeySet();
    private final Set<Channel> trackedChannels = ConcurrentHashMap.newKeySet(), typedPlayerInfoWrites = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<UUID, Long> settlingDisplays = new ConcurrentHashMap<>(), settlingViewers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Channel, Player> channelPlayers = new ConcurrentHashMap<>();

    public VelocityProtocolBridge(DdsManagerPlugin plugin) { this.plugin = plugin; }

    /** Injects after authentication. PostLogin fires before Velocity connects the player to the first backend. */
    public boolean attach(Player player) {
        if (closed.get() || !supported(player)) return false;
        Channel channel = null;
        try {
            channel = channel(player); if (channel == null) return false; Channel target = channel;
            track(target, player); run(target, () -> install(target));
            boolean installed = target.pipeline().get(WIRE_HANDLER) != null && target.pipeline().get(OBJECT_HANDLER) != null;
            if (installed) failureLogged.remove(player.getUniqueId()); return installed;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (channel != null) { channelPlayers.remove(channel, player); remove(channel, WIRE_HANDLER); remove(channel, OBJECT_HANDLER); }
            logFailure(player, "attach", e); return false;
        }
    }

    public void settleTabViewer(Player player) {
        UUID uuid = player.getUniqueId();
        if (closed.get() || !plugin.config().features.syncTabList) { settlingViewers.remove(uuid); return; }
        settlingViewers.put(uuid, System.currentTimeMillis() + TAB_SETTLE_TTL_MS);
    }

    private void track(Channel channel, Player player) {
        channelPlayers.put(channel, player);
        if (trackedChannels.add(channel)) channel.closeFuture().addListener(ignored -> {
            channelPlayers.remove(channel); trackedChannels.remove(channel); typedPlayerInfoWrites.remove(channel);
        });
    }

    private boolean supported(Player player) {
        if (MinecraftProtocolSupport.supports(player.getProtocolVersion())) return true;
        if (unsupportedLogged.add(player.getUniqueId())) plugin.logger().debug("DDS Presence disabled for unsupported Minecraft protocol {} ({})", player.getProtocolVersion().getProtocol(), player.getProtocolVersion());
        return false;
    }

    private void install(Channel channel) {
        if (closed.get()) return;
        var pipeline = channel.pipeline();
        if (pipeline.get(WIRE_HANDLER) != null && pipeline.get(OBJECT_HANDLER) != null) return;
        removeNow(pipeline, WIRE_HANDLER); removeNow(pipeline, OBJECT_HANDLER);
        if (pipeline.get(ENCODER) == null) throw new IllegalStateException("Velocity minecraft-encoder is unavailable");
        // Outbound traverses tail -> head: typed packet -> OBJECT -> minecraft-encoder -> WIRE ByteBuf.
        pipeline.addBefore(ENCODER, WIRE_HANDLER, new ClientWireTap());
        try { pipeline.addAfter(ENCODER, OBJECT_HANDLER, new ClientObjectTap()); }
        catch (RuntimeException | LinkageError e) { removeNow(pipeline, WIRE_HANDLER); throw e; }
    }

    public void detach(Player player) {
        UUID uuid = player.getUniqueId(); unsupportedLogged.remove(uuid); failureLogged.remove(uuid); settlingDisplays.remove(uuid); settlingViewers.remove(uuid);
        try {
            Channel channel = channel(player); if (channel != null) { channelPlayers.remove(channel, player); trackedChannels.remove(channel); typedPlayerInfoWrites.remove(channel); remove(channel, WIRE_HANDLER); remove(channel, OBJECT_HANDLER); }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        settlingDisplays.clear(); settlingViewers.clear(); for (Player player : List.copyOf(plugin.proxy().getAllPlayers())) detach(player);
        channelPlayers.clear(); trackedChannels.clear(); typedPlayerInfoWrites.clear();
    }

    private PresenceChange capture(Player player, Object packet) {
        var capture = DimensionPacketInspector.inspect(packet).orElse(null); if (capture == null) return null;
        String server = plugin.presence().packetServer(player, capture.kind() == DimensionPacketInspector.Kind.JOIN_GAME).orElse("");
        if (server.isBlank() || !plugin.presence().update(player, server, capture.signals())) return null;
        return new PresenceChange(server);
    }

    private void publish(Player player, PresenceChange change) {
        if (change == null) return;
        if (plugin.config().features.syncTabList) settlingDisplays.put(player.getUniqueId(), System.currentTimeMillis() + TAB_SETTLE_TTL_MS);
        plugin.logger().debug("DDS Presence: {} @ {} -> {}", player.getUsername(), change.server(), plugin.presence().diagnostic(player, change.server()));
    }

    static Set<UUID> activeSettlingIds(Map<UUID, Long> deadlines, long now) {
        deadlines.entrySet().removeIf(entry -> entry.getValue() < now);
        return Set.copyOf(deadlines.keySet());
    }

    static boolean consumeSettling(Map<UUID, Long> deadlines, UUID uuid, long now) {
        Long deadline = deadlines.remove(uuid);
        return deadline != null && deadline >= now;
    }

    private List<Player> settlingPlayers() {
        if (!plugin.config().features.syncTabList) { settlingDisplays.clear(); return List.of(); }
        List<Player> players = new ArrayList<>();
        for (UUID uuid : activeSettlingIds(settlingDisplays, System.currentTimeMillis()))
            plugin.proxy().getPlayer(uuid).ifPresentOrElse(players::add, () -> settlingDisplays.remove(uuid));
        return players;
    }

    private void refreshDisplays(Player player, PresenceChange change, PlayerInfoChange playerInfo) {
        if (!plugin.config().features.syncTabList) { settlingDisplays.clear(); settlingViewers.clear(); return; }
        if (change != null) refreshDisplay(player);
        if (playerInfo == null) return;

        UUID viewer = player.getUniqueId(); boolean settle = consumeSettling(settlingViewers, viewer, System.currentTimeMillis());
        if (playerInfo.localGameModeChange(viewer)) reapplyViewer(player);
        else if (playerInfo.conservative() || settle) refreshViewer(player);
        else if (playerInfo.affectsPresentation() && !playerInfo.subjects().isEmpty()) repairViewerSubjects(player, playerInfo.subjects());

        settlingPlayers().forEach(this::refreshDisplay);
    }

    private void refreshViewer(Player player) {
        try { plugin.tabSync().refreshViewer(player); }
        catch (RuntimeException e) { plugin.logger().debug("Unable to settle DDS tab viewer {}", player.getUsername(), e); }
    }

    private void reapplyViewer(Player player) {
        try { plugin.tabSync().reapplyViewer(player); }
        catch (RuntimeException e) { plugin.logger().debug("Unable to reapply DDS tab viewer {}", player.getUsername(), e); }
    }

    private void repairViewerSubjects(Player player, Collection<UUID> subjects) {
        try { plugin.tabSync().refreshViewerSubjects(player, subjects); }
        catch (RuntimeException e) { plugin.logger().debug("Unable to repair DDS tab entries for {}", player.getUsername(), e); }
    }

    private void refreshDisplay(Player player) {
        try { plugin.tabSync().refreshSubjectDisplay(player); }
        catch (RuntimeException e) { plugin.logger().debug("Unable to settle DDS tab display for {}", player.getUsername(), e); }
    }

    static boolean mayReplaceTabDisplay(Object packet) { return playerInfoChange(packet) != null; }

    static PlayerInfoChange playerInfoChange(Object packet) {
        if (packet == null) return null;
        try {
            return switch (packet.getClass().getSimpleName()) {
                case "RemovePlayerInfoPacket" -> new PlayerInfoChange(subjectIds(packet, "getProfilesToRemove", null), Set.of("REMOVE_PLAYER"), false);
                case "LegacyPlayerListItemPacket" -> {
                    Object rawAction = invoke(packet, "getAction");
                    int action = rawAction instanceof Number number ? number.intValue() : -1;
                    String name = switch (action) {
                        case 0 -> "ADD_PLAYER";
                        case 1 -> "UPDATE_GAME_MODE";
                        case 2 -> "UPDATE_LATENCY";
                        case 3 -> "UPDATE_DISPLAY_NAME";
                        case 4 -> "REMOVE_PLAYER";
                        default -> "UNKNOWN";
                    };
                    yield new PlayerInfoChange(subjectIds(packet, "getItems", "getUuid"), Set.of(name), action < 0 || action > 4);
                }
                case "UpsertPlayerInfoPacket" -> {
                    Set<String> actions = new LinkedHashSet<>();
                    Object raw = invoke(packet, "getActions");
                    if (raw instanceof Iterable<?> values) for (Object action : values) actions.add(String.valueOf(action));
                    yield new PlayerInfoChange(subjectIds(packet, "getEntries", "getProfileId"), Set.copyOf(actions), false);
                }
                default -> null;
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            return switch (packet.getClass().getSimpleName()) {
                case "RemovePlayerInfoPacket", "LegacyPlayerListItemPacket", "UpsertPlayerInfoPacket" -> PlayerInfoChange.unknown();
                default -> null;
            };
        }
    }

    private static Set<UUID> subjectIds(Object packet, String collectionGetter, String idGetter) throws ReflectiveOperationException {
        Object raw = invoke(packet, collectionGetter); if (!(raw instanceof Iterable<?> values)) return Set.of();
        Set<UUID> ids = new LinkedHashSet<>();
        for (Object value : values) {
            Object id = idGetter == null ? value : invoke(value, idGetter);
            if (id instanceof UUID uuid) ids.add(uuid);
        }
        return Set.copyOf(ids);
    }

    private void logFailure(Player player, String stage, Throwable error) {
        if (failureLogged.add(player.getUniqueId())) plugin.logger().warn("DDS Presence protocol {} failed for {}", stage, player.getUsername(), error);
    }

    private static Channel channel(Object owner) throws ReflectiveOperationException {
        Object connection = invoke(owner, "getConnection"); if (connection == null) return null; Object channel = invoke(connection, "getChannel"); return channel instanceof Channel c ? c : null;
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        if (target == null) return null;
        try { return target.getClass().getMethod(name).invoke(target); }
        catch (NoSuchMethodException ignored) {
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) try { Method method = type.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target); } catch (NoSuchMethodException ignoredAgain) {}
            throw new NoSuchMethodException(target.getClass().getName() + "." + name + "()");
        }
    }

    private static void run(Channel channel, Runnable task) { if (channel.eventLoop().inEventLoop()) task.run(); else channel.eventLoop().submit(task).syncUninterruptibly(); }
    private static void remove(Channel channel, String name) { Runnable task = () -> removeNow(channel.pipeline(), name); if (channel.eventLoop().inEventLoop()) task.run(); else channel.eventLoop().execute(task); }
    private static void removeNow(ChannelPipeline pipeline, String name) { if (pipeline.get(name) != null) pipeline.remove(name); }

    private record PresenceChange(String server) {}

    record PlayerInfoChange(Set<UUID> subjects, Set<String> actions, boolean conservative) {
        private static PlayerInfoChange unknown() { return new PlayerInfoChange(Set.of(), Set.of("UNKNOWN"), true); }
        boolean affectsPresentation() { return actions.stream().anyMatch(PRESENTATION_ACTIONS::contains); }
        boolean localGameModeChange(UUID viewer) { return subjects.contains(viewer) && actions.contains("UPDATE_GAME_MODE"); }
    }

    /** Primary path for Velocity-recognized packets. */
    private final class ClientObjectTap extends ChannelOutboundHandlerAdapter {
        private boolean refreshingDisplay;
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Player player = channelPlayers.get(ctx.channel()); if (player == null) { ctx.write(msg, promise); return; }
            PresenceChange change = msg instanceof ByteBuf ? null : capture(player, msg); PlayerInfoChange playerInfo = msg instanceof ByteBuf ? null : playerInfoChange(msg);
            if (playerInfo != null) typedPlayerInfoWrites.add(ctx.channel());
            try { ctx.write(msg, promise); } finally { if (playerInfo != null) typedPlayerInfoWrites.remove(ctx.channel()); }
            publish(player, change);
            if (!refreshingDisplay && (playerInfo != null || change != null && plugin.config().features.syncTabList)) {
                refreshingDisplay = true; try { refreshDisplays(player, change, playerInfo); } finally { refreshingDisplay = false; }
            }
        }
    }

    /** Raw fallback for unknown/pre-encoded packets, reusing Velocity's active protocol registry. */
    private final class ClientWireTap extends ChannelOutboundHandlerAdapter {
        private final VelocityWirePacketInspector inspector = new VelocityWirePacketInspector(); private boolean refreshingDisplay;
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Player player = channelPlayers.get(ctx.channel()); if (player == null) { ctx.write(msg, promise); return; }
            PresenceChange change = null; PlayerInfoChange playerInfo = null;
            if (msg instanceof ByteBuf encoded && !typedPlayerInfoWrites.contains(ctx.channel())) try {
                var inspection = inspector.inspect(ctx.pipeline().get(ENCODER), encoded).orElse(null);
                if (inspection != null) {
                    if (inspection.role() == VelocityWirePacketInspector.Role.PLAYER_INFO)
                        playerInfo = inspection.packet() == null ? PlayerInfoChange.unknown() : playerInfoChange(inspection.packet());
                    else if (inspection.packet() != null) change = capture(player, inspection.packet());
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { logFailure(player, "wire-decode", e); }
            ctx.write(msg, promise); publish(player, change);
            if (!refreshingDisplay && (playerInfo != null || change != null && plugin.config().features.syncTabList)) {
                refreshingDisplay = true; try { refreshDisplays(player, change, playerInfo); } finally { refreshingDisplay = false; }
            }
        }
    }
}
