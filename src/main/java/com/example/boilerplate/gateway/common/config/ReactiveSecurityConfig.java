package com.example.boilerplate.gateway.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

import com.example.boilerplate.gateway.common.filter.JwtAuthenticationFilter;

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
				// Identity 서비스의 인증 불필요 엔드포인트
				.pathMatchers(
					"/api/auth/login",
					"/api/auth/join",
					"/api/auth/check-id",
					"/api/auth/check-email",
					"/api/auth/verify"
				).permitAll()
				.pathMatchers("/api/public/**").permitAll()
				.pathMatchers("/css/**", "/images/**").permitAll()
				.pathMatchers("/fallback/**").permitAll()
				.pathMatchers("/actuator/**").permitAll()
				.anyExchange().authenticated()
			)
			.addFilterBefore(jwtAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
			.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
			.build();
	}
}
