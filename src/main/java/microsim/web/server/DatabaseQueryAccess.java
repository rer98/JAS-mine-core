/* (C) Copyright 2026, by Ross Richardson
 * Provision and verify least-privilege H2 access for web analytical queries.
 * @author ross richardson
 */
package microsim.web.server;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

public final class DatabaseQueryAccess {
    // This is a database privilege boundary, not a login credential. Session ownership
    // is enforced at the HTTP/network boundary; the database is never exposed as JDBC.
    public static final String USER = "JASMINE_WEB_READER";
    public static final String PASSWORD = "";
    private DatabaseQueryAccess() { }

    public record Relation(String schema, String name) { }
    public record Catalogue(Set<Relation> tables, Set<String> aliases) { }

    public static String url(Path base, boolean readOnly) throws java.io.IOException {
        Path file = Path.of(base.toAbsolutePath().normalize() + ".mv.db");
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file))
            throw new java.io.IOException("An existing regular H2 database is required");
        String canonical = file.toRealPath().toString();
        if (canonical.contains(";") || canonical.contains("\n") || canonical.contains("\r"))
            throw new java.io.IOException("Database path contains unsupported JDBC URL characters");
        return "jdbc:h2:file:" + canonical.substring(0, canonical.length() - 6)
                + ";IFEXISTS=TRUE" + (readOnly ? ";ACCESS_MODE_DATA=r" : "");
    }

    /** Trusted setup only: never called as an implicit fallback by executeQuery. */
    public static void provision(Path base) throws java.io.IOException, SQLException {
        try (var admin = DriverManager.getConnection(url(base, false), "sa", "")) { provision(admin); }
    }

    public static void provision(Connection admin) throws SQLException {
        Catalogue catalogue = catalogue(admin);
        try (var stmt = admin.createStatement()) {
            stmt.setQueryTimeout(10);
            stmt.execute("CREATE USER IF NOT EXISTS " + USER + " PASSWORD ''");
            verifyPrivileges(admin, catalogue);
            for (Relation table : catalogue.tables())
                stmt.execute("GRANT SELECT ON " + quote(table.schema()) + "." + quote(table.name()) + " TO " + USER);
        }
    }

    /** Output tables may appear lazily. Leave an already-provisioned file unchanged. */
    static void provisionOutputIfNeeded(Path base) throws java.io.IOException, SQLException {
        boolean required;
        try (var admin = DriverManager.getConnection(url(base, true), "sa", "")) {
            try { required = !verifyPrivileges(admin, catalogue(admin)); }
            catch (SQLException missing) {
                if (!"JQ001".equals(missing.getSQLState())) throw missing;
                required = true;
            }
        }
        if (required) provision(base);
    }

    /** Only fixed metadata SQL runs as administrator; submitted SQL never uses this connection. */
    static Catalogue inspect(String jdbcUrl) throws SQLException {
        try (var admin = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            Catalogue catalogue = catalogue(admin);
            if (!verifyPrivileges(admin, catalogue)) throw new SQLException("Query table grants need setup", "JQ001");
            return catalogue;
        }
    }

    private static Catalogue catalogue(Connection admin) throws SQLException {
        Set<Relation> tables = new HashSet<>(), triggered = new HashSet<>();
        Set<String> aliases = new HashSet<>();
        try (var stmt = admin.createStatement()) {
            stmt.setQueryTimeout(10);
            try (var rows = stmt.executeQuery("SELECT EVENT_OBJECT_SCHEMA, EVENT_OBJECT_TABLE FROM INFORMATION_SCHEMA.TRIGGERS")) {
                while (rows.next()) triggered.add(new Relation(rows.getString(1), rows.getString(2)));
            }
            try (var rows = stmt.executeQuery("SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, TABLE_CLASS FROM INFORMATION_SCHEMA.TABLES")) {
                while (rows.next()) {
                    var table = new Relation(rows.getString(1), rows.getString(2));
                    if ("BASE TABLE".equals(rows.getString(3)) && "org.h2.mvstore.db.MVTable".equals(rows.getString(4))
                            && !triggered.contains(table)) tables.add(table);
                }
            }
            try (var rows = stmt.executeQuery("SELECT ROUTINE_NAME FROM INFORMATION_SCHEMA.ROUTINES")) {
                while (rows.next()) aliases.add(rows.getString(1).toUpperCase(Locale.ROOT));
            }
        }
        return new Catalogue(Set.copyOf(tables), Set.copyOf(aliases));
    }

    private static boolean verifyPrivileges(Connection admin, Catalogue catalogue) throws SQLException {
        Set<Relation> granted = new HashSet<>();
        try (var stmt = admin.prepareStatement("SELECT IS_ADMIN FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME=?")) {
            stmt.setString(1, USER);
            try (var rows = stmt.executeQuery()) {
                if (!rows.next()) throw new SQLException("Secure query account is not provisioned", "JQ001");
                if (rows.getBoolean(1)) throw new SQLException("Query account must not be an administrator", "JQ002");
            }
        }
        // H2 hides rights from non-admin users, hence this trusted metadata check.
        try (var stmt = admin.prepareStatement("SELECT GRANTEDROLE, RIGHTS, TABLE_SCHEMA, TABLE_NAME FROM INFORMATION_SCHEMA.RIGHTS WHERE GRANTEE IN (?, 'PUBLIC')")) {
            stmt.setString(1, USER);
            try (var rows = stmt.executeQuery()) {
                while (rows.next()) {
                    if (rows.getString(1) != null || !"SELECT".equals(rows.getString(2))
                            || !catalogue.tables().contains(new Relation(rows.getString(3), rows.getString(4))))
                        throw new SQLException("Query account has unsupported effective privileges", "JQ002");
                    granted.add(new Relation(rows.getString(3), rows.getString(4)));
                }
            }
        }
        return granted.containsAll(catalogue.tables());
    }

    static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }

    private static String sha256(Path file) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int size; (size = stream.read(buffer)) >= 0;) digest.update(buffer, 0, size);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /** Explicit packaging/setup command. Changes security metadata, not simulation rows.
     * Optional receipt records the source/result hashes after all DB connections close. */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException("Expected database base path and optional new receipt path");
        Path base = Path.of(args[0]), file = Path.of(args[0] + ".mv.db");
        Path receipt = args.length == 2 ? Path.of(args[1]) : null;
        if (receipt != null && Files.exists(receipt)) throw new IllegalArgumentException("Receipt already exists");
        String before = receipt == null ? null : sha256(file);
        provision(base);
        if (receipt != null) {
            String after = sha256(file);
            var record = Map.of("format_version", 1, "principal", USER, "privileges", "SELECT on approved local base tables",
                    "source_database_sha256", before, "database_sha256", after);
            Files.writeString(receipt, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(record) + "\n",
                    StandardOpenOption.CREATE_NEW);
        }
        System.out.println("Provisioned restricted DB Explorer account");
    }
}
