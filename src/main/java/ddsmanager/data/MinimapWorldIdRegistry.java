package ddsmanager.data;

import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.CRC32;

public final class MinimapWorldIdRegistry {
    private final Path file; private final AtomicJsonStore store; private final Logger logger; private State state;

    public MinimapWorldIdRegistry(Path dataDirectory, AtomicJsonStore store, Logger logger) {
        this.file = dataDirectory.resolve("minimap-world-ids.json"); this.store = store; this.logger = logger; this.state = load();
    }

    public synchronized int idFor(String serverName, InetSocketAddress address) {
        String key = key(serverName), endpoint = endpoint(address); Entry current = state.servers.get(key);
        if (current != null) {
            if (!endpoint.isEmpty() && !endpoint.equals(current.endpoint)) { State next = state.copy(); next.servers.put(key, new Entry(current.xaeroId, endpoint)); persist(next); }
            return current.xaeroId;
        }

        Entry sameEndpoint = endpoint.isEmpty() ? null : state.servers.values().stream().filter(e -> endpoint.equals(e.endpoint)).findFirst().orElse(null);
        int id = sameEndpoint == null ? allocate(serverName, endpoint) : sameEndpoint.xaeroId;
        State next = state.copy(); next.servers.put(key, new Entry(id, endpoint)); persist(next); return id;
    }

    static int legacyId(String serverName) { CRC32 crc = new CRC32(); crc.update(serverName.getBytes(StandardCharsets.UTF_8)); return (int) crc.getValue(); }

    private int allocate(String serverName, String endpoint) {
        for (int salt = 0; ; salt++) {
            String source = salt == 0 ? serverName : serverName + "#" + salt; int candidate = legacyId(source);
            boolean conflict = state.servers.values().stream().anyMatch(e -> e.xaeroId == candidate && (endpoint.isEmpty() || !endpoint.equals(e.endpoint)));
            if (!conflict) return candidate;
        }
    }

    private State load() {
        try { return store.read(file, State.class).map(State::normalize).orElseGet(State::new); }
        catch (IOException e) { throw new IllegalStateException("无法读取小地图世界 ID 注册表: " + file, e); }
    }

    private void persist(State next) {
        try { store.write(file, next); state = next; }
        catch (IOException e) { logger.error("Unable to persist minimap world IDs to {}", file, e); }
    }

    private static String key(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String endpoint(InetSocketAddress value) { return value == null ? "" : value.getHostString().toLowerCase(Locale.ROOT) + ":" + value.getPort(); }

    private static final class State {
        int schemaVersion = 1; Map<String, Entry> servers = new TreeMap<>();
        State normalize() {
            schemaVersion = 1; Map<String, Entry> clean = new TreeMap<>();
            if (servers != null) servers.forEach((name, entry) -> { if (entry != null && !key(name).isEmpty()) clean.put(key(name), new Entry(entry.xaeroId, entry.endpoint == null ? "" : entry.endpoint.trim().toLowerCase(Locale.ROOT))); });
            servers = clean; return this;
        }
        State copy() { State next = new State(); next.servers.putAll(servers); return next; }
    }

    private static final class Entry {
        int xaeroId; String endpoint;
        Entry(int xaeroId, String endpoint) { this.xaeroId = xaeroId; this.endpoint = endpoint == null ? "" : endpoint; }
    }
}
