/* (C) Copyright 2026, by Ross Richardson
 *
 * Web Build Validation Test.
 *
 * @author ross richardson
 *
 */

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
        @microsim.annotation.GUIparameter public int startYear = 2019;
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
        Field model = SimulationServer.class.getDeclaredField("modelClassName");
        model.setAccessible(true);
        Object oldModel = model.get(null);
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
                            assertEquals(Map.of("code", "parameter_validation_rejected", "error", "Prepared profile requires 2019"), args[0]);
                            yield proxy;
                        }
                        default -> throw new AssertionError("Unexpected context call: " + method.getName());
                    };
                });
        try {
            start.set(null, RejectingBuilder.class.getName());
            model.set(null, RejectingBuilder.class.getName());
            engine.set(null, null);
            Method build = SimulationServer.class.getDeclaredMethod("handleBuild", Context.class);
            build.setAccessible(true);
            build.invoke(null, context);
            assertEquals(400, status.get());
            assertNull(engine.get(null));
        } finally {
            start.set(null, oldStart);
            model.set(null, oldModel);
            engine.set(null, oldEngine);
        }
    }
    public static class ConstrainedBuilder implements ExperimentBuilder, microsim.parameter.ParameterConstraints.Provider {
        @microsim.annotation.GUIparameter public int endYear = 2026;
        public void buildExperiment(SimulationEngine engine) { fail("No setup on rejection"); }
        public Map<String, microsim.parameter.ParameterConstraints.Rule> parameterConstraints() {
            return Map.of("endYear", microsim.parameter.ParameterConstraints.Rule.integerRange(2019, 2026, "Invalid end year"));
        }
    }

    @Test void typeAndRangeRejectionsAreExplicitAndDoNotCreateEngine() throws Exception {
        var saved = new java.util.LinkedHashMap<Field, Object>();
        for (String name : new String[]{"startClassName", "modelClassName", "engine"}) {
            Field field = SimulationServer.class.getDeclaredField(name);
            field.setAccessible(true);
            saved.put(field, field.get(null));
            field.set(null, name.equals("engine") ? null : ConstrainedBuilder.class.getName());
        }
        try {
            Method build = SimulationServer.class.getDeclaredMethod("handleBuild", Context.class);
            build.setAccessible(true);
            for (Object value : new Object[]{2027, 2020.5, "invalid"}) {
                var params = new java.util.LinkedHashMap<String,Object>(Map.of("endYear", value));
                AtomicInteger status = new AtomicInteger();
                Context context = (Context) Proxy.newProxyInstance(Context.class.getClassLoader(),
                    new Class<?>[]{Context.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "bodyAsClass" -> params;
                            case "status" -> { status.set((Integer) args[0]); yield proxy; }
                            case "json" -> {
                                Map<?,?> result = (Map<?,?>) args[0];
                                assertEquals("parameter_validation_rejected", result.get("code"));
                                assertTrue(((Map<?,?>) result.get("fieldErrors")).containsKey("endYear"));
                                yield proxy;
                            }
                            default -> throw new AssertionError(method.getName());
                        };
                    });
                build.invoke(null, context);
                assertEquals(400, status.get());
                assertEquals(Map.of("endYear", value), params);
                for (Field field : saved.keySet()) if (field.getName().equals("engine")) assertNull(field.get(null));
            }
        } finally {
            for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
        }
    }

}
