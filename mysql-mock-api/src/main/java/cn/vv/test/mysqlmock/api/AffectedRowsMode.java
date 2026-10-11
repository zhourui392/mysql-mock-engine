package cn.vv.test.mysqlmock.api;

/**
 * UPDATE 影响行数口径（D-10）。
 * <p>MATCHED 为 MySQL Connector/J 默认（useAffectedRows=false，返回 found rows）；CHANGED 为
 * useAffectedRows=true 口径。v0.1 默认 MATCHED。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
public enum AffectedRowsMode {

    /** WHERE 命中即计数（MySQL 客户端默认）。 */
    MATCHED,

    /** 值实际发生变化才计数。 */
    CHANGED
}
