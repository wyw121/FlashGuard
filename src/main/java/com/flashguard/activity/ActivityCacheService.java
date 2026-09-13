package com.flashguard.activity;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ActivityCacheService {
    private static final String PREFIX = "fg:cache:activity:";
    private final ActivityMapper mapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final long redisTtlMillis;
    private final Clock clock;
    private final Cache<Long, Snapshot> local;

    @Autowired
    public ActivityCacheService(ActivityMapper mapper, StringRedisTemplate redis, ObjectMapper json,
        @Value("${flashguard.activity-cache.local-ttl-ms:3000}") long localTtlMillis,
        @Value("${flashguard.activity-cache.redis-ttl-ms:15000}") long redisTtlMillis,
        @Value("${flashguard.activity-cache.maximum-size:10000}") long maximumSize) {
        this(mapper, redis, json, localTtlMillis, redisTtlMillis, maximumSize, Clock.systemUTC(), Ticker.systemTicker());
    }

    public ActivityCacheService(ActivityMapper mapper, StringRedisTemplate redis, ObjectMapper json,
        long localTtlMillis, long redisTtlMillis) {
        this(mapper, redis, json, localTtlMillis, redisTtlMillis, 10000);
    }

    ActivityCacheService(ActivityMapper mapper, StringRedisTemplate redis, ObjectMapper json,
        long localTtlMillis, long redisTtlMillis, long maximumSize, Clock clock, Ticker ticker) {
        if (localTtlMillis <= 0 || redisTtlMillis <= localTtlMillis || maximumSize <= 0) {
            throw new IllegalArgumentException("cache requires 0 < local TTL < Redis TTL and positive maximum size");
        }
        this.mapper = Objects.requireNonNull(mapper);
        this.redis = Objects.requireNonNull(redis);
        this.json = Objects.requireNonNull(json);
        this.redisTtlMillis = redisTtlMillis;
        this.clock = clock;
        this.local = Caffeine.newBuilder().maximumSize(maximumSize)
            .expireAfterWrite(Duration.ofMillis(localTtlMillis)).ticker(ticker).build();
    }

    public ActivityView findById(long activityId) {
        if (activityId <= 0) throw new IllegalArgumentException("activityId must be positive");
        // Atomic per-key loading also coalesces missing-ID requests on this JVM.
        Snapshot snapshot = local.get(activityId, this::load);
        if (snapshot.activity() == null) throw new BusinessException(BusinessCode.ACTIVITY_NOT_FOUND);
        return snapshot.activity();
    }

    private Snapshot load(long id) {
        try {
            String raw = redis.opsForValue().get(PREFIX + id);
            if (raw != null) {
                Snapshot snapshot = json.readValue(raw, Snapshot.class);
                if (snapshot != null && snapshot.expiresAt() > clock.millis()
                    && (snapshot.activity() == null || snapshot.activity().id() == id)) return snapshot;
            }
        } catch (Exception ignored) {
            // Corrupt entries or an unavailable optional cache fall back to the database.
        }
        Snapshot snapshot = new Snapshot(mapper.findById(id).orElse(null), clock.millis() + redisTtlMillis);
        try {
            redis.opsForValue().set(PREFIX + id, json.writeValueAsString(snapshot), Duration.ofMillis(redisTtlMillis));
        } catch (Exception ignored) {
            // A cache outage must not make database reads unavailable.
        }
        return snapshot;
    }

    private record Snapshot(ActivityView activity, long expiresAt) {}
}
