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
package com.alibaba.cloud.ai.dataagent.service.aimodelconfig;

import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import com.alibaba.cloud.ai.dataagent.converter.ModelConfigConverter;
import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import com.alibaba.cloud.ai.dataagent.entity.ModelConfig;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Slf4j
@Service
@AllArgsConstructor
public class ModelConfigOpsService {

	private final ModelConfigDataService modelConfigDataService;

	private final DynamicModelFactory modelFactory;

	private final AiModelRegistry aiModelRegistry;

	private final EmbeddingModelCompatibilityValidator embeddingModelCompatibilityValidator;

	private final ModelReadinessService modelReadinessService;

	/**
	 * 专门处理：更新配置并热刷新的聚合逻辑
	 */
	@Transactional(rollbackFor = Exception.class)
	public void updateAndRefresh(ModelConfigDTO dto) {
		ModelConfigDTO currentEmbedding = null;
		if (ModelType.EMBEDDING.getCode().equalsIgnoreCase(dto.getModelType())) {
			currentEmbedding = modelConfigDataService.getActiveConfigByType(ModelType.EMBEDDING);
			if (currentEmbedding != null && Objects.equals(currentEmbedding.getId(), dto.getId())) {
				embeddingModelCompatibilityValidator.validateModelChange(currentEmbedding, dto);
			}
		}
		// 1. 更新数据库
		ModelConfig entity = modelConfigDataService.updateConfigInDb(dto);

		// 2. 检查是否是激活状态
		if (Boolean.TRUE.equals(entity.getIsActive())) {
			try {
				// 3. 刷新内存模型
				log.info("Detected update on active config [{}], refreshing memory...", entity.getModelType());
				refreshMemoryModel(entity.getModelType());
			}
			catch (Exception e) {
				// 抛出异常回滚数据库事务
				throw new RuntimeException("配置更新失败: " + e.getMessage(), e);
			}
		}
		// Any edit changes the configuration signature, so a previous test no longer applies.
		modelReadinessService.invalidate(dto.getId());
	}

	/**
	 * 激活指定配置
	 */
	@Transactional(rollbackFor = Exception.class)
	public void activateConfig(Integer id) {
		// 1. 查数据
		ModelConfig entity = modelConfigDataService.findById(id);
		if (entity == null) {
			throw new RuntimeException("配置不存在");
		}
		if (ModelType.EMBEDDING.equals(entity.getModelType())) {
			ModelConfigDTO current = modelConfigDataService.getActiveConfigByType(ModelType.EMBEDDING);
			ModelConfigDTO target = ModelConfigConverter.toDTO(entity);
			embeddingModelCompatibilityValidator.validateModelChange(current, target);
		}

		// 2. 先更新数据库状态，避免缓存清空后并发请求重新加载旧配置
		log.info("Activating config ID={}, Type={}...", id, entity.getModelType());
		modelConfigDataService.switchActiveStatus(id, entity.getModelType());

		// 3. 清空内存模型，后续请求将从已切换的配置重新加载
		refreshMemoryModel(entity.getModelType());

		log.info("Config ID={} activated successfully.", id);
	}

	/**
	 * 私有方法：根据实体创建并替换内存代理
	 */
	private void refreshMemoryModel(ModelType type) {
		if (ModelType.CHAT.equals(type)) {
			aiModelRegistry.refreshChat();
		}
		else if (ModelType.EMBEDDING.equals(type)) {
			aiModelRegistry.refreshEmbedding();
		}
		else {
			throw new RuntimeException("未知的模型类型: " + type);
		}
	}

	/**
	 * 测试连接逻辑 注意：这里创建的模型是“临时”的，用完即丢，不会影响当前系统正在运行的模型
	 */
	public void testConnection(Integer id) {
		ModelConfig entity = modelConfigDataService.findById(id);
		if (entity == null) {
			throw new ModelConnectionTestException(ModelConnectionErrorClassifier.INVALID_MODEL_CONFIGURATION,
					"模型配置不存在。");
		}
		if (entity.getModelType() == null) {
			throw new ModelConnectionTestException(ModelConnectionErrorClassifier.INVALID_MODEL_CONFIGURATION,
					"模型配置无效，请检查模型类型。");
		}
		ModelConfigDTO config = ModelConfigConverter.toDTO(entity);
		try {
			testConnection(config);
			modelReadinessService.recordSuccess(config);
		}
		catch (ModelConnectionTestException exception) {
			modelReadinessService.recordFailure(config, exception.getMessage());
			throw exception;
		}
	}

	private void testConnection(ModelConfigDTO config) {
		String modelType = config.getModelType();

		try {
			if (ModelType.CHAT.getCode().equalsIgnoreCase(modelType)) {
				testChatModel(config);
			}
			else if (ModelType.EMBEDDING.getCode().equalsIgnoreCase(modelType)) {
				testEmbeddingModel(config);
			}
			else {
				throw new IllegalArgumentException("未知的模型类型: " + modelType);
			}
		}
		catch (Exception e) {
			ModelConnectionTestException safe = ModelConnectionErrorClassifier.classify(e);
			log.error("Failed to test model connection. Type: {}, provider: {}, model: {}, code: {}, errorType: {}, diagnostic: {}",
					config.getModelType(), config.getProvider(), config.getModelName(), safe.code(),
					e.getClass().getSimpleName(), ModelConnectionErrorClassifier.diagnostic(e, config));
			throw safe;
		 }
	}

	private void testChatModel(ModelConfigDTO config) {
		log.info("Testing Chat Model connection, provider: {}, modelName: {}", config.getProvider(),
				config.getModelName());

		// 1. 创建临时模型
		ChatModel tempModel = modelFactory.createChatModel(config);

		// 2. 发起最轻量的请求
		String promptText = "Hello";

		// 3. 调用
		String response = tempModel.call(promptText);

		// 4. 校验结果
		if (!StringUtils.hasText(response)) {
			throw new ModelConnectionTestException(ModelConnectionErrorClassifier.UNSUPPORTED_RESPONSE,
					"模型返回内容为空。");
		}
		log.debug("Chat Model test passed. Response: {}", response);
	}

	private void testEmbeddingModel(ModelConfigDTO config) {
		log.info("Testing Embedding Model connection, provider: {} modelName: {}", config.getProvider(),
				config.getModelName());
		// 1. 创建临时模型
		EmbeddingModel tempModel = modelFactory.createEmbeddingModel(config);

		// 2. 发起请求
		float[] embedding = tempModel.embed("Test");

		// 3. 校验结果
		if (embedding == null || embedding.length == 0) {
			throw new ModelConnectionTestException(ModelConnectionErrorClassifier.UNSUPPORTED_RESPONSE,
					"模型生成的向量为空。");
		}
		embeddingModelCompatibilityValidator.validateDimension(embedding.length);
		log.info("Embedding Model test passed. Dimension: {}", embedding.length);
	}

}
