package com.flashguard.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class RedisContainerSupport {

    private static final String REDIS_IMAGE = System.getProperty(
        "flashguard.test.redis.image",
        "redis:7.4.8"
    );

    protected static final GenericContainer<?> REDIS = new GenericContainer<>(
        DockerImageName.parse(REDIS_IMAGE)
    ).withExposedPorts(6379);

    protected static LettuceConnectionFactory connectionFactory;
    protected static StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        System.out.printf(
            "FLASHGUARD_TEST_REDIS_IMAGE=%s SERVER_VERSION=%s%n",
            REDIS.getDockerImageName(),
            redis.getConnectionFactory().getConnection().serverCommands().info("server")
                .getProperty("redis_version")
        );
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        REDIS.stop();
    }
}
