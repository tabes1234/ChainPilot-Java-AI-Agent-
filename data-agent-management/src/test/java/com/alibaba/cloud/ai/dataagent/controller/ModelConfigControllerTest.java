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
package com.alibaba.cloud.ai.dataagent.controller;

import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConfigOpsService;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConnectionErrorClassifier;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConnectionTestException;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelReadinessService;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import com.alibaba.cloud.ai.dataagent.vo.ModelCheckVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelConfigControllerTest {

	@Mock
	private ModelConfigDataService modelConfigDataService;

	@Mock
	private ModelConfigOpsService modelConfigOpsService;

	@Mock
	private ModelReadinessService modelReadinessService;

	private ModelConfigController modelConfigController;

	@BeforeEach
	void setUp() {
		modelConfigController = new ModelConfigController(modelConfigDataService, modelConfigOpsService,
				modelReadinessService);
	}

	@Test
	void createModelConfig_validRequest_returnsCreated() {
		ModelConfigDTO config = ModelConfigDTO.builder()
			.provider("openai")
			.baseUrl("https://api.openai.com")
			.modelName("gpt-4")
			.modelType("CHAT")
			.build();
		doNothing().when(modelConfigDataService).addConfig(any(ModelConfigDTO.class));

		ApiResponse<String> result = modelConfigController.add(config);

		assertTrue(result.isSuccess());
		verify(modelConfigDataService).addConfig(any(ModelConfigDTO.class));
	}

	@Test
	void activateModel_validId_activatesModel() {
		doNothing().when(modelConfigOpsService).activateConfig(1);

		ApiResponse<String> result = modelConfigController.activate(1);

		assertTrue(result.isSuccess());
		verify(modelConfigOpsService).activateConfig(1);
	}

	@Test
	void activateModel_failure_returnsError() {
		doThrow(new RuntimeException("config not found")).when(modelConfigOpsService).activateConfig(999);

		ApiResponse<String> result = modelConfigController.activate(999);

		assertFalse(result.isSuccess());
		assertTrue(result.getMessage().contains("config not found"));
	}

	@Test
	void testConnection_validId_returnsSuccess() {
		doNothing().when(modelConfigOpsService).testConnection(1);

		ApiResponse<String> result = modelConfigController.testConnection(1);

		assertTrue(result.isSuccess());
		verify(modelConfigOpsService).testConnection(1);
	}

	@Test
	void testConnection_classifiedFailureReturnsSafeCodeAndMessage() {
		doThrow(new ModelConnectionTestException(ModelConnectionErrorClassifier.AUTHENTICATION_FAILED,
				"鉴权失败，请检查 API Key 或访问权限。")).when(modelConfigOpsService).testConnection(1);

		ApiResponse<String> result = modelConfigController.testConnection(1);

		assertFalse(result.isSuccess());
		assertEquals(ModelConnectionErrorClassifier.AUTHENTICATION_FAILED, result.getCode());
		assertEquals("鉴权失败，请检查 API Key 或访问权限。", result.getMessage());
	}

	@Test
	void testConnection_unexpectedFailureNeverEchoesRawException() {
		doThrow(new RuntimeException("Authorization: Bearer canary-controller-secret"))
			.when(modelConfigOpsService).testConnection(1);

		ApiResponse<String> result = modelConfigController.testConnection(1);

		assertFalse(result.isSuccess());
		assertEquals(ModelConnectionErrorClassifier.CONNECTION_TEST_FAILED, result.getCode());
		assertFalse(result.getMessage().contains("canary-controller-secret"));
		assertFalse(result.getMessage().contains("Authorization"));
	}

	@Test
	void checkReady_delegatesToReadinessServiceAndExposesAgentGate() {
		when(modelReadinessService.readiness()).thenReturn(ModelCheckVo.builder()
			.chatModelReady(true)
			.embeddingModelReady(false)
			.ready(false)
			.chatConnectionVerified(true)
			.chatState(ModelReadinessService.STATE_VERIFIED)
			.embeddingState(ModelReadinessService.STATE_NOT_CONFIGURED)
			.embeddingRequiredForAgentEvaluation(false)
			.agentEvaluationReady(true)
			.verificationScope(ModelReadinessService.VERIFICATION_SCOPE)
			.build());

		ApiResponse<ModelCheckVo> result = modelConfigController.checkReady();

		assertTrue(result.isSuccess());
		assertTrue(result.getData().isChatModelReady());
		assertFalse(result.getData().isEmbeddingModelReady());
		assertTrue(result.getData().isChatConnectionVerified());
		assertTrue(result.getData().isAgentEvaluationReady());
		assertFalse(result.getData().isEmbeddingRequiredForAgentEvaluation());
		verify(modelReadinessService).readiness();
		verifyNoInteractions(modelConfigDataService);
	}

	@Test
	void checkReady_unverifiedChatIsNotAgentReady() {
		when(modelReadinessService.readiness()).thenReturn(ModelCheckVo.builder()
			.chatModelReady(true)
			.chatConnectionVerified(false)
			.chatState(ModelReadinessService.STATE_CONFIGURED_UNVERIFIED)
			.agentEvaluationReady(false)
			.agentEvaluationBlockReason("CHAT 模型尚未完成手动连接测试")
			.build());

		ApiResponse<ModelCheckVo> result = modelConfigController.checkReady();

		assertTrue(result.isSuccess());
		assertFalse(result.getData().isReady());
		assertFalse(result.getData().isAgentEvaluationReady());
		assertTrue(result.getData().getAgentEvaluationBlockReason().contains("手动连接测试"));
	}

}
