package cn.vv.test.mysqlmock.compat;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T12 JDK 8 门禁（R8/D-01）：全部模块构建产物 class major version = 52；
 * 构建线即 JDK 8（source/target 1.8 + JDK8 javac），API 使用面由编译期天然约束。
 * 运行方式：随 `mvn install` 全仓构建（compat 为最后模块，前序产物已就位）。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
class BytecodeVersionTest {

    private static final String[] MODULES = {
            "mysql-mock-api", "mysql-mock-schema", "mysql-mock-storage", "mysql-mock-calcite",
            "mysql-mock-jdbc", "mysql-mock-mybatis", "mysql-mock-junit5",
    };

    @Test
    void allModuleClassesAreJdk8BytecodeLevel() throws IOException {
        int checked = 0;
        for (String module : MODULES) {
            Path classesDir = Paths.get("..", module, "target", "classes");
            assertTrue(Files.isDirectory(classesDir), "缺少构建产物: " + classesDir
                    + "（请以 mvn install 全仓构建运行本测试）");
            try (Stream<Path> files = Files.walk(classesDir)) {
                List<Path> classFiles = new ArrayList<>();
                files.filter(path -> path.toString().endsWith(".class")).forEach(classFiles::add);
                assertTrue(classFiles.size() > 0, module + " 无 class 产物");
                for (Path classFile : classFiles) {
                    assertEquals(52, majorVersion(classFile),
                            "非 JDK8 字节码: " + classFile);
                    checked++;
                }
            }
        }
        assertTrue(checked > 40, "抽检量异常: " + checked);
    }

    private int majorVersion(Path classFile) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(classFile))) {
            assertEquals(0xCAFEBABE, in.readInt(), "非 class 文件: " + classFile);
            in.readUnsignedShort();
            return in.readUnsignedShort();
        }
    }
}
