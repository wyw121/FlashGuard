package com.flashguard.activity;

import java.time.LocalDateTime;
import java.util.Optional;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ActivityMapper {
    @Select("SELECT id FROM activity WHERE id > #{afterId} ORDER BY id LIMIT #{limit}")
    java.util.List<Long> findIdsAfter(@Param("afterId") long afterId, @Param("limit") int limit);

    @Select("""
        SELECT a.id,
               a.name,
               a.start_time,
               a.end_time,
               a.status,
               s.initial_stock,
               s.available_stock
        FROM activity a
        JOIN activity_stock s ON s.activity_id = a.id
        WHERE a.id = #{activityId}
        """)
    @ConstructorArgs({
        @Arg(column = "id", javaType = long.class, id = true),
        @Arg(column = "name", javaType = String.class),
        @Arg(column = "start_time", javaType = LocalDateTime.class),
        @Arg(column = "end_time", javaType = LocalDateTime.class),
        @Arg(column = "status", javaType = ActivityStatus.class),
        @Arg(column = "initial_stock", javaType = int.class),
        @Arg(column = "available_stock", javaType = int.class)
    })
    Optional<ActivityView> findById(@Param("activityId") long activityId);
}
