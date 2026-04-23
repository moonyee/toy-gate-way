package com.example.boilerplate.gateway.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/fallback")
public class FallbackController {

	@GetMapping("/identity")
	public Mono<ResponseEntity<Map<String, Object>>> identityFallback() {
		Map<String, Object> response = new HashMap<>();
		response.put("status", "error");
		response.put("message", "인증 서비스가 현재 원활하지 않습니다. 잠시 후 다시 시도해 주세요.");

		return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response));
	}
}