package com.example.boilerplate.gateway.common.logger;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 애플리케이션 기동이 완료된 시점에 등록된 게이트웨이 라우트를 로그로 출력한다.
 * 기존 구현은 @PostConstruct에서 비결정적으로 subscribe했고
 * 파일명(GatewayRouteLogger)과 클래스명(GatewayConfig)이 불일치했다.
 */
@Component
@Slf4j
public class GatewayRouteLogger {

	private final RouteLocator routeLocator;

	public GatewayRouteLogger(RouteLocator routeLocator) {
		this.routeLocator = routeLocator;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void printRoutes() {
		routeLocator.getRoutes().subscribe(route ->
			log.info("### Route registered — id: {}, uri: {}, predicates: {}",
				route.getId(), route.getUri(), route.getPredicate())
		);
	}
}
