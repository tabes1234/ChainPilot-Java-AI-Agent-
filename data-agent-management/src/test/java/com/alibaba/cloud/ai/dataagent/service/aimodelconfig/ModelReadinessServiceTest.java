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

import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import com.alibaba.cloud.ai.dataagent.vo.ModelCheckVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelReadinessServiceTest {

    @Mock
    private ModelConfigDataService modelConfigDataService;

    private ModelReadinessService service;

    @BeforeEach
    void setUp() {
        service = new ModelReadinessService(modelConfigDataService);
    }

    @Test
    void missingChatConfigurationBlocksAgentEvaluation() {
        ModelCheckVo readiness = service.readiness();

        assertEquals(ModelReadinessService.STATE_NOT_CONFIGURED, readiness.getChatState());
        assertEquals(ModelReadinessService.STATE_NOT_CONFIGURED, readiness.getEmbeddingState());
        assertFalse(readiness.isChatConnectionVerified());
        assertFalse(readiness.isAgentEvaluationReady());
        assertTrue(readiness.getAgentEvaluationBlockReason().contains("未配置激活的 CHAT 模型"));
        assertEquals(ModelReadinessService.VERIFICATION_SCOPE, readiness.getVerificationScope());
    }

    @Test
    void configuredChatIsNotVerifiedUntilTheUserTestsIt() {
        ModelConfigDTO chat = chatConfig();
        when(modelConfigDataService.getActiveConfigByType(ModelType.CHAT)).thenReturn(chat);

        ModelCheckVo readiness = service.readiness();

        assertTrue(readiness.isChatModelReady());
        assertEquals(ModelReadinessService.STATE_CONFIGURED_UNVERIFIED, readiness.getChatState());
        assertFalse(readiness.isAgentEvaluationReady());
        assertTrue(readiness.getAgentEvaluationBlockReason().contains("手动连接测试"));
    }

    @Test
    void failedConnectionTestIsReportedWithoutLeakingTheApiKey() {
        ModelConfigDTO chat = chatConfig();
        when(modelConfigDataService.getActiveConfigByType(ModelType.CHAT)).thenReturn(chat);
        service.recordFailure(chat, "401 invalid key=sk-secret-value for provider");

        ModelCheckVo readiness = service.readiness();

        assertEquals(ModelReadinessService.STATE_TEST_FAILED, readiness.getChatState());
        assertFalse(readiness.isAgentEvaluationReady());
        assertNotNull(readiness.getChatTestedAt());
        assertTrue(readiness.getChatTestError().contains("401"));
        assertFalse(readiness.getChatTestError().contains("sk-secret-value"));
        assertTrue(readiness.getAgentEvaluationBlockReason().contains("连接测试失败"));
    }

    @Test
    void verifiedChatAllowsAgentEvaluationWithoutEmbedding() {
        ModelConfigDTO chat = chatConfig();
        when(modelConfigDataService.getActiveConfigByType(ModelType.CHAT)).thenReturn(chat);
        service.recordSuccess(chat);

        ModelCheckVo readiness = service.readiness();

        assertEquals(ModelReadinessService.STATE_VERIFIED, readiness.getChatState());
        assertTrue(readiness.isChatConnectionVerified());
        assertEquals(ModelReadinessService.STATE_NOT_CONFIGURED, readiness.getEmbeddingState());
        assertFalse(readiness.isEmbeddingRequiredForAgentEvaluation());
        assertTrue(readiness.isAgentEvaluationReady());
        assertNull(readiness.getAgentEvaluationBlockReason());
        assertNotNull(readiness.getChatTestedAt());
    }

    @Test
    void changingTheActiveConfigurationMakesThePreviousTestStale() {
        ModelConfigDTO chat = chatConfig();
        when(modelConfigDataService.getActiveConfigByType(ModelType.CHAT)).thenReturn(chat);
        service.recordSuccess(chat);
        assertTrue(service.readiness().isAgentEvaluationReady());

        chat.setModelName("changed-model");

        assertFalse(service.readiness().isAgentEvaluationReady());
        assertEquals(ModelReadinessService.STATE_CONFIGURED_UNVERIFIED, service.readiness().getChatState());
    }

    @Test
    void changingAuthenticationOrProxySettingsInvalidatesTheOldVerification() {
        ModelConfigDTO chat = chatConfig();
        when(modelConfigDataService.getActiveConfigByType(ModelType.CHAT)).thenReturn(chat);
        service.recordSuccess(chat);
        assertTrue(service.readiness().isAgentEvaluationReady());

        chat.setApiKey("sk-rotated-value");

        assertFalse(service.readiness().isAgentEvaluationReady());
        assertEquals(ModelReadinessService.STATE_CONFIGURED_UNVERIFIED, service.readiness().getChatState());
    }

    @Test
    void explicitInvalidationRequiresANewTest() {
        ModelConfigDTO chat = chatConfig();
        when(modelConfigDataService.getActiveConfigByType(ModelType.CHAT)).thenReturn(chat);
        service.recordSuccess(chat);

        service.invalidate(chat.getId());

        assertEquals(ModelReadinessService.STATE_CONFIGURED_UNVERIFIED, service.readiness().getChatState());
    }

    private static ModelConfigDTO chatConfig() {
        ModelConfigDTO chat = new ModelConfigDTO();
        chat.setId(11);
        chat.setProvider("deepseek");
        chat.setBaseUrl("https://api.deepseek.com");
        chat.setModelName("deepseek-chat");
        chat.setModelType(ModelType.CHAT.getCode());
        chat.setApiKey("sk-secret-value");
        return chat;
    }

}
