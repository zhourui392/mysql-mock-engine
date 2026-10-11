package cn.vv.test.mysqlmock.boot;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * 模式 A 接管（方案 §3.8）：`mysql-mock.replace-datasource-bean=<beanName>` 显式指定时，
 * 经 BDRPP 替换该 Bean 定义；两种模式都要求 `mysql-mock.enabled=true` 显式开启。
 * <p>替换保留原定义的 primary / lazy 标志；其余属性（scope、description 等）不迁移
 * （目标 Bean 即 DataSource，接管后语义完全替换）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
public class MysqlMockDataSourceReplacer
        implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, Ordered {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (!"true".equalsIgnoreCase(environment.getProperty("mysql-mock.enabled"))) {
            return;
        }
        String target = environment.getProperty("mysql-mock.replace-datasource-bean");
        if (target == null || target.trim().isEmpty() || !registry.containsBeanDefinition(target)) {
            return;
        }
        org.springframework.beans.factory.config.BeanDefinition old = registry.getBeanDefinition(target);
        GenericBeanDefinition replacement = new GenericBeanDefinition();
        replacement.setBeanClass(MysqlMockReplacedDataSourceFactoryBean.class);
        replacement.setPrimary(old.isPrimary());
        replacement.setLazyInit(old.isLazyInit());
        replacement.setSynthetic(true);
        registry.removeBeanDefinition(target);
        registry.registerBeanDefinition(target, replacement);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // 仅做 Bean 定义层替换
    }

    @Override
    public int getOrder() {
        // 早于常规 BDRPP（如 MybatisMapperScanner）完成接管
        return HIGHEST_PRECEDENCE + 10;
    }
}
