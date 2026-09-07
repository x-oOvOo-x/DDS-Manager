package ddsmanager.service;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ConfirmationService {
    private static final long CONFIRM_MS = 120_000, APPROVAL_MS = 10_000;
    private static final int MAX_PENDING = 1024;
    private final Map<String, PendingAction> actions = new ConcurrentHashMap<>();
    private final Map<ApprovalKey, Long> approvals = new ConcurrentHashMap<>();

    public String create(CommandSource source, String command, String description) {
        cleanup();
        if (actions.size() >= MAX_PENDING) actions.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue().expiresAt())).ifPresent(e -> actions.remove(e.getKey()));
        String token = UUID.randomUUID().toString().replace("-", "");
        actions.put(token, new PendingAction(owner(source), clean(command), description, System.currentTimeMillis() + CONFIRM_MS));
        return token;
    }

    public Optional<PendingAction> confirm(CommandSource source, String token) {
        PendingAction action = valid(source, token);
        if (action != null && actions.remove(token, action)) {
            cleanup();
            approvals.put(new ApprovalKey(action.owner(), normalize(action.command())), System.currentTimeMillis() + APPROVAL_MS);
            return Optional.of(action);
        }
        return Optional.empty();
    }

    public boolean consumeApproval(CommandSource source, String command) {
        ApprovalKey key = new ApprovalKey(owner(source), normalize(command)); Long expiresAt = approvals.remove(key);
        return expiresAt != null && expiresAt >= System.currentTimeMillis();
    }

    private PendingAction valid(CommandSource source, String token) {
        if (token == null) return null; PendingAction action = actions.get(token);
        if (action == null) return null;
        if (action.expiresAt() < System.currentTimeMillis()) { actions.remove(token); return null; }
        return action.owner().equals(owner(source)) ? action : null;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        actions.entrySet().removeIf(e -> e.getValue().expiresAt() < now); approvals.entrySet().removeIf(e -> e.getValue() < now);
    }

    private static String clean(String command) {
        String value = command == null ? "" : command.trim(); while (value.startsWith("/")) value = value.substring(1).trim();
        return value.replaceAll("\\s+", " ");
    }
    private static String normalize(String command) { return clean(command).toLowerCase(Locale.ROOT); }
    private static String owner(CommandSource source) { return source instanceof Player p ? p.getUniqueId().toString() : "console"; }
    private record ApprovalKey(String owner, String command) {}
    public record PendingAction(String owner, String command, String description, long expiresAt) {}
}
