package com.flashguard.reservation;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashguard.activity.ActivityStatus;
import com.flashguard.activity.ActivityView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisReservationStore {

    private static final String KEY_PREFIX = "fg:{activity:";
    private static final DefaultRedisScript<List> WARMUP_SCRIPT = script(
        "redis/warmup_stock.lua"
    );
    private static final DefaultRedisScript<List> RESERVE_SCRIPT = script(
        "redis/reserve_stock.lua"
    );

    private final StringRedisTemplate redis;
    private final long reservationTimeoutMillis;
    private final ObjectMapper objectMapper;

    public RedisReservationStore(
        StringRedisTemplate redis,
        @Value("${flashguard.reservation.timeout:120s}") Duration reservationTimeout
    ) {
        this.redis = Objects.requireNonNull(redis, "redis");
        Objects.requireNonNull(reservationTimeout, "reservationTimeout");
        if (reservationTimeout.isZero() || reservationTimeout.isNegative()) {
            throw new IllegalArgumentException("Reservation timeout must be positive");
        }
        this.reservationTimeoutMillis = reservationTimeout.toMillis();
        this.objectMapper = new ObjectMapper();
    }

    public WarmUpResult warmUp(ActivityView activity) {
        Objects.requireNonNull(activity, "activity");
        if (activity.id() <= 0 || activity.status() != ActivityStatus.ACTIVE) {
            throw new IllegalArgumentException("Only active activities with a positive id can be warmed up");
        }
        if (activity.availableStock() < 0) {
            throw new IllegalArgumentException("Available stock cannot be negative");
        }
        long startAt = activity.startTime().toInstant(ZoneOffset.UTC).toEpochMilli();
        long endAt = activity.endTime().toInstant(ZoneOffset.UTC).toEpochMilli();
        if (endAt <= startAt) {
            throw new IllegalArgumentException("Activity end time must be after start time");
        }

        List<?> response = execute(
            WARMUP_SCRIPT,
            keys(activity.id()),
            "1",
            Integer.toString(activity.availableStock()),
            Long.toString(startAt),
            Long.toString(endAt)
        );
        String code = responseCode(response);
        if ("STATE_ERROR".equals(code)) {
            throw stateError(response);
        }
        try {
            return WarmUpResult.valueOf(code);
        } catch (IllegalArgumentException exception) {
            throw new ReservationStoreException("Unexpected warmup result: " + code, exception);
        }
    }

    public ReservationResult reserve(long activityId, long userId, String requestId) {
        requirePositiveId(activityId, "activityId");
        requirePositiveId(userId, "userId");
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId cannot be blank");
        }

        List<?> response = execute(
            RESERVE_SCRIPT,
            keys(activityId),
            Long.toString(activityId),
            Long.toString(userId),
            requestId,
            UUID.randomUUID().toString(),
            Long.toString(reservationTimeoutMillis),
            "RESERVE"
        );
        String code = responseCode(response);
        if ("STATE_ERROR".equals(code)) {
            throw stateError(response);
        }

        ReservationDecision decision;
        try {
            decision = ReservationDecision.valueOf(code);
        } catch (IllegalArgumentException exception) {
            throw new ReservationStoreException("Unexpected reservation result: " + code, exception);
        }
        if (decision == ReservationDecision.SUCCESS || decision == ReservationDecision.REPLAY) {
            if (response.size() < 2) {
                throw new ReservationStateException("Reservation result omitted its record");
            }
            return new ReservationResult(decision, decode(response.get(1).toString()));
        }
        return ReservationResult.withoutReservation(decision);
    }

    public Optional<PendingReservation> findByRequestId(long activityId, String requestId) {
        requirePositiveId(activityId, "activityId");
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId cannot be blank");
        }
        List<?> response = execute(
            RESERVE_SCRIPT,
            keys(activityId),
            Long.toString(activityId),
            "",
            requestId,
            "",
            "0",
            "FIND"
        );
        String code = responseCode(response);
        if ("STATE_ERROR".equals(code)) {
            throw stateError(response);
        }
        if ("NOT_FOUND".equals(code) || "NOT_READY".equals(code)) {
            return Optional.empty();
        }
        if (!"FOUND".equals(code) || response.size() < 2) {
            throw new ReservationStoreException("Unexpected lookup result: " + code);
        }
        PendingReservation reservation = decode(response.get(1).toString());
        if (!Long.toString(activityId).equals(reservation.activityId())
            || !requestId.equals(reservation.requestId())) {
            throw new ReservationStateException("Stored request record has mismatched identity");
        }
        return Optional.of(reservation);
    }

    private List<?> execute(
        DefaultRedisScript<List> script,
        List<String> keys,
        String... arguments
    ) {
        try {
            List<?> result = redis.execute(script, keys, (Object[]) arguments);
            if (result == null || result.isEmpty()) {
                throw new ReservationStoreException("Redis script returned no result");
            }
            return result;
        } catch (ReservationStoreException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new ReservationStoreException("Redis script execution failed", exception);
        }
    }

    private PendingReservation decode(String json) {
        try {
            PendingReservation reservation = objectMapper.readValue(json, PendingReservation.class);
            if (reservation.status() != ReservationStatus.PENDING) {
                throw new ReservationStateException("Unsupported reservation status");
            }
            return reservation;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ReservationStateException("Stored reservation is invalid", exception);
        }
    }

    private String responseCode(List<?> response) {
        Object code = response.get(0);
        if (code == null) {
            throw new ReservationStoreException("Redis script returned an empty code");
        }
        return code.toString();
    }

    private ReservationStateException stateError(List<?> response) {
        String detail = response.size() > 1 ? response.get(1).toString() : "unknown state error";
        return new ReservationStateException("Redis reservation state is invalid: " + detail);
    }

    private List<String> keys(long activityId) {
        String prefix = keyPrefix(activityId);
        return List.of(
            prefix + "stock",
            prefix + "requests",
            prefix + "users",
            prefix + "pending"
        );
    }

    private String keyPrefix(long activityId) {
        return KEY_PREFIX + activityId + "}:";
    }

    private void requirePositiveId(long id, String name) {
        if (id <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static DefaultRedisScript<List> script(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }
}
