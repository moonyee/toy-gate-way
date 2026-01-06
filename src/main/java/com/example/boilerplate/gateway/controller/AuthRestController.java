package com.example.boilerplate.gateway.controller;

import com.example.boilerplate.gateway.common.config.JwtTokenUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth2")
@RequiredArgsConstructor
public class AuthRestController {

	private final JwtTokenUtil jwtTokenUtil;

	@PostMapping("/auth/test")
	public Map<String, String> test() {
		Map<String, String> response = new HashMap<>();
		response.put("data", "test");
		return response;
	}
}