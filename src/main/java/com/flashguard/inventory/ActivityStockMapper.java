package com.flashguard.inventory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ActivityStockMapper {

    @Update("""
        UPDATE activity_stock
        SET available_stock = available_stock - 1,
            version = version + 1,
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE activity_id = #{activityId}
          AND available_stock > 0
        """)
    int decrementIfAvailable(@Param("activityId") long activityId);
}
