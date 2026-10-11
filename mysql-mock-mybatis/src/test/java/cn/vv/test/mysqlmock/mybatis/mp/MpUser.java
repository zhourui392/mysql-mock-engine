package cn.vv.test.mysqlmock.mybatis.mp;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

/** T8 MP 实体：自增主键 + 逻辑删除 + 插入填充。 */
@TableName("t_mp_user")
public class MpUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private BigDecimal amount;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    @Override
    public String toString() {
        return "MpUser{id=" + id + ", name='" + name + "', amount=" + amount
                + ", deleted=" + deleted + ", createdBy='" + createdBy + "'}";
    }
}
