package cn.vv.test.mysqlmock.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置键（前缀 mysql-mock，方案 §4）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
@ConfigurationProperties(prefix = "mysql-mock")
public class MysqlMockProperties {

    /** 必须 true 才装配（默认不装配）。 */
    private boolean enabled;

    /** DDL 资源位置（classpath: 或文件路径）。 */
    private List<String> schema = new ArrayList<>();

    /** YAML 数据集资源位置。 */
    private List<String> datasets = new ArrayList<>();

    /** 隔离策略：RECREATE（默认，重放）/ SNAPSHOT（快照恢复）。 */
    private ResetStrategy resetStrategy = ResetStrategy.RECREATE;

    /** strict 模式（默认 true；本期恒按 strict 执行，false 时启动显式告警）。 */
    private boolean sqlStrict = true;

    /** 模式 A：显式接管的 DataSource Bean 名。 */
    private String replaceDatasourceBean;

    /** 声明的 MySQL 版本（差分基准参数）。 */
    private String mysqlVersion = "8.0";

    /** 差分期望声明（mysql-version / sql-mode 等；未实现项启动时显式反馈）。 */
    private Map<String, String> compatibility = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getSchema() {
        return schema;
    }

    public void setSchema(List<String> schema) {
        this.schema = schema;
    }

    public List<String> getDatasets() {
        return datasets;
    }

    public void setDatasets(List<String> datasets) {
        this.datasets = datasets;
    }

    public ResetStrategy getResetStrategy() {
        return resetStrategy;
    }

    public void setResetStrategy(ResetStrategy resetStrategy) {
        this.resetStrategy = resetStrategy;
    }

    public boolean isSqlStrict() {
        return sqlStrict;
    }

    public void setSqlStrict(boolean sqlStrict) {
        this.sqlStrict = sqlStrict;
    }

    public String getReplaceDatasourceBean() {
        return replaceDatasourceBean;
    }

    public void setReplaceDatasourceBean(String replaceDatasourceBean) {
        this.replaceDatasourceBean = replaceDatasourceBean;
    }

    public String getMysqlVersion() {
        return mysqlVersion;
    }

    public void setMysqlVersion(String mysqlVersion) {
        this.mysqlVersion = mysqlVersion;
    }

    public Map<String, String> getCompatibility() {
        return compatibility;
    }

    public void setCompatibility(Map<String, String> compatibility) {
        this.compatibility = compatibility;
    }

    /** 隔离策略（与 junit5 扩展同名策略对齐，boot 层独立枚举避免对 jupiter 工件的类型耦合）。 */
    public enum ResetStrategy {
        /** 重放 DDL 与数据集。 */
        RECREATE,
        /** 初始化后快照，方法结束恢复。 */
        SNAPSHOT
    }
}
