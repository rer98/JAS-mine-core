package microsim.web;

import io.javalin.http.Context;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import microsim.engine.ExperimentBuilder;
import microsim.engine.SimulationEngine;
import microsim.web.server.WebBuildValidator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebBuildValidationTest {
    public static class RejectingBuilder implements ExperimentBuilder, WebBuildValidator {
        public void buildExperiment(SimulationEngine engine) {
            fail("Rejected requests must not construct managers");
        }
        public void validateWebBuildParameters(Map<String, Object> params) {
            assertEquals(2011, params.get("startYear"));
            throw new IllegalArgumentException("Prepared profile requires 2019");
        }
    }

    @Test
    void rejectedProfileReturns400BeforeEngineMutation() throws Exception {
        Field start = SimulationServer.class.getDeclaredField("startClassName");
        Field engine = SimulationServer.class.getDeclaredField("engine");
        start.setAccessible(true);
        engine.setAccessible(true);
        Object oldStart = start.get(null), oldEngine = engine.get(null);
        AtomicInteger status = new AtomicInteger();
        Context context = (Context) Proxy.newProxyInstance(Context.class.getClassLoader(),
                new Class<?>[]{Context.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "bodyAsClass" -> Map.of("startYear", 2011);
                        case "status" -> { status.set((Integer) args[0]); yield proxy; }
                        case "json" -> {
                            assertEquals(Map.of("error", "Prepared profile requires 2019"), args[0]);
                            yield proxy;
                        }
                        default -> throw new AssertionError("Unexpected context call: " + method.getName());
                    };
                });
        try {
            start.set(null, RejectingBuilder.class.getName());
            engine.set(null, null);
            Method build = SimulationServer.class.getDeclaredMethod("handleBuild", Context.class);
            build.setAccessible(true);
            build.invoke(null, context);
            assertEquals(400, status.get());
            assertNull(engine.get(null));
        } finally {
            start.set(null, oldStart);
            engine.set(null, oldEngine);
        }
    }
}
