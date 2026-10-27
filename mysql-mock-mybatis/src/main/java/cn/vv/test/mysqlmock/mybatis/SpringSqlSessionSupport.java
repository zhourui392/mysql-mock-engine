package cn.vv.test.mysqlmock.mybatis;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * mybatis-spring 引用隔离在这里：{@link MysqlMockMybatis} 的其他出口不依赖 Spring，
 * 只有调用 {@code buildSqlSessionTemplate()} 时才加载本类。
 *
 * @author zhourui
 * @since 2026-10-23
 */
final class SpringSqlSessionSupport {

    static final String TEMPLATE_CLASS = "org.mybatis.spring.SqlSessionTemplate";

    private SpringSqlSessionSupport() {
    }

    static SqlSession createTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(new AutoCommitSqlSessionFactory(sqlSessionFactory));
    }
}
