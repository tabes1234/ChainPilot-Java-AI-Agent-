/*
 * Copyright 2026 the original author or authors.
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
package com.alibaba.cloud.ai.dataagent.service.aimodelconfig;

/**
 * A classified, user-safe model connection test failure. The message must never contain
 * credentials or raw provider diagnostics.
 */
public class ModelConnectionTestException extends RuntimeException {

    private final String code;

    public ModelConnectionTestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

}
