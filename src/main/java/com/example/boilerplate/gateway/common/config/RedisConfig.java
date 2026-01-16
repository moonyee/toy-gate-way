package com.example.boilerplate.gateway.common.config;

import io.lettuce.core.ReadFrom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

@Configuration
public class RedisConfig {

	@Value("${spring.data.redis.master.host}")
	private String masterHost;

	@Value("${spring.data.redis.master.port}")
	private int masterPort;

	@Value("${spring.data.redis.slave.host}")
	private String slaveHost;

	@Value("${spring.data.redis.slave.port}")
	private int slavePort;

	@Bean
	@Primary
	public ReactiveRedisConnectionFactory reactiveRedisConnectionFactory() {
		// 1. 읽기 전략 설정: 슬레이브가 있다면 슬레이브에서 우선적으로 읽음
		LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
			.readFrom(ReadFrom.REPLICA_PREFERRED)	// 분산
			.build();

		// 2. 마스터와 슬레이브 노드 구성
		RedisStaticMasterReplicaConfiguration staticConfig =
			new RedisStaticMasterReplicaConfiguration(masterHost, masterPort); // 마스터

		staticConfig.addNode(slaveHost, slavePort); // 슬레이브 추가
		// staticConfig.addNode(slaveHost, slavePort2); // 필요시 슬레이브 추가
		// staticConfig.addNode(slaveHost, slavePort3); // 필요시 슬레이브 추가

		return new LettuceConnectionFactory(staticConfig, clientConfig);
	}

	@Bean
	public ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory factory) {
		return new ReactiveStringRedisTemplate(factory);
	}
}