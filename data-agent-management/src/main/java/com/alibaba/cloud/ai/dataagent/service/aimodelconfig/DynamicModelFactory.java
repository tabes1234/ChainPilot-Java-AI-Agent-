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

import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.http.HttpClient.Version;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicModelFactory {

	private final ObservationRegistry observationRegistry;

	/**
	 * 统一使用 OpenAiChatModel，通过 baseUrl 实现多厂商兼容
	 */
	public ChatModel createChatModel(ModelConfigDTO config) {

		log.info("Creating NEW ChatModel instance. Provider: {}, Model: {}", config.getProvider(),
				config.getModelName());
		// 1. 验证参数
		checkBasic(config);

		// 2. 构建 OpenAiApi (核心通讯对象)
		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
			.apiKey(apiKey)
			.baseUrl(config.getBaseUrl())
			.restClientBuilder(getProxiedRestClientBuilder(config))
			.webClientBuilder(getProxiedWebClientBuilder(config));

		if (StringUtils.hasText(config.getCompletionsPath())) {
			apiBuilder.completionsPath(config.getCompletionsPath());
		}
		OpenAiApi openAiApi = apiBuilder.build();

		// 3. 构建运行时选项 (设置默认的模型名称，如 "deepseek-chat" 或 "gpt-4")
		OpenAiChatOptions openAiChatOptions = OpenAiChatOptions.builder()
			.model(config.getModelName())
			.temperature(config.getTemperature())
			.maxTokens(config.getMaxTokens())
			.streamUsage(true)
			.build();
		// 4. 返回统一的 OpenAiChatModel；模型观测与内部工具执行观测共用应用注册表
		DefaultToolCallingManager toolCallingManager = DefaultToolCallingManager.builder()
			.observationRegistry(observationRegistry)
			.build();
		return OpenAiChatModel.builder()
			.openAiApi(openAiApi)
			.defaultOptions(openAiChatOptions)
			.toolCallingManager(toolCallingManager)
			.observationRegistry(observationRegistry)
			.build();
	}

	/**
	 * Embedding 同理
	 */
	public EmbeddingModel createEmbeddingModel(ModelConfigDTO config) {
		log.info("Creating NEW EmbeddingModel instance. Provider: {}, Model: {}", config.getProvider(),
				config.getModelName());
		checkBasic(config);

		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
			.apiKey(apiKey)
			.baseUrl(config.getBaseUrl())
			.restClientBuilder(getProxiedRestClientBuilder(config))
			.webClientBuilder(getProxiedWebClientBuilder(config));

		if (StringUtils.hasText(config.getEmbeddingsPath())) {
			apiBuilder.embeddingsPath(config.getEmbeddingsPath());
		}

		OpenAiApi openAiApi = apiBuilder.build();
		return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED,
				OpenAiEmbeddingOptions.builder().model(config.getModelName()).build(),
				RetryUtils.DEFAULT_RETRY_TEMPLATE, observationRegistry);
	}

	private static void checkBasic(ModelConfigDTO config) {
		Assert.hasText(config.getBaseUrl(), "baseUrl must not be empty");
		if (!"custom".equalsIgnoreCase(config.getProvider())) {
			Assert.hasText(config.getApiKey(), "apiKey must not be empty");
		}
		Assert.hasText(config.getModelName(), "modelName must not be empty");
	}

	private RestClient.Builder getProxiedRestClientBuilder(ModelConfigDTO config) {
		if (config.getProxyEnabled() == null || !config.getProxyEnabled()) {
			java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.version(Version.HTTP_1_1)
				.build();
			JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
			requestFactory.setReadTimeout(Duration.ofMinutes(3));
			return RestClient.builder().requestFactory(requestFactory).observationRegistry(observationRegistry);
		}

		// 打印同步代理日志
		log.info("【Proxy-Init】Model [{}] is using SYNC proxy -> {}:{}", config.getModelName(), config.getProxyHost(),
				config.getProxyPort());

		BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
		if (StringUtils.hasText(config.getProxyUsername())) {
			log.info("【Proxy-Auth】Enabling authenticated SYNC proxy");
			credsProvider.setCredentials(new AuthScope(config.getProxyHost(), config.getProxyPort()),
					new UsernamePasswordCredentials(config.getProxyUsername(),
							config.getProxyPassword().toCharArray()));
		}

		RequestConfig requestConfig = RequestConfig.custom()
			.setConnectionRequestTimeout(Timeout.ofSeconds(10))
			.setResponseTimeout(Timeout.ofMinutes(3))
			.build();
		ConnectionConfig connectionConfig = ConnectionConfig.custom()
			.setConnectTimeout(Timeout.ofSeconds(10))
			.build();
		var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
			.setDefaultConnectionConfig(connectionConfig)
			.build();
		CloseableHttpClient httpClient = HttpClients.custom()
			.setProxy(new HttpHost(config.getProxyHost(), config.getProxyPort()))
			.setDefaultCredentialsProvider(credsProvider)
			.setDefaultRequestConfig(requestConfig)
			.setConnectionManager(connectionManager)
			.build();

		return RestClient.builder()
			.requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
			.observationRegistry(observationRegistry);
	}

	private WebClient.Builder getProxiedWebClientBuilder(ModelConfigDTO config) {
		if (config.getProxyEnabled() == null || !config.getProxyEnabled()) {
			HttpClient nettyClient = HttpClient.create().responseTimeout(Duration.ofMinutes(3));
			return WebClient.builder()
				.clientConnector(new ReactorClientHttpConnector(nettyClient))
				.observationRegistry(observationRegistry);
		}

		log.info("【Proxy-Init】Model [{}] is using ASYNC (Netty) proxy -> {}:{}", config.getModelName(),
				config.getProxyHost(), config.getProxyPort());

		HttpClient nettyClient = HttpClient.create().responseTimeout(Duration.ofMinutes(3)).proxy(p -> {
			ProxyProvider.Builder proxyBuilder = p.type(ProxyProvider.Proxy.HTTP)
				.host(config.getProxyHost())
				.port(config.getProxyPort());

			if (StringUtils.hasText(config.getProxyUsername())) {
				log.info("【Proxy-Auth】Enabling authenticated ASYNC proxy");
				proxyBuilder.username(config.getProxyUsername()).password(s -> config.getProxyPassword());
			}
		});

		return WebClient.builder()
			.clientConnector(new ReactorClientHttpConnector(nettyClient))
			.observationRegistry(observationRegistry);
	}

}
