package com.flashguard.participation;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.flashguard.shared.error.BusinessCode;
import com.flashguard.shared.error.BusinessException;

@Service
public class ParticipationRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = script();

    private static DefaultRedisScript<Long> script() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/participation_rate_limit.lua"));
        script.setResultType(Long.class);
        return script;
    }
    private final Map<Long, ArrayDeque<Long>> activities = new HashMap<>();
    private final Map<Long, ArrayDeque<Long>> users = new HashMap<>();
    private final boolean enabled;
    private final boolean globalEnabled;
    private final int activityLimit;
    private final long activityWindowMs;
    private final int userLimit;
    private final long userWindowMs;
    private final Clock clock;
    private final StringRedisTemplate redis;
    private final Counter activityRejectCounter;
    private final Counter userRejectCounter;
    private final Counter unavailableCounter;

    @Autowired
    public ParticipationRateLimiter(MeterRegistry registry,
        @Value("${flashguard.ratelimit.enabled:true}") boolean enabled,
        @Value("${flashguard.ratelimit.activity.limit:120}") int activityLimit,
        @Value("${flashguard.ratelimit.activity.window-ms:1000}") long activityWindowMs,
        @Value("${flashguard.ratelimit.user.limit:5}") int userLimit,
        @Value("${flashguard.ratelimit.user.window-ms:1000}") long userWindowMs,
        StringRedisTemplate redis,
        @Value("${flashguard.ratelimit.global-enabled:false}") boolean globalEnabled) {
        this(registry, enabled, activityLimit, activityWindowMs, userLimit, userWindowMs, redis, globalEnabled, Clock.systemUTC());
    }

    public ParticipationRateLimiter(MeterRegistry registry, boolean enabled, int activityLimit,
        long activityWindowMs, int userLimit, long userWindowMs) {
        this(registry, enabled, activityLimit, activityWindowMs, userLimit, userWindowMs, null, false, Clock.systemUTC());
    }

    ParticipationRateLimiter(MeterRegistry registry, boolean enabled, int activityLimit,
        long activityWindowMs, int userLimit, long userWindowMs, StringRedisTemplate redis,
        boolean globalEnabled, Clock clock) {
        if (activityLimit <= 0 || userLimit <= 0 || activityWindowMs <= 0 || userWindowMs <= 0)
            throw new IllegalArgumentException("rate limits and windows must be positive");
        if (globalEnabled && redis == null) throw new IllegalArgumentException("global limiter requires Redis");
        this.enabled = enabled;
        this.globalEnabled = globalEnabled;
        this.activityLimit = activityLimit;
        this.activityWindowMs = activityWindowMs;
        this.userLimit = userLimit;
        this.userWindowMs = userWindowMs;
        this.redis = redis;
        this.clock = clock;
        activityRejectCounter = counter(registry, "activity");
        userRejectCounter = counter(registry, "user");
        unavailableCounter = counter(registry, "redis_unavailable");
    }

    private Counter counter(MeterRegistry registry, String scope) {
        return Counter.builder("flashguard_rate_limit_reject_total").tag("scope", scope).register(registry);
    }

    public void ensureAllowed(long activityId, long userId) {
        if (!enabled) return;
        long result;
        if (globalEnabled) {
            try {
                Long value = redis.execute(SCRIPT, List.of("fg:limit:{participation}:activity:" + activityId,
                    "fg:limit:{participation}:user:" + userId), Integer.toString(activityLimit),
                    Long.toString(activityWindowMs), Integer.toString(userLimit), Long.toString(userWindowMs),
                    UUID.randomUUID().toString());
                result = value == null ? -1 : value;
            } catch (org.springframework.dao.DataAccessException exception) {
                result = -1;
            }
        } else {
            result = acquireLocal(activityId, userId);
        }
        if (result != 0) {
            (result == 1 ? activityRejectCounter : result == 2 ? userRejectCounter : unavailableCounter).increment();
            throw new BusinessException(BusinessCode.THROTTLED);
        }
    }

    // One short critical section keeps both budgets and cleanup atomic; rejected requests consume neither.
    private synchronized long acquireLocal(long activityId, long userId) {
        long now = clock.millis();
        ArrayDeque<Long> activity = activities.computeIfAbsent(activityId, ignored -> new ArrayDeque<>());
        evict(activity, now - activityWindowMs);
        if (activity.size() >= activityLimit) return 1;
        ArrayDeque<Long> user = users.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        evict(user, now - userWindowMs);
        if (user.size() >= userLimit) {
            if (activity.isEmpty()) activities.remove(activityId);
            return 2;
        }
        activity.addLast(now);
        user.addLast(now);
        return 0;
    }

    @Scheduled(fixedDelayString = "60s")
    public synchronized void cleanup() {
        long now = clock.millis();
        activities.values().removeIf(window -> { evict(window, now - activityWindowMs); return window.isEmpty(); });
        users.values().removeIf(window -> { evict(window, now - userWindowMs); return window.isEmpty(); });
    }

    private void evict(ArrayDeque<Long> window, long cutoff) {
        while (!window.isEmpty() && window.peekFirst() <= cutoff) window.removeFirst();
    }
}

