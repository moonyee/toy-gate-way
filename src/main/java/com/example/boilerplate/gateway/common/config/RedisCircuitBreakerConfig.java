package com.example.boilerplate.gateway.common.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RedisCircuitBreakerConfig {

	@Bean
	public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCustomizer() {
		return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
			.circuitBreakerConfig(CircuitBreakerConfig.custom()
				.failureRateThreshold(50) // 실패율 50% 이상 시 차단
				.waitDurationInOpenState(Duration.ofSeconds(60)) // 차단 후 60초 대기
				.slidingWindowSize(10) // 최근 10번의 호출을 기준으로 판단
				.build())
			.timeLimiterConfig(TimeLimiterConfig.custom()
				.timeoutDuration(Duration.ofSeconds(3)) // 3초 초과 시 타임아웃
				.build())
			.build());
	}
}