/* (C) Copyright 2026, by Ross Richardson
 * Restricted interactive routes reject console access while preserving safe status.
 * @author ross richardson
 */
package microsim.web.server;

import io.javalin.http.Context;
import microsim.web.SimulationServer;
import java.lang.reflect.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RestrictedConsoleAccessTest {
    @Test
    void allConsoleHandlersDenyRestrictedRequestsBeforeReadingQueryOrLogContents() throws Exception {
        Field allowed=SimulationServer.class.getDeclaredField("detailedDataAccessAllowed");
        allowed.setAccessible(true);
        boolean previous=allowed.getBoolean(null);
        SimulationServer.addLogMessage("fictional household identifier 7654321");
        try {
            allowed.setBoolean(null, false);
            for (String name : new String[] {"handleLogPoll", "handleLogTail", "handleLogSearch"}) {
                Capture capture=new Capture();
                invoke(name, capture.context);
                assertEquals(403, capture.status);
                assertEquals(Map.of("error", LogRequestUtils.RESTRICTED_LOG_MESSAGE), capture.body);
                assertFalse(capture.body.toString().contains("7654321"));
            }
            Capture safe=new Capture();
            invoke("handleSafeDiagnostics", safe.context);
            assertTrue(safe.body instanceof Map<?,?>);
            assertTrue(((Map<?,?>)safe.body).containsKey("state"));
            assertFalse(safe.body.toString().contains("7654321"));
        } finally { allowed.setBoolean(null, previous); }
    }

    private static void invoke(String name, Context context) throws Exception {
        Method handler=SimulationServer.class.getDeclaredMethod(name, Context.class);
        handler.setAccessible(true);
        handler.invoke(null, context);
    }

    private static class Capture {
        int status=200;
        Object body;
        final Context context=(Context)Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[] {Context.class},
            (proxy, method, args) -> {
                if (method.getName().equals("status")) { status=(Integer)args[0]; return proxy; }
                if (method.getName().equals("json")) { body=args[0]; return proxy; }
                throw new AssertionError("Unexpected request access: "+method.getName());
            });
    }
}
