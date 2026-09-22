/* (C) Copyright 2026, by Ross Richardson
 * Fail-closed, non-executing SQL syntax policy for the H2 DB Explorer.
 * @author ross richardson
 */
package microsim.web.server;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.create.table.ColDataType;

public final class SqlSafety {
    public static final int MAX_SQL_BYTES = 64 * 1024;
    private SqlSafety() { }
    public record CheckedQuery(String sql, Set<DatabaseQueryAccess.Relation> tables, Set<String> functions) { }

    private static final Set<String> FUNCTIONS = Set.of(
        "COUNT", "SUM", "AVG", "MIN", "MAX", "STDDEV_POP", "STDDEV_SAMP", "VAR_POP", "VAR_SAMP",
        "CORR", "COVAR_POP", "COVAR_SAMP", "MEDIAN", "PERCENTILE_CONT", "PERCENTILE_DISC",
        "ABS", "ROUND", "FLOOR", "CEIL", "CEILING", "SQRT", "POWER", "MOD", "SIGN",
        "COALESCE", "NULLIF", "IFNULL", "NVL", "GREATEST", "LEAST", "LOWER", "UPPER",
        "LENGTH", "CHAR_LENGTH", "CHARACTER_LENGTH", "TRIM", "LTRIM", "RTRIM", "SUBSTRING", "CONCAT",
        "ROW_NUMBER", "RANK", "DENSE_RANK", "PERCENT_RANK", "CUME_DIST", "NTILE", "LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE");
    private static final Set<String> CAST_TYPES = Set.of("TINYINT", "SMALLINT", "INT", "INTEGER", "BIGINT",
        "NUMERIC", "DECIMAL", "REAL", "FLOAT", "DOUBLE", "DOUBLE PRECISION", "BOOLEAN", "CHAR", "VARCHAR",
        "CHARACTER VARYING", "DATE", "TIME", "TIMESTAMP", "TIME WITH TIME ZONE", "TIMESTAMP WITH TIME ZONE");
    private static final Set<DatabaseQueryAccess.Relation> METADATA = Set.of(
        new DatabaseQueryAccess.Relation("INFORMATION_SCHEMA", "TABLES"),
        new DatabaseQueryAccess.Relation("INFORMATION_SCHEMA", "COLUMNS"));

    /* Explicit AST field schema for the pinned parser. Walking all fields, rather than
       an adapter's permissive default visits, makes unknown nodes/clauses fail closed.
       Only the parser's source-position tree (ASTNodeAccessImpl) is ignored.
       Upgrade this schema together with the dependency and negative query corpus. */
    private static final Map<String, Set<String>> FIELDS = fields();
    private static Map<String, Set<String>> fields() {
        var m = new HashMap<String, Set<String>>();
        String[] definitions = {
            "Select:withItemsList limit offset fetch orderByElements alias",
            "PlainSelect:distinct selectItems fromItem joins where groupBy having qualify windowDefinitions",
            "ParenthesedSelect:alias select", "ParenthesedFromItem:fromItem joins alias",
            "WithItem:statement alias withItemList", "SetOperationList:selects operations orderByElements",
            "SetOperation:type modifier", "UnionOp:", "IntersectOp:", "ExceptOp:", "MinusOp:",
            "Join:onExpressions usingColumns outer right left natural full inner simple cross fromItem",
            "SelectItem:expression alias", "Distinct:onSelectItems", "OrderByElement:expression asc ascDescPresent nullOrdering",
            "GroupByElement:groupByExpressions groupingSets groupBySortDirections",
            "Limit:rowCount offset", "Offset:offsetExpression offsetParam", "Fetch:expression isFetchParamFirst fetchParameters",
            "Table:partItems partDelimiters alias", "Column:table columnName tableDelimiter",
            "Alias:name useAs aliasColumns", "AliasColumn:name", "AllColumns:", "AllTableColumns:table",
            "NullValue:", "BooleanValue:value", "LongValue:stringValue", "DoubleValue:value stringValue",
            "StringValue:value prefix quoteStr", "DateValue:value", "TimeValue:value", "TimestampValue:value",
            "DateTimeLiteralExpression:value type", "DateUnitExpression:type", "SignedExpression:sign expression",
            "BinaryExpression:leftExpression rightExpression", "OldOracleJoinBinaryExpression:", "ComparisonOperator:operator",
            "Addition:", "Subtraction:", "Multiplication:", "Division:", "Modulo:", "Concat:",
            "AndExpression:", "OrExpression:", "EqualsTo:", "NotEqualsTo:", "GreaterThan:", "GreaterThanEquals:", "MinorThan:", "MinorThanEquals:",
            "Between:leftExpression not betweenExpressionStart betweenExpressionEnd usingSymmetric usingAsymmetric",
            "InExpression:leftExpression not rightExpression", "IsNullExpression:leftExpression not",
            "IsBooleanExpression:leftExpression not isTrue", "IsDistinctExpression:leftExpression rightExpression not",
            "LikeExpression:not escapeExpression likeKeyWord", "NotExpression:expression", "ExistsExpression:rightExpression not",
            "CaseExpression:usingBrackets switchExpression whenClauses elseExpression", "WhenClause:whenExpression thenExpression",
            "Function:nameparts parameters allColumns distinct orderByElements nullHandling",
            "AnalyticExpression:name expression offset defaultValue allColumns type distinct filterExpression windowName windowDef",
            "WindowDefinition:orderBy partitionBy windowElement windowName", "OrderByClause:orderByElements",
            "PartitionByClause:partitionExpressionList brackets", "WindowElement:type offset range exclusion",
            "WindowRange:start end", "WindowOffset:expression type",
            "CastExpression:keyword leftExpression colDataType isImplicitCast", "ColDataType:dataType argumentsStringList precision scale",
            "ExtractExpression:name expression", "TrimFunction:trimSpecification fromExpression expression"
        };
        for (String definition : definitions) {
            String[] parts = definition.split(":", 2);
            m.put(parts[0], parts[1].isEmpty() ? Set.of() : Set.of(parts[1].split(" ")));
        }
        return Map.copyOf(m);
    }

    public static CheckedQuery check(String sql) {
        if (sql == null || sql.isBlank() || sql.length() > MAX_SQL_BYTES || sql.getBytes(StandardCharsets.UTF_8).length > MAX_SQL_BYTES)
            throw rejected("Query is empty or exceeds the 64 KiB SQL limit");
        try {
            var statements = CCJSqlParserUtil.parseStatements(sql, parser -> parser.withTimeOut(2000));
            if (statements.size() != 1) throw rejected("Only a single SELECT query is allowed");
            var statement = statements.get(0);
            if (!(statement instanceof Select)) throw rejected("Only supported SELECT queries and SELECT-only CTEs are allowed");
            var guard = new Guard();
            guard.walk(statement, Set.of(), false, 0);
            return new CheckedQuery(statement.toString(), Set.copyOf(guard.tables), Set.copyOf(guard.functions));
        } catch (RejectedQuery e) { throw e; }
        catch (Exception | StackOverflowError e) { throw rejected("Query syntax is unsupported or too complex"); }
    }

    public static void checkCatalogue(CheckedQuery query, DatabaseQueryAccess.Catalogue catalogue) {
        for (var table : query.tables())
            if (!METADATA.contains(table) && !catalogue.tables().contains(table))
                throw rejected("Only local base tables and supported metadata tables can be queried");
        for (String function : query.functions())
            if (catalogue.aliases().contains(function)) throw rejected("Model-defined SQL functions are not supported");
    }

    private static final class Guard {
        final Set<DatabaseQueryAccess.Relation> tables = new HashSet<>();
        final Set<String> functions = new HashSet<>();
        int nodes;
        void walk(Object value, Set<String> ctes, boolean relation, int depth) throws ReflectiveOperationException {
            if (value == null) return;
            if (++nodes > 20000 || depth > 80) throw rejected("Query is too complex");
            if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character
                    || value instanceof Enum<?> || value instanceof java.util.Date) return;
            if (value instanceof Collection<?> values) {
                String name = value.getClass().getName();
                if (name.startsWith("net.sf.jsqlparser.") && !Set.of("ExpressionList", "ParenthesedExpressionList", "PartitionByClause").contains(value.getClass().getSimpleName()))
                    throw rejected("Unsupported expression list");
                for (Object child : values) walk(child, ctes, relation, depth + 1);
                return;
            }
            Class<?> type = value.getClass();
            if (!type.getName().startsWith("net.sf.jsqlparser.") || !FIELDS.containsKey(type.getSimpleName()))
                throw rejected("Unsupported SQL construct: " + type.getSimpleName());
            if (value instanceof Select select && select.getWithItemsList() != null) {
                ctes = new HashSet<>(ctes);
                for (var item : select.getWithItemsList()) {
                    // A CTE sees earlier CTEs, not itself or later declarations. Otherwise
                    // a same-named physical view in its body could evade catalogue checks.
                    walk(item, Set.copyOf(ctes), false, depth + 1);
                    ctes.add(identifier(item.getAlias().getName()));
                }
            }
            if (value instanceof net.sf.jsqlparser.expression.StringValue string) {
                if (!"'".equals(string.getQuoteStr())
                        || (string.getPrefix() != null && !"N".equalsIgnoreCase(string.getPrefix()))
                        || !pairedQuotes(string.getValue(), '\''))
                    throw rejected("Use standard single-quoted SQL strings");
            }
            if (value instanceof net.sf.jsqlparser.schema.Column column) identifier(column.getColumnName());
            if (value instanceof net.sf.jsqlparser.expression.Alias alias) identifier(alias.getName());
            if (value instanceof Function function) function(function.getName());
            if (value instanceof AnalyticExpression analytic) function(analytic.getName());
            if (value instanceof ColDataType dataType) {
                String castType = dataType.getDataType().toUpperCase(Locale.ROOT).strip();
                // Parser 5.4 can keep precision/scale inside dataType as well as separate fields.
                var sized = java.util.regex.Pattern.compile("^([A-Z ]+)\\s*\\(\\s*([0-9]{1,5})(?:\\s*,\\s*([0-9]{1,5}))?\\s*\\)$").matcher(castType);
                if (sized.matches()) {
                    if (Integer.parseInt(sized.group(2)) > 65536
                            || (sized.group(3) != null && Integer.parseInt(sized.group(3)) > 65536))
                        throw rejected("CAST size exceeds the supported limit");
                    castType = sized.group(1).strip();
                }
                if (!CAST_TYPES.contains(castType)) throw rejected("Unsupported CAST type");
                if (dataType.getArgumentsStringList() != null)
                    for (String argument : dataType.getArgumentsStringList())
                        if (!argument.matches("[0-9]{1,5}") || Integer.parseInt(argument) > 65536)
                            throw rejected("CAST size exceeds the supported limit");
            }
            if (value instanceof Table table && relation) {
                if (table.getNameParts().size() > 2) throw rejected("Only local database tables can be queried");
                String name = identifier(table.getName());
                String schema = table.getSchemaName() == null ? "PUBLIC" : identifier(table.getSchemaName());
                if (table.getSchemaName() != null || !ctes.contains(name)) tables.add(new DatabaseQueryAccess.Relation(schema, name));
            }
            for (Class<?> current = type; current != Object.class; current = current.getSuperclass()) {
                if (current.getSimpleName().equals("ASTNodeAccessImpl")) continue;
                Set<String> allowed = FIELDS.get(current.getSimpleName());
                if (allowed == null) throw rejected("Unsupported SQL construct: " + current.getSimpleName());
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                    if (value instanceof Select && field.getName().equals("withItemsList")) continue;
                    field.setAccessible(true);
                    Object child = field.get(value);
                    if (!allowed.contains(field.getName())) {
                        if (unset(child) || (field.getName().equals("emitMode") && "NONE".equals(String.valueOf(child)))) continue;
                        throw rejected("Unsupported SQL clause: " + field.getName());
                    }
                    if (child != null && field.getName().equals("modifier")
                            && !Set.of("ALL", "DISTINCT").contains(child.toString().toUpperCase(Locale.ROOT)))
                        throw rejected("Unsupported set operation modifier");
                    if (field.getName().equals("likeKeyWord") && !Set.of("LIKE", "ILIKE").contains(String.valueOf(child)))
                        throw rejected("Unsupported pattern operator");
                    if (field.getName().equals("quoteStr") && !"'".equals(child)) throw rejected("Unsupported string quotation");
                    boolean from = (value instanceof PlainSelect || value instanceof Join || value instanceof ParenthesedFromItem)
                            && field.getName().equals("fromItem");
                    walk(child, ctes, from, depth + 1);
                }
            }
        }
        void function(String name) {
            String upper = name.toUpperCase(Locale.ROOT);
            if (!FUNCTIONS.contains(upper)) throw rejected("SQL function is not supported for analytical queries");
            functions.add(upper);
        }
    }

    private static boolean unset(Object value) {
        return value == null || Boolean.FALSE.equals(value) || (value instanceof Number n && n.doubleValue() == 0)
                || (value instanceof Collection<?> c && c.isEmpty());
    }
    private static String identifier(String value) {
        if (value.startsWith("\"") && value.endsWith("\"")) {
            String body = value.substring(1, value.length()-1);
            if (!pairedQuotes(body, '"')) throw rejected("Unsupported identifier quotation");
            return body.replace("\"\"", "\"");
        }
        if (!value.matches("[A-Za-z_][A-Za-z0-9_$]*")) throw rejected("Unsupported identifier quotation");
        return value.toUpperCase(Locale.ROOT);
    }
    private static boolean pairedQuotes(String text, char quote) {
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) == quote && (++i == text.length() || text.charAt(i) != quote)) return false;
        return true;
    }
    private static final class RejectedQuery extends IllegalArgumentException {
        RejectedQuery(String message) { super(message); }
    }
    private static RejectedQuery rejected(String message) { return new RejectedQuery(message); }
    public static boolean isAllowedReadOnlyQuery(String sql) {
        try { check(sql); return true; } catch (IllegalArgumentException e) { return false; }
    }
    /** Kept for source compatibility; the full parser is authoritative. */
    public static String firstSqlKeyword(String sql) {
        if (sql == null) return "";
        String text = sql.stripLeading();
        while (true) {
            if (text.startsWith("--")) { int end=text.indexOf('\n'); if(end<0)return ""; text=text.substring(end+1).stripLeading(); }
            else if(text.startsWith("/*")) {int end=text.indexOf("*/");if(end<0)return "";text=text.substring(end+2).stripLeading();}
            else if(text.startsWith("(")) text=text.substring(1).stripLeading();
            else break;
        }
        var matcher=java.util.regex.Pattern.compile("^[A-Za-z]+").matcher(text);
        return matcher.find()?matcher.group().toLowerCase(Locale.ROOT):"";
    }
    /** Invalid and multi-statement text is rejected alike; quoted semicolons remain data. */
    public static boolean hasMultipleStatements(String sql) {
        if (sql == null || sql.length() > MAX_SQL_BYTES) return true;
        try { return CCJSqlParserUtil.parseStatements(sql, parser -> parser.withTimeOut(2000)).size() != 1; }
        catch (Exception | StackOverflowError e) { return true; }
    }
}
