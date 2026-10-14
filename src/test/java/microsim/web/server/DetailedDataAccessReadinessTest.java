/* (C) Copyright 2026, by Ross Richardson
 * Model data policy remains authenticated and readable while Build owns the engine lock.
 * @author ross richardson
 */
package microsim.web.server;

import io.javalin.http.Context;
import microsim.web.SimulationServer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DetailedDataAccessReadinessTest {
    @Test
    void policyResponsesDoNotWaitBehindBuildAndRestrictedRequestsStillNeedAuthority() throws Exception {
        Field allowed = field("detailedDataAccessAllowed");
        Field restricted = field("requiresAuth");
        boolean previousAllowed = allowed.getBoolean(null), previousRestricted = restricted.getBoolean(null);
        ReentrantReadWriteLock lock = (ReentrantReadWriteLock) field("lock").get(null);
        CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        var build = workers.submit(() -> {
            lock.writeLock().lock();
            try { held.countDown(); release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { lock.writeLock().unlock(); }
        });
        try {
            assertTrue(held.await(2, TimeUnit.SECONDS));
            restricted.setBoolean(null, false);
            for (boolean policy : new boolean[]{true, false}) {
                allowed.setBoolean(null, policy);
                Capture capture = new Capture();
                workers.submit(() -> invoke(capture.context)).get(2, TimeUnit.SECONDS);
                assertEquals(200, capture.status);
                assertEquals(Map.of("detailedDataAccessAllowed", policy), capture.body);
            }
            restricted.setBoolean(null, true);
            Capture denied = new Capture();
            workers.submit(() -> invoke(denied.context)).get(2, TimeUnit.SECONDS);
            assertEquals(403, denied.status);
            assertEquals(Map.of("error", "Invalid or missing data token"), denied.body);
        } finally {
            release.countDown();
            build.get(2, TimeUnit.SECONDS);
            workers.shutdown();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
            allowed.setBoolean(null, previousAllowed);
            restricted.setBoolean(null, previousRestricted);
        }
    }

    private static Field field(String name) throws Exception {
        Field value = SimulationServer.class.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static void invoke(Context context) {
        try {
            Method handler = SimulationServer.class.getDeclaredMethod("handleDetailedDataAccess", Context.class);
            handler.setAccessible(true);
            handler.invoke(null, context);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static class Capture {
        int status = 200;
        Object body;
        final Context context = (Context) Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[]{Context.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "status" -> { status = (Integer) args[0]; yield proxy; }
                case "json" -> { body = args[0]; yield proxy; }
                case "header", "queryParam" -> null;
                default -> throw new AssertionError("Unexpected request access: " + method.getName());
            });
    }
}
