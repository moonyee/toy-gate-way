package com.example.boilerplate.gateway.common.config;

import com.example.boilerplate.gateway.common.filter.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

@Configuration
@EnableWebFluxSecurity
public class ReactiveSecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;

	public ReactiveSecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
	}

	@Bean
	public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
		return http
			.csrf(ServerHttpSecurity.CsrfSpec::disable)
			.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
			.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
			.authorizeExchange(exchanges -> exchanges
				.pathMatchers("/api/auth/login").permitAll()       // 👈 인증 없이 허용
				.pathMatchers("/api/auth2/login").permitAll()       // 👈 인증 없이 허용
				.pathMatchers("/api/public/**").permitAll()   // 👈 특정 공개 API 허용
				.pathMatchers("/css/**", "/images/**").permitAll() // 👈 정적 리소스 허용
				.anyExchange().authenticated() // 👈 그 외 모든 요청은 인증 필요
			)
			// JWT 필터가 다른 보안 필터보다 먼저 실행되도록 설정
			.addFilterBefore(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
			.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())

			.build();
	}
}