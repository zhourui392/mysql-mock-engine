package cn.vv.test.mysqlmock.compat;

import cn.vv.test.mysqlmock.api.MysqlMockEngine;
import cn.vv.test.mysqlmock.junit5.MysqlMockTest;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T13 classpath 零新增（R8/D-12）：junit5 对外工件 shade 后——
 * ①安装 pom 无 compile/runtime 传递依赖（消费方依赖树零新增，Boot 2.1 BOM 形态由 CI 矩阵承接）；
 * ②公共 API（cn.vv.test.mysqlmock.api）签名不泄漏 relocated 包名；
 * ③CRUD 全链经 shaded 工件真执行（无 NoClassDefFoundError）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class ClasspathCoexistenceTest {

    private static final String PUBLISHED_POM_PROPERTY = "mysqlmock.junit5.publishedPom";

    @Test
    void shadedArtifactPomHasNoRuntimeTransitives() throws Exception {
        String publishedPom = System.getProperty(PUBLISHED_POM_PROPERTY);
        assertNotNull(publishedPom, "缺少系统属性 " + PUBLISHED_POM_PROPERTY + "（由本模块 surefire 配置注入）");
        Path pom = Paths.get(publishedPom);
        assertTrue(Files.exists(pom), "缺少 junit5 发布 pom（请在本仓根目录跑 reactor 构建）: " + pom);
        Element dependencies = (Element) DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(pom.toFile())
                .getElementsByTagName("dependencies").item(0);
        if (dependencies == null) {
            return;
        }
        // shade 后仅余 provided（junit-jupiter-api / mybatis），无任何 compile/runtime 第三方依赖
        org.w3c.dom.NodeList depList = dependencies.getElementsByTagName("dependency");
        for (int i = 0; i < depList.getLength(); i++) {
            Element dep = (Element) depList.item(i);
            String scope = textOf(dep, "scope");
            String groupId = textOf(dep, "groupId");
            assertTrue("provided".equals(scope) || "test".equals(scope),
                    "shade 后出现非 provided 依赖: " + groupId + ":" + textOf(dep, "artifactId"));
            assertFalse(groupId.startsWith("org.apache.calcite")
                            || groupId.startsWith("org.apache.shardingsphere")
                            || groupId.startsWith("com.google.guava"),
                    "重依赖未内联: " + groupId);
        }
    }

    @Test
    void publicApiNeverLeaksRelocatedPackageNames() throws Exception {
        Class<?>[] apiClasses = {
                cn.vv.test.mysqlmock.api.MysqlMockEngine.class,
                cn.vv.test.mysqlmock.api.MysqlMockEngineBuilder.class,
                cn.vv.test.mysqlmock.api.QueryResult.class,
                cn.vv.test.mysqlmock.api.UpdateResult.class,
                cn.vv.test.mysqlmock.api.Dataset.class,
                cn.vv.test.mysqlmock.api.MysqlMockException.class,
                cn.vv.test.mysqlmock.junit5.MysqlMockTest.class,
        };
        String relocated = "cn.vv.test.shaded";
        for (Class<?> apiClass : apiClasses) {
            for (Method method : apiClass.getDeclaredMethods()) {
                assertFalse(method.getReturnType().getName().contains(relocated),
                        apiClass.getName() + "#" + method.getName() + " 返回类型泄漏 relocated 包名");
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertFalse(parameterType.getName().contains(relocated),
                            apiClass.getName() + "#" + method.getName() + " 参数类型泄漏 relocated 包名");
                }
            }
        }
    }

    @Test
    void shadedJarContainsRelocatedInternals() throws Exception {
        JarFile jar = jarOf(cn.vv.test.mysqlmock.junit5.MysqlMockTest.class);
        boolean foundRelocated = false;
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith("cn/vv/test/shaded/org/apache/calcite/")) {
                foundRelocated = true;
                break;
            }
        }
        assertTrue(foundRelocated, "shaded jar 应内联 relocated 的 Calcite 内部实现");
    }

    @MysqlMockTest(schema = "classpath:mysql/orders.sql")
    static class ShadedCrudIT {

        @Test
        void fullCrudThroughShadedArtifact(MysqlMockEngine engine) {
            assertEquals(1, engine.execute(
                    "INSERT INTO t_order (customer_id, amount, status) VALUES (1, 5.00, 'PAID')")
                    .affectedRows());
            assertEquals(1L, engine.query(
                    "SELECT COUNT(*) FROM t_order WHERE amount = 5.00").rows().get(0)[0]);
            assertEquals(1, engine.execute(
                    "UPDATE t_order SET status = 'PENDING' WHERE amount = 5.00").affectedRows());
            assertEquals("PENDING", engine.query(
                    "SELECT status FROM t_order WHERE amount = 5.00").rows().get(0)[0]);
            assertEquals(1, engine.execute(
                    "DELETE FROM t_order WHERE amount = 5.00").affectedRows());
            assertEquals(0L, engine.query(
                    "SELECT COUNT(*) FROM t_order WHERE amount = 5.00").rows().get(0)[0]);
        }
    }

    private static String textOf(Element element, String tag) {
        org.w3c.dom.NodeList nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static JarFile jarOf(Class<?> marker) throws Exception {
        URL location = marker.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull(location, "无法定位类来源");
        return new JarFile(Paths.get(location.toURI()).toFile());
    }
}
