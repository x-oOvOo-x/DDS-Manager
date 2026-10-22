package ddsmanager.service;

import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

final class ConfirmationServiceTest {
    @Test void confirmsOnceAndOnlyForOwner() {
        ConfirmationService service = new ConfirmationService();
        Player owner = player(UUID.randomUUID()), other = player(UUID.randomUUID());
        String token = service.create(owner, "dds whitelist remove all Steve", "删除 Steve");

        assertTrue(service.confirm(other, token).isEmpty());
        assertEquals("dds whitelist remove all Steve", service.confirm(owner, token).orElseThrow().command());
        assertTrue(service.consumeApproval(owner, "/DDS   WHITELIST REMOVE ALL Steve"));
        assertFalse(service.consumeApproval(owner, "dds whitelist remove all Steve"));
        assertTrue(service.confirm(owner, token).isEmpty());
    }

    @Test void consoleCanConfirmByTypingCommandAndCannotReplay() {
        ConfirmationService service = new ConfirmationService();
        com.velocitypowered.api.command.CommandSource console =
                (com.velocitypowered.api.command.CommandSource) Proxy.newProxyInstance(
                        com.velocitypowered.api.command.CommandSource.class.getClassLoader(),
                        new Class<?>[]{com.velocitypowered.api.command.CommandSource.class},
                        (proxy, method, args) -> null);
        Player player = player(UUID.randomUUID());
        String command = "dds ban add 00000000-0000-0000-0000-000000000001 test";
        String token = service.create(console, command, "UUID ban");

        assertTrue(service.confirm(player, token).isEmpty(), "player must not confirm a console action");
        assertEquals(command, service.confirm(console, token).orElseThrow().command());
        assertTrue(service.consumeApproval(console, command));
        assertFalse(service.consumeApproval(console, command), "approval is single-use");
        assertTrue(service.confirm(console, token).isEmpty(), "token is single-use");
    }

    @Test void concurrentConfirmationsExecuteOnlyOnce() throws Exception {
        ConfirmationService service = new ConfirmationService(); UUID uuid = UUID.randomUUID();
        Player owner = player(uuid); String token = service.create(owner, "dds reload", "reload");
        CyclicBarrier readers = new CyclicBarrier(8);
        Player concurrentOwner = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> { if (!method.getName().equals("getUniqueId")) return null; readers.await(5, TimeUnit.SECONDS); return uuid; });
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> service.confirm(concurrentOwner, token).isPresent()));
            int confirmed = 0; for (var result : results) if (result.get(10, TimeUnit.SECONDS)) confirmed++;
            assertEquals(1, confirmed); assertTrue(service.consumeApproval(owner, "dds reload"));
            assertFalse(service.consumeApproval(owner, "dds reload"));
        } finally { executor.shutdownNow(); }
    }

    private static Player player(UUID uuid) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getUsername" -> "Admin";
                    default -> null;
                });
    }
}
