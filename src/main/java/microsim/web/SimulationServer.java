package microsim.web;

import io.javalin.Javalin;
import io.javalin.http.Context;

import microsim.data.db.DatabaseUtils;
import microsim.engine.ExperimentBuilder;
import microsim.engine.SimulationEngine;
import microsim.engine.SimulationManager;
import microsim.gui.GuiUtils;
import microsim.web.server.ApiErrors;
import microsim.web.server.HardResetAuth;
import microsim.web.server.ChartResponseUtils;
import microsim.web.server.DataTokenAuth;
import microsim.web.server.DatabaseFileUtils;
import microsim.web.server.DatabaseQueryUtils;
import microsim.web.server.DatabaseRequestUtils;
import microsim.web.server.PathSafety;
import microsim.web.server.ParameterIntrospection;
import microsim.web.server.ParameterResponseUtils;
import microsim.web.server.ParameterUpdateLog;
import microsim.web.server.ExportFileUtils;
import microsim.web.server.ExportStreamingUtils;
import microsim.web.server.InputFileUtils;
import microsim.web.server.MemoryMonitor;
import microsim.web.server.GracefulShutdown;
import microsim.web.server.MetadataFileUtils;
import microsim.web.server.LogRequestUtils;
import microsim.web.server.OutputFileResolver;
import microsim.web.server.SimulationLifecycleResponses;
import microsim.web.server.SimulationLogBuffer;
import microsim.web.server.TabularDataUtils;
import microsim.web.server.TabularExportUtils;
import microsim.web.server.WebServerConfig;
import microsim.web.server.SqlSafety;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
// import java.util.concurrent.ExecutorService;  // Unused — SSE infrastructure commented out (see Javadoc)
// import java.util.concurrent.Executors;        // Unused — SSE infrastructure commented out (see Javadoc)
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.ConcurrentModificationException;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;


/* (C) Copyright 2026, by Ross Richardson
 *
 * HTTP server entrypoint for running JAS-mine simulations through JAS-mine Web.
 *
 * Registers Javalin routes and coordinates simulation lifecycle operations (build,
 * start, pause, step, reset), parameter endpoints, chart/log/readme/metadata access,
 * input/export/database endpoints, and restricted-model data-plane authentication.
 * Focused helper logic lives in the microsim.web.server package; this class remains
 * the route, locking, HTTP-status, and SimulationEngine orchestration layer.
 *
 * Configuration is loaded from webserver.properties and runtime environment variables.
 *
 * @author ross richardson
 *
 */

public class SimulationServer {

    // Configuration loaded from properties
    private static String modelPrefix;
    private static String modelPackage;
    private static String experimentPackage;
    private static int serverPort;
    private static String corsAllowedHosts;
    private static boolean allowDataExport = true;      // If not specified in webserver properties, default to true.
    private static boolean requiresAuth = false;        // Restricted deployed models require data-plane tokens.
    private static int dbQueryMaxRows = 5000;          // Max rows returned by DB Explorer queries; 0 disables the cap.
    private static int dbQueryTimeoutSeconds = 60;     // Max DB Explorer query execution time; 0 disables the timeout.
    private static String modelId;
    private static String simId;
    private static String hardResetSecret;
    private static String dataPlaneSecret;
    
    // Derived class names
    private static String modelClassName;
    private static String collectorClassName;
    private static String observerClassName;
    private static String startClassName;

    // Runtime state
    private static SimulationEngine engine;
    private static SimulationManager model;
    private static SimulationManager collector;
    // private static SimulationManager observer;

    private static Map<String, Object> cachedParameters = null;

    // ── Legacy SSE infrastructure — preserved for potential future revival ───
    // Originally used to stream simulation logs to the browser via Server-Sent
    // Events. Replaced by cached `/simulation/logs/poll` polling because Firebase
    // Hosting enforces a ~3-minute connection lifetime that caused EventSource
    // reconnection loops, eroding the per-IP rate budget and triggering HTTP 429
    // cascades across all other endpoints (see JAS-mine Web paper Section 6.5).
    // Could be re-enabled for deployments without managed-hosting
    // connection-lifetime limits (e.g. self-hosted VM deployments).
    // private static final int SSE_THREAD_POOL_SIZE = 10;
    // private static final int SSE_POLL_INTERVAL_MS = 200;
    // private static final ExecutorService sseExecutor = Executors.newFixedThreadPool(SSE_THREAD_POOL_SIZE);
    private static final long SINGLE_FILE_ZIP_THRESHOLD_BYTES = 128L * 1024L * 1024L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int COLUMN_SUMMARY_MAX_DISTINCT_VALUES = 100_000;
    private static final int EXPORT_COLUMNS_MAX_PREVIEW_ROWS = 20;
    private static final int EXPORT_FIND_ROWS_DEFAULT_LIMIT = 20;
    private static final int EXPORT_FIND_ROWS_MAX_LIMIT = 5_000;
    private static final long SHUTDOWN_LOCK_TIMEOUT_MILLIS = 2_000L;

    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private static final SimulationLogBuffer logs = new SimulationLogBuffer(SimulationLogBuffer.DEFAULT_MAX_LINES);
    private static final MemoryMonitor memoryMonitor = new MemoryMonitor(SimulationServer::addLogMessage);

    public static void addLogMessage(String message) {
        logs.add(message);
    }

    private static void loadConfiguration() throws Exception {
        WebServerConfig config = WebServerConfig.load();

        modelPrefix = config.getModelPrefix();
        modelPackage = config.getModelPackage();
        experimentPackage = config.getExperimentPackage();
        serverPort = config.getServerPort();
        corsAllowedHosts = config.getCorsAllowedHosts();
        allowDataExport = config.isAllowDataExport();
        requiresAuth = config.isRequiresAuth();
        dbQueryMaxRows = config.getDbQueryMaxRows();
        dbQueryTimeoutSeconds = config.getDbQueryTimeoutSeconds();
        modelId = config.getModelId();
        simId = config.getSimId();
        hardResetSecret = config.getHardResetSecret();
        dataPlaneSecret = config.getDataPlaneSecret();
        modelClassName = config.getModelClassName();
        collectorClassName = config.getCollectorClassName();
        observerClassName = config.getObserverClassName();
        startClassName = config.getStartClassName();

        config.printSummary();
    }

    public static void main(String[] args) {
        GuiUtils.setWebMode(true);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        System.setOut(new PrintStream(logs.capturingOutputStream(originalOut), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(logs.capturingOutputStream(originalErr), true, StandardCharsets.UTF_8));

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            for (String line : sw.toString().split("\n")) {
                addLogMessage("[EXCEPTION] " + line);
            }
            originalErr.println("Uncaught exception in thread " + thread.getName());
            throwable.printStackTrace(originalErr);
        });

        System.out.println("\n\n========== JAS-MINE WEB SERVER STARTING ==========\n");

        try {
            loadConfiguration();
        } catch (Exception e) {
            System.err.println("Failed to load webserver.properties: " + e.getMessage());
            System.exit(1);
        }

        startMemoryMonitor();

        // Eagerly create the SimulationEngine singleton before the server starts
        // accepting requests, so no two concurrent cold-start requests can race
        // on its lazy (non-synchronized) getInstance() initialization.
        SimulationEngine.getInstance();

        GracefulShutdown gracefulShutdown = new GracefulShutdown(
            lock.writeLock(),
            SHUTDOWN_LOCK_TIMEOUT_MILLIS,
            SimulationServer::stopMemoryMonitor,
            SimulationServer::disposeSimulationStateForShutdown,
            message -> System.out.println(message)
        );

        Javalin app = Javalin.create(config -> {
//            config.plugins.enableCors(cors -> cors.add(it -> {            // For Javalin 5
            config.events.serverStopping(gracefulShutdown::shutdown);
            config.events.serverStopped(gracefulShutdown::shutdown);


            config.bundledPlugins.enableCors(cors -> cors.addRule(it -> {   // For Javalin 6
                for (String host : corsAllowedHosts.split(",")) {
                    it.allowHost(host.trim());
                }
            }));

            // Health check endpoint
            config.routes.get("/health", SimulationServer::handleHealth);

            // Control endpoints
            config.routes.post("/simulation/pause", SimulationServer::handlePause);
            config.routes.post("/simulation/step", SimulationServer::handleStep);
            config.routes.post("/simulation/speed", SimulationServer::handleSpeed);
            config.routes.get("/simulation/status", SimulationServer::handleStatus);

            // Read endpoints
            config.routes.get("/simulation/parameters", SimulationServer::handleParameters);
            config.routes.get("/simulation/current-params", SimulationServer::handleCurrentParams);
            config.routes.get("/simulation/parameters/history", SimulationServer::handleParameterHistory);
            config.routes.get("/simulation/allow-export", SimulationServer::handleAllowExport);
            config.routes.get("/simulation/export/list", SimulationServer::handleExportList);
            config.routes.get("/simulation/charts", SimulationServer::handleCharts);
            config.routes.get("/simulation/readme", SimulationServer::handleReadme);
            config.routes.get("/simulation/metadata/data-dictionary", SimulationServer::handleDataDictionary);
            config.routes.get("/simulation/metadata/source-info", SimulationServer::handleSourceInfo);

            // Write endpoints
            config.routes.post("/simulation/build", SimulationServer::handleBuild);
            config.routes.post("/simulation/start", SimulationServer::handleStart);
            config.routes.post("/simulation/update-params", SimulationServer::handleUpdateParams);
            config.routes.post("/simulation/reset", SimulationServer::handleReset);
            // app.get("/simulation/export/zip/{timestamp}", SimulationServer::handleExportZip);
            config.routes.get("/simulation/export/zip", SimulationServer::handleExportZip);
            config.routes.get("/simulation/export/download", SimulationServer::handleExportDownload);
            config.routes.get("/simulation/export/rows", SimulationServer::handleExportRows);
            config.routes.post("/simulation/export/find-rows", SimulationServer::handleExportFindRows);
            config.routes.get("/simulation/export/columns", SimulationServer::handleExportColumns);
            config.routes.post("/simulation/export/column-summary", SimulationServer::handleExportColumnSummary);
            config.routes.post("/simulation/export/sample-rows", SimulationServer::handleExportSampleRows);

            // To access input directory
            config.routes.get("/simulation/input/list", SimulationServer::handleInputList);
            // app.get("/simulation/input/download/{filename}", SimulationServer::handleInputDownload);
            config.routes.get("/simulation/input/download", SimulationServer::handleInputDownload);
            config.routes.post("/simulation/input/upload/{filename}", SimulationServer::handleInputUpload);

            // For database querying via JAS-mine Web
            config.routes.post("/simulation/db/query", SimulationServer::handleDbQuery);

            // Legacy SSE Logs endpoint — disabled along with SSE infrastructure (see field declarations above).
            // app.sse("/simulation/logs", client -> {
            //     client.keepAlive();
            //     final boolean[] connected = {true};
            //     client.onClose(() -> connected[0] = false);
            //     
            //     for (String log : logBuffer) {
            //         client.sendEvent("log", log);
            //     }
            //     
            //     sseExecutor.submit(() -> {
            //         int lastSize = logBuffer.size();
            //         while (connected[0]) {
            //             try {
            //                 Thread.sleep(SSE_POLL_INTERVAL_MS);
            //                 if (logBuffer.size() > lastSize) {
            //                     String[] logs = logBuffer.toArray(new String[0]);
            //                     for (int i = lastSize; i < logs.length; i++) {
            //                         if (!connected[0]) break;
            //                         client.sendEvent("log", logs[i]);
            //                     }
            //                     lastSize = logs.length;
            //                 }
            //             } catch (Exception e) {
            //                 break;
            //             }
            //         }
            //     });
            // });

            // Polling
            config.routes.get("/simulation/logs/poll", ctx -> {
                long since = ctx.queryParamAsClass("since", Long.class).getOrDefault(0L);
                ctx.json(logs.poll(since));
            });

            config.routes.get("/simulation/logs/tail", SimulationServer::handleLogTail);
            config.routes.get("/simulation/logs/search", SimulationServer::handleLogSearch);





            config.routes.post("/simulation/hard-reset", ctx -> {
                HardResetAuth.Result auth = HardResetAuth.check(hardResetSecret, ctx.header(HardResetAuth.HEADER));
                if (!auth.allowed()) {
                    ApiErrors.jsonError(ctx, 403, auth.message());
                    return;
                }
                System.out.println("Hard reset requested - shutting down for container restart");
                ctx.json(Map.of("status", "restarting"));
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                        System.exit(0);
                    } catch (InterruptedException e) {}
                }).start();
            });

        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                app.stop();
            } catch (RuntimeException e) {
                System.err.println("JAS-mine server stop failed during JVM shutdown: " + e.getMessage());
            } finally {
                gracefulShutdown.shutdown();
            }
        }, "jasmine-web-shutdown"));
        app.start(serverPort);
        System.out.println("JAS-mine server initialized. Ready to build.");
        
    }



    //========== Helper methods ============

    private static Integer intQueryParam(Context ctx, String name) {
        return LogRequestUtils.parseInteger(ctx.queryParam(name));
    }

    private static void handleLogTail(Context ctx) {
        if (!requireDataToken(ctx)) return;
        int maxLines = LogRequestUtils.tailMaxLines(intQueryParam(ctx, "max_lines"));
        ctx.json(logs.tail(maxLines));
    }

    private static void handleLogSearch(Context ctx) {
        if (!requireDataToken(ctx)) return;
        String query = ctx.queryParam("query");
        if (query == null || query.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing query parameter"));
            return;
        }
        LogRequestUtils.SearchRequest req = LogRequestUtils.searchRequest(
            query,
            ctx.queryParam("regex"),
            ctx.queryParam("case"),
            intQueryParam(ctx, "max_matches"),
            intQueryParam(ctx, "context")
        );
        if (LogRequestUtils.regexTooLong(req)) {
            ctx.status(400).json(Map.of("error", LogRequestUtils.regexTooLongMessage()));
            return;
        }
        try {
            ctx.json(logs.search(req.query(), req.regex(), req.caseSensitive(), req.maxMatches(), req.context()));
        } catch (java.util.regex.PatternSyntaxException e) {
            ctx.status(400).json(Map.of("error", "Invalid regex: " + e.getMessage()));
        }
    }



    // ===== Endpoint Handlers =====

    private static void handleHealth(Context ctx) {
        ctx.json(Map.of("status", "ok", "timestamp", System.currentTimeMillis(), "modelPrefix", modelPrefix));
    }

    private static void handlePause(Context ctx) {
        lock.writeLock().lock();
        try {
            if (engine == null) {
                ApiErrors.jsonError(ctx, 409, "No simulation running");
                return;
            }
            engine.pause();
            ctx.json(SimulationLifecycleResponses.timedStatus("paused", engine.getTime()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void handleStep(Context ctx) {
        lock.writeLock().lock();
        try {
            if (engine == null) {
                ApiErrors.jsonError(ctx, 409, "No simulation initialized");
                return;
            }
            try {
                engine.step();
                ctx.json(SimulationLifecycleResponses.timedStatus("stepped", engine.getTime()));
            } catch (Exception e) {
                ApiErrors.handleError(ctx, e, false);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }


    private static void handleSpeed(Context ctx) {
        lock.writeLock().lock();
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int millis = body.get("speed") != null ? ((Number) body.get("speed")).intValue() : 0;
            if (engine != null) {
                engine.setEventTimeTreshold(millis);
                ctx.json(SimulationLifecycleResponses.speedSet(millis));
            } else {
                ApiErrors.jsonError(ctx, 409, "Engine not initialized");
            }
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, false);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void handleStatus(Context ctx) {
        lock.readLock().lock();
        try {
            if (engine == null) {
                ctx.json(SimulationLifecycleResponses.notInitializedStatus());
                return;
            }
            ctx.json(SimulationLifecycleResponses.currentStatus(
                engine.getRunningStatus(),
                engine.getTime(),
                engine.getModelBuildStatus()
            ));
        } finally {
            lock.readLock().unlock();
        }
    }

    private static void handleParameters(Context ctx) {
        // Fast path: serve from cache under a shared read lock.
        lock.readLock().lock();
        try {
            if (cachedParameters != null) {
                ctx.json(cachedParameters);
                return;
            }
        } finally {
            lock.readLock().unlock();
        }

        // Cold path: compute under the exclusive write lock so the
        // SimulationEngine singleton (setup/getManager/disposeModels) is
        // never touched concurrently. Re-check the cache after acquiring
        // the write lock, since another thread may have populated it while
        // we were waiting (double-checked locking).
        lock.writeLock().lock();
        try {
            if (cachedParameters != null) {
                ctx.json(cachedParameters);
                return;
            }

            if (engine != null && engine.getModelBuildStatus()) {
                ctx.status(409).json(Map.of(
                    "error", "Default parameters can only be cold-loaded before build; use current-params for a built simulation"
                ));
                return;
            }

            List<Map<String, Object>> modelParameters = new ArrayList<>();
            List<Map<String, Object>> collectorParameters = new ArrayList<>();

            try {
                Class<?> startClass = Class.forName(startClassName);
                ExperimentBuilder tempBuilder = (ExperimentBuilder) startClass.getDeclaredConstructor().newInstance();

                SimulationEngine tempEngine = SimulationEngine.getInstance();
                tempEngine.setExperimentBuilder(tempBuilder);
                tempEngine.setup();

                SimulationManager tempModel = tempEngine.getManager(modelClassName);
                SimulationManager tempCollector = tempEngine.getManager(collectorClassName);

                if (tempModel != null) {
                    ParameterIntrospection.extractParameters(tempModel, modelParameters);
                }
                if (tempCollector != null) {
                    ParameterIntrospection.extractParameters(tempCollector, collectorParameters);
                }

                tempEngine.disposeModels();

                cachedParameters = ParameterResponseUtils.parameterLists(modelParameters, collectorParameters);

                ctx.json(cachedParameters);
            } catch (Exception e) {
                ApiErrors.handleError(ctx, e, true);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void handleCurrentParams(Context ctx) {
        lock.readLock().lock();
        try {
            if (model == null) {
                ApiErrors.jsonError(ctx, 409, "No simulation running");
                return;
            }
            
            List<Map<String, Object>> modelParameters = new ArrayList<>();
            List<Map<String, Object>> collectorParameters = new ArrayList<>();
            ParameterIntrospection.extractParameters(model, modelParameters);
            if (collector != null) {
                ParameterIntrospection.extractParameters(collector, collectorParameters);
            }
            
            ctx.json(ParameterResponseUtils.parameterLists(modelParameters, collectorParameters));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, true);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static boolean requireDataToken(Context ctx) {
        if (!requiresAuth) return true;
        if (validateDataToken(ctx)) return true;
        ctx.status(403).json(Map.of("error", "Invalid or missing data token"));
        return false;
    }

    private static boolean validateDataToken(Context ctx) {
        String token = ctx.header(DataTokenAuth.HEADER);
        if (token == null || token.isBlank()) {
            token = ctx.queryParam("token");
        }
        return DataTokenAuth.validateToken(token, modelId, simId, dataPlaneSecret);
    }

    private static void handleDataDictionary(Context ctx) {
        try {
            if (!requireDataToken(ctx)) return;
            if (!allowDataExport) {
                ctx.status(403).json(Map.of("error", "Data dictionary access is disabled for this model"));
                return;
            }
            ctx.json(MetadataFileUtils.metadataJson(
                "data-dictionary.json",
                "No metadata/data-dictionary.json file found for this model",
                MAPPER
            ));
        } catch (MetadataFileUtils.InvalidMetadataPathException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, false);
        }
    }


    private static void handleSourceInfo(Context ctx) {
        try {
            if (!requireDataToken(ctx)) return;
            ctx.json(MetadataFileUtils.metadataJson(
                "source-info.json",
                "No metadata/source-info.json file found for this model",
                MAPPER
            ));
        } catch (MetadataFileUtils.InvalidMetadataPathException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, false);
        }
    }


    private static void handleAllowExport(Context ctx) {
        if (!requireDataToken(ctx)) return;
        lock.readLock().lock();
        try {
            ctx.json(Map.of("allowed", allowDataExport));
        } finally {
            lock.readLock().unlock();
        }
    }

    private static void handleExportList(Context ctx) {
        try {
            if (!requireDataToken(ctx)) return;
            if (!allowDataExport) {
                ctx.status(403).json(Map.of("error", "Data export is disabled for this model"));
                return;
            }
            File outputDir = new File("output");
            if (!outputDir.exists()) {
                ctx.json(Map.of("files", new ArrayList<>(), "message", "No exports yet"));
                return;
            }

            List<Map<String, String>> allFiles = new ArrayList<>();
            File[] subdirs = outputDir.listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File subdir : subdirs) {
                    ExportFileUtils.addFilesRecursively(subdir, subdir, allFiles);
                }
            }
            ctx.json(Map.of("files", allFiles));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, false);
        }
    }



    private static void handleCharts(Context ctx) {
        // System.out.println("DEBUG: handleCharts ENTERED, ctx.path() = " + ctx.path() + ", queryParams = " + ctx.queryParamMap());
        lock.readLock().lock();
        try {
            ctx.json(ChartResponseUtils.buildChartResponse(
                new ArrayList<>(GuiUtils.getPlotterRegistry()),
                ctx.queryParam("since"),
                MAPPER
            ));
        } catch (ConcurrentModificationException e) {
            ctx.json(Map.of("error", "retry"));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, true);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static void handleReadme(Context ctx) {
        if (!requireDataToken(ctx)) return;
        try {
            ctx.json(Map.of("content", MetadataFileUtils.readReadme()));
        } catch (FileNotFoundException e) {
            ctx.status(404).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, false);
        }
    }

    private static void handleBuild(Context ctx) {
        lock.writeLock().lock();
        try {
            if (engine != null && engine.getModelBuildStatus()) {
                ApiErrors.jsonError(ctx, 409, "Simulation already built");
                return;
            }
            // startMemoryMonitor();

            engine = SimulationEngine.getInstance();
            engine.reset();
            
            Class<?> startClass = Class.forName(startClassName);
            ExperimentBuilder experimentBuilder = (ExperimentBuilder) startClass.getDeclaredConstructor().newInstance();
            
            Map<String, Object> params = ctx.bodyAsClass(Map.class);
            if (params != null) {
                // Preserve support for any experiment-builder/start-class parameters;
                // model and collector parameters are strictly validated after setup.
                ParameterIntrospection.applyParameters(startClass, experimentBuilder, params, SimulationServer::addLogMessage);
            }
            
            engine.setExperimentBuilder(experimentBuilder);
            engine.setup();
            
            model = engine.getManager(modelClassName);
            collector = engine.getManager(collectorClassName);
            try {
                // Load observer to trigger chart registration with GuiUtils (return value not needed)
                engine.getManager(observerClassName);
            } catch (Exception e) {
                System.out.println("Warning: Observer not loaded - charts will not be available. Reason: " + e.getMessage());
            }
            
            if (params != null && !params.isEmpty()) {
                try {
                    ParameterIntrospection.validateAndApplyMatchingParameters(params,
                        new ParameterIntrospection.ParameterTarget(startClass, experimentBuilder),
                        new ParameterIntrospection.ParameterTarget(model == null ? null : model.getClass(), model),
                        new ParameterIntrospection.ParameterTarget(collector == null ? null : collector.getClass(), collector));
                } catch (IllegalArgumentException e) {
                    try { engine.disposeModels(); } catch (Exception ignored) {}
                    model = null;
                    collector = null;
                    ctx.status(400).json(Map.of("error", "Invalid build parameters: " + e.getMessage()));
                    return;
                }
            }
            
            addLogMessage("-----------------------------");
            addLogMessage("--- Building New Simulation ---");
            addLogMessage("-----------------------------");
            engine.buildModels();
            String outputRun = null;
            try {
                File outputFolder = new File(engine.getCurrentExperiment().getOutputFolder());
                outputRun = outputFolder.getName();
                addLogMessage("--- Output run: " + outputRun + " ---");
            } catch (Exception e) {
                addLogMessage("--- Output run: unavailable (" + e.getClass().getSimpleName() + ") ---");
            }
            ctx.json(SimulationLifecycleResponses.timedStatus("built", engine.getTime()));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, true);
        } finally {
            // stopMemoryMonitor(); 
            lock.writeLock().unlock();
        }
    }

    private static void handleStart(Context ctx) {
        lock.writeLock().lock();
        try {
            if (engine == null || model == null) {
                ApiErrors.jsonError(ctx, 409, "Simulation not initialized");
                return;
            }
            if (engine.getRunningStatus()) {
                ApiErrors.jsonError(ctx, 409, "Simulation already running");
                return;
            }
            if (!engine.isAlive()) {
                engine.startSimulation();
            } else {
                engine.setRunningStatus(true);
            }
            ctx.json(SimulationLifecycleResponses.timedStatus("started", engine.getTime()));
        } catch (IllegalThreadStateException e) {
            ctx.status(409).json(SimulationLifecycleResponses.restartableEngineStateError());
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void writeParameterUpdate(Map<String, Object> params) {
        try {
            String outputFolder = SimulationEngine.getInstance().getCurrentExperiment().getOutputFolder();
            ParameterUpdateLog.append(new File(outputFolder), SimulationEngine.getInstance().getTime(), params);
        } catch (Exception e) {
            System.out.println("Warning: could not write parameter update log: " + e.getMessage());
        }
    }


    private static void handleParameterHistory(Context ctx) {
        try {
            if (!requireDataToken(ctx)) return;

            String timestamp = ctx.queryParam("timestamp");
            if (!PathSafety.isSafeTimestamp(timestamp)) {
                ctx.status(400).json(Map.of("error", "Missing or invalid timestamp parameter"));
                return;
            }

            OutputFileResolver.Result resolved = OutputFileResolver.outputFile(timestamp, "GUIparameters.csv");
            if (resolved.runDir() == null) {
                ctx.status(404).json(Map.of("error", "Output run directory not found"));
                return;
            }

            File file = resolved.file();
            if (file == null) {
                ctx.json(ParameterResponseUtils.noParameterHistory(timestamp));
                return;
            }

            long offset = TabularExportUtils.parseLongQueryParam(ctx.queryParam("offset"), 0);
            int limit = (int) TabularExportUtils.parseLongQueryParam(ctx.queryParam("limit"), 5000);
            boolean count = "true".equalsIgnoreCase(ctx.queryParam("count"));
            if (offset < 0) offset = 0;
            if (limit < 0) limit = 0;
            final int MAX_LIMIT = 5000;
            if (limit > MAX_LIMIT) limit = MAX_LIMIT;

            Map<String, Object> result = TabularDataUtils.rows(file, ',', offset, limit, true, count);
            ctx.json(ParameterResponseUtils.foundParameterHistory(result, timestamp));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, false);
        }
    }


    private static void handleUpdateParams(Context ctx) {
        lock.writeLock().lock();
        try {
            if (model == null) {
                ApiErrors.jsonError(ctx, 409, "No simulation running");
                return;
            }
            
            Map<String, Object> params = ctx.bodyAsClass(Map.class);
            if (params != null && !params.isEmpty()) {
                try {
                    // All-or-nothing: validate every parameter against the model
                    // before applying any. Runtime updates target the model only
                    // (collector parameters are not changed mid-run).
                    ParameterIntrospection.validateAndApplyParameters(model.getClass(), model, params);
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", "Invalid parameter update: " + e.getMessage()));
                    return;
                }
                writeParameterUpdate(params);
            }
            
            ctx.json(SimulationLifecycleResponses.simpleStatus("updated"));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void disposeSimulationState(boolean advanceRunNumber) {
        boolean hadModel = model != null;
        SimulationEngine activeEngine = engine;
        if (activeEngine != null) {
            if (activeEngine.getRunningStatus()) activeEngine.pause();
            activeEngine.disposeModels();
        }
        engine = null;
        if (advanceRunNumber && hadModel) {
            SimulationEngine singletonEngine = SimulationEngine.getInstance();
            singletonEngine.setCurrentRunNumber(singletonEngine.getCurrentRunNumber() + 1);
        }
        model = null;
        collector = null;
        GuiUtils.clearRegistry();
    }

    private static void handleReset(Context ctx) {
        lock.writeLock().lock();
        try {
            disposeSimulationState(true);

            // Check if engine is in a bad state (thread terminated, likely from exception)
            SimulationEngine singletonEngine = SimulationEngine.getInstance();
            if (!singletonEngine.isAlive() && singletonEngine.getState() == Thread.State.TERMINATED) {
                ApiErrors.jsonError(ctx, 409, "Engine in bad state - requires hard reset");
                return;
            }
            
            ctx.json(SimulationLifecycleResponses.simpleStatus("reset"));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, true);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // This streams the zip directly to the client, using almost no heap memory.
    private static void handleExportZip(Context ctx) {
        boolean responseCommitted = false;
        try {
            if (!requireDataToken(ctx)) return;
            if (!allowDataExport) {
                ctx.status(403).json(Map.of("error", "Data export is disabled for this model"));
                return;
            }
            String timestamp = ctx.queryParam("timestamp");
            if (!PathSafety.isSafeTimestamp(timestamp)) {
                ctx.status(400).json(Map.of("error", "Missing or invalid timestamp parameter"));
                return;
            }
            String modelName = modelPrefix;
            File sourceDir = OutputFileResolver.runDirectory(timestamp);
            if (sourceDir == null) {
                ctx.status(404).json(Map.of("error", "Directory not found"));
                return;
            }
            ctx.contentType("application/zip")
               .header("Content-Disposition", "attachment; filename=\"" + modelName + "-" + timestamp + ".zip\"");
            try (ZipOutputStream zos = new ZipOutputStream(ctx.outputStream())) {
                responseCommitted = true;
                ExportFileUtils.zipDirectory(sourceDir, sourceDir.getName(), zos);
            }
        } catch (Exception e) {
            if (responseCommitted) {
                ExportStreamingUtils.logStreamingException("Export zip", e, SimulationServer::addLogMessage);
            } else {
                ApiErrors.handleError(ctx, e, false);
            }
        }
    }



    private static void handleExportDownload(Context ctx) {
        boolean responseCommitted = false;
        try {
            if (!requireDataToken(ctx)) return;
            if (!allowDataExport) {
                ctx.status(403).json(Map.of("error", "Data export is disabled for this model"));
                return;
            }
            String timestamp = ctx.queryParam("timestamp");
            String path = ctx.queryParam("path");
            String zipMode = ctx.queryParam("zip");
            OutputFileResolver.Result resolved = OutputFileResolver.outputFile(timestamp, path);
            if (!PathSafety.isSafeTimestamp(timestamp)) {
                ctx.status(400).json(Map.of("error", "Missing or invalid timestamp parameter"));
                return;
            }
            if (resolved.runDir() == null) {
                ctx.status(404).json(Map.of("error", "Output run directory not found"));
                return;
            }
            File file = resolved.file();
            if (file == null || !file.exists() || !file.isFile()) {
                ctx.status(404).json(Map.of("error", "Output file not found"));
                return;
            }
            responseCommitted = true;
            ExportStreamingUtils.streamSingleFile(ctx, file, file.getName(), zipMode, SINGLE_FILE_ZIP_THRESHOLD_BYTES);
        } catch (IllegalArgumentException e) {
            if (responseCommitted) {
                addLogMessage("Export download failed mid-stream: " + e.getMessage());
            } else {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            }
        } catch (Exception e) {
            if (responseCommitted) {
                ExportStreamingUtils.logStreamingException("Export download", e, SimulationServer::addLogMessage);
            } else {
                ApiErrors.handleError(ctx, e, false);
            }
        }
    }


    private static void handleExportColumns(Context ctx) {
        try {
            if (!requireDataToken(ctx)) return;
            if (!allowDataExport) { ctx.status(403).json(Map.of("error", "Data export is disabled for this model")); return; }
            String timestamp = ctx.queryParam("timestamp"), path = ctx.queryParam("path");
            File file = OutputFileResolver.tabularFile(timestamp, path);
            if (file == null) { ctx.status(404).json(Map.of("error", "CSV/TSV output file not found")); return; }
            char delim = TabularExportUtils.delimiterForFile(file, ctx.queryParam("delimiter"));
            int previewRows = Math.min(Math.max(ctx.queryParamAsClass("preview", Integer.class).getOrDefault(0), 0), EXPORT_COLUMNS_MAX_PREVIEW_ROWS);
            ctx.json(TabularDataUtils.columns(file, delim, previewRows));
        } catch (Exception e) { ApiErrors.handleError(ctx, e, false); }
    }

    @SuppressWarnings("unchecked")
    private static void handleExportColumnSummary(Context ctx) {
        try {
            if (!requireDataToken(ctx)) return;
            if (!allowDataExport) { ctx.status(403).json(Map.of("error", "Data export is disabled for this model")); return; }
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            File file = OutputFileResolver.tabularFile((String) body.get("timestamp"), (String) body.get("path"));
            if (file == null) { ctx.status(404).json(Map.of("error", "CSV/TSV output file not found")); return; }
            String column = (String) body.get("column");
            if (column == null || column.isEmpty()) { ctx.status(400).json(Map.of("error", "column is required")); return; }
            int topN = Math.min(Math.max(((Number) body.getOrDefault("topN", 10)).intValue(), 1), 100);
            char delim = TabularExportUtils.delimiterForFile(file, body.get("delimiter"));
            try {
                ctx.json(TabularDataUtils.columnSummary(file, delim, column, topN, COLUMN_SUMMARY_MAX_DISTINCT_VALUES));
            } catch (TabularDataUtils.MissingColumnException e) {
                ctx.status(400).json(Map.of("error", e.getMessage(), "columns", e.getColumns()));
            }
        } catch (Exception e) { ApiErrors.handleError(ctx, e, false); }
    }

    @SuppressWarnings("unchecked")
    private static void handleExportSampleRows(Context ctx) {
        try {
            if (!requireDataToken(ctx)) return;
            if (!allowDataExport) { ctx.status(403).json(Map.of("error", "Data export is disabled for this model")); return; }
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            File file = OutputFileResolver.tabularFile((String) body.get("timestamp"), (String) body.get("path"));
            if (file == null) { ctx.status(404).json(Map.of("error", "CSV/TSV output file not found")); return; }
            int limit = Math.min(Math.max(((Number) body.getOrDefault("limit", 20)).intValue(), 1), 1000);
            long seed = ((Number) body.getOrDefault("seed", 1)).longValue();
            char delim = TabularExportUtils.delimiterForFile(file, body.get("delimiter"));
            ctx.json(TabularDataUtils.sampleRows(file, delim, limit, seed));
        } catch (Exception e) { ApiErrors.handleError(ctx, e, false); }
    }


    @SuppressWarnings("unchecked")
    private static void handleExportFindRows(Context ctx) {
        try {
            if (!requireDataToken(ctx)) return;
            if (!allowDataExport) {
                ctx.status(403).json(Map.of("error", "Data export is disabled for this model"));
                return;
            }
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String timestamp = (String) body.get("timestamp");
            String path = (String) body.get("path");
            if (!PathSafety.isSafeTimestamp(timestamp)) {
                ctx.status(400).json(Map.of("error", "Missing or invalid timestamp"));
                return;
            }
            OutputFileResolver.Result resolved = OutputFileResolver.outputFile(timestamp, path);
            File file = resolved.file();
            if (file == null || !file.exists() || !file.isFile()) {
                ctx.status(404).json(Map.of("error", "Output file not found"));
                return;
            }
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".csv") || lower.endsWith(".tsv"))) {
                ctx.status(400).json(Map.of("error", "find-rows only supports CSV/TSV files"));
                return;
            }
            int limit = Math.min(Math.max(((Number) body.getOrDefault("limit", EXPORT_FIND_ROWS_DEFAULT_LIMIT)).intValue(), 1), EXPORT_FIND_ROWS_MAX_LIMIT);
            boolean header = !Boolean.FALSE.equals(body.get("header"));
            char delim = TabularExportUtils.delimiterForFile(file, body.get("delimiter"));
            List<Map<String, Object>> filters = (List<Map<String, Object>>) body.getOrDefault("filters", new ArrayList<>());
            if (filters.isEmpty()) {
                ctx.status(400).json(Map.of("error", "At least one filter is required"));
                return;
            }
            ctx.json(TabularDataUtils.findRows(file, delim, header, filters, limit));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, false);
        }
    }

    /**
     * Row-aware chunking for tabular output files (primarily CSV/TSV). Streams the
     * file and returns only the requested window of rows, so very large files never
     * need to be transferred whole. Honours allowDataExport and the data token.
     */
    private static void handleExportRows(Context ctx) {
        try {
            if (!requireDataToken(ctx)) return;
            if (!allowDataExport) {
                ctx.status(403).json(Map.of("error", "Data export is disabled for this model"));
                return;
            }
            String timestamp = ctx.queryParam("timestamp");
            String path = ctx.queryParam("path");
            OutputFileResolver.Result resolved = OutputFileResolver.outputFile(timestamp, path);
            if (!PathSafety.isSafeTimestamp(timestamp)) {
                ctx.status(400).json(Map.of("error", "Missing or invalid timestamp parameter"));
                return;
            }
            if (resolved.runDir() == null) {
                ctx.status(404).json(Map.of("error", "Output run directory not found"));
                return;
            }
            File file = resolved.file();
            if (file == null || !file.exists() || !file.isFile()) {
                ctx.status(404).json(Map.of("error", "Output file not found"));
                return;
            }

            long offset = TabularExportUtils.parseLongQueryParam(ctx.queryParam("offset"), 0);
            int limit = (int) TabularExportUtils.parseLongQueryParam(ctx.queryParam("limit"), 100);
            boolean header = !"false".equalsIgnoreCase(ctx.queryParam("header"));
            boolean count = "true".equalsIgnoreCase(ctx.queryParam("count"));
            char delim = TabularExportUtils.delimiterForFile(file, ctx.queryParam("delimiter"));
            if (offset < 0) offset = 0;
            if (limit < 0) limit = 0;
            final int MAX_LIMIT = 5000;
            if (limit > MAX_LIMIT) limit = MAX_LIMIT;

            ctx.json(TabularDataUtils.rows(file, delim, offset, limit, header, count));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, false);
        }
    }



    // Handle user interacting with input directory online
    private static void handleInputList(Context ctx) {
        try {
            if (!requireDataToken(ctx)) return;
            ctx.json(Map.of("files", InputFileUtils.listVisibleInputFiles(new File("input"))));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, false);
        }
    }


    private static void handleInputDownload(Context ctx) {
        boolean responseCommitted = false;
        try {
            if (!requireDataToken(ctx)) return;
            String filename = ctx.queryParam("filename");
            if (filename == null || filename.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Missing filename parameter"));
                return;
            }
            File file = InputFileUtils.resolveInputFile(filename);
            if (file == null) {
                ctx.status(400).json(Map.of("error", "Invalid filename"));
                return;
            }
            if (!file.exists()) {
                ctx.status(404).json(Map.of("error", "File not found"));
                return;
            }
            // Block download of non-Excel files (such as proprietary databases) if export not allowed
            if (!allowDataExport && !InputFileUtils.isExcelFile(filename)) {
                ctx.status(403).json(Map.of("error", "Only Excel files can be downloaded for this model"));
                return;
            }
            responseCommitted = true;
            ExportStreamingUtils.streamSingleFile(ctx, file, filename, ctx.queryParam("zip"), SINGLE_FILE_ZIP_THRESHOLD_BYTES);
        } catch (IllegalArgumentException e) {
            if (responseCommitted) {
                addLogMessage("Input download failed mid-stream: " + e.getMessage());
            } else {
                ctx.status(400).json(Map.of("error", e.getMessage()));
            }
        } catch (Exception e) {
            if (responseCommitted) {
                ExportStreamingUtils.logStreamingException("Input download", e, SimulationServer::addLogMessage);
            } else {
                ApiErrors.handleError(ctx, e, false);
            }
        }
    }
    private static void handleInputUpload(Context ctx) {
        if (!requireDataToken(ctx)) return;
        lock.writeLock().lock();
        try {
            if (engine != null && engine.getModelBuildStatus()) {
                ctx.status(409).json(Map.of("error", "Input files can only be changed before Build. Reset before changing input files."));
                return;
            }

            String filename = ctx.pathParam("filename");
            // Prevent path traversal
            File file = InputFileUtils.resolveInputFile(filename);
            if (file == null) {
                ctx.status(400).json(Map.of("error", "Invalid filename"));
                return;
            }
            // Reject filenames that don't match an existing input file
            if (!file.exists()) {
                ctx.status(400).json(Map.of("error", "Filename does not match any existing input file"));
                return;
            }
            
            try (java.io.InputStream in = ctx.bodyInputStream()) {
                InputFileUtils.replaceInputFile(file, in);
            }
            // Invalidate parameter cache: an uploaded input file may change @GUIparameter defaults
            cachedParameters = null;
            ctx.json(Map.of("status", "uploaded", "filename", filename));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, false);
        } finally {
            lock.writeLock().unlock();
        }
    }


    // To allow database querying via JAS-mine Web
    private static void handleDbQuery(Context ctx) {
        if (!requireDataToken(ctx)) return;
        if (!allowDataExport) {
            ctx.status(403).json(Map.of("error", "Database access is not allowed for this model"));
            return;
        }
        try {
            DatabaseRequestUtils.QueryRequest request;
            try {
                request = DatabaseRequestUtils.parse(ctx.bodyAsClass(Map.class));
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
                return;
            }

            // Read-only: allow SELECT and WITH/CTE result-set queries only.
            if (!SqlSafety.isAllowedReadOnlyQuery(request.getSql())) {
                ctx.status(400).json(Map.of("error", "Only SELECT or WITH/CTE read-only queries are allowed. Use INFORMATION_SCHEMA.TABLES to list tables and INFORMATION_SCHEMA.COLUMNS to list columns; SHOW and DESCRIBE are not supported."));
                return;
            }
            if (SqlSafety.hasMultipleStatements(request.getSql())) {
                ctx.status(400).json(Map.of("error", "Only a single read-only query is allowed"));
                return;
            }

            DatabaseRequestUtils.DatabaseTarget target;
            try {
                target = DatabaseRequestUtils.resolveTarget(request);
            } catch (DatabaseFileUtils.NoDatabaseFileException e) {
                ctx.status(404).json(Map.of("error", e.getMessage()));
                return;
            } catch (DatabaseFileUtils.AmbiguousDatabaseFileException e) {
                ctx.status(400).json(Map.of("error", e.getMessage(), "files", e.getFiles()));
                return;
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("error", e.getMessage()));
                return;
            }

            try {
                ctx.json(DatabaseQueryUtils.executeQuery(target.getJdbcUrl(), request.getSql(), dbQueryMaxRows, dbQueryTimeoutSeconds));
            } catch (SQLException e) {
                addLogMessage("H2 query error: " + e.getMessage());
                ctx.status(400).json(Map.of("error", DatabaseQueryUtils.friendlySqlErrorMessage(e, request.getRole())));
                return;
            }

        } catch (Exception e) {
            ApiErrors.handleError(ctx, e, true);
        }
    }

    public static void startMemoryMonitor() {
        memoryMonitor.start();
    }

    public static void stopMemoryMonitor() {
        memoryMonitor.stop();
    }

    private static void disposeSimulationStateForShutdown() {
        try {
            disposeSimulationState(false);
        } finally {
            DatabaseUtils.closeEntityManagerFactories();
        }
    }


}
