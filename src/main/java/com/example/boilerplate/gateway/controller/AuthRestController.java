package com.example.boilerplate.gateway.controller;

import com.example.boilerplate.gateway.common.config.JwtTokenUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

	@PostMapping("login")
	public Map<String, String> login(@RequestBody Map<String, String> loginRequest) {
		String username = loginRequest.get("username");
		String password = loginRequest.get("password");

		// 실제로는 DB에서 사용자 정보를 검증해야 합니다. 여기서는 간단히 통과시킵니다.
		if ("testuser".equals(username) && "password".equals(password)) {
			String token = jwtTokenUtil.generateToken(username);
			Map<String, String> response = new HashMap<>();
			response.put("token", token);
			return response;
		}

		throw new RuntimeException("Invalid username or password");
	}

	@PostMapping("/auth/test")
	public Map<String, String> test() {
		Map<String, String> response = new HashMap<>();
		response.put("data", "test");
		return response;
	}
}