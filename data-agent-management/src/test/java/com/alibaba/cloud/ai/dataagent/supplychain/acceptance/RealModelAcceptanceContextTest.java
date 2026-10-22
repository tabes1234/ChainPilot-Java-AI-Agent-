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

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConfigOpsService;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelReadinessService;
import com.alibaba.cloud.ai.dataagent.supplychain.ChainPilotTestApplication;
import com.alibaba.cloud.ai.dataagent.vo.ModelCheckVo;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zero-cost pre-flight for the opt-in real-model acceptance harness. Boots exactly the bean graph the
 * acceptance IT declares - without enabling it - and proves the wiring is complete and that starting
 * the harness and reading model readiness issues no outbound request.
 *
 * <p>This exists because the acceptance IT imported {@code ModelConfigOpsService} and
 * {@code ModelConfigController} while their {@code ModelReadinessService} dependency sat outside the
 * test component scan: the class compiled and only an actual run revealed that the context could not
 * start. The drift test below keeps the two definitions from separating again.
 */
@SpringBootTest(classes = { ChainPilotTestApplication.class,
        SupplyChainRealModelAcceptanceIT.RealModelAcceptanceConfiguration.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:chainpilot_acceptance_context;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=MySQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:sql/h2/schema-h2.sql",
                "spring.sql.init.continue-on-error=true",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration",
                "logging.level.root=warn",
                "logging.level.com.alibaba.cloud.ai.dataagent.supplychain=info" })
class RealModelAcceptanceContextTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ModelReadinessService modelReadinessService;

    @Autowired
    private ModelConfigOpsService modelConfigOpsService;

    @Autowired
    private ModelConfigDataService modelConfigDataService;

    @Autowired
    private AiModelRegistry aiModelRegistry;

    @Autowired
    private RecordingObservationHandler observations;

    @Test
    void harnessContextStartsWithTheProductionModelConfigurationBeans() {
        assertNotNull(modelConfigOpsService, "the harness must own the ops service the controller uses");
        assertNotNull(modelConfigDataService);
        assertNotNull(aiModelRegistry);
        assertNotNull(observations);
    }

    @Test
    void readinessPreflightNeverTouchesTheProvider() {
        ModelCheckVo readiness = modelReadinessService.readiness();
        assertEquals(ModelReadinessService.STATE_NOT_CONFIGURED, readiness.getChatState(),
                "an empty model_config table must report NOT_CONFIGURED, never a claimed connection");
        assertEquals(ModelReadinessService.VERIFICATION_SCOPE, readiness.getVerificationScope());
        assertFalse(readiness.isChatConnectionVerified(),
                "readiness may not report a verified connection that this process never tested");

        JsonNode response = webTestClient.get()
            .uri("/api/model-config/check-ready")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(JsonNode.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(response);
        assertEquals(Boolean.FALSE, response.path("data").path("chatModelReady").asBoolean());

        webTestClient.get()
            .uri("/api/model-config/list")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(JsonNode.class);

        assertEquals(List.of(), modelConfigDataService.listConfigs());
        assertEquals(0, observations.modelOperations(), "starting the harness must not call a model");
        assertEquals(0, observations.httpAttempts(), "starting the harness must not send a request");
        assertEquals(0, observations.runHttpAttempts(), "the run-wide budget must still be untouched");
    }

    @Test
    void acceptanceRunStaysDisabledUnlessTheAuthorizationPropertyIsPassed() {
        EnabledIfSystemProperty gate = SupplyChainRealModelAcceptanceIT.class
            .getAnnotation(EnabledIfSystemProperty.class);
        assertNotNull(gate, "the acceptance IT must keep its explicit enable gate");
        assertEquals(RealModelAcceptanceSettings.ENABLE_PROPERTY, gate.named());
        assertEquals("true", gate.matches());
        assertTrue(System.getProperty(RealModelAcceptanceSettings.ENABLE_PROPERTY) == null,
                "this pre-flight must run with the real-model acceptance switch absent");
    }

    @Test
    void harnessBeanAndContextConfigurationCannotDriftApart() {
        SpringBootTest enabled = SupplyChainRealModelAcceptanceIT.class.getAnnotation(SpringBootTest.class);
        SpringBootTest preflight = RealModelAcceptanceContextTest.class.getAnnotation(SpringBootTest.class);
        assertNotNull(enabled);
        assertNotNull(preflight);
        assertEquals(List.of(enabled.classes()), List.of(preflight.classes()),
                "the pre-flight must boot the same bean graph the real-model acceptance run uses");
        assertEquals(contextProperties(enabled.properties()), contextProperties(preflight.properties()),
                "the pre-flight must use the same context settings as the real-model acceptance run; only the"
                        + " isolated in-memory database name may differ");
    }

    /** Everything except the throwaway database URL, which is deliberately a different schema. */
    private static List<String> contextProperties(String[] properties) {
        return Arrays.stream(properties).filter(property -> !property.startsWith("spring.datasource.url")).toList();
    }

}
