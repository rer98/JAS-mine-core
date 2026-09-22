<!-- (C) Copyright 2026, by Ross Richardson
Restricted DB Explorer query policy, account setup and compatibility guidance.
@author ross richardson
-->

# Web database queries

The DB Explorer and AI database tools share `DatabaseQueryUtils`. Detailed-data
permission remains required. The helper validates the complete SQL submission,
executes it with a restricted H2 principal, and bounds the returned data.

## Supported queries

The supported analytical subset includes SELECT, joins, filters, grouping,
subqueries, non-recursive SELECT-only CTEs, set operations, and reviewed arithmetic,
string, aggregate and window functions. Metadata queries can read
`INFORMATION_SCHEMA.TABLES` and `INFORMATION_SCHEMA.COLUMNS`. Use ordinary SQL
single-quoted strings and double-quoted identifiers where quoting is necessary.
The definitive syntax/field/function allowlists are in `SqlSafety`.

Unrecognised constructs fail validation. In particular, commands, multiple
statements, row locking, sequences, file/network functions, Java aliases,
unreviewed table functions, linked tables, views and tables with triggers are
excluded. Querying an arbitrary view is unsafe because its definition may call
functions. The H2 catalogue is inspected using fixed administrator metadata
queries; user-submitted SQL is never executed on that administrator connection.

The non-executing parser is pinned to `com.github.jsqlparser:jsqlparser:5.4`, used
under its Apache-2.0 license option (also offered under LGPL-2.1 by its authors).
Its license/provenance is included at `META-INF/licenses/JSQLParser-5.4.md`.
Its AST is checked recursively against an explicit field schema. Parsing consumes
the entire submission; the parser's single-statement convenience method does not
guarantee this. Upgrades must include a schema review and the positive/negative
SQL corpus in `SqlSafetyTest`, not just a version change.

## Input database setup

Before packaging a prepared database or recording approved input versions, run
trusted setup in its owning process, or against its **closed** file:

```sh
java -cp app.jar microsim.web.server.DatabaseQueryAccess input/input query-access.json
```

The first argument is an existing H2 base path without `.mv.db`. The optional,
new receipt records SHA-256 hashes before and after setup. Setup adds a non-admin
`JASMINE_WEB_READER` principal and SELECT grants on approved local base tables.
It does not change simulation rows. It verifies that the principal and PUBLIC
have no extra grants/roles that would widen its privileges. Models with unusual
existing grants need explicit integration; unsafe configurations fail closed.

This packaged principal has an empty password. It is a database privilege
boundary, **not** a session authentication secret. Never publish JDBC access to
these embedded databases. HTTP authentication, filesystem access and container
network isolation provide separate boundaries. Those deployment controls need
their own verification; restricted SQL does not replace them.

Input queries do not add accounts, refresh grants or fall back to `sa`. An old
image without provisioned access receives an explanatory error. Rebuild prepared
images and record their new database hashes together. The SimPaths preparation
hooks provision before approval, and its image definitions provision before
runtime. Source preparation packages are preserved during release repackaging.

## Shared output databases

The trusted output-query setup refreshes grants on the existing selected output
database before executing a query. This is necessary for tables created lazily
by model exports. It runs inside the same JVM, with the lifecycle read lock and
the single-query admission slot. Normal embedded H2 locking remains enabled;
there is no `FILE_LOCK=NO` or second-JVM access to live files.

This does not change the shared output path, model factories, reset semantics or
cleanup protections. Tests compare separate runs while the model connection is
open, after closure, and after cleanup retains the protected database. New output
tables receive query access without replacing that database.

## Limits and errors

Limits are 64 KiB SQL text, one concurrent query per JVM/session, 5,000 rows,
60 seconds JDBC query timeout, 256 columns, 64 KiB per JSON-encoded value, and
4 MiB for the encoded response. Existing row/timeout settings can lower the
limits; zero selects the safe defaults and larger values are capped. String and
LOB readers stop at a bound; unsafe result types such as JAVA_OBJECT and binary
LOBs are rejected. Numeric/date/text extraction uses typed readers, not
`ResultSet.getObject()` deserialization.

Responses retain `truncated`, which now also indicates the response byte limit.
An oversized individual value is rejected; truncation occurs between complete
rows. Timeouts release the query slot. JDBC cancellation is not a universal
CPU/memory isolation guarantee: container budgets are still required.

Public errors use SQL states/vendor codes and safe wording. Raw SQL exceptions,
query text and private record values are not written to the shared console or
returned in query errors. Invalid queries are ordinary HTTP 400 responses and
do not change simulation lifecycle state.

Run `DatabaseQuerySecurityTest`, `DatabaseQueryUtilsTest`, `SqlSafetyTest`,
`DatabaseFileUtilsTest` and `DatabaseRequestUtilsTest` when changing this contract.
Image/browser acceptance remains necessary after rebuilding deployments.
