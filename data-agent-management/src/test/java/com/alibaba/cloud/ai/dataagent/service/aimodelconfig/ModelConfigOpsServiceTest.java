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
import com.alibaba.cloud.ai.dataagent.entity.ModelConfig;
import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelConfigOpsServiceTest {

	private ModelConfigOpsService service;

	@Mock
	private ModelConfigDataService modelConfigDataService;

	@Mock
	private DynamicModelFactory modelFactory;

	@Mock
	private AiModelRegistry aiModelRegistry;

	@Mock
	private ModelReadinessService modelReadinessService;

	@BeforeEach
	void setUp() {
		service = new ModelConfigOpsService(modelConfigDataService, modelFactory, aiModelRegistry,
				new EmbeddingModelCompatibilityValidator(new DataAgentProperties()), modelReadinessService);
	}

	@Test
	void testUpdateAndRefresh_activeChat() {
		ModelConfigDTO dto = new ModelConfigDTO();
		ModelConfig entity = new ModelConfig();
		entity.setIsActive(true);
		entity.setModelType(ModelType.CHAT);
		when(modelConfigDataService.updateConfigInDb(dto)).thenReturn(entity);

		service.updateAndRefresh(dto);

		verify(aiModelRegistry).refreshChat();
	}

	@Test
	void testUpdateAndRefresh_activeEmbedding() {
		ModelConfigDTO dto = new ModelConfigDTO();
		ModelConfig entity = new ModelConfig();
		entity.setIsActive(true);
		entity.setModelType(ModelType.EMBEDDING);
		when(modelConfigDataService.updateConfigInDb(dto)).thenReturn(entity);

		service.updateAndRefresh(dto);

		verify(aiModelRegistry).refreshEmbedding();
	}

	@Test
	void testUpdateAndRefresh_inactive() {
		ModelConfigDTO dto = new ModelConfigDTO();
		dto.setId(8);
		ModelConfig entity = new ModelConfig();
		entity.setIsActive(false);
		when(modelConfigDataService.updateConfigInDb(dto)).thenReturn(entity);

		service.updateAndRefresh(dto);

		verify(aiModelRegistry, never()).refreshChat();
		verify(aiModelRegistry, never()).refreshEmbedding();
		verify(modelReadinessService).invalidate(8);
	}

	@Test
	void testActivateConfig_chat() {
		ModelConfig entity = new ModelConfig();
		entity.setModelType(ModelType.CHAT);
		when(modelConfigDataService.findById(1)).thenReturn(entity);

		service.activateConfig(1);

		InOrder activationOrder = inOrder(modelConfigDataService, aiModelRegistry);
		activationOrder.verify(modelConfigDataService).switchActiveStatus(1, ModelType.CHAT);
		activationOrder.verify(aiModelRegistry).refreshChat();
	}

	@Test
	void testActivateConfig_notFound() {
		when(modelConfigDataService.findById(1)).thenReturn(null);

		assertThrowsExactly(RuntimeException.class, () -> service.activateConfig(1));
	}

	@Test
	void testTestConnection_chat_usesStoredApiKey() {
		ModelConfig entity = new ModelConfig();
		entity.setId(1);
		entity.setModelType(ModelType.CHAT);
		entity.setProvider("openai");
		entity.setModelName("gpt-4");
		entity.setApiKey("stored-api-key");
		when(modelConfigDataService.findById(1)).thenReturn(entity);

		ChatModel chatModel = mock(ChatModel.class);
		when(modelFactory.createChatModel(argThat(config -> "stored-api-key".equals(config.getApiKey()))))
			.thenReturn(chatModel);
		when(chatModel.call("Hello")).thenReturn("Hi there");

		assertDoesNotThrow(() -> service.testConnection(1));
		verify(modelFactory).createChatModel(argThat(config -> "stored-api-key".equals(config.getApiKey())));
		verify(chatModel).call("Hello");
		verify(modelReadinessService).recordSuccess(argThat(config -> "stored-api-key".equals(config.getApiKey())));
	}

	@Test
	void testTestConnection_embedding() {
		ModelConfig entity = new ModelConfig();
		entity.setId(2);
		entity.setModelType(ModelType.EMBEDDING);
		entity.setProvider("openai");
		entity.setModelName("text-embedding");
		when(modelConfigDataService.findById(2)).thenReturn(entity);

		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(modelFactory.createEmbeddingModel(any(ModelConfigDTO.class))).thenReturn(embeddingModel);
		when(embeddingModel.embed("Test")).thenReturn(new float[] { 0.1f, 0.2f });

		assertDoesNotThrow(() -> service.testConnection(2));
		verify(modelFactory).createEmbeddingModel(argThat(config -> "text-embedding".equals(config.getModelName())));
		verify(embeddingModel).embed("Test");
	}

	@Test
	void testTestConnection_unknownType() {
		ModelConfig entity = new ModelConfig();
		entity.setId(3);
		entity.setModelType(null);
		when(modelConfigDataService.findById(3)).thenReturn(entity);

		ModelConnectionTestException exception = assertThrowsExactly(ModelConnectionTestException.class,
				() -> service.testConnection(3));

		assertEquals(ModelConnectionErrorClassifier.INVALID_MODEL_CONFIGURATION, exception.code());
		assertEquals("模型配置无效，请检查模型类型。", exception.getMessage());
		verifyNoInteractions(modelFactory);
	}

	@Test
	void testTestConnection_notFound() {
		when(modelConfigDataService.findById(99)).thenReturn(null);

		ModelConnectionTestException exception = assertThrowsExactly(ModelConnectionTestException.class,
				() -> service.testConnection(99));

		assertEquals(ModelConnectionErrorClassifier.INVALID_MODEL_CONFIGURATION, exception.code());
		assertEquals("模型配置不存在。", exception.getMessage());
		verifyNoInteractions(modelFactory);
	}

	@Test
	void testTestConnection_chatReturnsEmpty() {
		ModelConfig entity = new ModelConfig();
		entity.setId(4);
		entity.setModelType(ModelType.CHAT);
		when(modelConfigDataService.findById(4)).thenReturn(entity);

		ChatModel chatModel = mock(ChatModel.class);
		when(modelFactory.createChatModel(any(ModelConfigDTO.class))).thenReturn(chatModel);
		when(chatModel.call("Hello")).thenReturn("");

		ModelConnectionTestException exception = assertThrowsExactly(ModelConnectionTestException.class,
				() -> service.testConnection(4));

		assertEquals(ModelConnectionErrorClassifier.UNSUPPORTED_RESPONSE, exception.code());
		assertEquals("模型返回内容为空。", exception.getMessage());
		verify(modelReadinessService).recordFailure(any(ModelConfigDTO.class), eq("模型返回内容为空。"));
	}

	@Test
	void testTestConnection_embeddingReturnsEmpty() {
		ModelConfig entity = new ModelConfig();
		entity.setId(5);
		entity.setModelType(ModelType.EMBEDDING);
		when(modelConfigDataService.findById(5)).thenReturn(entity);

		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		when(modelFactory.createEmbeddingModel(any(ModelConfigDTO.class))).thenReturn(embeddingModel);
		when(embeddingModel.embed("Test")).thenReturn(new float[0]);

		ModelConnectionTestException exception = assertThrowsExactly(ModelConnectionTestException.class,
				() -> service.testConnection(5));

		assertEquals(ModelConnectionErrorClassifier.UNSUPPORTED_RESPONSE, exception.code());
		assertEquals("模型生成的向量为空。", exception.getMessage());
	}

	@Test
	void testTestConnection_exceptionWithoutMessage_returnsExceptionType() {
		ModelConfig entity = new ModelConfig();
		entity.setId(6);
		entity.setModelType(ModelType.CHAT);
		when(modelConfigDataService.findById(6)).thenReturn(entity);

		ChatModel chatModel = mock(ChatModel.class);
		when(modelFactory.createChatModel(any(ModelConfigDTO.class))).thenReturn(chatModel);
		when(chatModel.call("Hello")).thenThrow(new RuntimeException());

		ModelConnectionTestException exception = assertThrowsExactly(ModelConnectionTestException.class,
				() -> service.testConnection(6));

		assertEquals(ModelConnectionErrorClassifier.CONNECTION_TEST_FAILED, exception.code());
		assertEquals("连接测试失败，请检查模型配置或稍后重试。", exception.getMessage());
	}

	@Test
	void testTestConnection_failureMessageReadinessAndLogsNeverExposeCanaries() {
		ModelConfig entity = new ModelConfig();
		entity.setId(7);
		entity.setModelType(ModelType.CHAT);
		entity.setApiKey("sk-secret-value");
		entity.setProxyPassword("canary-proxy-pass");
		when(modelConfigDataService.findById(7)).thenReturn(entity);

		String canary = "Authorization: Bearer canary-auth-123; "
				+ "url=https://canary-user:canary-pass@example.invalid/v1?api_key=canary-key&token=canary-token; "
				+ "key=sk-secret-value";
		HttpClientErrorException providerFailure = HttpClientErrorException.create(HttpStatus.UNAUTHORIZED,
				"Unauthorized", new HttpHeaders(), canary.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
		ChatModel chatModel = mock(ChatModel.class);
		when(modelFactory.createChatModel(any(ModelConfigDTO.class))).thenReturn(chatModel);
		when(chatModel.call("Hello")).thenThrow(providerFailure);

		Logger logger = (Logger) LoggerFactory.getLogger(ModelConfigOpsService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			ModelConnectionTestException exception = assertThrowsExactly(ModelConnectionTestException.class,
					() -> service.testConnection(7));

			assertEquals(ModelConnectionErrorClassifier.AUTHENTICATION_FAILED, exception.code());
			assertNoCanary(exception.getMessage());
			verify(modelReadinessService).recordFailure(any(ModelConfigDTO.class), argThat(message -> {
				assertNoCanary(message);
				return message.contains("鉴权失败");
			}));
			String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
				.collect(Collectors.joining("\n"));
			assertNoCanary(logs);
			assertTrue(logs.contains(ModelConnectionErrorClassifier.AUTHENTICATION_FAILED));
		}
		finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	private static void assertNoCanary(String value) {
		for (String canary : new String[] { "canary-auth-123", "canary-user", "canary-pass", "canary-key",
				"canary-token", "canary-proxy-pass", "sk-secret-value" }) {
			assertFalse(value.contains(canary), "sensitive canary leaked: " + canary);
		}
	}

}
