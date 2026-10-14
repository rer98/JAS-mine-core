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
 *     {@link microsim.monitoring.MemoryMonitor} provides shared container-aware
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
 * <h2>Console and export boundaries</h2>
 *
 * <p>Restricted models ({@code allowDetailedDataAccess=false}) reject console
 * poll, tail, and search requests with HTTP 403. Their reviewed diagnostics and
 * lifecycle status remain available. Log searches use the same sanitized text
 * delivered to callers, and output-run annotations accept run identifiers only.</p>
 *
 * <p>Recursive output listing and ZIP export require a filesystem provider with
 * {@link java.nio.file.SecureDirectoryStream} support (available on the target
 * Linux deployment). They fail closed on unsupported providers, including some
 * desktop filesystems. Every directory component and file open rejects symbolic
 * links; traversal is limited to 100,000 entries and 64 nested directories.
 * Model output writers remain trusted: replacing files concurrently with device
 * nodes or FIFOs is unsupported. The model containers must not be treated as a
 * sandbox for arbitrary third-party code, and output directories must not be
 * writable by other untrusted local users.</p>
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
