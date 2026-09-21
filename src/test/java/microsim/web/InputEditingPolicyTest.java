/* (C) Copyright 2026, by Ross Richardson
 * Checks model-owned read-only rules reject uploads before reading or changing files.
 * @author ross richardson
 */
package microsim.web;
import io.javalin.http.Context;
import microsim.input.InputEditingPolicy;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputEditingPolicyTest {
    public static class Policy implements InputEditingPolicy {
        public String inputReadOnlyReason(String path, State state) {
            assertFalse(path.startsWith("input/"));
            return "Read-only test schedule";
        }
    }
    @Test void uploadCannotBypassReadOnlyPolicy() throws Exception {
        Path root = Path.of("input");
        boolean created = !Files.exists(root);
        Files.createDirectories(root);
        Path file = Files.createTempFile(root, "policy-test-", ".xlsx");
        Files.writeString(file, "original workbook bytes");
        var saved = new LinkedHashMap<Field,Object>();
        for (String name : List.of("startClassName", "engine", "requiresAuth")) {
            Field f = SimulationServer.class.getDeclaredField(name); f.setAccessible(true);
            saved.put(f,f.get(null));
            f.set(null, name.equals("startClassName") ? Policy.class.getName() : name.equals("requiresAuth") ? false : null);
        }
        var status = new AtomicInteger();
        Context ctx = (Context) Proxy.newProxyInstance(Context.class.getClassLoader(), new Class<?>[]{Context.class},
            (proxy, method, args) -> switch(method.getName()) {
                case "queryParam" -> file.getFileName().toString();
                case "status" -> { status.set((Integer)args[0]); yield proxy; }
                case "json" -> { assertEquals("input_read_only", ((Map<?,?>)args[0]).get("code")); yield proxy; }
                default -> throw new AssertionError("Must not consume upload body: " + method.getName());
            });
        try {
            Method upload = SimulationServer.class.getDeclaredMethod("handleInputUpload", Context.class);
            upload.setAccessible(true); upload.invoke(null,ctx);
            assertEquals(403,status.get());
            assertEquals("original workbook bytes",Files.readString(file));
        } finally {
            for(var entry:saved.entrySet()) entry.getKey().set(null,entry.getValue());
            Files.delete(file); if(created) Files.delete(root);
        }
    }
}
