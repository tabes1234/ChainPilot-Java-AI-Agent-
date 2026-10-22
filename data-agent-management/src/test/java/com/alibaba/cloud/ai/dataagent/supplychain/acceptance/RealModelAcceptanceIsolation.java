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
package com.alibaba.cloud.ai.dataagent.supplychain.acceptance;

import java.util.ArrayList;
import java.util.List;

/**
 * Database isolation for the real-model acceptance run, decided from the JDBC URL of the connection that
 * is actually open rather than from the properties that were supposed to produce it.
 *
 * <p>The distinction matters because the run writes through the production APIs: it inserts a model
 * configuration, activates it (which deactivates every other row of the same model type), then creates
 * tasks, events and evaluation rows. The deployed demo profile keeps exactly those tables in a
 * file-backed H2 database and the default profile points at a shared MySQL schema, so a run bound to
 * either one would change the user's daily environment instead of a throw-away one.
 */
final class RealModelAcceptanceIsolation {

    /** The dedicated database the AGENT provider run must live in; used by both its URL and its guard. */
    static final String AGENT_ACCEPTANCE_DATABASE = "chainpilot_real_model_agent_acceptance";

    /** The dedicated database the loopback rehearsal lives in, so the two can never share rows. */
    static final String HARNESS_DATABASE = "chainpilot_agent_harness";

    private static final String H2_MEMORY_PREFIX = "jdbc:h2:mem:";

    private RealModelAcceptanceIsolation() {
    }

    /**
     * @return a credential-free description of the database kind, never the host, path, user or any URL
     *         parameter, so a failed check can be reported without leaking a connection string.
     */
    static String kind(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return "<no live jdbc url>";
        }
        if (jdbcUrl.startsWith(H2_MEMORY_PREFIX)) {
            return "h2-mem";
        }
        if (jdbcUrl.startsWith("jdbc:h2:file:")) {
            return "h2-file";
        }
        if (jdbcUrl.startsWith("jdbc:h2:")) {
            return "h2-other";
        }
        String rest = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring("jdbc:".length()) : jdbcUrl;
        for (int index = 0; index < rest.length(); index++) {
            char character = rest.charAt(index);
            if (character == ':' || character == '/' || character == ';') {
                return index == 0 ? "<unrecognised jdbc url>" : rest.substring(0, index);
            }
        }
        return "<unrecognised jdbc url>";
    }

    /** @return the reasons this connection may not host a real-model acceptance run, empty when it may. */
    static List<String> problems(String jdbcUrl, String expectedDatabaseName) {
        List<String> problems = new ArrayList<>();
        String kind = kind(jdbcUrl);
        if (!"h2-mem".equals(kind)) {
            problems.add("the live connection is a " + kind + " database; this run writes a model configuration, "
                    + "activates it, and creates tasks, events and evaluation rows, so it must not point at the "
                    + "file-backed demo database or any shared server");
        }
        else if (jdbcUrl == null || !jdbcUrl.startsWith(H2_MEMORY_PREFIX + expectedDatabaseName)) {
            problems.add("the live connection is an in-memory H2 database but not the dedicated acceptance "
                    + "database, so its rows could be shared with another test context");
        }
        return problems;
    }

    /**
     * Called before the harness touches any table. Refusing here is the point: an unisolated run cannot be
     * talked into "safe enough" by a Maven profile name or a file name.
     */
    static void requireIsolated(String jdbcUrl, String expectedDatabaseName) {
        List<String> problems = problems(jdbcUrl, expectedDatabaseName);
        if (problems.isEmpty()) {
            return;
        }
        throw new IllegalStateException("Real-model acceptance database isolation check failed:\n - "
                + String.join("\n - ", problems) + "\nNo external model request was made.");
    }

}
