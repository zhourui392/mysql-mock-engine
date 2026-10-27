package cn.vv.test.mysqlmock.calcite;

import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * LAST_INSERT_ID(expr) 的回写通道。UDF 是静态方法，拿不到引擎实例；引擎执行语句时在当前线程登记回写目标，
 * UDF 取值后经这里写回该引擎，后续 LAST_INSERT_ID() 读到同一个值（MySQL 按连接记录，这里按引擎实例）。
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class LastInsertIdContext {

    private static final ThreadLocal<LongConsumer> RECORDER = new ThreadLocal<>();

    private LastInsertIdContext() {
    }

    /**
     * 在登记了回写目标的上下文里执行语句。
     *
     * @param recorder 回写目标
     * @param action   语句执行
     * @param <T>      执行结果类型
     * @return 执行结果
     */
    static <T> T runWith(LongConsumer recorder, Supplier<T> action) {
        LongConsumer previous = RECORDER.get();
        RECORDER.set(recorder);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                RECORDER.remove();
            } else {
                RECORDER.set(previous);
            }
        }
    }

    static void record(long value) {
        LongConsumer recorder = RECORDER.get();
        if (recorder != null) {
            recorder.accept(value);
        }
    }
}
