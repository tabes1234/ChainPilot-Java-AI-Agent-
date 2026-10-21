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
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/api/model-config")
public class ModelConfigController {

	private final ModelConfigDataService modelConfigDataService;

	private final ModelConfigOpsService modelConfigOpsService;

	private final ModelReadinessService modelReadinessService;

	// 1. 获取列表
	@GetMapping("/list")
	public ApiResponse<List<ModelConfigDTO>> list() {
		try {
			return ApiResponse.success("获取模型配置列表成功", modelConfigDataService.listConfigs());
		}
		catch (Exception e) {
			return ApiResponse.error("获取模型配置列表失败: " + e.getMessage());
		}
	}

	// 2. 新增配置
	@PostMapping("/add")
	public ApiResponse<String> add(@Valid @RequestBody ModelConfigDTO config) {
		try {
			modelConfigDataService.addConfig(config);
			return ApiResponse.success("配置已保存");
		}
		catch (Exception e) {
			return ApiResponse.error("保存失败: " + e.getMessage());
		}
	}

	// 3. 修改配置
	@PutMapping("/update")
	public ApiResponse<String> update(@Valid @RequestBody ModelConfigDTO config) {
		try {
			modelConfigOpsService.updateAndRefresh(config);
			return ApiResponse.success("配置已更新");
		}
		catch (Exception e) {
			return ApiResponse.error("更新失败: " + e.getMessage());
		}
	}

	// 4. 删除配置
	@DeleteMapping("/{id}")
	public ApiResponse<String> delete(@PathVariable Integer id) {
		try {
			modelConfigDataService.deleteConfig(id);
			modelReadinessService.invalidate(id);
			return ApiResponse.success("配置已删除");
		}
		catch (Exception e) {
			return ApiResponse.error("删除失败: " + e.getMessage());
		}
	}

	// 5. 启用/切换配置
	@PostMapping("/activate/{id}")
	public ApiResponse<String> activate(@PathVariable Integer id) {
		try {
			modelConfigOpsService.activateConfig(id);
			return ApiResponse.success("模型切换成功！");
		}
		catch (Exception e) {
			return ApiResponse.error("切换失败，请检查配置是否正确: " + e.getMessage());
		}
	}

	/**
	 * 6. 连通性测试 根据配置 ID 从服务端读取完整配置，避免前端回传脱敏凭据
	 */
	@PostMapping("/test/{id}")
	public ApiResponse<String> testConnection(@PathVariable Integer id) {
		try {
			modelConfigOpsService.testConnection(id);
			return ApiResponse.success("连接测试成功！");
		}
		catch (ModelConnectionTestException e) {
			return ApiResponse.errorWithCode(e.code(), e.getMessage());
		}
		catch (Exception e) {
			// Do not expose provider exceptions or request details to the client.
			return ApiResponse.errorWithCode(ModelConnectionErrorClassifier.CONNECTION_TEST_FAILED,
					"连接测试失败，请检查模型配置或稍后重试。");
		}
	}

	/**
	 * 7. 检查模型配置与连接验证状态。此接口只读，不会自动调用模型。
	 */
	@GetMapping("/check-ready")
	public ApiResponse<ModelCheckVo> checkReady() {
		return ApiResponse.success("模型配置检查完成", modelReadinessService.readiness());
	}

}
