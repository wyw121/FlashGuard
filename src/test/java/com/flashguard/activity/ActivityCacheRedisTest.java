package com.flashguard.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashguard.shared.error.BusinessException;
import com.flashguard.support.RedisContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivityCacheRedisTest extends RedisContainerSupport {
    @BeforeEach
    void clear() { redis.getConnectionFactory().getConnection().serverCommands().flushAll(); }

    @Test
    void positiveAndNegativeEntriesAreSharedAndRedisExpiryReloadsDatabase() {
        ActivityMapper mapper = mock(ActivityMapper.class);
        var json = new ObjectMapper().findAndRegisterModules();
        var view = new ActivityView(1, "sale", LocalDateTime.now(), LocalDateTime.now().plusHours(1), ActivityStatus.ACTIVE, 10, 10);
        when(mapper.findById(1)).thenReturn(Optional.of(view));
        when(mapper.findById(2)).thenReturn(Optional.empty());
        var first = new ActivityCacheService(mapper, redis, json, 50, 2000);
        var second = new ActivityCacheService(mapper, redis, json, 50, 2000);
        assertThat(first.findById(1)).isEqualTo(view);
        assertThat(second.findById(1)).isEqualTo(view);
        assertThatThrownBy(() -> first.findById(2)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> second.findById(2)).isInstanceOf(BusinessException.class);
        verify(mapper).findById(1);
        verify(mapper).findById(2);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
            .until(() -> !Boolean.TRUE.equals(redis.hasKey("fg:cache:activity:2")));
        var third = new ActivityCacheService(mapper, redis, json, 50, 2000);
        assertThatThrownBy(() -> third.findById(2)).isInstanceOf(BusinessException.class);
        verify(mapper, times(2)).findById(2);
    }
}

