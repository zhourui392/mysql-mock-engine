package cn.vv.test.mysqlmock.mybatis;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SqlSessionTemplate 出口：唯一键冲突（1062 / 23000）按生产同款 MyBatisExceptionTranslator 翻译为
 * {@link DuplicateKeyException}，应用层 {@code catch (DuplicateKeyException)} 分支可在纯单测里走到；
 * 裸 SqlSession 仍是 MyBatis 的 {@link PersistenceException}。
 *
 * @author zhourui
 * @since 2026-10-23
 */
class SqlSessionTemplateTranslationTest {

    private MysqlMockEngine engine;

    @BeforeEach
    void setUp() {
        engine = MysqlMockEngine.builder().schema("classpath:mysql/mp-user.sql").build();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void duplicatePrimaryKeyIsTranslatedToDuplicateKeyException() {
        SqlSession template = mybatis().buildSqlSessionTemplate();
        UserWriteMapper mapper = template.getMapper(UserWriteMapper.class);
        mapper.insert(1L, "first");

        assertThrows(DuplicateKeyException.class, () -> mapper.insert(1L, "second"));
        assertEquals("first", mapper.selectName(1L));
    }

    @Test
    void plainSqlSessionKeepsMybatisException() {
        try (SqlSession session = mybatis().buildSqlSessionFactory().openSession(true)) {
            UserWriteMapper mapper = session.getMapper(UserWriteMapper.class);
            mapper.insert(1L, "first");

            assertThrows(PersistenceException.class, () -> mapper.insert(1L, "second"));
        }
    }

    private MysqlMockMybatis mybatis() {
        return MysqlMockMybatis.builder(engine).mapperClass(UserWriteMapper.class).build();
    }

    /** 显式主键写入，制造主键冲突。 */
    interface UserWriteMapper {

        @Insert("INSERT INTO t_mp_user (id, name) VALUES (#{id}, #{name})")
        int insert(@Param("id") long id, @Param("name") String name);

        @Select("SELECT name FROM t_mp_user WHERE id = #{id}")
        String selectName(@Param("id") long id);
    }
}
