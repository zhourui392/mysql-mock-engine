package cn.vv.test.mysqlmock.mybatis;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.mybatis.mp.MpUser;
import cn.vv.test.mysqlmock.mybatis.mp.MpUserMapper;
import cn.vv.test.mysqlmock.mybatis.mp.RawSqlMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * T8 MyBatis-Plus 3.4.3.4：BaseMapper CRUD + 分页（total/records）+ 逻辑删除（UPDATE 落库）
 * + 自动填充；分页方言经 getDatabaseProductName="MySQL" 识别。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class MybatisPlusEndToEndTest {

    private static MysqlMockEngine engine;
    private static SqlSessionFactory sessionFactory;

    @BeforeAll
    static void setUp() {
        engine = MysqlMockEngine.builder()
                .schema("classpath:mysql/mp-user.sql")
                .build();
        MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        sessionFactory = MysqlMockMybatis.builder(engine)
                .mybatisPlus(true)
                .mapperClass(MpUserMapper.class)
                .mapperClass(RawSqlMapper.class)
                .interceptors(pagination)
                .customize(new MysqlMockMybatis.ConfigurationCustomizer() {
                    @Override
                    public void apply(org.apache.ibatis.session.Configuration configuration) {
                        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
                        globalConfig.setMetaObjectHandler(new MetaObjectHandler() {
                            @Override
                            public void insertFill(MetaObject metaObject) {
                                strictInsertFill(metaObject, "createdBy", String.class, "auto-filler");
                            }

                            @Override
                            public void updateFill(MetaObject metaObject) {
                                // 无更新填充
                            }
                        });
                        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
                    }
                })
                .build()
                .buildSqlSessionFactory();
    }

    @AfterAll
    static void tearDown() {
        engine.close();
    }

    @Test
    void baseMapperCrudWithAutoIncrementBackfill() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            MpUserMapper mapper = session.getMapper(MpUserMapper.class);
            MpUser user = new MpUser();
            user.setName("Alice");
            user.setAmount(new BigDecimal("9.90"));
            assertEquals(1, mapper.insert(user));
            assertNotNull(user.getId(), "自增主键回填");

            MpUser loaded = mapper.selectById(user.getId());
            assertEquals("Alice", loaded.getName());
            assertEquals(0, new BigDecimal("9.90").compareTo(loaded.getAmount()));
            assertEquals("auto-filler", loaded.getCreatedBy(), "插入自动填充生效");

            loaded.setName("Alice2");
            assertEquals(1, mapper.updateById(loaded));
            assertEquals("Alice2", mapper.selectById(loaded.getId()).getName());
        }
    }

    @Test
    void paginationTotalAndRecords() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            MpUserMapper mapper = session.getMapper(MpUserMapper.class);
            for (int i = 2; i <= 7; i++) {
                MpUser user = new MpUser();
                user.setName("user-" + i);
                user.setAmount(BigDecimal.valueOf(i));
                mapper.insert(user);
            }
            Page<MpUser> page = mapper.selectPage(new Page<MpUser>(2, 3),
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<MpUser>()
                            .likeRight("name", "user-"));
            assertEquals(6, page.getTotal(), "user-* 共 6 条");
            assertEquals(3, page.getRecords().size(), "每页 3 条");
            assertEquals("user-5", page.getRecords().get(0).getName(), "第二页首行（跳过前 3 行）");
        }
    }

    @Test
    void logicDeleteIsUpdateAndFiltersQueries() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            MpUserMapper mapper = session.getMapper(MpUserMapper.class);
            RawSqlMapper raw = session.getMapper(RawSqlMapper.class);

            MpUser user = new MpUser();
            user.setName("To-Delete");
            mapper.insert(user);
            long before = raw.countLive();

            assertEquals(1, mapper.deleteById(user.getId()), "逻辑删除影响行数");
            assertEquals(Integer.valueOf(1), raw.selectDeleted(user.getId()), "落库形态是 UPDATE deleted=1");
            assertEquals(before - 1, raw.countLive(), "计数过滤已删行");
            assertNull(mapper.selectById(user.getId()), "selectById 过滤逻辑删除行");
        }
    }

    /** GlobalConfig 工具（MybatisConfiguration 与 GlobalConfig 关联）。 */
    static final class GlobalConfigUtils {

        static GlobalConfig defaults() {
            return com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.defaults();
        }

        static void setGlobalConfig(org.apache.ibatis.session.Configuration configuration,
                                    GlobalConfig globalConfig) {
            com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);
        }
    }
}
