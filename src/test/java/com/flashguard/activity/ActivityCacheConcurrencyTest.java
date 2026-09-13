package com.flashguard.activity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashguard.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ActivityCacheConcurrencyTest {
    @Test
    @SuppressWarnings("unchecked")
    void missingIdsAreCachedAndExpireLocally() {
        ActivityMapper mapper = mock(ActivityMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(mapper.findById(99)).thenReturn(Optional.empty());
        AtomicLong ticker = new AtomicLong();
        ActivityCacheService cache = new ActivityCacheService(mapper, redis, new ObjectMapper(),
            100, 1000, 10, Clock.systemUTC(), ticker::get);
        assertThatThrownBy(() -> cache.findById(99)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> cache.findById(99)).isInstanceOf(BusinessException.class);
        verify(mapper).findById(99);
        ticker.set(TimeUnit.MILLISECONDS.toNanos(100));
        assertThatThrownBy(() -> cache.findById(99)).isInstanceOf(BusinessException.class);
        verify(mapper, times(2)).findById(99);
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentColdReadersShareOneDatabaseLoad() throws Exception {
        ActivityMapper mapper = mock(ActivityMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ActivityView view = new ActivityView(1, "sale", LocalDateTime.now(), LocalDateTime.now().plusHours(1),
            ActivityStatus.ACTIVE, 10, 10);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(mapper.findById(1)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("load timeout");
            return Optional.of(view);
        });
        ActivityCacheService cache = new ActivityCacheService(mapper, redis, new ObjectMapper().findAndRegisterModules(), 3000, 15000);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            var requests = new ArrayList<Future<ActivityView>>();
            for (int i = 0; i < 16; i++) requests.add(pool.submit(() -> cache.findById(1)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (var request : requests) assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo(view);
            verify(mapper).findById(1);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
