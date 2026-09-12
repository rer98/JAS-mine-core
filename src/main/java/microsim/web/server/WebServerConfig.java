package microsim.web.server;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;


/* (C) Copyright 2026, by Ross Richardson
 *
 * Configuration loader for the JAS-mine Web Java server.
 * Reads webserver.properties and environment overrides, validates ports/auth/DB settings,
 * derives model class names, and prints the startup configuration summary.
 *
 * @author ross richardson
 *
 */

/** Immutable configuration loaded from webserver.properties and runtime environment. */
public final class WebServerConfig {
    private final String modelPrefix;
    private final String modelPackage;
    private final String experimentPackage;
    private final int serverPort;
    private final String corsAllowedHosts;
    private final boolean allowDataExport;
    private final boolean requiresAuth;
    private final int dbQueryMaxRows;
    private final int dbQueryTimeoutSeconds;
    private final String modelId;
    private final String simId;
    private final String hardResetSecret;
    private final String dataPlaneSecret;
    private final String modelClassName;
    private final String collectorClassName;
    private final String observerClassName;
    private final String startClassName;

    private WebServerConfig(
            String modelPrefix,
            String modelPackage,
            String experimentPackage,
            int serverPort,
            String corsAllowedHosts,
            boolean allowDataExport,
            boolean requiresAuth,
            int dbQueryMaxRows,
            int dbQueryTimeoutSeconds,
            String modelId,
            String simId,
            String hardResetSecret,
            String dataPlaneSecret) {
        this.modelPrefix = modelPrefix;
        this.modelPackage = modelPackage;
        this.experimentPackage = experimentPackage;
        this.serverPort = serverPort;
        this.corsAllowedHosts = corsAllowedHosts;
        this.allowDataExport = allowDataExport;
        this.requiresAuth = requiresAuth;
        this.dbQueryMaxRows = dbQueryMaxRows;
        this.dbQueryTimeoutSeconds = dbQueryTimeoutSeconds;
        this.modelId = modelId;
        this.simId = simId;
        this.hardResetSecret = hardResetSecret;
        this.dataPlaneSecret = dataPlaneSecret;
        this.modelClassName = modelPackage + "." + modelPrefix + "Model";
        this.collectorClassName = experimentPackage + "." + modelPrefix + "Collector";
        this.observerClassName = experimentPackage + "." + modelPrefix + "Observer";
        this.startClassName = experimentPackage + "." + modelPrefix + "Start";
    }

    public static WebServerConfig load() throws IOException {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream("webserver.properties")) {
            props.load(in);
        }
        return fromProperties(props, System.getenv());
    }

    public static WebServerConfig fromProperties(Properties props, Map<String, String> env) {
        String modelPrefix = props.getProperty("model.prefix");
        if (modelPrefix == null || modelPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("model.prefix is required in webserver.properties");
        }
        String modelPackage = props.getProperty("model.package", "model");
        String experimentPackage = props.getProperty("experiment.package", "experiment");
        int serverPort = parsePort(props.getProperty("server.port", "7070"));
        int dbQueryMaxRows = parseNonNegativeIntSetting(props, env, "db.query.maxRows", "DB_QUERY_MAX_ROWS", 5000, Integer.MAX_VALUE - 1);
        int dbQueryTimeoutSeconds = parseNonNegativeIntSetting(props, env, "db.query.timeoutSeconds", "DB_QUERY_TIMEOUT_SECONDS", 60);
        String corsAllowedHosts = props.getProperty("cors.allowed.hosts", "http://localhost:5001");
        boolean allowDataExport = Boolean.parseBoolean(props.getProperty("allowDataExport", "true"));

        String modelId = env.get("MODEL_ID");
        if (modelId == null || modelId.trim().isEmpty()) {
            modelId = props.getProperty("model.id", modelPrefix);
        }
        modelId = modelId.trim();

        String simId = env.get("SIM_ID");
        if (simId == null || simId.trim().isEmpty()) {
            simId = props.getProperty("sim.id", modelId);
        }
        simId = simId.trim();

        String requiresAuthStr = env.get("REQUIRES_AUTH");
        boolean requiresAuth = Boolean.parseBoolean(requiresAuthStr == null ? "false" : requiresAuthStr);

        String dataPlaneSecret = env.get("DATA_PLANE_SECRET");
        dataPlaneSecret = dataPlaneSecret == null ? "" : dataPlaneSecret.trim();
        if (requiresAuth && dataPlaneSecret.isEmpty()) {
            throw new IllegalStateException("REQUIRES_AUTH=true but DATA_PLANE_SECRET is missing");
        }

        String hardResetSecret = env.get("HARD_RESET_SECRET");
        if (hardResetSecret == null || hardResetSecret.trim().isEmpty()) {
            hardResetSecret = props.getProperty("hardReset.secret", "");
        }
        hardResetSecret = hardResetSecret == null ? "" : hardResetSecret.trim();

        return new WebServerConfig(
            modelPrefix,
            modelPackage,
            experimentPackage,
            serverPort,
            corsAllowedHosts,
            allowDataExport,
            requiresAuth,
            dbQueryMaxRows,
            dbQueryTimeoutSeconds,
            modelId,
            simId,
            hardResetSecret,
            dataPlaneSecret
        );
    }

    public void printSummary() {
        System.out.println("Configuration loaded:");
        System.out.println("  Model: " + modelClassName);
        System.out.println("  Collector: " + collectorClassName);
        System.out.println("  Observer: " + observerClassName);
        System.out.println("  Start: " + startClassName);
        System.out.println("  DB query max rows: " + dbQueryMaxRows);
        System.out.println("  DB query timeout seconds: " + dbQueryTimeoutSeconds);
        System.out.println("  Port: " + serverPort);
        System.out.println("  CORS allowed hosts: " + corsAllowedHosts);
    }

    private static int parsePort(String portStr) {
        try {
            int serverPort = Integer.parseInt(portStr);
            if (serverPort < 1 || serverPort > 65535) {
                throw new IllegalArgumentException("server.port must be between 1 and 65535, got: " + portStr);
            }
            return serverPort;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid server.port value '" + portStr + "' - must be a number");
        }
    }

    static int parseNonNegativeIntSetting(Properties props, Map<String, String> env, String propKey, String envKey, int defaultValue) {
        String value = env.get(envKey);
        if (value == null || value.trim().isEmpty()) value = props.getProperty(propKey);
        if (value == null || value.trim().isEmpty()) value = Integer.toString(defaultValue);
        return parseNonNegativeInt(value.trim(), envKey + " / " + propKey);
    }

    static int parseNonNegativeIntSetting(Properties props, Map<String, String> env, String propKey, String envKey, int defaultValue, int maxValue) {
        int parsed = parseNonNegativeIntSetting(props, env, propKey, envKey, defaultValue);
        if (parsed > maxValue) {
            throw new IllegalArgumentException("Invalid " + envKey + " / " + propKey + " value '" + parsed + "' - must be no greater than " + maxValue);
        }
        return parsed;
    }

    static int parseNonNegativeInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException("negative value");
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + " value '" + value + "' - must be a non-negative integer");
        }
    }

    public String getModelPrefix() { return modelPrefix; }
    public String getModelPackage() { return modelPackage; }
    public String getExperimentPackage() { return experimentPackage; }
    public int getServerPort() { return serverPort; }
    public String getCorsAllowedHosts() { return corsAllowedHosts; }
    public boolean isAllowDataExport() { return allowDataExport; }
    public boolean isRequiresAuth() { return requiresAuth; }
    public int getDbQueryMaxRows() { return dbQueryMaxRows; }
    public int getDbQueryTimeoutSeconds() { return dbQueryTimeoutSeconds; }
    public String getModelId() { return modelId; }
    public String getSimId() { return simId; }
    public String getHardResetSecret() { return hardResetSecret; }
    public String getDataPlaneSecret() { return dataPlaneSecret; }
    public String getModelClassName() { return modelClassName; }
    public String getCollectorClassName() { return collectorClassName; }
    public String getObserverClassName() { return observerClassName; }
    public String getStartClassName() { return startClassName; }
}
