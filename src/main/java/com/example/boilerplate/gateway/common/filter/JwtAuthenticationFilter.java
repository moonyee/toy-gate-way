package com.example.boilerplate.gateway.common.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@Component
public class JwtAuthenticationFilter implements WebFilter {

    private final SecretKey signingKey;
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String[] LOGIN_PATH = { "/api/auth/login" };
    private final ReactiveStringRedisTemplate redisTemplate;

    private final ReactiveCircuitBreakerFactory circuitBreaker;

    public JwtAuthenticationFilter(
        @Value("${jwt.secret}") String secretKey,
		ReactiveStringRedisTemplate redisTemplate,
        ReactiveCircuitBreakerFactory circuitBreaker
    ) {
        this.signingKey = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));
		this.redisTemplate = redisTemplate;
		this.circuitBreaker = circuitBreaker;
	}

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        log.info("### Request received for path: {}", request.getPath());
        log.info("### Authorization Header: {}", request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        String path = request.getPath().value();

        // 로그인 경로는 필터를 건너뜁니다
        for( String loginPath : LOGIN_PATH){
            if (path.equals(loginPath)) {
                return chain.filter(exchange);
            }
        }

        return extractAndValidateToken(request)
            .doOnSuccess(jwt -> log.info("### JWT token successfully extracted."))
            .flatMap(this::parseJwt)
            .flatMap(claims -> {
                String userId = claims.getSubject();
                String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                String jwt = authHeader.substring(BEARER_PREFIX.length());

                // [수정] 고도화된 Redis 키 구조: "AUTH:userId:token"
                String redisKey = "AUTH:" + userId + ":" + jwt;

                log.info("### Gateway checking Redis key: {}", redisKey);

                return redisTemplate.opsForValue().get(redisKey)
                    .flatMap(savedToken -> {
                        // 사실 키 자체에 토큰이 포함되어 있으므로, 데이터가 존재한다는 것만으로도 검증이 됩니다.
                        if (jwt.equals(savedToken)) {
                            return Mono.just(claims);
                        }
                        return Mono.error(new RuntimeException("Token mismatch"));
                    })
                    // Redis에 해당 세션 키가 없으면 로그아웃되었거나 만료된 것으로 간주
                    .switchIfEmpty(Mono.error(new RuntimeException("No session found in Redis for this token")));
            })
            .doOnSuccess(claims -> log.info("### JWT parsed for user: {}", claims.getSubject()))
            .flatMap(claims -> processRequest(exchange, chain, claims))
            .doOnSuccess(v -> {
                // 최종 응답 상태 확인
                HttpStatusCode status = exchange.getResponse().getStatusCode();
                log.info("### Final response status: {}", status);
                // 응답 헤더 확인
                log.info("### Response headers: {}", exchange.getResponse().getHeaders());
            })
            .doOnError(e -> log.error("### Error in filter chain: {}", e.getMessage()))
            .onErrorResume(ExpiredJwtException.class, e -> {
                log.error("JWT token is expired: {}", e.getMessage());
                return onError(exchange, "JWT token is expired", HttpStatus.UNAUTHORIZED);
            })
            .onErrorResume(Exception.class, e -> {
                log.error("### Invalid JWT token or filter error: {}", e.getMessage());
                return onError(exchange, "Invalid JWT token", HttpStatus.UNAUTHORIZED);
            });

    }

    private Mono<String> extractAndValidateToken(ServerHttpRequest request) {
        return Mono.justOrEmpty(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
            .filter(header -> header.startsWith(BEARER_PREFIX))
            .map(header -> header.substring(BEARER_PREFIX.length()));
    }

    private Mono<Claims> parseJwt(String jwt) {
        return Mono.fromCallable(() -> Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(jwt)
                .getBody())
            .subscribeOn(Schedulers.boundedElastic());

    }

    private Mono<Void> processRequest(ServerWebExchange exchange, WebFilterChain chain, Claims claims) {
        // 1. JWT Claims(페이로드)에서 사용자 아이디(subject)를 추출합니다.
        // 'sub'는 JWT 표준 클레임으로, 주로 사용자의 고유 식별자를 담습니다.
        String userid = claims.getSubject();

        // 2. Spring Security용 Authentication 객체를 생성합니다.
        // UsernamePasswordAuthenticationToken은 인증된 사용자를 나타내는 표준 클래스입니다.
        // - 첫 번째 인자: 주체(Principal), 즉 사용자 아이디
        // - 두 번째 인자: 자격 증명(Credentials), 여기서는 이미 토큰 검증을 마쳤으므로 null
        // - 세 번째 인자: 권한(Authorities), 사용자가 가진 역할을 부여합니다.
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            userid,
            null,
            Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
        );

        // 3. 엣지 서버로 전달할 헤더를 추가하여 새로운 요청(ServerHttpRequest) 객체를 만듭니다.
        // ServerHttpRequest는 불변(immutable) 객체이므로 mutate()를 통해 새로운 인스턴스를 생성해야 합니다.
        // 'X-Auth-Username' 헤더는 게이트웨이가 인증을 완료했음을 엣지 서버에 알려주는 신뢰의 증표입니다.
        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
            .header("x-auth-user-id", userid)
            .build();

        // 4. 새로운 요청 객체를 포함하는 새로운 ServerWebExchange 객체를 만듭니다.
        // ServerWebExchange 또한 불변 객체이므로, 변경된 요청을 담기 위해 새로운 인스턴스를 생성합니다.
        ServerWebExchange modifiedExchange = exchange.mutate()
            .request(modifiedRequest)
            .build();

        // 5. 다음 필터 체인으로 요청을 전달하고, 보안 컨텍스트를 전파합니다.
        // - chain.filter(modifiedExchange): 변경된 요청을 다음 필터로 넘깁니다.
        // - .contextWrite(...): Mono의 컨텍스트(Context)에 인증 정보를 저장합니다.
        //   이를 통해 다음 필터나 컨트롤러에서 ReactiveSecurityContextHolder를 사용해 인증 정보를 꺼내 쓸 수 있습니다.
        return chain.filter(modifiedExchange)
            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                Mono.just(new SecurityContextImpl(authentication))
            ));
    }

    private Mono<Void> onError(ServerWebExchange exchange, String err, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);

        // JSON 형태의 에러 응답
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", Instant.now().toString());
        errorResponse.put("status", httpStatus.value());
        errorResponse.put("error", httpStatus.getReasonPhrase());
        errorResponse.put("message", err);

        byte[] bytes = null;
        try {
            bytes = new ObjectMapper().writeValueAsBytes(errorResponse);
        } catch (JsonProcessingException e) {
            log.error("Error creating error response", e);
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(buffer));

    }
}