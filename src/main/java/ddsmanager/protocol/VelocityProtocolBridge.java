package ddsmanager.protocol;

import com.velocitypowered.api.proxy.Player;
import ddsmanager.DdsManagerPlugin;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Lightweight PacketEvents-style frontend injection for DDS presence. */
public final class VelocityProtocolBridge implements AutoCloseable {
    private static final String WIRE_HANDLER = "dds-manager-protocol-wire", OBJECT_HANDLER = "dds-manager-protocol-object", ENCODER = "minecraft-encoder";
    private static final long DISPLAY_SETTLE_TTL_MS = 2_000L;
    private static final Method INIT_CHANNEL = initChannelMethod();
    private final DdsManagerPlugin plugin;
    private final Set<UUID> unsupportedLogged = ConcurrentHashMap.newKeySet(), failureLogged = ConcurrentHashMap.newKeySet();
    private final Set<Channel> trackedChannels = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean initializerFailureLogged = new AtomicBoolean(), closed = new AtomicBoolean();
    private final ConcurrentHashMap<UUID, Long> settlingDisplays = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Channel, Player> channelPlayers = new ConcurrentHashMap<>();
    private volatile Object initializerHolder; private volatile ChannelInitializer<Channel> originalInitializer, installedInitializer;

    public VelocityProtocolBridge(DdsManagerPlugin plugin) { this.plugin = plugin; }

    /** Wraps Velocity's frontend ChannelInitializer, matching PacketEvents' reliable injection lifetime. */
    public void initialize() {
        if (closed.get()) return;
        try {
            Object manager = valueBySimpleName(plugin.proxy(), "ConnectionManager");
            Object holder = valueBySimpleName(manager, "ServerChannelInitializerHolder");
            if (!(holder instanceof Supplier<?> supplier)) throw new IllegalStateException("Velocity ServerChannelInitializerHolder unavailable");
            Object current = supplier.get(); if (!(current instanceof ChannelInitializer<?> raw)) throw new IllegalStateException("Velocity frontend initializer unavailable");
            @SuppressWarnings("unchecked") ChannelInitializer<Channel> original = (ChannelInitializer<Channel>) raw;
            ChannelInitializer<Channel> wrapper = new FrontendInitializer(original); setInitializer(holder, wrapper);
            initializerHolder = holder; originalInitializer = original; installedInitializer = wrapper;
            plugin.logger().debug("DDS Presence frontend protocol initializer installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            plugin.logger().warn("DDS Presence could not wrap Velocity frontend initializer; per-player injection fallback remains active", e);
        }
    }

    /** Binds an authenticated Player to its already-injected channel and repairs the taps if necessary. */
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

    private void track(Channel channel, Player player) {
        channelPlayers.put(channel, player);
        if (trackedChannels.add(channel)) channel.closeFuture().addListener(ignored -> {
            channelPlayers.remove(channel); trackedChannels.remove(channel);
        });
    }

    private boolean supported(Player player) {
        if (MinecraftProtocolSupport.supports(player.getProtocolVersion())) return true;
        if (unsupportedLogged.add(player.getUniqueId())) plugin.logger().debug("DDS Presence disabled for unsupported Minecraft protocol {} ({})", player.getProtocolVersion().getProtocol(), player.getProtocolVersion());
        return false;
    }

    private void install(Channel channel) {
        if (closed.get()) return;
        var pipeline = channel.pipeline(); removeNow(pipeline, WIRE_HANDLER); removeNow(pipeline, OBJECT_HANDLER);
        if (pipeline.get(ENCODER) == null) throw new IllegalStateException("Velocity minecraft-encoder is unavailable");
        // Outbound traverses tail -> head: typed packet -> OBJECT -> minecraft-encoder -> WIRE ByteBuf.
        pipeline.addBefore(ENCODER, WIRE_HANDLER, new ClientWireTap());
        try { pipeline.addAfter(ENCODER, OBJECT_HANDLER, new ClientObjectTap()); }
        catch (RuntimeException | LinkageError e) { removeNow(pipeline, WIRE_HANDLER); throw e; }
    }

    public void detach(Player player) {
        UUID uuid = player.getUniqueId(); unsupportedLogged.remove(uuid); failureLogged.remove(uuid); settlingDisplays.remove(uuid);
        try {
            Channel channel = channel(player); if (channel != null) { channelPlayers.remove(channel, player); trackedChannels.remove(channel); remove(channel, WIRE_HANDLER); remove(channel, OBJECT_HANDLER); }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        settlingDisplays.clear(); for (Player player : List.copyOf(plugin.proxy().getAllPlayers())) detach(player);
        channelPlayers.clear(); trackedChannels.clear();
        Object holder = initializerHolder; ChannelInitializer<Channel> wrapper = installedInitializer, original = originalInitializer;
        if (holder != null && wrapper != null && original != null) try {
            if (holder instanceof Supplier<?> supplier && supplier.get() == wrapper) setInitializer(holder, original);
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    private PresenceChange capture(Player player, Object packet) {
        var capture = DimensionPacketInspector.inspect(packet).orElse(null); if (capture == null) return null;
        String server = plugin.presence().packetServer(player, capture.kind() == DimensionPacketInspector.Kind.JOIN_GAME).orElse("");
        if (server.isBlank() || !plugin.presence().update(player, server, capture.signals())) return null;
        return new PresenceChange(server);
    }

    private void publish(Player player, PresenceChange change) {
        if (change == null) return;
        if (plugin.config().features.syncTabList) settlingDisplays.put(player.getUniqueId(), System.currentTimeMillis() + DISPLAY_SETTLE_TTL_MS);
        plugin.logger().debug("DDS Presence: {} @ {} -> {}", player.getUsername(), change.server(), plugin.presence().diagnostic(player, change.server()));
    }

    static Set<UUID> activeSettlingIds(Map<UUID, Long> deadlines, long now) {
        deadlines.entrySet().removeIf(entry -> entry.getValue() < now);
        return Set.copyOf(deadlines.keySet());
    }

    private List<Player> settlingPlayers() {
        if (!plugin.config().features.syncTabList) { settlingDisplays.clear(); return List.of(); }
        List<Player> players = new ArrayList<>();
        for (UUID uuid : activeSettlingIds(settlingDisplays, System.currentTimeMillis()))
            plugin.proxy().getPlayer(uuid).ifPresentOrElse(players::add, () -> settlingDisplays.remove(uuid));
        return players;
    }

    private void refreshDisplays(Player player, PresenceChange change, boolean playerInfo) {
        if (change != null && plugin.config().features.syncTabList) refreshDisplay(player);
        if (playerInfo) settlingPlayers().forEach(this::refreshDisplay);
    }

    private void refreshDisplay(Player player) {
        try { plugin.tabSync().refreshSubjectDisplay(player); }
        catch (RuntimeException e) { plugin.logger().debug("Unable to settle DDS tab display for {}", player.getUsername(), e); }
    }

    static boolean mayReplaceTabDisplay(Object packet) {
        if (packet == null) return false;
        return switch (packet.getClass().getSimpleName()) { case "LegacyPlayerListItemPacket", "UpsertPlayerInfoPacket", "RemovePlayerInfoPacket" -> true; default -> false; };
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
    private static Object valueBySimpleName(Object target, String simpleName) throws ReflectiveOperationException {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) for (Field field : type.getDeclaredFields()) {
            if (!field.getType().getSimpleName().equals(simpleName)) continue; field.setAccessible(true); Object value = field.get(target); if (value != null) return value;
        }
        return null;
    }
    private static void setInitializer(Object holder, ChannelInitializer<Channel> initializer) throws ReflectiveOperationException {
        Method setter = null;
        for (Method method : holder.getClass().getMethods()) if (method.getName().equals("set") && method.getParameterCount() == 1 && ChannelInitializer.class.isAssignableFrom(method.getParameterTypes()[0])) { setter = method; break; }
        if (setter == null) throw new NoSuchMethodException(holder.getClass().getName() + ".set(ChannelInitializer)"); setter.invoke(holder, initializer);
    }
    private static Method initChannelMethod() {
        try { Method method = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private static void run(Channel channel, Runnable task) { if (channel.eventLoop().inEventLoop()) task.run(); else channel.eventLoop().submit(task).syncUninterruptibly(); }
    private static void remove(Channel channel, String name) { Runnable task = () -> removeNow(channel.pipeline(), name); if (channel.eventLoop().inEventLoop()) task.run(); else channel.eventLoop().execute(task); }
    private static void removeNow(ChannelPipeline pipeline, String name) { if (pipeline.get(name) != null) pipeline.remove(name); }

    private record PresenceChange(String server) {}

    private final class FrontendInitializer extends ChannelInitializer<Channel> {
        private final ChannelInitializer<Channel> delegate; private FrontendInitializer(ChannelInitializer<Channel> delegate) { this.delegate = delegate; }
        @Override protected void initChannel(Channel channel) throws Exception {
            try { INIT_CHANNEL.invoke(delegate, channel); }
            catch (ReflectiveOperationException e) { Throwable cause = e.getCause(); if (cause instanceof Exception ex) throw ex; throw e; }
            try { install(channel); }
            catch (RuntimeException | LinkageError e) {
                if (initializerFailureLogged.compareAndSet(false, true))
                    plugin.logger().warn("DDS Presence frontend tap was not ready during channel initialization; per-player fallback remains active", e);
            }
        }
    }

    /** Primary path for Velocity-recognized packets. */
    private final class ClientObjectTap extends ChannelOutboundHandlerAdapter {
        private boolean refreshingDisplay;
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Player player = channelPlayers.get(ctx.channel()); if (player == null) { ctx.write(msg, promise); return; }
            PresenceChange change = msg instanceof ByteBuf ? null : capture(player, msg); boolean playerInfo = mayReplaceTabDisplay(msg);
            ctx.write(msg, promise); publish(player, change);
            if (!refreshingDisplay && (playerInfo || change != null && plugin.config().features.syncTabList)) { refreshingDisplay = true; try { refreshDisplays(player, change, playerInfo); } finally { refreshingDisplay = false; } }
        }
    }

    /** Raw fallback for unknown/pre-encoded packets, reusing Velocity's active protocol registry. */
    private final class ClientWireTap extends ChannelOutboundHandlerAdapter {
        private final VelocityWirePacketInspector inspector = new VelocityWirePacketInspector(); private boolean refreshingDisplay;
        @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Player player = channelPlayers.get(ctx.channel()); if (player == null) { ctx.write(msg, promise); return; }
            PresenceChange change = null; boolean playerInfo = false;
            if (msg instanceof ByteBuf encoded) try {
                var inspection = inspector.inspect(ctx.pipeline().get(ENCODER), encoded).orElse(null);
                if (inspection != null) { playerInfo = inspection.role() == VelocityWirePacketInspector.Role.PLAYER_INFO; if (inspection.packet() != null) change = capture(player, inspection.packet()); }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { logFailure(player, "wire-decode", e); }
            ctx.write(msg, promise); publish(player, change);
            if (!refreshingDisplay && (playerInfo || change != null && plugin.config().features.syncTabList)) { refreshingDisplay = true; try { refreshDisplays(player, change, playerInfo); } finally { refreshingDisplay = false; } }
        }
    }
}
