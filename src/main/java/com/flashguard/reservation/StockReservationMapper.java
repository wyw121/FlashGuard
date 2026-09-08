package com.flashguard.reservation;

import java.time.LocalDateTime;
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
}
