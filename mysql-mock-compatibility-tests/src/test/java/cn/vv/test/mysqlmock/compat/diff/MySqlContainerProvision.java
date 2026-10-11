package cn.vv.test.mysqlmock.compat.diff;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

/**
 * 差分基准容器（D-09）：docker 起真实 MySQL（本机镜像；CI 应钉 mysql:8.0），
 * 基准实例参数随语料采集——库级 utf8mb4 / utf8mb4_general_ci（D-16 清单值）、
 * server 默认 sql_mode。就绪探测走 JDBC 重试。
 *
 * @author zhourui(V33215020)
 * @since 2026-10-11
 */
final class MySqlContainerProvision {

    private static final String ROOT_PASSWORD = "mock-diff";
    private static final String DATABASE = "mockdiff";
    private static final String QUALIFIED_DATABASE = "vv_dine";

    private final String containerId;
    private final int port;
    private final String jdbcUrl;
    private volatile boolean stopped;

    private MySqlContainerProvision(String containerId, int port, String jdbcUrl) {
        this.containerId = containerId;
        this.port = port;
        this.jdbcUrl = jdbcUrl;
    }

    static MySqlContainerProvision start(String dockerImage) throws IOException, InterruptedException {
        int port = 18306 + (int) (System.currentTimeMillis() % 1000);
        String containerId = exec("docker", "run", "-d", "--rm",
                "-p", port + ":3306",
                "-e", "MYSQL_ROOT_PASSWORD=" + ROOT_PASSWORD,
                dockerImage).trim();
        String baseParams = "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                + "&connectionTimeZone=Asia/Shanghai";
        // 就绪探测不带库名（目标库尚未创建，带库连接会因 Unknown database 永远失败）
        awaitReady(containerId, "jdbc:mysql://localhost:" + port + "/" + baseParams);
        return new MySqlContainerProvision(containerId, port,
                "jdbc:mysql://localhost:" + port + "/" + DATABASE + baseParams);
    }

    Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "root", ROOT_PASSWORD);
    }

    /** 无库连接（建库用）。 */
    Connection openServer() throws SQLException {
        return DriverManager.getConnection(
                jdbcUrl.substring(0, jdbcUrl.indexOf('/', "jdbc:mysql://".length() + 1) + 1)
                        + jdbcUrl.substring(jdbcUrl.indexOf('?')),
                "root", ROOT_PASSWORD);
    }

    /** 初始化两个库：mockdiff（无限定名表）与 vv_dine（dine 语料的限定库）。 */
    void initializeDatabases() throws SQLException {
        try (Connection connection = openServer(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            statement.execute("CREATE DATABASE IF NOT EXISTS " + QUALIFIED_DATABASE
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        }
    }

    int port() {
        return port;
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            exec("docker", "rm", "-f", containerId);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitReady(String containerId, String url) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        SQLException last = null;
        while (System.nanoTime() < deadline) {
            try (Connection ignored = DriverManager.getConnection(url, "root", ROOT_PASSWORD)) {
                return;
            } catch (SQLException e) {
                last = e;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            System.out.println("[compat] 等待 MySQL 就绪: " + containerId.substring(0, 12));
        }
        throw new IllegalStateException("MySQL 容器 120s 内未就绪", last);
    }

    private static String exec(String... command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = builder.start();
        byte[] output;
        try (java.io.InputStream in = process.getInputStream();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            output = out.toByteArray();
        }
        int code = process.waitFor();
        if (code != 0) {
            throw new IOException("命令失败(" + code + "): " + String.join(" ", command)
                    + " -> " + new String(output, java.nio.charset.StandardCharsets.UTF_8));
        }
        return new String(output, java.nio.charset.StandardCharsets.UTF_8);
    }
}
