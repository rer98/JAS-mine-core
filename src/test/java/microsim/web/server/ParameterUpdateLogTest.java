package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for ParameterUpdateLog.
 * Verifies the focused JAS-mine Web helper behaviour implemented by ParameterUpdateLog
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class ParameterUpdateLogTest {
    @Test
    public void appendCreatesHeaderAndRowsInInsertionOrder() throws Exception {
        Path dir = Files.createTempDirectory("parameter-update-log");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("alpha", 1);
        params.put("needs,escaping", "hello, world");
        params.put("empty", null);

        ParameterUpdateLog.append(dir.toFile(), 12.5, params);

        assertEquals(
            String.join(System.lineSeparator(),
                "time,parameter,value",
                "12.5,alpha,1",
                "12.5,\"needs,escaping\",\"hello, world\"",
                "12.5,empty,"
            ) + System.lineSeparator(),
            Files.readString(dir.resolve("GUIparameters.csv"))
        );
    }

    @Test
    public void appendDoesNotRepeatHeader() throws Exception {
        Path dir = Files.createTempDirectory("parameter-update-log-append");

        ParameterUpdateLog.append(dir.toFile(), 1.0, Map.of("first", 10));
        ParameterUpdateLog.append(dir.toFile(), 2.0, Map.of("second", 20));

        String csv = Files.readString(dir.resolve("GUIparameters.csv"));
        assertEquals(1, csv.split("time,parameter,value", -1).length - 1);
        assertTrue(csv.contains("1.0,first,10"));
        assertTrue(csv.contains("2.0,second,20"));
    }
}
