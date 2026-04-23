package com.example.boilerplate.gateway.common.filter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class JwtAuthenticationFilter implements WebFilter {

	private static final String BEARER_PREFIX = "Bearer ";
	/**
	 * 인증 없이 통과시키는 경로(Ant 패턴).
	 * AntPathMatcher로 비교하므로 와일드카드가 정상 동작한다.
	 * 기존 코드는 path.equals()로 비교해 와일드카드가 동작하지 않는 버그가 있었다.
	 */
	private static final List<String> PERMIT_PATHS = List.of(
		"/api/auth/login",
		"/api/auth/join",
		"/api/auth/check-id",
		"/api/auth/check-email",
		"/api/auth/verify",
		"/api/public/**",
		"/fallback/**"
	);

	private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final SecretKey signingKey;
	private final ReactiveStringRedisTemplate redisTemplate;
	private final ReactiveCircuitBreaker redisCircuitBreaker;

	public JwtAuthenticationFilter(
		@Value("${jwt.secret}") String secretKey,
		ReactiveStringRedisTemplate redisTemplate,
		ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory
	) {
		this.signingKey = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
		this.redisTemplate = redisTemplate;
		// Redis 호출을 감싸 장애 시 즉시 401로 떨어뜨려 Gateway 전체 정체를 막는다.
		this.redisCircuitBreaker = circuitBreakerFactory.create("redisAuthCB");
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		ServerHttpRequest request = exchange.getRequest();
		String path = request.getPath().value();

		// 인증 헤더 전체를 로그에 남기지 않는다. Bearer 여부와 prefix 일부만 노출.
		if (log.isDebugEnabled()) {
			log.debug("### Request path: {}, hasAuth: {}", path, hasAuthHeader(request));
		}

		for (String permit : PERMIT_PATHS) {
			if (PATH_MATCHER.match(permit, path)) {
				return chain.filter(exchange);
			}
		}

		return extractBearer(request)
			.flatMap(this::parseJwt)
			.flatMap(this::verifyRedisSession)
			.flatMap(claims -> processRequest(exchange, chain, claims))
			.onErrorResume(ExpiredJwtException.class, e -> {
				log.warn("### JWT token is expired");
				return onError(exchange, "JWT token is expired", HttpStatus.UNAUTHORIZED);
			})
			.onErrorResume(Exception.class, e -> {
				log.warn("### Authentication failed: {}", e.getMessage());
				return onError(exchange, "Invalid JWT token", HttpStatus.UNAUTHORIZED);
			});
	}

	private boolean hasAuthHeader(ServerHttpRequest request) {
		String h = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		return h != null && h.startsWith(BEARER_PREFIX);
	}

	private Mono<String> extractBearer(ServerHttpRequest request) {
		return Mono.justOrEmpty(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
			.filter(header -> header.startsWith(BEARER_PREFIX))
			.map(header -> header.substring(BEARER_PREFIX.length()))
			.switchIfEmpty(Mono.error(new IllegalArgumentException("Missing or invalid Authorization header")));
	}

	private Mono<Claims> parseJwt(String jwt) {
		// JJWT 파싱은 짧은 CPU 작업이므로 별도 스케줄러 없이 즉시 실행.
		return Mono.fromCallable(() -> Jwts.parserBuilder()
			.setSigningKey(signingKey)
			.build()
			.parseClaimsJws(jwt)
			.getBody());
	}

	/**
	 * Redis 세션 키 존재 여부 검증.
	 * Circuit Breaker로 감싸 Redis 장애 시 즉시 fallback(예외)으로 떨어진다.
	 * Identity 서비스가 jti(JWT ID)를 사용하므로 키 형식은 `AUTH:{userId}:{jti}`.
	 */
	private Mono<Claims> verifyRedisSession(Claims claims) {
		String userId = claims.getSubject();
		String jti = claims.getId();
		if (jti == null || jti.isBlank()) {
			return Mono.error(new IllegalStateException("JWT missing jti claim"));
		}
		String redisKey = "AUTH:" + userId + ":" + jti;

		Mono<String> lookup = redisTemplate.opsForValue().get(redisKey);

		return redisCircuitBreaker.run(
			lookup,
			throwable -> {
				log.warn("### Redis circuit open or failure: {}", throwable.toString());
				return Mono.error(new IllegalStateException("auth cache unavailable"));
			}
		)
		.switchIfEmpty(Mono.error(new IllegalStateException("No active session")))
		.thenReturn(claims);
	}

	private Mono<Void> processRequest(ServerWebExchange exchange, WebFilterChain chain, Claims claims) {
		String userId = claims.getSubject();
		String role = claims.get("role", String.class);
		if (role == null || role.isBlank()) {
			role = "ROLE_USER";
		}

		Authentication authentication = new UsernamePasswordAuthenticationToken(
			userId, null, Collections.singletonList(new SimpleGrantedAuthority(role))
		);

		ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
			.header("x-auth-user-id", userId)
			.header("x-auth-user-role", role)
			.build();

		ServerWebExchange modifiedExchange = exchange.mutate()
			.request(modifiedRequest)
			.build();

		return chain.filter(modifiedExchange)
			.contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
				Mono.just(new SecurityContextImpl(authentication))
			));
	}

	private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(httpStatus);
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

		Map<String, Object> errorResponse = new HashMap<>();
		errorResponse.put("timestamp", Instant.now().toString());
		errorResponse.put("status", httpStatus.value());
		errorResponse.put("error", httpStatus.getReasonPhrase());
		errorResponse.put("message", err);

		byte[] bytes;
		try {
			bytes = MAPPER.writeValueAsBytes(errorResponse);
		} catch (JsonProcessingException e) {
			log.error("Error creating error response", e);
			bytes = ("{\"error\":\"" + httpStatus.getReasonPhrase() + "\"}").getBytes(StandardCharsets.UTF_8);
		}

		DataBuffer buffer = response.bufferFactory().wrap(bytes);
		return response.writeWith(Mono.just(buffer));
	}
}
