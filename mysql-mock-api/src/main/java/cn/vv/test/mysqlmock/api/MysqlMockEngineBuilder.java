package cn.vv.test.mysqlmock.api;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** 引擎构造器。schema/dataset 位置支持 classpath: 与文件路径两种形式。 */
public final class MysqlMockEngineBuilder {

    private static final String FACTORY_IMPL = "cn.vv.test.mysqlmock.calcite.MysqlMockEngineFactoryImpl";

    private String mysqlVersion = "8.0";
    private AffectedRowsMode affectedRowsMode = AffectedRowsMode.MATCHED;
    private final List<String> schemaLocations = new ArrayList<>();
    private final List<String> datasetLocations = new ArrayList<>();
    private Clock clock = Clock.systemDefaultZone();

    public MysqlMockEngineBuilder mysqlVersion(String version) {
        this.mysqlVersion = version;
        return this;
    }

    /** UPDATE 影响行数口径（D-10：默认 MATCHED）。 */
    public MysqlMockEngineBuilder affectedRowsMode(AffectedRowsMode mode) {
        this.affectedRowsMode = mode == null ? AffectedRowsMode.MATCHED : mode;
        return this;
    }

    public MysqlMockEngineBuilder schema(String... locations) {
        for (String location : locations) {
            schemaLocations.add(location);
        }
        return this;
    }

    public MysqlMockEngineBuilder dataset(String... locations) {
        for (String location : locations) {
            datasetLocations.add(location);
        }
        return this;
    }

    /** 差分与确定性测试注入时钟（D-09：非确定函数固定时钟）。 */
    public MysqlMockEngineBuilder clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    public String mysqlVersion() {
        return mysqlVersion;
    }

    public AffectedRowsMode affectedRowsMode() {
        return affectedRowsMode;
    }

    public List<String> schemaLocations() {
        return schemaLocations;
    }

    public List<String> datasetLocations() {
        return datasetLocations;
    }

    public Clock clock() {
        return clock;
    }

    public MysqlMockEngine build() {
        try {
            Class<?> impl = Class.forName(FACTORY_IMPL);
            MysqlMockEngineFactory factory = (MysqlMockEngineFactory) impl.getDeclaredConstructor().newInstance();
            return factory.create(this);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("找不到引擎实现，请确认 classpath 包含 mysql-mock-calcite 模块", e);
        }
    }
}
