/* (C) Copyright 2026, by Ross Richardson
 *
 * Web Reset Database Test.
 *
 * @author ross richardson
 *
 */

package microsim.web;

import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import microsim.data.db.DatabaseUtils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebResetDatabaseTest {
    @Test void disposingWebStatePreservesSessionOutput() throws Exception {
        Field output = DatabaseUtils.class.getDeclaredField("outEntityManagerFactory");
        Field engine = SimulationServer.class.getDeclaredField("engine");
        output.setAccessible(true); engine.setAccessible(true);
        Object oldOutput = output.get(null), oldEngine = engine.get(null);
        AtomicBoolean outputOpen = new AtomicBoolean(true);
        try {
            engine.set(null, null);
            EntityManagerFactory sessionOutput = factory(outputOpen);
            output.set(null, sessionOutput);
            Method dispose = SimulationServer.class.getDeclaredMethod("disposeSimulationState", boolean.class);
            dispose.setAccessible(true);
            dispose.invoke(null, false);
            assertTrue(outputOpen.get());
            assertSame(sessionOutput, output.get(null));
            dispose.invoke(null, false); // Repeated reset is safe.
            assertTrue(outputOpen.get());
            assertSame(sessionOutput, output.get(null));
        } finally {
            output.set(null, oldOutput); engine.set(null, oldEngine);
        }
    }

    private EntityManagerFactory factory(AtomicBoolean open) {
        return (EntityManagerFactory) Proxy.newProxyInstance(EntityManagerFactory.class.getClassLoader(),
            new Class<?>[]{EntityManagerFactory.class}, (proxy, method, args) -> switch (method.getName()) {
                case "isOpen" -> open.get();
                case "close" -> { open.set(false); yield null; }
                default -> throw new AssertionError(method.getName());
            });
    }
}
