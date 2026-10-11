package cn.vv.test.mysqlmock.calcite;

import cn.vv.test.mysqlmock.api.AffectedRowsMode;
import cn.vv.test.mysqlmock.api.DatabaseSnapshot;
import cn.vv.test.mysqlmock.api.Dataset;
import cn.vv.test.mysqlmock.api.DatasetHandle;
import cn.vv.test.mysqlmock.api.MysqlMockDataSourceFactory;
import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.api.MysqlMockEngineBuilder;
import cn.vv.test.mysqlmock.api.MysqlMockException;
import cn.vv.test.mysqlmock.api.QueryResult;
import cn.vv.test.mysqlmock.api.ResourceLoader;
import cn.vv.test.mysqlmock.api.UpdateResult;
import cn.vv.test.mysqlmock.schema.DatasetLoader;
import cn.vv.test.mysqlmock.schema.MySqlDdlParser;
import cn.vv.test.mysqlmock.schema.SchemaCatalog;
import cn.vv.test.mysqlmock.schema.SqlScriptSplitter;
import cn.vv.test.mysqlmock.schema.TableDefinition;
import cn.vv.test.mysqlmock.storage.MemoryTable;
import cn.vv.test.mysqlmock.storage.SnapshotManager;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.config.Lex;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

import javax.sql.DataSource;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎实现：DDL 装载（SS 5.3.2 门禁 + 受控原文提取）、内存表注册、SELECT 经 Calcite、
 * DML 经自有执行器、快照 / 重放隔离（D-06）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
public final class MysqlMockEngineImpl implements MysqlMockEngine {

    /** JDBC 接入面工厂（反射装配，避免 calcite → jdbc 循环依赖）。 */
    public static final String DATASOURCE_FACTORY_IMPL =
            "cn.vv.test.mysqlmock.jdbc.MysqlMockDataSourceFactoryImpl";

    private final String mysqlVersion;
    private final AffectedRowsMode affectedRowsMode;
    private final Clock clock;
    private final SchemaCatalog catalog = new SchemaCatalog();
    private final Map<String, MemoryTable> tables = new LinkedHashMap<>();
    private final MySqlDdlParser ddlParser = new MySqlDdlParser();
    private final DatasetLoader datasetLoader = new DatasetLoader();
    private final SnapshotManager snapshotManager = new SnapshotManager();
    private final CalciteQueryEngine queryEngine;
    private final ModificationExecutor modificationExecutor;
    private final CalciteGeneratedColumnEvaluator generatedColumnEvaluator =
            new CalciteGeneratedColumnEvaluator();
    /** reset 重放用原始输入。 */
    private final List<String> ddlScripts = new ArrayList<>();
    private final List<Dataset> loadedDatasets = new ArrayList<>();

    private DataSource dataSource;
    private boolean closed;

    public MysqlMockEngineImpl(MysqlMockEngineBuilder builder) {
        this.mysqlVersion = builder.mysqlVersion();
        this.affectedRowsMode = builder.affectedRowsMode();
        this.clock = builder.clock();
        this.queryEngine = new CalciteQueryEngine(catalog, tables);
        this.modificationExecutor = new ModificationExecutor(tables, queryEngine,
                generatedColumnEvaluator, clock, affectedRowsMode);
        for (String location : builder.schemaLocations()) {
            executeDdl(ResourceLoader.readAsString(location));
        }
        for (String location : builder.datasetLocations()) {
            try (InputStream in = ResourceLoader.open(location)) {
                loadDataset(datasetLoader.load(in));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("读取数据集失败: " + location, e);
            }
        }
    }

    @Override
    public void executeDdl(String ddlScript) {
        ensureOpen();
        ddlScripts.add(ddlScript);
        for (String statement : SqlScriptSplitter.split(ddlScript)) {
            TableDefinition definition = ddlParser.parse(statement);
            if (ddlParser.isIfNotExists(statement) && catalog.exists(definition.name())) {
                continue;
            }
            for (cn.vv.test.mysqlmock.schema.ColumnDefinition column : definition.columns()) {
                generatedColumnEvaluator.validate(definition, column);
            }
            catalog.register(definition);
            tables.put(definition.name(), new MemoryTable(definition));
        }
    }

    @Override
    public QueryResult query(String sql, Object... params) {
        ensureOpen();
        return queryEngine.executeSelect(sql, params);
    }

    @Override
    public UpdateResult execute(String sql, Object... params) {
        ensureOpen();
        SqlNode node = parseSql(sql);
        switch (node.getKind()) {
            case INSERT:
                return modificationExecutor.executeInsert((SqlInsert) node, params);
            case UPDATE:
                return modificationExecutor.executeUpdate((SqlUpdate) node, params);
            case DELETE:
                return modificationExecutor.executeDelete((SqlDelete) node, params);
            default:
                throw MysqlMockException.unsupported("execute() 仅接受 INSERT/UPDATE/DELETE，收到 "
                        + node.getKind() + "（SQL: " + sql + "）");
        }
    }

    @Override
    public void loadDataset(InputStream datasetYaml) {
        loadDataset(datasetLoader.load(datasetYaml));
    }

    @Override
    public void loadDataset(Dataset dataset) {
        ensureOpen();
        loadedDatasets.add(dataset);
        applyDataset(dataset);
    }

    @Override
    public DatasetHandle dataset() {
        ensureOpen();
        return new StreamingDatasetHandle();
    }

    @Override
    public DatabaseSnapshot snapshot() {
        ensureOpen();
        return snapshotManager.snapshot(tables);
    }

    @Override
    public void restore(DatabaseSnapshot snapshot) {
        ensureOpen();
        snapshotManager.restore(snapshot, tables);
    }

    @Override
    public void reset() {
        ensureOpen();
        List<String> scripts = new ArrayList<>(ddlScripts);
        List<Dataset> datasets = new ArrayList<>(loadedDatasets);
        ddlScripts.clear();
        loadedDatasets.clear();
        catalog.clear();
        tables.clear();
        for (String script : scripts) {
            executeDdl(script);
        }
        for (Dataset dataset : datasets) {
            // 经 loadDataset 走完整登记（重放后仍保 reset 能力，可反复 reset）
            loadDataset(dataset);
        }
    }

    @Override
    public synchronized DataSource dataSource() {
        ensureOpen();
        if (dataSource == null) {
            try {
                Class<?> impl = Class.forName(DATASOURCE_FACTORY_IMPL);
                MysqlMockDataSourceFactory factory =
                        (MysqlMockDataSourceFactory) impl.getDeclaredConstructor().newInstance();
                dataSource = factory.create(this);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "找不到 JDBC 实现，请确认 classpath 包含 mysql-mock-jdbc 模块", e);
            }
        }
        return dataSource;
    }

    @Override
    public void close() {
        closed = true;
        queryEngine.close();
    }

    /** JDBC DatabaseMetaData 读模型入口。 */
    public SchemaCatalog catalog() {
        return catalog;
    }

    /** 声明的 MySQL 版本（DatabaseMetaData 展示用）。 */
    public String mysqlVersion() {
        return mysqlVersion;
    }

    private void applyDataset(Dataset dataset) {
        for (Map.Entry<String, List<Map<String, Object>>> tableEntry : dataset.tables().entrySet()) {
            for (Map<String, Object> row : tableEntry.getValue()) {
                modificationExecutor.insertDatasetRow(tableEntry.getKey(), row);
            }
        }
    }

    private SqlNode parseSql(String sql) {
        try {
            SqlParser parser = SqlParser.create(sql, SqlParser.config()
                    .withLex(Lex.MYSQL)
                    .withCaseSensitive(false)
                    .withConformance(SqlConformanceEnum.MYSQL_5));
            return parser.parseQuery();
        } catch (SqlParseException e) {
            throw MysqlMockException.parseError(e.getMessage() == null ? sql : e.getMessage());
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("引擎已关闭");
        }
    }

    /** engine.dataset().table("t").row(...).load() 流式装载。 */
    private final class StreamingDatasetHandle implements DatasetHandle {

        private final List<Dataset.TableBuilder> builders = new ArrayList<>();
        private Dataset.TableBuilder currentBuilder;

        @Override
        public DatasetHandle table(String tableName) {
            currentBuilder = Dataset.table(tableName);
            builders.add(currentBuilder);
            return this;
        }

        @Override
        public DatasetHandle row(Object... columnValuePairs) {
            if (currentBuilder == null) {
                throw new IllegalStateException("必须先调用 table() 指定表名");
            }
            currentBuilder.row(columnValuePairs);
            return this;
        }

        @Override
        public void load() {
            Map<String, List<Map<String, Object>>> merged = new LinkedHashMap<>();
            for (Dataset.TableBuilder builder : builders) {
                merged.putAll(builder.build().tables());
            }
            loadDataset(new Dataset(merged));
        }
    }
}
