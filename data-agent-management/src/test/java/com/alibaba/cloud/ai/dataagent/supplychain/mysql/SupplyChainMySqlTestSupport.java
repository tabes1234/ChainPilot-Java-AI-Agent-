/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.supplychain.mysql;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared opt-in MySQL setup and safety checks for ChainPilot integration tests. Tests either get a
 * temporary mysql:8 container or an explicitly supplied loopback MySQL whose database is exactly
 * {@link #DEDICATED_DATABASE}; there is intentionally no H2 fallback.
 */
public final class SupplyChainMySqlTestSupport {

    public static final String ENABLE_PROPERTY = "chainpilot.mysql.integration";

    public static final String EXTERNAL_URL_PROPERTY = "chainpilot.mysql.url";

    public static final String EXTERNAL_USERNAME_PROPERTY = "chainpilot.mysql.username";

    public static final String EXTERNAL_PASSWORD_PROPERTY = "chainpilot.mysql.password";

    public static final String DEDICATED_DATABASE = "chainpilot_mysql_it";

    private SupplyChainMySqlTestSupport() {
    }

    public static MySqlTarget start() {
        String externalUrl = System.getProperty(EXTERNAL_URL_PROPERTY, "").trim();
        if (!externalUrl.isEmpty()) {
            assertDedicatedLocalDatabase(externalUrl);
            return new MySqlTarget(externalUrl, System.getProperty(EXTERNAL_USERNAME_PROPERTY, "root"),
                    System.getProperty(EXTERNAL_PASSWORD_PROPERTY, ""), null);
        }
        boolean dockerAvailable = false;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        }
        catch (RuntimeException ignored) {
            // Testcontainers reports host-specific detection errors; use the actionable message below.
        }
        if (!dockerAvailable) {
            fail("MySQL integration test was enabled with -D" + ENABLE_PROPERTY + "=true, but no Docker daemon is "
                    + "available. Start Docker Desktop or another Docker engine, or point -D" + EXTERNAL_URL_PROPERTY
                    + " at a local dedicated MySQL 8 database named " + DEDICATED_DATABASE + ". The test never "
                    + "falls back to H2.");
        }
        MySQLContainer<?> container = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName(DEDICATED_DATABASE)
                .withUsername("chainpilot")
                .withPassword(UUID.randomUUID().toString());
        container.start();
        return new MySqlTarget(container.getJdbcUrl(), container.getUsername(), container.getPassword(), container);
    }

    /** Allows only a loopback MySQL URL whose database is the dedicated throwaway test schema. */
    public static void assertDedicatedLocalDatabase(String url) {
        String prefix = "jdbc:mysql://";
        if (!url.startsWith(prefix)) {
            fail("-D" + EXTERNAL_URL_PROPERTY + " must start with " + prefix);
        }
        String remainder = url.substring(prefix.length());
        int slash = remainder.indexOf('/');
        if (slash < 0) {
            fail("-D" + EXTERNAL_URL_PROPERTY + " must include the " + DEDICATED_DATABASE + " database name");
        }
        String hostAndPort = remainder.substring(0, slash);
        if (!hostAndPort.matches("(?i)(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?")) {
            fail("-D" + EXTERNAL_URL_PROPERTY + " must point at loopback (localhost, 127.0.0.1 or [::1]); "
                    + "public or remote databases are not allowed");
        }
        String database = remainder.substring(slash + 1);
        int query = database.indexOf('?');
        if (query >= 0) {
            database = database.substring(0, query);
        }
        if (!DEDICATED_DATABASE.equals(database)) {
            fail("-D" + EXTERNAL_URL_PROPERTY + " must use the dedicated database " + DEDICATED_DATABASE
                    + " so existing data is never touched");
        }
    }

    public record MySqlTarget(String jdbcUrl, String username, String password, MySQLContainer<?> container) {

        public void close() {
            if (container != null) {
                try {
                    container.stop();
                }
                catch (RuntimeException ignored) {
                    // Cleanup must not hide the primary test result, including a failed container start.
                }
            }
        }
    }
}
