package cn.vv.test.mysqlmock.api;

import java.util.Collections;
import java.util.List;

/** DML 执行结果：影响行数（默认 matched 语义，D-10）与自增回填键。 */
public final class UpdateResult {

    private final int affectedRows;
    private final List<Object> generatedKeys;

    public UpdateResult(int affectedRows, List<Object> generatedKeys) {
        this.affectedRows = affectedRows;
        this.generatedKeys = generatedKeys == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(generatedKeys);
    }

    public int affectedRows() {
        return affectedRows;
    }

    public List<Object> generatedKeys() {
        return generatedKeys;
    }
}
