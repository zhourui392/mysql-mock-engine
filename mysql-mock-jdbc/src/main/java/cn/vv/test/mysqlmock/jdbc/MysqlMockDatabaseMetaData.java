package cn.vv.test.mysqlmock.jdbc;

import cn.vv.test.mysqlmock.api.QueryResult;
import cn.vv.test.mysqlmock.calcite.MysqlMockEngineImpl;
import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.schema.ColumnDefinition;
import cn.vv.test.mysqlmock.schema.IndexDefinition;
import cn.vv.test.mysqlmock.schema.TableDefinition;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DatabaseMetaData：getURL（jdbc:mysql:mock: 前缀）/ getDatabaseProductName（MySQL，
 * MyBatis-Plus 分页方言据此识别）/ getTables / getColumns / getPrimaryKeys。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class MysqlMockDatabaseMetaData implements DatabaseMetaData {

    private final MysqlMockConnection connection;

    MysqlMockDatabaseMetaData(MysqlMockConnection connection) {
        this.connection = connection;
    }

    @Override
    public Connection getConnection() throws java.sql.SQLException {
        return connection;
    }

    @Override
    public String getURL() throws java.sql.SQLException {
        return "jdbc:mysql:mock:";
    }

    @Override
    public String getUserName() throws java.sql.SQLException {
        return "mysql-mock";
    }

    @Override
    public String getDatabaseProductName() throws java.sql.SQLException {
        return "MySQL";
    }

    @Override
    public int getDatabaseMajorVersion() throws java.sql.SQLException {
        return 8;
    }

    @Override
    public int getDatabaseMinorVersion() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public String getDatabaseProductVersion() throws java.sql.SQLException {
        return engineVersion() + "-mysql-mock";
    }

    @Override
    public String getDriverName() throws java.sql.SQLException {
        return "mysql-mock-jdbc";
    }

    @Override
    public String getDriverVersion() throws java.sql.SQLException {
        return "0.1.0";
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 1;
    }

    @Override
    public int getDriverMajorVersion() {
        return 0;
    }

    @Override
    public int getDriverMinorVersion() {
        return 1;
    }

    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern,
                               String[] types) throws java.sql.SQLException {
        ResultBuilder builder = new ResultBuilder("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME",
                "TABLE_TYPE", "REMARKS", "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME",
                "SELF_REFERENCING_COL_NAME", "REF_GENERATION");
        Pattern pattern = likePattern(tableNamePattern);
        List<String> sorted = new ArrayList<>(catalogImpl().all().keySet());
        Collections.sort(sorted);
        for (String name : sorted) {
            if (pattern != null && !pattern.matcher(name).matches()) {
                continue;
            }
            TableDefinition definition = catalogImpl().get(name);
            String tableCatalog = definition.database() == null ? "mock" : definition.database();
            builder.row(tableCatalog, null, name, "BASE TABLE",
                    definition.comment(), null, null, null, null, null);
        }
        return new MysqlMockResultSet(builder.build());
    }

    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern,
                                String columnNamePattern) throws java.sql.SQLException {
        ResultBuilder builder = new ResultBuilder("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME",
                "COLUMN_NAME", "DATA_TYPE", "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH",
                "DECIMAL_DIGITS", "NUM_PREC_RADIX", "NULLABLE", "REMARKS", "COLUMN_DEF",
                "SQL_DATA_TYPE", "SQL_DATETIME_SUB", "CHAR_OCTET_LENGTH", "ORDINAL_POSITION",
                "IS_NULLABLE", "SCOPE_CATALOG", "SCOPE_SCHEMA", "SCOPE_TABLE", "SOURCE_DATA_TYPE",
                "IS_AUTOINCREMENT", "IS_GENERATEDCOLUMN");
        Pattern tablePattern = likePattern(tableNamePattern);
        Pattern columnPattern = likePattern(columnNamePattern);
        List<String> sorted = new ArrayList<>(catalogImpl().all().keySet());
        Collections.sort(sorted);
        for (String tableName : sorted) {
            if (tablePattern != null && !tablePattern.matcher(tableName).matches()) {
                continue;
            }
            TableDefinition definition = catalogImpl().get(tableName);
            String tableCatalog = definition.database() == null ? "mock" : definition.database();
            List<ColumnDefinition> columns = definition.columns();
            for (int i = 0; i < columns.size(); i++) {
                ColumnDefinition column = columns.get(i);
                if (columnPattern != null && !columnPattern.matcher(column.name()).matches()) {
                    continue;
                }
                builder.row(tableCatalog, null, tableName, column.name(),
                        JdbcTypes.sqlType(column), JdbcTypes.typeName(column),
                        column.precision() > 0 ? (long) column.precision() : null, null,
                        column.type() == cn.vv.test.mysqlmock.schema.MysqlType.DECIMAL
                                && column.scale() > 0 ? (long) column.scale() : null,
                        JdbcTypes.isNumeric(column.type()) ? 10L : null,
                        column.notNull() ? (long) columnNoNulls : (long) columnNullable,
                        column.comment(),
                        column.hasDefault() ? String.valueOf(column.defaultValue()) : null,
                        null, null, null, (long) (i + 1),
                        column.notNull() ? "NO" : "YES",
                        null, null, null, null,
                        column.autoIncrement() ? "YES" : "NO",
                        column.generated() ? "YES" : "NO");
            }
        }
        return new MysqlMockResultSet(builder.build());
    }

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws java.sql.SQLException {
        ResultBuilder builder = new ResultBuilder("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME",
                "COLUMN_NAME", "KEY_SEQ", "PK_NAME");
        TableDefinition definition = table == null ? null : catalogImpl().get(table);
        if (definition != null && definition.primaryIndex() != null) {
            IndexDefinition primary = definition.primaryIndex();
            String tableCatalog = definition.database() == null ? "mock" : definition.database();
            for (int i = 0; i < primary.columns().size(); i++) {
                builder.row(tableCatalog, null, definition.name(), primary.columns().get(i),
                        (long) (i + 1), primary.name());
            }
        }
        return new MysqlMockResultSet(builder.build());
    }

    @Override
    public ResultSet getClientInfoProperties() throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getTableTypes() throws java.sql.SQLException {
        ResultBuilder builder = new ResultBuilder("TABLE_TYPE");
        builder.row("BASE TABLE");
        return new MysqlMockResultSet(builder.build());
    }

    // ---- 事务 / 标识符语义（D-14 + MySQL 形态） ----

    @Override
    public boolean supportsTransactions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public int getDefaultTransactionIsolation() throws java.sql.SQLException {
        return Connection.TRANSACTION_NONE;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) throws java.sql.SQLException {
        return level == Connection.TRANSACTION_NONE;
    }

    @Override
    public boolean supportsResultSetType(int type) throws java.sql.SQLException {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) throws java.sql.SQLException {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public String getIdentifierQuoteString() throws java.sql.SQLException {
        return "`";
    }

    @Override
    public String getSearchStringEscape() throws java.sql.SQLException {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() throws java.sql.SQLException {
        return "$";
    }

    // ---- 其余元数据：合理默认 ----

    @Override
    public boolean allProceduresAreCallable() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean allTablesAreSelectable() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws java.sql.SQLException {
        return true;
    }

    @Override
    public ResultSet getAttributes(String catalog, String schemaPattern, String typeNamePattern,
                                   String attributeNamePattern) throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope,
                                          boolean nullable) {
        return empty();
    }

    @Override
    public ResultSet getCatalogs() throws java.sql.SQLException {
        ResultBuilder builder = new ResultBuilder("TABLE_CAT");
        builder.row("mock");
        return new MysqlMockResultSet(builder.build());
    }

    @Override
    public String getCatalogTerm() throws java.sql.SQLException {
        return "DATABASE";
    }

    @Override
    public String getCatalogSeparator() throws java.sql.SQLException {
        return ".";
    }

    @Override
    public boolean isCatalogAtStart() throws java.sql.SQLException {
        return true;
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table,
                                         String columnNamePattern) {
        return empty();
    }

    @Override
    public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
                                       String foreignCatalog, String foreignSchema, String foreignTable)
            throws java.sql.SQLException {
        return empty();
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws java.sql.SQLException {
        return false;
    }

    @Override
    public int getSQLStateType() throws java.sql.SQLException {
        return sqlStateSQL;
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getFunctionColumns(String catalog, String schemaPattern,
                                        String functionNamePattern, String columnNamePattern)
            throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique,
                                  boolean approximate) {
        return empty();
    }

    @Override
    public int getMaxBinaryLiteralLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() throws java.sql.SQLException {
        return 64;
    }

    @Override
    public int getMaxColumnsInGroupBy() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() throws java.sql.SQLException {
        return 16;
    }

    @Override
    public int getMaxColumnsInOrderBy() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxConnections() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxCursorNameLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxIndexLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxRowSize() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxStatementLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxStatements() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() throws java.sql.SQLException {
        return 64;
    }

    @Override
    public int getMaxTablesInSelect() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public int getMaxUserNameLength() throws java.sql.SQLException {
        return 0;
    }

    @Override
    public ResultSet getProcedureColumns(String catalog, String schemaPattern,
                                         String procedureNamePattern, String columnNamePattern)
            throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) throws java.sql.SQLException {
        return empty();
    }

    @Override
    public String getProcedureTerm() throws java.sql.SQLException {
        return "PROCEDURE";
    }

    @Override
    public ResultSet getSchemas() throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws java.sql.SQLException {
        return empty();
    }

    @Override
    public String getSchemaTerm() throws java.sql.SQLException {
        return "SCHEMA";
    }

    @Override
    public String getSQLKeywords() throws java.sql.SQLException {
        return "";
    }

    @Override
    public String getNumericFunctions() throws java.sql.SQLException {
        return "";
    }

    @Override
    public String getStringFunctions() throws java.sql.SQLException {
        return "";
    }

    @Override
    public String getSystemFunctions() throws java.sql.SQLException {
        return "";
    }

    @Override
    public String getTimeDateFunctions() throws java.sql.SQLException {
        return "";
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getTypeInfo() throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) throws java.sql.SQLException {
        return empty();
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws java.sql.SQLException {
        return empty();
    }

    @Override
    public boolean insertsAreDetected(int type) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean isReadOnly() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean locatorsUpdateCopy() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedHigh() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean nullsAreSortedLow() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsANSI92FullSQL() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsBatchUpdates() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsColumnAliasing() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsConvert() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsFullOuterJoins() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupBy() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsLikeEscapeClause() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsMultipleOpenResults() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleResultSets() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsNamedParameters() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsNonNullableColumns() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsOrderByUnrelated() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsOuterJoins() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsPositionedDelete() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() throws java.sql.SQLException {
        return false;
    }

    @Override
    public int getResultSetHoldability() throws java.sql.SQLException {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) throws java.sql.SQLException {
        return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public boolean supportsSavepoints() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsStatementPooling() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsStoredProcedures() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsTableCorrelationNames() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsUnion() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean supportsUnionAll() throws java.sql.SQLException {
        return true;
    }

    @Override
    public boolean updatesAreDetected(int type) throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() throws java.sql.SQLException {
        return false;
    }

    @Override
    public boolean usesLocalFiles() throws java.sql.SQLException {
        return false;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() throws java.sql.SQLException {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws java.sql.SQLException {
        return true;
    }

    @Override
    public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern,
                                      String columnNamePattern) throws java.sql.SQLException {
        return empty();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException {
        return iface.isInstance(this);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return (T) this;
        }
        throw new SQLException("DatabaseMetaData 不实现 " + iface.getName());
    }

    // ---- 私有 ----

    private cn.vv.test.mysqlmock.schema.SchemaCatalog catalogImpl() throws java.sql.SQLException {
        MysqlMockEngine engine = connection.engine();
        if (engine instanceof MysqlMockEngineImpl) {
            return ((MysqlMockEngineImpl) engine).catalog();
        }
        throw SqlExceptions.of("引擎实现缺少 catalog 读模型: " + engine.getClass().getName());
    }

    private String engineVersion() {
        MysqlMockEngine engine = connection.engine();
        if (engine instanceof MysqlMockEngineImpl) {
            return ((MysqlMockEngineImpl) engine).mysqlVersion();
        }
        return "8.0";
    }

    private static ResultSet empty() {
        return new MysqlMockResultSet(MysqlMockResultSet.EMPTY);
    }

    /** % → .*，_ → .；null 表示全匹配。 */
    private static Pattern likePattern(String pattern) {
        if (pattern == null) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '%':
                    regex.append(".*");
                    break;
                case '_':
                    regex.append('.');
                    break;
                default:
                    regex.append(Pattern.quote(String.valueOf(c)));
                    break;
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    /** 元数据结果装配器。 */
    private static final class ResultBuilder {

        private final List<String> names;
        private final List<Integer> types;
        private final List<Object[]> rows = new ArrayList<>();

        ResultBuilder(String... columnNames) {
            this.names = Arrays.asList(columnNames);
            this.types = new ArrayList<>(Collections.nCopies(columnNames.length,
                    java.sql.Types.VARCHAR));
        }

        ResultBuilder row(Object... values) {
            rows.add(values);
            return this;
        }

        QueryResult build() {
            return new QueryResult(names, types, rows);
        }
    }
}
