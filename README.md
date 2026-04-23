# gateway-server

외부 트래픽의 단일 진입점. JWT 검증·라우팅·Circuit Breaker·신뢰 헤더 주입을 담당합니다. 비즈니스 로직은 포함하지 않습니다.

## 기술 스택

| 항목 | 내용 |
|------|------|
| 언어 | Java 21 |
| 프레임워크 | Spring Boot 3.5.5, Spring Cloud 2025.0.0, Spring Cloud Gateway (WebFlux/Netty) |
| 포트 | `8180` |
| 보안 | Spring Security Reactive, OAuth2 Resource Server, JJWT 0.11.5 (HS256) |
| 장애 격리 | Resilience4j (Reactive Circuit Breaker) |
| 세션 검증 | Spring Data Redis Reactive |
| 관측성 | Spring Boot Actuator (`/actuator/health`, `/actuator/circuitbreakers`) |
| 빌드 | Gradle (`bootJar` enabled, 일반 jar 비활성) |

## 아키텍처 위치

```
Client ─▶ Gateway(:8180) ─▶ identity-server(:8182)  (/api/auth/**, /api/v1/auth/**)
                         ─▶ edge-server(:8181)      (/api/user/**)
                         ─▶ product-server(:8183)   (/api/product/**)  ← 미구현
```

Gateway는 신뢰 경계(trust boundary)입니다. 백엔드 서비스는 Gateway가 주입한 `x-auth-user-id` / `x-auth-user-role` 헤더만 신뢰해 비즈니스 로직을 수행합니다 (Edge 경유 시). Identity 는 자체 JWT 필터를 추가로 두어 Gateway 우회 접근도 방어합니다.

## 라우팅 규칙

`src/main/resources/application.yml` 에 정의:

| Route ID | Path Predicate | 대상 | Circuit Breaker | Fallback |
|---------|---------------|------|----------------|---------|
| `edge_service` | `/api/user/**` | `http://localhost:8181` | `edgeCircuitBreaker` | `/fallback/identity` |
| `auth_service` | `/api/auth/**` | `http://localhost:8182` | `authCircuitBreaker` | `/fallback/identity` |
| `auth_service_v1` | `/api/v1/auth/**` | `http://localhost:8182` | `authCircuitBreaker`(공유) | `/fallback/identity` |
| `product_service` | `/api/product/**` | `http://localhost:8183` | `productCircuitBreaker` | `/fallback/identity` |

Circuit Breaker 기본값 (`CircuitBreakerConfig.java`):
- 실패율 임계: **50%**
- OPEN 유지: **120초**
- 슬라이딩 윈도우: **최근 100회**
- 타임아웃: **30초**

## JWT 검증 흐름

`JwtAuthenticationFilter` (`WebFilter`)가 요청마다 다음을 수행합니다.

1. `PERMIT_PATHS` 에 `AntPathMatcher.match()` 로 비교 (와일드카드 지원). 일치 시 통과.
2. `Authorization: Bearer {jwt}` 헤더 추출. 누락 시 `401`.
3. `Jwts.parserBuilder().setSigningKey(...)` 로 서명·만료 검증.
4. `jti` 클레임 필수. 누락 시 `401`.
5. Redis `AUTH:{userId}:{jti}` 키 존재 확인 (Circuit Breaker `redisAuthCB` 경유). Redis 장애 시 즉시 `401`로 떨어뜨려 Gateway 정체 방지.
6. 검증 성공 시 `x-auth-user-id`, `x-auth-user-role` 헤더를 **mutate**하여 백엔드로 전달.

### permit 경로 (인증 스킵)
```
/api/auth/login, /api/auth/join, /api/auth/check-id, /api/auth/check-email
/api/v1/auth/verify, /api/v1/auth/resend
/api/public/**
/fallback/**
```

이 목록은 `ReactiveSecurityConfig.authorizeExchange` 의 `permitAll()` 목록과 **완전히 일치**해야 합니다 (한쪽만 수정하면 상시 401 발생). 동일한 이유로 identity 측 `SecurityConfig` 및 `JwtAuthenticationFilter.SKIP_PATHS`와도 경로 의미가 일치해야 합니다 (context-path `/api` 차이만큼 offset).

## Fallback

`FallbackController`:
- `GET /fallback/identity` → HTTP 503 + `{status, message}` JSON

Circuit Open 상태의 요청은 백엔드까지 가지 않고 이 fallback 으로 전환됩니다.

## 라우트 로깅

기동 완료(`ApplicationReadyEvent`) 시 `GatewayRouteLogger` 가 등록된 라우트 목록을 INFO 로그로 출력합니다.

## 환경변수

| 변수 | 설명 |
|------|------|
| `JWT_SECRET` | JWT 서명 비밀키. **identity-server와 동일 값 필수**. 기본값은 dev 전용 플레이스홀더 — 운영 반드시 오버라이드 |

Redis 연결은 현재 `spring.data.redis.master/slave` 로 `localhost:6379/6380` 하드코딩. 운영에서는 프로파일 분리 권장.

## 실행

### 사전 요구사항
- JDK 21
- identity-server 기동 + 연결 가능한 Redis (`6379`)

### 실행
```bash
# PowerShell 예시
$env:JWT_SECRET="please-override-in-real-env-with-at-least-32-bytes"
./gradlew bootRun
```

### 빌드
```bash
./gradlew build
./gradlew bootJar    # 배포용 fat jar
```

### 헬스/상태 확인
```bash
curl http://localhost:8180/actuator/health
curl http://localhost:8180/actuator/circuitbreakers
```

## 디렉토리 구조

```
src/main/java/com/example/boilerplate/gateway/
├── GatewayServeApplication.java
├── common/
│   ├── config/    # CircuitBreakerConfig, ReactiveSecurityConfig, RedisConfig
│   ├── filter/    # JwtAuthenticationFilter (WebFilter)
│   └── logger/    # GatewayRouteLogger
└── controller/    # FallbackController
```

## 테스트

기존 테스트:
- `GatewayServeApplicationTests.contextLoads`
- `JwtAuthenticationFilterTest` — Circuit Breaker fallback/OPEN/HALF_OPEN 회복 시나리오

```bash
./gradlew test
```

## 알려진 제약 및 후속 과제

- **Swagger 미지원** — 현재 각 백엔드 서비스의 Swagger UI를 직접 접근. Gateway를 통한 집계(`/api/docs/{service}`) 는 후속 과제
- **JWKS(RS256) 전환** 권장 — 현재 HS256 대칭키를 identity와 공유. 장기적으로 `spring-boot-starter-oauth2-resource-server` 의 JwkSetUri 기반 검증으로 전환
- **CORS 미설정** — 필요 시 Global Filter 또는 `SecurityWebFilterChain`에 추가
- **Edge 신뢰 경계** — `x-auth-user-id` 헤더 스푸핑 방어 위해 Gateway ↔ Edge 간 mTLS 또는 HMAC 서명 헤더 권장
- **Kafka 미구현** — 초기 README에 언급된 비동기 메시징은 현재 코드에 없음. 필요 시 별도 Phase에서 도입
- **관측성 강화** — 트레이싱(Micrometer Tracing + OTel) 추가 권장
