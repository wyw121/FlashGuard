package com.flashguard.reservation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StockReservationMapper {

    @Insert("""
        INSERT INTO stock_reservation(
            reservation_id,
            activity_id,
            user_id,
            request_id,
            status,
            message_key,
            expire_at
        )
        VALUES (#{reservationId}, #{activityId}, #{userId}, #{requestId}, #{status}, #{messageKey}, #{expireAt})
        """)
    void insert(StockReservationRecord reservation);

    @Select("""
        SELECT id,
               reservation_id,
               activity_id,
               user_id,
               request_id,
               status,
               message_key,
               expire_at,
               created_at,
               updated_at
        FROM stock_reservation
        WHERE reservation_id = #{reservationId}
        """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "reservation_id", javaType = String.class),
        @Arg(column = "activity_id", javaType = long.class),
        @Arg(column = "user_id", javaType = long.class),
        @Arg(column = "request_id", javaType = String.class),
        @Arg(column = "status", javaType = StockReservationStatus.class),
        @Arg(column = "message_key", javaType = String.class),
        @Arg(column = "expire_at", javaType = LocalDateTime.class),
        @Arg(column = "created_at", javaType = LocalDateTime.class),
        @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    Optional<StockReservationRecord> findByReservationId(@Param("reservationId") String reservationId);

    @Select("""
        SELECT id,
               reservation_id,
               activity_id,
               user_id,
               request_id,
               status,
               message_key,
               expire_at,
               created_at,
               updated_at
        FROM stock_reservation
        WHERE activity_id = #{activityId}
          AND request_id = #{requestId}
        """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "reservation_id", javaType = String.class),
        @Arg(column = "activity_id", javaType = long.class),
        @Arg(column = "user_id", javaType = long.class),
        @Arg(column = "request_id", javaType = String.class),
        @Arg(column = "status", javaType = StockReservationStatus.class),
        @Arg(column = "message_key", javaType = String.class),
        @Arg(column = "expire_at", javaType = LocalDateTime.class),
        @Arg(column = "created_at", javaType = LocalDateTime.class),
        @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    Optional<StockReservationRecord> findByActivityAndRequestId(
        @Param("activityId") long activityId,
        @Param("requestId") String requestId
    );

    @Select("""
        SELECT id,
               reservation_id,
               activity_id,
               user_id,
               request_id,
               status,
               message_key,
               expire_at,
               created_at,
               updated_at
        FROM stock_reservation
        WHERE request_id = #{requestId}
        """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "reservation_id", javaType = String.class),
        @Arg(column = "activity_id", javaType = long.class),
        @Arg(column = "user_id", javaType = long.class),
        @Arg(column = "request_id", javaType = String.class),
        @Arg(column = "status", javaType = StockReservationStatus.class),
        @Arg(column = "message_key", javaType = String.class),
        @Arg(column = "expire_at", javaType = LocalDateTime.class),
        @Arg(column = "created_at", javaType = LocalDateTime.class),
        @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    Optional<StockReservationRecord> findByRequestId(@Param("requestId") String requestId);

    @Update("""
        UPDATE stock_reservation
        SET status = #{status},
            message_key = #{messageKey},
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE reservation_id = #{reservationId}
        """)
    int updateStatusAndMessage(
        @Param("reservationId") String reservationId,
        @Param("status") StockReservationStatus status,
        @Param("messageKey") String messageKey
    );

    @Update("""
        UPDATE stock_reservation
        SET status = 'MQ_COMMITTED',
            message_key = #{messageKey},
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE reservation_id = #{reservationId}
          AND status = 'PENDING'
        """)
    int markAsMqCommitted(
        @Param("reservationId") String reservationId,
        @Param("messageKey") String messageKey
    );

    @Update("""
        UPDATE stock_reservation
        SET status = 'CONFIRMED',
            message_key = #{messageKey},
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE reservation_id = #{reservationId}
          AND status = 'MQ_COMMITTED'
        """)
    int markAsConfirmed(
        @Param("reservationId") String reservationId,
        @Param("messageKey") String messageKey
    );

    @Select("""
        SELECT id,
               reservation_id,
               activity_id,
               user_id,
               request_id,
               status,
               message_key,
               expire_at,
               created_at,
               updated_at
        FROM stock_reservation
        WHERE reservation_id = #{reservationId}
        FOR UPDATE
        """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "reservation_id", javaType = String.class),
        @Arg(column = "activity_id", javaType = long.class),
        @Arg(column = "user_id", javaType = long.class),
        @Arg(column = "request_id", javaType = String.class),
        @Arg(column = "status", javaType = StockReservationStatus.class),
        @Arg(column = "message_key", javaType = String.class),
        @Arg(column = "expire_at", javaType = LocalDateTime.class),
        @Arg(column = "created_at", javaType = LocalDateTime.class),
        @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    Optional<StockReservationRecord> findByReservationIdForUpdate(@Param("reservationId") String reservationId);

    @Select("""
        SELECT COUNT(*)
        FROM stock_reservation
        WHERE status IN ('PENDING', 'MQ_COMMITTED')
        """)
    long countMqBacklog();

    @Select("""
        SELECT id,
               reservation_id,
               activity_id,
               user_id,
               request_id,
               status,
               message_key,
               expire_at,
               created_at,
               updated_at
        FROM stock_reservation
        WHERE status IN ('PENDING', 'MQ_COMMITTED')
        ORDER BY updated_at ASC
        LIMIT #{limit}
        """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "reservation_id", javaType = String.class),
        @Arg(column = "activity_id", javaType = long.class),
        @Arg(column = "user_id", javaType = long.class),
        @Arg(column = "request_id", javaType = String.class),
        @Arg(column = "status", javaType = StockReservationStatus.class),
        @Arg(column = "message_key", javaType = String.class),
        @Arg(column = "expire_at", javaType = LocalDateTime.class),
        @Arg(column = "created_at", javaType = LocalDateTime.class),
        @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    List<StockReservationRecord> findConsumable(@Param("limit") int limit);

    @Select("""
        SELECT id,
               reservation_id,
               activity_id,
               user_id,
               request_id,
               status,
               message_key,
               expire_at,
               created_at,
               updated_at
        FROM stock_reservation
        WHERE status IN ('PENDING', 'MQ_COMMITTED', 'CANCEL_PENDING', 'COMPENSATION_FAILED')
          AND expire_at <= #{expireBefore}
        ORDER BY updated_at ASC, id ASC
        LIMIT #{limit}
        """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "reservation_id", javaType = String.class),
        @Arg(column = "activity_id", javaType = long.class),
        @Arg(column = "user_id", javaType = long.class),
        @Arg(column = "request_id", javaType = String.class),
        @Arg(column = "status", javaType = StockReservationStatus.class),
        @Arg(column = "message_key", javaType = String.class),
        @Arg(column = "expire_at", javaType = LocalDateTime.class),
        @Arg(column = "created_at", javaType = LocalDateTime.class),
        @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    List<StockReservationRecord> findExpiredConsumable(
        @Param("expireBefore") LocalDateTime expireBefore,
        @Param("limit") int limit
    );

    @Update("""
        UPDATE stock_reservation
        SET status = 'CANCEL_PENDING',
            message_key = COALESCE(message_key, #{messageKey}),
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE reservation_id = #{reservationId}
          AND status IN ('PENDING', 'MQ_COMMITTED', 'CANCEL_PENDING', 'COMPENSATION_FAILED')
          AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.reservation_id = stock_reservation.reservation_id)
        """)
    int markAsCancelPending(
        @Param("reservationId") String reservationId,
        @Param("messageKey") String messageKey
    );

    @Update("""
        UPDATE stock_reservation
        SET status = 'CANCELLED',
            message_key = COALESCE(message_key, #{messageKey}),
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE reservation_id = #{reservationId}
          AND status = 'CANCEL_PENDING'
        """)
    int markAsCancelled(
        @Param("reservationId") String reservationId,
        @Param("messageKey") String messageKey
    );

    @Update("""
        UPDATE stock_reservation
        SET status = 'COMPENSATION_FAILED',
            message_key = COALESCE(message_key, #{messageKey}),
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE reservation_id = #{reservationId}
          AND status = 'CANCEL_PENDING'
        """)
    int markAsCompensationFailed(
        @Param("reservationId") String reservationId,
        @Param("messageKey") String messageKey
    );

    @Select("""
        SELECT COUNT(*)
        FROM stock_reservation
        WHERE activity_id = #{activityId}
        """)
    long countByActivity(@Param("activityId") long activityId);

    @Select("""
        SELECT COUNT(*)
        FROM stock_reservation
        WHERE activity_id = #{activityId}
          AND status IN ('PENDING', 'MQ_COMMITTED', 'CANCEL_PENDING', 'COMPENSATION_FAILED')
        """)
    long countOpenReservations(@Param("activityId") long activityId);

    @Select("""
        SELECT COUNT(*)
        FROM stock_reservation
        WHERE activity_id = #{activityId}
          AND status = 'CONFIRMED'
        """)
    long countConfirmedByActivity(@Param("activityId") long activityId);

    @Select("""
        SELECT COUNT(*)
        FROM stock_reservation sr
        LEFT JOIN orders o ON o.reservation_id = sr.reservation_id
        WHERE sr.activity_id = #{activityId}
          AND sr.status = 'CONFIRMED'
          AND o.reservation_id IS NULL
        """)
    long countConfirmedWithoutOrder(@Param("activityId") long activityId);
    @Select("""
        SELECT 0 AS id, reservation_id, activity_id, user_id, request_id, status,
               reservation_id AS message_key, expire_at, created_at, updated_at
        FROM reservation_cancellation_fence WHERE reservation_id = #{reservationId}
        """)
    @org.apache.ibatis.annotations.Results(id = "cancellationFence")
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class),
        @Arg(column = "reservation_id", javaType = String.class, id = true),
        @Arg(column = "activity_id", javaType = long.class),
        @Arg(column = "user_id", javaType = long.class),
        @Arg(column = "request_id", javaType = String.class),
        @Arg(column = "status", javaType = StockReservationStatus.class),
        @Arg(column = "message_key", javaType = String.class),
        @Arg(column = "expire_at", javaType = LocalDateTime.class),
        @Arg(column = "created_at", javaType = LocalDateTime.class),
        @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    Optional<StockReservationRecord> findCancellationFence(@Param("reservationId") String reservationId);

    @Select("""
        SELECT 0 AS id, reservation_id, activity_id, user_id, request_id, status,
               reservation_id AS message_key, expire_at, created_at, updated_at
        FROM reservation_cancellation_fence WHERE reservation_id = #{reservationId} FOR UPDATE
        """)
    @org.apache.ibatis.annotations.ResultMap("cancellationFence")
    Optional<StockReservationRecord> findCancellationFenceForUpdate(@Param("reservationId") String reservationId);

    @Insert("""
        INSERT INTO reservation_cancellation_fence(reservation_id, activity_id, user_id, request_id, expire_at, status)
        VALUES (#{reservationId}, #{activityId}, #{userId}, #{requestId}, #{expireAt}, 'CANCEL_PENDING')
        """)
    void insertCancellationFence(StockReservationRecord fence);

    @Select("""
        SELECT 0 AS id, reservation_id, activity_id, user_id, request_id, status,
               reservation_id AS message_key, expire_at, created_at, updated_at
        FROM reservation_cancellation_fence WHERE status = 'CANCEL_PENDING'
        ORDER BY updated_at, reservation_id LIMIT #{limit}
        """)
    @org.apache.ibatis.annotations.ResultMap("cancellationFence")
    List<StockReservationRecord> findPendingCancellationFences(@Param("limit") int limit);

    @Update("""
        UPDATE reservation_cancellation_fence SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP(6)
        WHERE reservation_id = #{reservationId} AND status = 'CANCEL_PENDING'
        """)
    int completeCancellationFence(@Param("reservationId") String reservationId);

    @Update("""
        UPDATE reservation_cancellation_fence SET updated_at = CURRENT_TIMESTAMP(6)
        WHERE reservation_id = #{reservationId} AND status = 'CANCEL_PENDING'
        """)
    int touchCancellationFence(@Param("reservationId") String reservationId);}
