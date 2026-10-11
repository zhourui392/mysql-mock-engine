package cn.vv.test.mysqlmock.storage;

import cn.vv.test.mysqlmock.api.DatabaseSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 整库快照与恢复（D-06：SNAPSHOT 隔离；恢复时重建索引并复位自增指针）。 */
public final class SnapshotManager {

    public DatabaseSnapshot snapshot(Map<String, MemoryTable> tables) {
        Map<String, TableSnapshot> states = new LinkedHashMap<>();
        for (Map.Entry<String, MemoryTable> entry : tables.entrySet()) {
            states.put(entry.getKey(), entry.getValue().snapshotState());
        }
        return new DatabaseSnapshotImpl(states);
    }

    public void restore(DatabaseSnapshot snapshot, Map<String, MemoryTable> tables) {
        if (!(snapshot instanceof DatabaseSnapshotImpl)) {
            throw new IllegalArgumentException("未知的快照实现: " + snapshot.getClass());
        }
        Map<String, TableSnapshot> states = ((DatabaseSnapshotImpl) snapshot).states;
        for (Map.Entry<String, MemoryTable> entry : tables.entrySet()) {
            TableSnapshot state = states.get(entry.getKey());
            if (state == null) {
                throw new IllegalStateException("快照缺少表 " + entry.getKey());
            }
            entry.getValue().restoreState(state);
        }
    }

    private static final class DatabaseSnapshotImpl implements DatabaseSnapshot {
        private final Map<String, TableSnapshot> states;

        private DatabaseSnapshotImpl(Map<String, TableSnapshot> states) {
            this.states = states;
        }
    }
}
