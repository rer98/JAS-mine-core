<!-- badges: start -->
[![](https://jitpack.io/v/jasmineRepo/JAS-mine-core.svg)](https://jitpack.io/#jasmineRepo/JAS-mine-core)
<!-- badges: end -->

# JAS-mine-core
JAS-mine is a Java platform that aims at providing a unique simulation tool for discrete-event simulations, including agent-based and microsimulation models. 
With the aim to develop large-scale, data-driven models, the main architectural choice of JAS-mine is to use whenever possible standard, open-source tools already available in the software development community.

The main value added of the platform lies in the integration with RDBMS (relational database management systems) tools through ad-hoc microsimulation Java libraries.

The management of input data persistence layers and simulation results is performed using standard database management tools, and the platform takes care of the automatic translation of the relational model (which is typical of a database) into the object-oriented simulation model, where each category of individuals or objects that populate the model is represented by a specific class, with its own properties and methods.

JAS-mine allows to separate data representation and management, which is automatically taken care of by the simulation engine, from the implementation of processes and behavioral algorithms, which should be the primary concern of the modeler. This results in quicker, more robust and more transparent model building.

It has built-in utilities for communicating with an underlying relational database. In addition, the platform provides standard tools which are frequently used both in agent-based modelling and dynamic microsimulations, like design of experiments (DOE), run-time monitoring and visualization with plots and graphs (GUI), I/O communication, statistical analysis.

See https://github.com/jasmineRepo for a list of JAS-mine projects including demonstration models.

See www.jas-mine.net for more details.

Categories: Modeling, Simulations, ORM (Object-relational mapping)

License: European Union Public Licence (EUPL)

Features: Artificial Intelligence Simulation Social sciences

## Documentation

For web DB Explorer query restrictions, account setup and result limits, see
[Web database queries](WEB_DATABASE_QUERIES.md).

The documentation can be generated locally with the following:
```sh
mvn javadoc:javadoc
```

It is generated in `target/reports/apidocs`.

## Shared memory monitoring

`microsim.monitoring.MemoryMonitor` supplies the same memory monitoring to the
interactive `SimulationServer` and an explicitly enabled `MultiRun` execution
thread. The web server starts monitoring from its dedicated entry point. Generic
MultiRun execution leaves monitoring disabled by default; hosted launchers opt in
with `-Djasmine.memory.monitor.enabled=true` before the Java main class or `-jar`.
Desktop launchers can use the same option if monitoring is wanted. It samples
every ten seconds and sends heap and container working-set warnings to each
application's current simulation console when usage exceeds 85%, at most once
every thirty seconds for each warning type. The monitor is a daemon and is
stopped on normal shutdown; terminating a MultiRun JVM also ends its monitor.

The public `sample()` method returns a numeric snapshot of used, committed and
maximum Java heap, the current container limit and usage, and inactive file
cache. Container working-set usage subtracts inactive file cache from raw usage,
clamped at zero; if cache measurements are unavailable, it uses raw usage.
Unavailable container measurements remain absent rather than being inferred
from heap measurements. Heap monitoring continues independently. Desktop heap
readings apply to its Java process; any cgroup readings describe the exposed
Linux resource group, not a portable measurement of Java off-heap memory.

The container limit is read again on every sample, so monitoring recognises an
operator-approved live limit increase. Monitoring does not change allocations,
Java heap settings, simulation calculations or retry policy. Automatic resource
growth and resource-specific recovery require separate hosting integration.
The original `microsim.web.server.MemoryMonitor` public class remains available
as a deprecated facade. Rebuild model artifacts with the updated core to include
the shared monitor; existing pinned model releases remain unchanged.


## Session storage protection

The web server supports opt-in usage reporting, build admission and confirmed
run-level cleanup. Configure JASMINE_STORAGE_BYTES, JASMINE_STORAGE_WARN_BYTES,
JASMINE_STORAGE_RESERVE_BYTES and (separately) JASMINE_STORAGE_CLEANUP_ENABLED.
Cleanup also requires detailed-data access and Reset/disposal.

Models register retained H2 databases using
`microsim.data.StorageProtection.protectDatabase(owner, basePath, reason)` and
release each independent owner only when its dependency has ended. Base paths
exclude `.mv.db`. Whole-directory protection remains available for other model
dependencies. Enable cleanup only after reviewing all retained resources.

Selected retired run directories are cleared except protected databases and their
companion files. Necessary paths remain; empty unprotected directories are removed.
Preview tokens are expiring/single-use and deletion rechecks candidates and claims.
Core SQL queries and file/ZIP downloads share the lifecycle lock with cleanup.
No factories are closed by cleanup. See JAS-mine-web `docs/session-storage.md`
for the complete contract, configuration and host quota limitations.
