package com.example.boilerplate.gateway.common.logger;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Configuration
@Slf4j
class GatewayConfig {
    
    private final RouteLocator routeLocator;

	public GatewayConfig(RouteLocator routeLocator) {
		this.routeLocator = routeLocator;
	}

	@PostConstruct
    public void printRoutes() {
        routeLocator.getRoutes()
            .subscribe(route -> {
                log.info("### Route ID: {}", route.getId());
                log.info("### Route URI: {}", route.getUri());
                log.info("### Route Predicates: {}", route.getPredicate());
            });
    }
}