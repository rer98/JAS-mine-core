/* (C) Copyright 2026, by Ross Richardson
 *
 * Package-level documentation for JAS-mine Web server helpers.
 * Describes the helper classes that support SimulationServer and records the intended boundary
 * between route orchestration and focused, testable utility code.
 *
 * @author ross richardson
 *
 */

/**
 * Helper classes for the JAS-mine Web Java server.
 *
 * <p>This package supports {@code microsim.web.SimulationServer}.  The
 * server class remains the composition/root orchestration point for Javalin
 * routes, HTTP status decisions, data-token checks, locks, simulation-engine
 * lifecycle, and response streaming.  Classes in this package hold focused,
 * testable helper logic that does not need to own route registration or global
 * simulation state.</p>
 *
 * <h2>Responsibility map</h2>
 *
 * <ul>
 *   <li><b>Errors and response shapes</b>:
 *     {@link microsim.web.server.ApiErrors},
 *     {@link microsim.web.server.SimulationLifecycleResponses}.</li>
 *
 *   <li><b>Authentication helpers</b>:
 *     {@link microsim.web.server.DataTokenAuth} validates restricted-model
 *     data-plane tokens;
 *     {@link microsim.web.server.HardResetAuth} validates hard-reset
 *     secrets.</li>
 *
 *   <li><b>Configuration and monitoring</b>:
 *     {@link microsim.web.server.WebServerConfig} loads Java web-server
 *     configuration from properties and environment variables;
 *     {@link microsim.web.server.MemoryMonitor} provides container-aware
 *     memory warnings.</li>
 *
 *   <li><b>Safe path and file handling</b>:
 *     {@link microsim.web.server.PathSafety},
 *     {@link microsim.web.server.InputFileUtils},
 *     {@link microsim.web.server.OutputFileResolver},
 *     {@link microsim.web.server.ExportFileUtils},
 *     {@link microsim.web.server.ExportStreamingUtils}, and
 *     {@link microsim.web.server.MetadataFileUtils} centralise filesystem,
 *     export, input-file, and metadata mechanics.</li>
 *
 *   <li><b>Tabular export helpers</b>:
 *     {@link microsim.web.server.CsvRecordReader},
 *     {@link microsim.web.server.TabularExportUtils},
 *     {@link microsim.web.server.TabularFilterUtils}, and
 *     {@link microsim.web.server.TabularDataUtils} handle CSV/TSV reading,
 *     escaping, filtering, summaries, sampling, searching, and pagination.</li>
 *
 *   <li><b>Database Explorer helpers</b>:
 *     {@link microsim.web.server.SqlSafety},
 *     {@link microsim.web.server.DatabaseFileUtils},
 *     {@link microsim.web.server.DatabaseRequestUtils}, and
 *     {@link microsim.web.server.DatabaseQueryUtils} handle read-only SQL
 *     validation, database-file selection, request parsing, and JDBC result
 *     shaping.</li>
 *
 *   <li><b>Parameter helpers</b>:
 *     {@link microsim.web.server.ParameterIntrospection} and
 *     {@link microsim.web.server.ParameterResponseUtils} handle web parameter
 *     reflection/coercion and response shaping. Shared initial snapshots and
 *     later changes are recorded by {@link microsim.data.GUIParameterHistory}.</li>
 *
 *   <li><b>Logs and charts</b>:
 *     {@link microsim.web.server.SimulationLogBuffer} and
 *     {@link microsim.web.server.LogRequestUtils} support log tail/search
 *     endpoints;
 *     {@link microsim.web.server.ChartResponseUtils} supports the chart
 *     response endpoint while chart conversion remains in
 *     {@code microsim.web.ChartProcessors}.</li>
 * </ul>
 *
 * <h2>Design rule</h2>
 *
 * <p>Add new code here when it is a cohesive helper that can be tested without
 * running a Javalin route or mutating the simulation lifecycle.  Keep endpoint
 * policy, HTTP status mapping, locking, and high-level orchestration in
 * {@code SimulationServer} unless a larger deliberate service refactor is being
 * undertaken.</p>
 */
package microsim.web.server;
