package com.flashguard.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ActivityCacheServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void redisReadFailureFallsBackToDatabaseAndLocalCache() {
        ActivityMapper mapper = mock(ActivityMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        ActivityView activity = new ActivityView(1, "sale", LocalDateTime.now(), LocalDateTime.now().plusHours(1),
            ActivityStatus.ACTIVE, 10, 10);
        when(mapper.findById(1)).thenReturn(Optional.of(activity));
        ActivityCacheService cache = new ActivityCacheService(mapper, redis, new ObjectMapper(), 3000, 15000);
        assertThat(cache.findById(1)).isEqualTo(activity);
        assertThat(cache.findById(1)).isEqualTo(activity);
        verify(mapper).findById(1);
    }
}
