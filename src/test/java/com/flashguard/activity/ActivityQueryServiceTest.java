package com.flashguard.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;

import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import com.flashguard.support.MySqlContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ActivityQueryServiceTest extends MySqlContainerSupport {

    @Autowired
    private ActivityQueryService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedActivity() {
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM activity_stock");
        jdbcTemplate.update("DELETE FROM activity");
        jdbcTemplate.update(
            """
                INSERT INTO activity(id, name, start_time, end_time, status)
                VALUES (?, ?, ?, ?, ?)
                """,
            1001L,
            "Flash sale",
            Timestamp.from(Instant.parse("2026-09-05T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-09-06T00:00:00Z")),
            "ACTIVE"
        );
        jdbcTemplate.update(
            """
                INSERT INTO activity_stock(activity_id, initial_stock, available_stock, version)
                VALUES (?, ?, ?, ?)
                """,
            1001L,
            10,
            10,
            0L
        );
    }

    @Test
    void returnsActivityWithCurrentStock() {
        ActivityView result = service.findById(1001L);

        assertThat(result.id()).isEqualTo(1001L);
        assertThat(result.name()).isEqualTo("Flash sale");
        assertThat(result.status()).isEqualTo(ActivityStatus.ACTIVE);
        assertThat(result.initialStock()).isEqualTo(10);
        assertThat(result.availableStock()).isEqualTo(10);
    }

    @Test
    void rejectsUnknownActivity() {
        assertThatThrownBy(() -> service.findById(9999L))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.code()).isEqualTo(BusinessCode.ACTIVITY_NOT_FOUND));
    }
}
