package cn.vv.test.mysqlmock.schema;

import cn.vv.test.mysqlmock.api.Dataset;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** YAML 数据集装载：表名 → 行列表（列名 → 值）。 */
public final class DatasetLoader {

    @SuppressWarnings("unchecked")
    public Dataset load(InputStream yamlStream) {
        Object loaded = new Yaml().load(yamlStream);
        if (loaded == null) {
            return new Dataset();
        }
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException("数据集 YAML 顶层必须是 表名 → 行列表 的映射");
        }
        Map<String, List<Map<String, Object>>> tables = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) loaded).entrySet()) {
            if (!(entry.getValue() instanceof List)) {
                throw new IllegalArgumentException("数据集表 " + entry.getKey() + " 的值必须是行列表");
            }
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (Object row : (List<Object>) entry.getValue()) {
                if (!(row instanceof Map)) {
                    throw new IllegalArgumentException("数据集表 " + entry.getKey() + " 的行必须是列名 → 值 的映射");
                }
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<String, Object> column : ((Map<String, Object>) row).entrySet()) {
                    normalized.put(column.getKey().toLowerCase(), column.getValue());
                }
                rows.add(normalized);
            }
            tables.put(entry.getKey().toLowerCase(), rows);
        }
        return new Dataset(tables);
    }
}
