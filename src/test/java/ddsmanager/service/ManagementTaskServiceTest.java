package ddsmanager.service;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

final class ManagementTaskServiceTest {
    @Test void rechecksAuthorizationAfterWaitingInQueue() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), denied = new CountDownLatch(1);
        AtomicBoolean allowed = new AtomicBoolean(true); AtomicInteger executed = new AtomicInteger();
        try (ManagementTaskService service = new ManagementTaskService(LoggerFactory.getLogger("test"))) {
            try {
                assertTrue(service.submit("blocker", () -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(service.submit("mutation", allowed::get, executed::incrementAndGet, denied::countDown));
                allowed.set(false); release.countDown(); assertTrue(denied.await(5, TimeUnit.SECONDS)); assertEquals(0, executed.get());
            } finally { release.countDown(); }
        }
    }
}
