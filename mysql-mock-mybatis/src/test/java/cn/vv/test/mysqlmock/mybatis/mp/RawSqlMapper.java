package cn.vv.test.mysqlmock.mybatis.mp;

import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** T8：MP 环境内的原生 SQL 校验（逻辑删除落库形态）。 */
public interface RawSqlMapper {

    @Select("SELECT deleted FROM t_mp_user WHERE id = #{id}")
    Integer selectDeleted(long id);

    @Select("SELECT COUNT(*) FROM t_mp_user WHERE deleted = 0")
    long countLive();

    @Select("SELECT id, name, amount, deleted, created_by AS createdBy FROM t_mp_user WHERE deleted = 0 ORDER BY id")
    List<Map<String, Object>> selectAllLive();
}
