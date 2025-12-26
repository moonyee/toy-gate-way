package com.example.boilerplate.gateway.common.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtTokenUtil {

	private final SecretKey signingKey;

	@Value("${jwt.secret}")
	private String jwtSecret;

	public JwtTokenUtil(@Value("${jwt.secret}") String jwtSecret) {
		this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
	}

	// JWT 토큰 생성
	public String generateToken(String username) {
		Date now = new Date();
		Date expiration = new Date(now.getTime() + 3600000); // 1시간 유효

		return Jwts.builder()
			.setSubject(username)
			.setIssuedAt(now)
			.setExpiration(expiration)
			.signWith(signingKey, SignatureAlgorithm.HS256)
			.compact();
	}
}