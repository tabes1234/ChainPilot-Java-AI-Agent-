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
package com.alibaba.cloud.ai.dataagent.evaluation.exception;

import org.springframework.http.HttpStatus;

/** A structured, client-safe refusal of an AGENT evaluation start. */
public class AgentPrerequisiteException extends RuntimeException {

    public static final String CHAT_NOT_READY = "AGENT_CHAT_NOT_READY";

    public static final String EMBEDDING_NOT_READY = "AGENT_EMBEDDING_NOT_READY";

    private final String code;

    private final HttpStatus status;

    public AgentPrerequisiteException(String code, String message) {
        this(code, message, HttpStatus.PRECONDITION_FAILED);
    }

    public AgentPrerequisiteException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

}
