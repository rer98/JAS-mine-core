package microsim.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Unit tests for WebServerConfig.
 * Verifies the focused JAS-mine Web helper behaviour implemented by WebServerConfig
 * so SimulationServer can delegate that concern without changing endpoint contracts.
 *
 * @author ross richardson
 *
 */

public class WebServerConfigTest {
    @Test
    public void loadsDefaultsAndDerivesClassNames() {
        Properties props = baseProps();
        WebServerConfig cfg = WebServerConfig.fromProperties(props, Map.of());

        assertEquals("Demo", cfg.getModelPrefix());
        assertEquals("model", cfg.getModelPackage());
        assertEquals("experiment", cfg.getExperimentPackage());
        assertEquals(7070, cfg.getServerPort());
        assertEquals("http://localhost:5001", cfg.getCorsAllowedHosts());
        assertTrue(cfg.isDetailedDataAccessAllowed());
        assertFalse(cfg.isRequiresAuth());
        assertEquals(5000, cfg.getDbQueryMaxRows());
        assertEquals(60, cfg.getDbQueryTimeoutSeconds());
        assertEquals("Demo", cfg.getModelId());
        assertEquals("Demo", cfg.getSimId());
        assertEquals("model.DemoModel", cfg.getModelClassName());
        assertEquals("experiment.DemoCollector", cfg.getCollectorClassName());
        assertEquals("experiment.DemoObserver", cfg.getObserverClassName());
        assertEquals("experiment.DemoStart", cfg.getStartClassName());
    }

    @Test
    public void appliesPropertiesAndEnvironmentOverrides() {
        Properties props = baseProps();
        props.setProperty("model.package", "m");
        props.setProperty("experiment.package", "e");
        props.setProperty("server.port", "9090");
        props.setProperty("cors.allowed.hosts", "http://example.test");
        props.setProperty("allowDetailedDataAccess", "false");
        props.setProperty("model.id", "model-from-props");
        props.setProperty("sim.id", "sim-from-props");
        props.setProperty("hardReset.secret", "props-hard-reset");
        props.setProperty("db.query.maxRows", "123");
        props.setProperty("db.query.timeoutSeconds", "7");

        WebServerConfig cfg = WebServerConfig.fromProperties(props, Map.of(
            "MODEL_ID", " env-model ",
            "SIM_ID", " env-sim ",
            "REQUIRES_AUTH", "true",
            "DATA_PLANE_SECRET", " secret ",
            "HARD_RESET_SECRET", " env-hard-reset ",
            "DB_QUERY_MAX_ROWS", "456",
            "DB_QUERY_TIMEOUT_SECONDS", "8"
        ));

        assertEquals("m", cfg.getModelPackage());
        assertEquals("e", cfg.getExperimentPackage());
        assertEquals(9090, cfg.getServerPort());
        assertEquals("http://example.test", cfg.getCorsAllowedHosts());
        assertFalse(cfg.isDetailedDataAccessAllowed());
        assertTrue(cfg.isRequiresAuth());
        assertEquals(456, cfg.getDbQueryMaxRows());
        assertEquals(8, cfg.getDbQueryTimeoutSeconds());
        assertEquals("env-model", cfg.getModelId());
        assertEquals("env-sim", cfg.getSimId());
        assertEquals("env-hard-reset", cfg.getHardResetSecret());
        assertEquals("secret", cfg.getDataPlaneSecret());
        assertEquals("m.DemoModel", cfg.getModelClassName());
        assertEquals("e.DemoStart", cfg.getStartClassName());
    }

    @Test
    public void fallsBackFromBlankEnvironmentToProperties() {
        Properties props = baseProps();
        props.setProperty("model.id", "model-prop");
        props.setProperty("sim.id", "sim-prop");
        props.setProperty("hardReset.secret", "hard-reset-prop");

        WebServerConfig cfg = WebServerConfig.fromProperties(props, Map.of(
            "MODEL_ID", " ",
            "SIM_ID", " ",
            "HARD_RESET_SECRET", " "
        ));

        assertEquals("model-prop", cfg.getModelId());
        assertEquals("sim-prop", cfg.getSimId());
        assertEquals("hard-reset-prop", cfg.getHardResetSecret());
    }

    @Test
    public void validatesRequiredModelPrefixAndPort() {
        Properties props = new Properties();
        assertBadConfig(props, "model.prefix is required in webserver.properties");

        props = baseProps();
        props.setProperty("server.port", "abc");
        assertBadConfig(props, "Invalid server.port value 'abc' - must be a number");

        props = baseProps();
        props.setProperty("server.port", "0");
        assertBadConfig(props, "server.port must be between 1 and 65535, got: 0");
    }

    @Test
    public void validatesAuthSecretAndDbQuerySettings() {
        assertBadState(baseProps(), Map.of("REQUIRES_AUTH", "true"), "REQUIRES_AUTH=true but DATA_PLANE_SECRET is missing");

        Properties props = baseProps();
        props.setProperty("db.query.timeoutSeconds", "-1");
        assertBadConfig(props, "Invalid DB_QUERY_TIMEOUT_SECONDS / db.query.timeoutSeconds value '-1' - must be a non-negative integer");

        props = baseProps();
        assertEquals(12, WebServerConfig.parseNonNegativeIntSetting(props, Map.of("DB_QUERY_TIMEOUT_SECONDS", " 12 "), "db.query.timeoutSeconds", "DB_QUERY_TIMEOUT_SECONDS", 60));

        props = baseProps();
        props.setProperty("db.query.maxRows", Integer.toString(Integer.MAX_VALUE));
        assertBadConfig(props, "Invalid DB_QUERY_MAX_ROWS / db.query.maxRows value '2147483647' - must be no greater than 2147483646");
    }

    @Test
    public void rejectsRenamedDetailedDataProperty() {
        Properties props = baseProps();
        props.setProperty("allowDataExport", "false");
        assertBadConfig(
            props,
            "allowDataExport has been renamed to allowDetailedDataAccess in webserver.properties"
        );
    }

    private static Properties baseProps() {
        Properties props = new Properties();
        props.setProperty("model.prefix", "Demo");
        return props;
    }

    private static void assertBadConfig(Properties props, String message) {
        try {
            WebServerConfig.fromProperties(props, Map.of());
            fail("Expected bad config");
        } catch (IllegalArgumentException e) {
            assertEquals(message, e.getMessage());
        }
    }

    private static void assertBadState(Properties props, Map<String, String> env, String message) {
        try {
            WebServerConfig.fromProperties(props, env);
            fail("Expected bad state");
        } catch (IllegalStateException e) {
            assertEquals(message, e.getMessage());
        }
    }
}
