package com.flashguard.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class MySqlContainerSupport {

    private static final String EXTERNAL_URL = System.getProperty("flashguard.test.db.url");
    private static final String EXTERNAL_USERNAME = System.getProperty("flashguard.test.db.username");
    private static final String EXTERNAL_PASSWORD = System.getProperty("flashguard.test.db.password");

    protected static final MySQLContainer MYSQL = createContainerWhenNeeded();

    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("flashguard.scheduling.enabled", () -> false);
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            registry.add("spring.datasource.url", MySqlContainerSupport::externalJdbcUrl);
            registry.add("spring.datasource.username", () -> EXTERNAL_USERNAME);
            registry.add("spring.datasource.password", () -> EXTERNAL_PASSWORD);
            return;
        }
        MYSQL.start();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    private static MySQLContainer createContainerWhenNeeded() {
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            return null;
        }
        return new MySQLContainer(DockerImageName.parse("mysql:8.4.8"))
            .withDatabaseName("flashguard")
            .withUsername("flashguard")
            .withPassword("flashguard-test");
    }

    private static String externalJdbcUrl() {
        if (EXTERNAL_URL.contains("?")) {
            return EXTERNAL_URL;
        }
        return EXTERNAL_URL
            + "?sslMode=DISABLED&allowPublicKeyRetrieval=true"
            + "&connectTimeout=5000&socketTimeout=30000";
    }
}
