package cn.vv.test.mysqlmock.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** classpath: 前缀与文件路径的资源装载。 */
public final class ResourceLoader {

    private ResourceLoader() {
    }

    public static InputStream open(String location) {
        if (location.startsWith("classpath:")) {
            String path = location.substring("classpath:".length());
            InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
            if (in == null) {
                in = ResourceLoader.class.getClassLoader().getResourceAsStream(path);
            }
            if (in == null) {
                throw new IllegalArgumentException("classpath 资源不存在: " + location);
            }
            return in;
        }
        try {
            return new java.io.FileInputStream(location);
        } catch (IOException e) {
            throw new IllegalArgumentException("资源文件不存在: " + location, e);
        }
    }

    public static String readAsString(String location) {
        try (InputStream in = open(location)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取资源失败: " + location, e);
        }
    }
}
