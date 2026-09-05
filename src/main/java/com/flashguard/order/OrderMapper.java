package com.flashguard.order;

import java.time.LocalDateTime;
import java.util.Optional;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper {

    @Select("""
        SELECT id, order_no, request_id, activity_id, user_id, status, created_at, updated_at
        FROM orders
        WHERE request_id = #{requestId}
        """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "order_no", javaType = String.class),
        @Arg(column = "request_id", javaType = String.class),
        @Arg(column = "activity_id", javaType = long.class),
        @Arg(column = "user_id", javaType = long.class),
        @Arg(column = "status", javaType = OrderStatus.class),
        @Arg(column = "created_at", javaType = LocalDateTime.class),
        @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    Optional<OrderRecord> findByRequestId(@Param("requestId") String requestId);

    @Select("""
        SELECT id, order_no, request_id, activity_id, user_id, status, created_at, updated_at
        FROM orders
        WHERE activity_id = #{activityId}
          AND user_id = #{userId}
        """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "order_no", javaType = String.class),
        @Arg(column = "request_id", javaType = String.class),
        @Arg(column = "activity_id", javaType = long.class),
        @Arg(column = "user_id", javaType = long.class),
        @Arg(column = "status", javaType = OrderStatus.class),
        @Arg(column = "created_at", javaType = LocalDateTime.class),
        @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    Optional<OrderRecord> findByActivityAndUser(
        @Param("activityId") long activityId,
        @Param("userId") long userId
    );

    @Select("""
        SELECT id, order_no, request_id, activity_id, user_id, status, created_at, updated_at
        FROM orders
        WHERE order_no = #{orderNo}
        """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "order_no", javaType = String.class),
        @Arg(column = "request_id", javaType = String.class),
        @Arg(column = "activity_id", javaType = long.class),
        @Arg(column = "user_id", javaType = long.class),
        @Arg(column = "status", javaType = OrderStatus.class),
        @Arg(column = "created_at", javaType = LocalDateTime.class),
        @Arg(column = "updated_at", javaType = LocalDateTime.class)
    })
    Optional<OrderRecord> findByOrderNo(@Param("orderNo") String orderNo);

    @Insert("""
        INSERT INTO orders(order_no, request_id, activity_id, user_id, status)
        VALUES (#{orderNo}, #{requestId}, #{activityId}, #{userId}, #{status})
        """)
    void insert(OrderRecord order);
}
