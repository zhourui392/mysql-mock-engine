package cn.vv.test.mysqlmock.boot;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;

import javax.sql.DataSource;

/**
 * 模式 A 接管目标的替换 Bean：经引擎暴露 DataSource（懒取，BDRPP 阶段引擎尚未建成）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class MysqlMockReplacedDataSourceFactoryBean implements FactoryBean<DataSource>, BeanFactoryAware {

    private BeanFactory beanFactory;
    private DataSource cached;

    @Override
    public DataSource getObject() {
        if (cached == null) {
            cached = beanFactory.getBean(MysqlMockEngine.class).dataSource();
        }
        return cached;
    }

    @Override
    public Class<?> getObjectType() {
        return DataSource.class;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }
}
