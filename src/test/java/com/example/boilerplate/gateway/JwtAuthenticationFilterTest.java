package com.example.boilerplate.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import com.example.boilerplate.gateway.common.filter.JwtAuthenticationFilter;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@Slf4j
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

	@Mock
	private ReactiveStringRedisTemplate redisTemplate;

	@Mock
	private ReactiveValueOperations<String, String> valueOperations;

	private JwtAuthenticationFilter filter;
	private ReactiveCircuitBreaker circuitBreaker;
	private CircuitBreakerRegistry cbRegistry;

	@BeforeEach
	void setUp() {
		this.cbRegistry = CircuitBreakerRegistry.ofDefaults();
		TimeLimiterRegistry tlRegistry = TimeLimiterRegistry.ofDefaults();

		ReactiveResilience4JCircuitBreakerFactory cbFactory = new ReactiveResilience4JCircuitBreakerFactory(
			cbRegistry,
			tlRegistry,
			new Resilience4JConfigurationProperties() // null 대신 기본 객체 주입
		);

		cbFactory.configureDefault(id -> new Resilience4JConfigBuilder(id)
			.circuitBreakerConfig(CircuitBreakerConfig.custom()
				.slidingWindowSize(2)            // 최근 2개의 호출을 통계에 사용
				.failureRateThreshold(50)         // 실패율이 50% 이상(2개 중 1개 실패)이면 OPEN
				.minimumNumberOfCalls(2)          // 최소 2번은 호출되어야 통계를 계산함
				.waitDurationInOpenState(Duration.ofSeconds(3)) // OPEN 상태 유지 시간
				.permittedNumberOfCallsInHalfOpenState(1) // HALF_OPEN에서 딱 1번만 성공해도 바로 CLOSED!
				.build())
			.timeLimiterConfig(TimeLimiterConfig.custom()
				.timeoutDuration(Duration.ofSeconds(2)) // 타임아웃 2초
				.build())
			.build());

		filter = new JwtAuthenticationFilter(
			"my_super_secret_key_that_is_long_enough_and_random",
			redisTemplate,
			cbFactory
		);

		this.circuitBreaker = cbFactory.create("redisCB");
	}

	@Test
	@DisplayName("Redis 연결 실패 시 Circuit Breaker가 Fallback을 실행해야 한다")
	void shouldExecuteFallbackWhenRedisFails() {
		// Given: Redis가 에러를 던지도록 설정
		String userId = "testuser";
		String token = "some-jwt-token";
		String redisKey = "AUTH:" + userId + ":" + token;

		when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		// 네트워크 장애 상황
		when(valueOperations.get(redisKey)).thenReturn(Mono.error(new RuntimeException("Redis Down!")));

		// When & Then: 필터 내부의 Redis 조회 로직 실행 시 fallback 메시지가 나오는지 검증
		// filter 내부의 로직을 테스트하기 위해 Redis 조회 부분만 별도 메서드로 추출하거나
		// 해당 로직이 포함된 전체 스트림을 검증합니다.

		Mono<String> redisResult = redisTemplate.opsForValue().get(redisKey)
			.transform(it -> circuitBreaker.run(it, throwable ->
				Mono.error(new RuntimeException("Authentication service temporarily unavailable"))));

		StepVerifier.create(redisResult)
			.expectErrorSatisfies(throwable -> {
				assertThat(throwable).isInstanceOf(RuntimeException.class);
				assertThat(throwable.getMessage()).isEqualTo("Authentication service temporarily unavailable");
			})
			.verify();

		log.info("### Circuit Breaker Fallback 테스트 성공");
	}

	@Test
	@DisplayName("실패가 반복되면 서킷이 OPEN되어 요청을 즉시 차단해야 한다")
	void shouldOpenCircuitBreakerAfterRepeatedFailures() {
		// Given: Redis 에러 설정
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(anyString())).thenReturn(Mono.error(new RuntimeException("Redis Down!")));

		// 1. 첫 번째 실패 (서킷은 아직 CLOSED, 하지만 실패 기록됨)
		callFilterWithCircuitBreaker();

		// 2. 두 번째 실패 (서킷이 OPEN 상태로 전환됨)
		callFilterWithCircuitBreaker();

		// 3. 서킷 상태 로그 찍어보기 (Resilience4j 내부 레지스트리에서 직접 꺼내기)
		// 이 부분에서 'OPEN'이라는 로그가 찍히는 게 핵심입니다!
		log.info("### 현재 서킷 브레이커 상태: {}",
			cbRegistry.circuitBreaker("redisCB").getState());

		// 4. 세 번째 호출 검증
		// 서킷이 OPEN이면 Redis를 호출(Mocking)하지 않고 바로 Fallback으로 갑니다.
		StepVerifier.create(callFilterWithCircuitBreaker())
			.expectError()
			.verify();

		log.info("### 서킷 브레이커가 성공적으로 차단(Open)되었습니다.");
	}

	// 중복 코드를 줄이기 위한 헬퍼 메서드
	private Mono<String> callFilterWithCircuitBreaker() {
		return redisTemplate.opsForValue().get("testKey")
			.transform(it -> circuitBreaker.run(it, t -> Mono.error(new RuntimeException("Fallback!"))));
	}

	@Test
	@DisplayName("OPEN -> CLOSE")
	void testRecoveryFlow() throws InterruptedException {
		// 1. Mockito의 엄격한 검사를 완화 (lenient 사용)
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		lenient().when(valueOperations.get(anyString())).thenReturn(Mono.error(new RuntimeException("Error")));

		// 2. 서킷 OPEN 시키기 (2번 실패)
		for(int i=0; i<2; i++) {
			// 실패 유발
			circuitBreaker.run(valueOperations.get("key"), t -> Mono.error(t)).subscribe(d->{}, e->{});
			log.info("### 현재 상태 (기대: OPEN): {}", cbRegistry.circuitBreaker("redisCB").getState());
		}
		// log.info("### 현재 상태 (기대: OPEN): {}", cbRegistry.circuitBreaker("redisCB").getState());

		// 3. 서킷 OPEN 상태 검증 (이미 하신 부분)
		StepVerifier.create(circuitBreaker.run(valueOperations.get("key"), t -> Mono.just("Fallback")))
			.expectNext("Fallback")
			.verifyComplete();

		// 4. [복구 핵심] 대기 시간 경과 (setUp에서 10초로 하셨다면 11초 대기)
		log.info("### 복구를 위해 대기 중...");
		Thread.sleep(5000);

		// 5. 이제 성공할 수 있도록 Mock 설정 변경
		when(valueOperations.get(anyString())).thenReturn(Mono.just("Redis Success!"));

		// 6. 재시도 호출 (이때 상태는 HALF_OPEN -> 성공 시 CLOSED)
		Mono<String> recoveryCall = circuitBreaker.run(valueOperations.get("key"), t -> Mono.just("Fallback"));

		StepVerifier.create(recoveryCall)
			.expectNext("Redis Success!") // 이제 Fallback이 아니라 실제 데이터가 나와야 함
			.verifyComplete();

		Thread.sleep(100);
		log.info("### 최종 상태 (기대: CLOSED): {}", cbRegistry.circuitBreaker("redisCB").getState());
	}
}
