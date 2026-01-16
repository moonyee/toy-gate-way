package com.example.boilerplate.gateway.common.config;

import java.time.Duration;

import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;

import io.github.resilience4j.timelimiter.TimeLimiterConfig;

public class CircuitBreakerConfig {

	@Bean
	public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCustomizer() {
		return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
			.circuitBreakerConfig(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
				.failureRateThreshold(50) // 실패율 50% 이상 시 차단
				.waitDurationInOpenState(Duration.ofSeconds(120)) // 차단 후 60초 대기
				.slidingWindowSize(100) // 최근 100번의 호출을 기준으로 판단
				.build())
			.timeLimiterConfig(TimeLimiterConfig.custom()
				.timeoutDuration(Duration.ofSeconds(30)) // 30초 초과 시 타임아웃
				.build())
			.build());
	}
}
